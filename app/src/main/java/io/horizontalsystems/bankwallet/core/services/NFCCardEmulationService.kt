package io.horizontalsystems.bankwallet.core.services

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.modules.nfc.core.WalletIntegrationHelper
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * Host Card Emulation (HCE) service for NFC payments.
 * Emulates an NFC card to send payment information when tapped by a merchant device.
 * 
 * Based on FreePay customer CardService but adapted for Unstoppable Wallet.
 */
class NFCCardEmulationService : HostApduService() {

    companion object {
        private const val TAG = "NFCCardEmulation"
        private const val FREEPAY_AID = "F046524545504159"

        // Response codes
        private val SELECT_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val UNKNOWN = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        // Command prefixes
        private val SELECT_PREFIX = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
        private val PAYMENT_CMD_PREFIX = byteArrayOf(0x80.toByte(), 0xCF.toByte(), 0x00.toByte(), 0x00.toByte())
        private val PAYMENT_URI_CMD_PREFIX = byteArrayOf(0x80.toByte(), 0xD0.toByte(), 0x00, 0x00)
        
        // Multi-token payment request commands
        private const val INS_PAYMENT_REQUEST = 0xD1.toByte()
        private const val INS_PAYMENT_REQUEST_START = 0xD2.toByte()
        private const val INS_PAYMENT_REQUEST_CHUNK = 0xD3.toByte()
    }
    
    // Chunked transfer buffer (instance-level to handle concurrent connections)
    private var chunkedBuffer: ByteArray? = null
    private var expectedTotalSize: Int = 0
    private var receivedSize: Int = 0

