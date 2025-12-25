package io.horizontalsystems.bankwallet.modules.nfc.core

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * Handler for NFC communication in reader mode (POS/Merchant).
 * Processes NFC tags and handles payment requests via NDEF or IsoDep.
 * 
 * Based on FreePay merchant NFC implementation but adapted for Unstoppable Wallet.
 */
class NFCReaderHandler(private val context: Context) {

    companion object {
        private const val TAG = "NFCReaderHandler"
        private const val WALLET_ADDRESS_URI = "wallet:address"
        private const val PAYMENT_CMD_PREFIX_BYTE1: Byte = 0x80.toByte()
        private const val PAYMENT_CMD_PREFIX_BYTE2: Byte = 0xCF.toByte()
        private const val PAYMENT_URI_CMD_BYTE2: Byte = 0xD0.toByte()
    }

    /**
     * Process payment via NFC tag.
     * 
     * @param tag NFC tag detected
     * @param amount Payment amount in base currency
     * @param merchantAddress Merchant's wallet address
     * @param chainId Blockchain chain ID
     * @return Payment result with success status and transaction details
     */
    suspend fun processPayment(
        tag: Tag,
        amount: BigDecimal,
        merchantAddress: String,
        chainId: Int
    ): PaymentResult = withContext(Dispatchers.IO) {
        try {
            when {
                tag.techList.contains(Ndef::class.java.name) -> {
                    val ndef = Ndef.get(tag) ?: return@withContext PaymentResult(
                        success = false,
                        error = "Failed to get NDEF interface"
                    )

                    if (!ndef.isConnected) {
                        ndef.connect()
                    }

                    processWithNdef(ndef, amount, merchantAddress, chainId)
                }

                tag.techList.contains(IsoDep::class.java.name) -> {
                    val isoDep = IsoDep.get(tag) ?: return@withContext PaymentResult(
                        success = false,
                        error = "Failed to get IsoDep interface"
                    )

                    isoDep.connect()
                    isoDep.timeout = 5000

                    processWithIsoDep(isoDep, amount, merchantAddress, chainId)
                }

                else -> {
                    logError("No supported technology found", null)
                    PaymentResult(
                        success = false,
                        error = "NFC tag does not support required technology"
                    )
                }
            }
        } catch (e: Exception) {
            logError("Error processing payment", e)
            PaymentResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Process payment using NDEF technology
     */
    private suspend fun processWithNdef(
        ndef: Ndef,
        amount: BigDecimal,
        merchantAddress: String,
        chainId: Int
    ): PaymentResult = withContext(Dispatchers.IO) {
        try {
            val walletAddress = requestWalletAddressNdef(ndef) ?: return@withContext PaymentResult(
                success = false,
                error = "Failed to get wallet address from customer device"
            )

            val paymentUri = createEIP681URI(amount, merchantAddress, chainId)

            if (sendPaymentRequestNdef(ndef, paymentUri)) {
                ndef.close()
                PaymentResult(
                    success = true,
                    walletAddress = walletAddress,
                    paymentUri = paymentUri
                )
            } else {
                PaymentResult(
                    success = false,
                    error = "Failed to send payment request to customer device"
                )
            }
        } catch (e: Exception) {
            logError("Error in NDEF processing", e)
            PaymentResult(
                success = false,
                error = e.message ?: "NDEF communication error"
            )
        }
    }

    /**
     * Process payment using IsoDep technology
     */
    private suspend fun processWithIsoDep(
        isoDep: IsoDep,
        amount: BigDecimal,
        merchantAddress: String,
        chainId: Int
    ): PaymentResult = withContext(Dispatchers.IO) {
        try {
            val aidBytes = byteArrayOf(
                0xF0.toByte(), 0x46, 0x52, 0x45, 0x45, 0x50, 0x41, 0x59
            )

            val selectCommand = byteArrayOf(
                0x00, // CLA
                0xA4.toByte(), // INS (SELECT)
                0x04, // P1 (Select by AID)
                0x00, // P2
                aidBytes.size.toByte()
            ) + aidBytes

            var response = isoDep.transceive(selectCommand)

            if (response.size >= 2 &&
                response[response.size - 2] == 0x90.toByte() &&
                response[response.size - 1] == 0x00.toByte()
            ) {

                val ndefMessage = NdefMessage(NdefRecord.createUri(WALLET_ADDRESS_URI))
                val ndefBytes = ndefMessage.toByteArray()

                val paymentCommand = ByteArray(4 + 1 + ndefBytes.size)
                paymentCommand[0] = PAYMENT_CMD_PREFIX_BYTE1
                paymentCommand[1] = PAYMENT_CMD_PREFIX_BYTE2
                paymentCommand[2] = 0x00
                paymentCommand[3] = 0x00
                paymentCommand[4] = ndefBytes.size.toByte()
                System.arraycopy(ndefBytes, 0, paymentCommand, 5, ndefBytes.size)

                response = isoDep.transceive(paymentCommand)

                if (response.isNotEmpty() && response[0] != 0x6A.toByte()) {
                    val walletAddressText = String(response, StandardCharsets.UTF_8)
                    val walletAddress = parseWalletAddress(walletAddressText)

                    val paymentUri = createEIP681URI(amount, merchantAddress, chainId)
                    val success = sendPaymentUriViaIsoDep(isoDep, paymentUri)

                    isoDep.close()

                    if (success) {
                        PaymentResult(
                            success = true,
                            walletAddress = walletAddress,
                            paymentUri = paymentUri
                        )
                    } else {
                        PaymentResult(
                            success = false,
                            error = "Failed to send payment URI to customer device"
                        )
                    }
                } else {
                    PaymentResult(
                        success = false,
                        error = "Customer device did not provide wallet address"
                    )
                }
            } else {
                PaymentResult(
                    success = false,
                    error = "Customer device not ready for NFC payment"
                )
            }
        } catch (e: Exception) {
            logError("Error in IsoDep processing", e)
            try {
                isoDep.close()
            } catch (_: Exception) {
            }
            PaymentResult(
                success = false,
                error = e.message ?: "IsoDep communication error"
            )
        }
    }

    private suspend fun requestWalletAddressNdef(ndef: Ndef): String? = withContext(Dispatchers.IO) {
        try {
            val uriRecord = NdefRecord.createUri(WALLET_ADDRESS_URI)
            val message = NdefMessage(uriRecord)

            ndef.writeNdefMessage(message)
            Thread.sleep(500)

            val response = ndef.ndefMessage

            if (response != null && response.records.isNotEmpty()) {
                val record = response.records[0]
                val addressText = String(record.payload, StandardCharsets.UTF_8)
                return@withContext parseWalletAddress(addressText)
            }

            null
        } catch (e: Exception) {
            logError("Error requesting wallet address", e)
            null
        }
    }

    private suspend fun sendPaymentRequestNdef(ndef: Ndef, paymentUri: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uriRecord = NdefRecord.createUri(paymentUri)
                val message = NdefMessage(uriRecord)

                ndef.writeNdefMessage(message)
                true
            } catch (e: Exception) {
                logError("Error sending payment request", e)
                false
            }
        }

    private suspend fun sendPaymentUriViaIsoDep(isoDep: IsoDep, paymentUri: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uriBytes = paymentUri.toByteArray(StandardCharsets.UTF_8)
                val payload = byteArrayOf(0x00) + uriBytes
                val payloadLength = payload.size

                val ndefRecord = if (payloadLength <= 255) {
                    byteArrayOf(
                        0xD1.toByte(),
                        0x01,
                        (payloadLength and 0xFF).toByte(),
                        0x55
                    ) + payload
                } else {
                    byteArrayOf(
                        0xC1.toByte(),
                        0x01,
                        ((payloadLength shr 24) and 0xFF).toByte(),
                        ((payloadLength shr 16) and 0xFF).toByte(),
                        ((payloadLength shr 8) and 0xFF).toByte(),
                        (payloadLength and 0xFF).toByte(),
                        0x55
                    ) + payload
                }

                val command = byteArrayOf(
                    PAYMENT_CMD_PREFIX_BYTE1,
                    PAYMENT_URI_CMD_BYTE2,
                    0x00,
                    0x00,
                    ndefRecord.size.toByte()
                ) + ndefRecord

                val response = isoDep.transceive(command)

                response.size >= 2 &&
                        response[response.size - 2] == 0x90.toByte() &&
                        response[response.size - 1] == 0x00.toByte()
            } catch (e: Exception) {
                logError("Error sending payment URI", e)
                false
            }
        }

    private fun parseWalletAddress(payload: String): String {
        return when {
            payload.contains(":") -> payload.substringAfterLast(":")
            payload.startsWith("0x") -> payload
            else -> {
                val addressRegex = "0x[a-fA-F0-9]{40}".toRegex()
                addressRegex.find(payload)?.value ?: payload
            }
        }
    }

    private fun createEIP681URI(amount: BigDecimal, merchantAddress: String, chainId: Int): String {
        val amountInWei = amount.multiply(BigDecimal("1000000000000000000")).toBigInteger().toString()
        return "ethereum:$merchantAddress@$chainId?value=$amountInWei"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

/**
 * Result of NFC payment processing
 */
data class PaymentResult(
    val success: Boolean,
    val walletAddress: String? = null,
    val paymentUri: String? = null,
    val error: String? = null
)

