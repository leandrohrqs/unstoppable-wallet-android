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
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
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

        // Unknown command
        return UNKNOWN
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