    override fun onCreate() {
        super.onCreate()
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // Check if NFC is enabled in app settings
        if (!App.localStorage.nfcEnabled) {
            logError("NFC payment rejected - NFC not enabled in app settings", null)
            return UNKNOWN
        }
        
        // Check if NFC Send screen is active - only process commands when on the send screen
        if (!io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager.isSendScreenActive) {
            Log.d(TAG, "📵 [CUSTOMER] NFC command ignored - Send screen not active")
            return UNKNOWN
        }

        sendBroadcast("NFC reader connected - processing request")

        // Handle SELECT command
        if (commandApdu.startsWith(SELECT_PREFIX)) {
            sendBroadcast("NFC handshake established")
            return SELECT_OK
        }

        // Handle PAYMENT command (request for wallet address)
        if (commandApdu.size >= PAYMENT_CMD_PREFIX.size &&
            commandApdu.take(PAYMENT_CMD_PREFIX.size).toByteArray().contentEquals(PAYMENT_CMD_PREFIX)
        ) {
            if (commandApdu.size <= PAYMENT_CMD_PREFIX.size) {
                logError("PAYMENT command received but no data", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }

            val lengthStartIndex = PAYMENT_CMD_PREFIX.size
            val ndefLength = commandApdu[lengthStartIndex].toInt() and 0xFF
            val ndefDataStartIndex = lengthStartIndex + 1

            val expectedTotalSize = PAYMENT_CMD_PREFIX.size + 1 + ndefLength
            if (commandApdu.size < expectedTotalSize) {
                logError("PAYMENT command data incomplete", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }

            val ndefData = Arrays.copyOfRange(commandApdu, ndefDataStartIndex, ndefDataStartIndex + ndefLength)
            return handleNDEFPaymentRequest(ndefData)
        }

        // Handle PAYMENT_URI command (receive payment URI from merchant)
        if (commandApdu.size >= PAYMENT_URI_CMD_PREFIX.size &&
            commandApdu.take(PAYMENT_URI_CMD_PREFIX.size).toByteArray().contentEquals(PAYMENT_URI_CMD_PREFIX)
        ) {
            if (commandApdu.size <= PAYMENT_URI_CMD_PREFIX.size) {
                logError("PAYMENT_URI command received but no data", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }

            val lengthStartIndex = PAYMENT_URI_CMD_PREFIX.size
            val ndefLength = commandApdu[lengthStartIndex].toInt() and 0xFF
            val ndefDataStartIndex = lengthStartIndex + 1

            val expectedTotalSize = PAYMENT_URI_CMD_PREFIX.size + 1 + ndefLength
            if (commandApdu.size < expectedTotalSize) {
                logError("PAYMENT_URI command data incomplete", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }

            val ndefData = Arrays.copyOfRange(commandApdu, ndefDataStartIndex, ndefDataStartIndex + ndefLength)
            val paymentUri = parseUriFromNDEF(ndefData)
            if (paymentUri != null) {
                sendPaymentUriBroadcast(paymentUri)
                sendBroadcast("Payment URI received - opening wallet")
                return SELECT_OK
            } else {
                logError("Failed to parse payment URI from NDEF", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }
        }

        // Handle multi-token payment request commands (check INS byte at position 1)
        if (commandApdu.size >= 5 && commandApdu[0] == 0x80.toByte()) {
            when (commandApdu[1]) {
                INS_PAYMENT_REQUEST -> return handlePaymentRequestCommand(commandApdu)
                INS_PAYMENT_REQUEST_START -> return handlePaymentRequestStart(commandApdu)
                INS_PAYMENT_REQUEST_CHUNK -> return handlePaymentRequestChunk(commandApdu)
            }
        }

        // Unknown command
        return UNKNOWN
    }
    
    /**
     * Handle chunked transfer start - receive total size info.
     */
    private fun handlePaymentRequestStart(commandApdu: ByteArray): ByteArray {
        try {
            if (commandApdu.size < 9) {
                logError("PAYMENT_REQUEST_START command too short", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }
            
            // P1:P2 = total chunks (not used currently, but could be for validation)
            // Data = 4 bytes total size
            val totalSize = ((commandApdu[5].toInt() and 0xFF) shl 24) or
                           ((commandApdu[6].toInt() and 0xFF) shl 16) or
                           ((commandApdu[7].toInt() and 0xFF) shl 8) or
                           (commandApdu[8].toInt() and 0xFF)
            
            Log.d(TAG, "📥 [CUSTOMER] Starting chunked receive, total size: $totalSize bytes")
            
            // Initialize buffer
            chunkedBuffer = ByteArray(totalSize)
            expectedTotalSize = totalSize
            receivedSize = 0
            
            return SELECT_OK
        } catch (e: Exception) {
            logError("Error handling PAYMENT_REQUEST_START", e)
            return byteArrayOf(0x6A.toByte(), 0x80.toByte())
        }
    }
    
    /**
     * Handle chunked transfer chunk - receive a piece of data.
     */
    private fun handlePaymentRequestChunk(commandApdu: ByteArray): ByteArray {
        try {
            if (commandApdu.size < 6) {
                logError("PAYMENT_REQUEST_CHUNK command too short", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }
            
            val isLastChunk = commandApdu[2] == 0x01.toByte()
            val chunkIndex = commandApdu[3].toInt() and 0xFF
            val dataLength = commandApdu[4].toInt() and 0xFF
            
            if (commandApdu.size < 5 + dataLength) {
                logError("PAYMENT_REQUEST_CHUNK data incomplete", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }
            
            val buffer = chunkedBuffer
            if (buffer == null) {
                logError("No chunked transfer in progress", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }
            
            // Copy chunk data to buffer
            val chunkData = Arrays.copyOfRange(commandApdu, 5, 5 + dataLength)
            if (receivedSize + dataLength > buffer.size) {
                logError("Chunk would overflow buffer", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }
            
            System.arraycopy(chunkData, 0, buffer, receivedSize, dataLength)
            receivedSize += dataLength
            
            Log.d(TAG, "📥 [CUSTOMER] Received chunk $chunkIndex, ${dataLength} bytes, total: $receivedSize/${expectedTotalSize}")
            
            if (isLastChunk || receivedSize >= expectedTotalSize) {
                // All chunks received - process the complete data
                Log.d(TAG, "📥 [CUSTOMER] All chunks received, processing...")
                val jsonString = String(buffer, 0, receivedSize, StandardCharsets.UTF_8)
                
                // Reset chunked state
                chunkedBuffer = null
                expectedTotalSize = 0
                receivedSize = 0
                
                if (jsonString.startsWith("{") && jsonString.contains("tokens")) {
                    Log.d(TAG, "📥 [CUSTOMER] Valid payment request received")
                    sendPaymentRequestBroadcast(jsonString)
                    sendBroadcast("Payment request received - select payment method")
                    return SELECT_OK
                } else {
                    logError("Invalid JSON in chunked data", null)
                    return byteArrayOf(0x6A.toByte(), 0x80.toByte())
                }
            }
            
            return SELECT_OK
        } catch (e: Exception) {
            logError("Error handling PAYMENT_REQUEST_CHUNK", e)
            chunkedBuffer = null
            return byteArrayOf(0x6A.toByte(), 0x80.toByte())
        }
    }
    
    /**
     * Handle single-chunk payment request command (INS=0xD1).
     * Used when payload fits in a single APDU.
     */
    private fun handlePaymentRequestCommand(commandApdu: ByteArray): ByteArray {
        try {
            // APDU format: CLA INS P1 P2 Lc Data
            // Header is 4 bytes (CLA INS P1 P2), then Lc (1 byte), then data
            val headerSize = 4
            
            if (commandApdu.size <= headerSize + 1) {
                logError("PAYMENT_REQUEST command received but no data", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }

            val dataLength = commandApdu[headerSize].toInt() and 0xFF
            val dataStartIndex = headerSize + 1

            if (commandApdu.size < dataStartIndex + dataLength) {
                logError("PAYMENT_REQUEST command data incomplete. Expected ${dataStartIndex + dataLength}, got ${commandApdu.size}", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }

            val payloadData = Arrays.copyOfRange(commandApdu, dataStartIndex, dataStartIndex + dataLength)
            
            // Try to parse as JSON directly
            val jsonString = tryParsePaymentRequestJson(payloadData)
            
            if (jsonString != null) {
                Log.d(TAG, "📥 [CUSTOMER] Received payment request: ${jsonString.take(100)}...")
                sendPaymentRequestBroadcast(jsonString)
                sendBroadcast("Payment request received - select payment method")
                return SELECT_OK
            } else {
                logError("Failed to parse payment request JSON", null)
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }
        } catch (e: Exception) {
            logError("Error handling PAYMENT_REQUEST command", e)
            return byteArrayOf(0x6A.toByte(), 0x80.toByte())
        }
    }
    
    /**
     * Try to parse payment request JSON from NDEF or raw data.
     */
    private fun tryParsePaymentRequestJson(data: ByteArray): String? {
        try {
            // First try to parse as NDEF Text record
            if (data.size > 7 && data[0] == 0xD1.toByte()) {
                // NDEF format - skip header and extract text
                val payloadLength = data[2].toInt() and 0xFF
                val langCodeLength = data[4].toInt() and 0x3F
                val textStart = 5 + langCodeLength
                
                if (data.size >= textStart + (payloadLength - 1 - langCodeLength)) {
                    val textBytes = Arrays.copyOfRange(data, textStart, textStart + payloadLength - 1 - langCodeLength)
                    val text = String(textBytes, StandardCharsets.UTF_8)
                    if (text.startsWith("{") && text.contains("tokens")) {
                        return text
                    }
                }
            }
            
            // Try to parse as raw JSON
            val rawText = String(data, StandardCharsets.UTF_8)
            if (rawText.startsWith("{") && rawText.contains("tokens")) {
                return rawText
            }
            
            // Try skipping any prefix bytes until we find JSON
            for (i in 0 until minOf(20, data.size)) {
                if (data[i] == '{'.code.toByte()) {
                    val jsonBytes = Arrays.copyOfRange(data, i, data.size)
                    val jsonText = String(jsonBytes, StandardCharsets.UTF_8)
                    if (jsonText.contains("tokens")) {
                        return jsonText
                    }
                }
            }
            
            return null
        } catch (e: Exception) {
            logError("Error parsing payment request JSON", e)
            return null
        }
    }
    
    private fun sendPaymentRequestBroadcast(paymentRequest: String) {
        val intent = Intent("io.horizontalsystems.bankwallet.PAYMENT_REQUEST_RECEIVED")
        intent.putExtra("payment_request", paymentRequest)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun handleNDEFPaymentRequest(ndefData: ByteArray): ByteArray {
        val parsedUri = parseUriFromNDEF(ndefData)

        if (parsedUri != null) {
            // Check if it's a wallet:address command
            if (parsedUri.trim() == "wallet:address") {
                return handleWalletAddressRequest()
            }

            // Handle ethereum: URI (shouldn't happen in this flow, but handle gracefully)
            if (parsedUri.startsWith("ethereum:")) {
                return byteArrayOf(0x6A.toByte(), 0x80.toByte())
            }

            logError("Unsupported URI scheme: $parsedUri", null)
            return byteArrayOf(0x6A.toByte(), 0x80.toByte())
        } else {
            logError("Failed to parse URI from NDEF", null)
            return byteArrayOf(0x6A.toByte(), 0x80.toByte())
        }
    }

    private fun handleWalletAddressRequest(): ByteArray {
        // Get wallet address from active account
        val walletAddress = getActiveWalletAddress()

        if (walletAddress != null) {
            // Format address in CAIP-10 format: eip155:1:0x...
            val chainId = 1 // Default to Ethereum mainnet
            val caip10Address = "eip155:$chainId:$walletAddress"

            val addressBytes = caip10Address.toByteArray(Charsets.UTF_8)
            val response = addressBytes + SELECT_OK // CRITICAL: Append 9000 status word

            sendBroadcast("Sent wallet address: ${walletAddress.take(6)}...${walletAddress.takeLast(4)}")
            return response
        } else {
            logError("No wallet configured", null)
            sendBroadcast("No wallet configured - please set up a wallet first")
            return UNKNOWN // Already has error status word
        }
    }

    private fun getActiveWalletAddress(): String? {
        return try {
            val walletHelper = WalletIntegrationHelper(
                App.accountManager,
                App.adapterManager,
                App.walletManager
            )

            // Get primary wallet address (runs synchronously in HCE context)
            runBlocking {
                walletHelper.getPrimaryWalletAddress()
            }
        } catch (e: Exception) {
            logError("Error getting active wallet address", e)
            null
        }
    }

    private fun parseUriFromNDEF(ndefData: ByteArray): String? {
        try {
            if (ndefData.size < 5) {
                logError("NDEF data too short: ${ndefData.size} bytes", null)
                return null
            }

            val recordHeader = ndefData[0].toInt() and 0xFF
            val typeLength = ndefData[1].toInt() and 0xFF

            val isShortRecord = (recordHeader and 0x10) != 0
            val hasIdLength = (recordHeader and 0x08) != 0

            val (payloadLength, payloadLengthBytes) = if (isShortRecord) {
                val length = ndefData[2].toInt() and 0xFF
                Pair(length, 1)
            } else {
                if (ndefData.size < 6) {
                    logError("NDEF data too short for long record", null)
                    return null
                }
                val length = ((ndefData[2].toInt() and 0xFF) shl 24) or
                        ((ndefData[3].toInt() and 0xFF) shl 16) or
                        ((ndefData[4].toInt() and 0xFF) shl 8) or
                        (ndefData[5].toInt() and 0xFF)
                Pair(length, 4)
            }

            val typeStart = 2 + payloadLengthBytes
            val idLengthPos = if (hasIdLength) typeStart + typeLength else -1
            val idLength = if (hasIdLength) ndefData[idLengthPos].toInt() and 0xFF else 0
            val payloadStart = typeStart + typeLength + (if (hasIdLength) 1 + idLength else 0)

            if (ndefData.size < payloadStart) {
                logError("NDEF data too short - expected at least $payloadStart bytes", null)
                return null
            }

            val recordType = ndefData[typeStart]

            // Check if it's a Well-Known URI record
            if ((recordHeader and 0x07) != 0x01 || typeLength != 0x01 || (recordType.toInt() and 0xFF) != 0x55) {
                logError("Not a valid URI record", null)
                return null
            }

            if (payloadStart >= ndefData.size) {
                logError("No payload data available", null)
                return null
            }

            val uriAbbreviation = ndefData[payloadStart]
            val uriDataStart = payloadStart + 1
            val uriDataEnd = payloadStart + payloadLength

            if (ndefData.size < uriDataEnd) {
                val availableDataLength = ndefData.size - uriDataStart
                if (availableDataLength <= 0) {
                    logError("No URI data available", null)
                    return null
                }

                val uriBytes = Arrays.copyOfRange(ndefData, uriDataStart, ndefData.size)
                val uri = String(uriBytes, StandardCharsets.UTF_8)
                return applyUriAbbreviation(uriAbbreviation, uri)
            }

            val uriBytes = Arrays.copyOfRange(ndefData, uriDataStart, uriDataEnd)
            val uri = String(uriBytes, StandardCharsets.UTF_8)
            return applyUriAbbreviation(uriAbbreviation, uri)
        } catch (e: Exception) {
            logError("Error parsing NDEF data", e)
            return null
        }
    }

    private fun applyUriAbbreviation(abbreviationCode: Byte, uri: String): String {
        return when (abbreviationCode) {
            0x00.toByte() -> uri
            else -> uri
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sendBroadcast(message: String) {
        val intent = Intent("io.horizontalsystems.bankwallet.NFC_DATA_RECEIVED")
        intent.putExtra("nfc_data", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendPaymentUriBroadcast(paymentUri: String) {
        val intent = Intent("io.horizontalsystems.bankwallet.PAYMENT_URI_RECEIVED")
        intent.putExtra("payment_uri", paymentUri)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDeactivated(reason: Int) {
        sendBroadcast("NFC connection lost")
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        this.size >= prefix.size && this.sliceArray(0 until prefix.size).contentEquals(prefix)

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

