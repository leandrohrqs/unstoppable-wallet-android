package io.horizontalsystems.bankwallet.modules.nfc.receive

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.IAccountManager
import io.horizontalsystems.bankwallet.core.IAdapterManager
import io.horizontalsystems.bankwallet.core.managers.CurrencyManager
import io.horizontalsystems.bankwallet.modules.nfc.core.BlockchainService
import io.horizontalsystems.bankwallet.modules.nfc.core.ConfigManager
import io.horizontalsystems.bankwallet.modules.nfc.core.WalletIntegrationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.*

/**
 * ViewModel for NFC Receive (POS/Merchant) screen.
 * Manages amount input and payment processing state.
 */
class NFCReceiveViewModel(
    private val accountManager: IAccountManager,
    private val adapterManager: IAdapterManager,
    private val currencyManager: CurrencyManager
) : ViewModel() {

    companion object {
        private const val TAG = "NFCReceiveViewModel"
        private const val MIN_AMOUNT_CENTS = 1L // $0.01
        private const val MAX_AMOUNT_CENTS = 1000000L // $10,000
    }

    var uiState by mutableStateOf(NFCReceiveUiState())
        private set

    private val walletIntegrationHelper = WalletIntegrationHelper(accountManager, adapterManager, App.walletManager)
    private var monitoringJob: Job? = null

    /**
     * Append a digit to the current amount
     */
    fun appendDigit(digit: String) {
        if (uiState.isProcessing) return

        val newAmountCents = uiState.amountCents * 10 + digit.toLong()

        if (newAmountCents <= MAX_AMOUNT_CENTS) {
            updateAmount(newAmountCents)
        }
    }

    /**
     * Clear the current amount
     */
    fun clearAmount() {
        if (uiState.isProcessing) return

        updateAmount(0L)
    }

    /**
     * Start payment process and enable NFC reader mode
     */
    fun startPayment() {
        if (uiState.amountCents < MIN_AMOUNT_CENTS) {
            logError("Amount too small: ${uiState.amountCents} cents", null)
            return
        }

        uiState = uiState.copy(
            isProcessing = true,
            statusMessage = "Tap customer's phone to receive payment"
        )
    }

    /**
     * Cancel the current payment
     */
    fun cancelPayment() {
        uiState = uiState.copy(
            isProcessing = false,
            statusMessage = ""
        )
    }

    /**
     * Update payment status message
     */
    fun updateStatusMessage(message: String) {
        uiState = uiState.copy(statusMessage = message)
    }

    /**
     * Complete payment successfully
     */
    fun completePayment(transactionHash: String) {
        uiState = uiState.copy(
            isProcessing = false,
            isPaymentConfirmed = true,
            transactionHash = transactionHash,
            statusMessage = ""
        )
    }
    
    /**
     * Reset after showing success screen
     */
    fun resetAfterSuccess() {
        uiState = uiState.copy(
            isPaymentConfirmed = false,
            transactionHash = null
        )
        clearAmount()
    }

    /**
     * Handle payment error
     */
    fun handlePaymentError(error: String) {
        val userFriendlyMessage = when {
            error.contains("Tag was lost", ignoreCase = true) -> "Connection lost. Please try again - hold devices closer and steady."
            error.contains("timeout", ignoreCase = true) -> "Connection timeout. Try again."
            error.contains("Unsupported", ignoreCase = true) -> "Device not supported."
            else -> "Error: $error. Tap to try again."
        }

        uiState = uiState.copy(
            isProcessing = true,
            statusMessage = userFriendlyMessage
        )

        logError("Payment error: $error", null)
    }

    /**
     * Handle discovered NFC tag and process payment
     */
    fun handleNFCTag(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            var isoDep: IsoDep? = null
            var ndef: Ndef? = null
            
            try {
                withContext(Dispatchers.Main) {
                    updateStatusMessage("Connected! Processing...")
                }

                when {
                    tag.techList.contains(IsoDep::class.java.name) -> {
                        isoDep = IsoDep.get(tag)!!
                        isoDep.connect()
                        isoDep.timeout = 2000
                        processWithIsoDep(isoDep, uiState.amount)
                    }
                    tag.techList.contains(Ndef::class.java.name) -> {
                        ndef = Ndef.get(tag)!!
                        if (!ndef.isConnected) {
                            ndef.connect()
                        }
                        processWithNdef(ndef, uiState.amount)
                    }
                    else -> {
                        logError("No supported NFC technology found", null)
                        withContext(Dispatchers.Main) {
                            handlePaymentError("Unsupported NFC device")
                        }
                    }
                }
            } catch (e: android.nfc.TagLostException) {
                logError("Tag lost during communication", e)
                withContext(Dispatchers.Main) {
                    updateStatusMessage("Connection lost. Hold devices closer and try again.")
                }
            } catch (e: Exception) {
                logError("Error processing NFC tag", e)
                withContext(Dispatchers.Main) {
                    handlePaymentError(e.message ?: "NFC communication error")
                }
            } finally {
                try {
                    isoDep?.close()
                    ndef?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
    }

    private suspend fun processWithIsoDep(isoDep: IsoDep, amount: BigDecimal) = withContext(Dispatchers.IO) {
        try {
            isoDep.timeout = 3000

            val aidBytes = byteArrayOf(0xF0.toByte(), 0x46, 0x52, 0x45, 0x45, 0x50, 0x41, 0x59)
            val selectAID = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aidBytes.size.toByte()) + aidBytes

            var response = isoDep.transceive(selectAID)

            if (response.size >= 2 && response[response.size - 2] == 0x90.toByte() && response[response.size - 1] == 0x00.toByte()) {
                withContext(Dispatchers.Main) {
                    updateStatusMessage("Device connected. Requesting wallet address...")
                }

                val walletAddressUri = "wallet:address"
                val uriBytes = walletAddressUri.toByteArray(StandardCharsets.UTF_8)
                
                // NDEF URI record format:
                // Header byte (0xD1 = MB, ME, SR, TNF=Well-Known)
                // Type Length (0x01)
                // Payload Length
                // Type (0x55 = 'U' for URI)
                // URI Identifier Code (0x00 = no prefix)
                // URI data
                val payloadLength = 1 + uriBytes.size // 1 byte for URI ID code + URI bytes
                val ndefRecord = byteArrayOf(
                    0xD1.toByte(), // Header: MB=1, ME=1, SR=1, TNF=001 (Well-Known)
                    0x01,          // Type Length = 1
                    payloadLength.toByte(), // Payload Length
                    0x55,          // Type = 'U' (URI)
                    0x00           // URI ID Code = 0x00 (no prefix)
                ) + uriBytes
                
                // Build PAYMENT command: CLA INS P1 P2 Lc Data
                val paymentCommand = byteArrayOf(
                    0x80.toByte(), // CLA
                    0xCF.toByte(), // INS (PAYMENT)
                    0x00,          // P1
                    0x00,          // P2
                    ndefRecord.size.toByte() // Lc (length of NDEF data)
                ) + ndefRecord

                response = isoDep.transceive(paymentCommand)

                // Check if response has status word 9000
                if (response.size > 2 && response[response.size - 2] == 0x90.toByte() && response[response.size - 1] == 0x00.toByte()) {
                    val addressBytes = response.copyOfRange(0, response.size - 2)
                    val walletAddress = String(addressBytes, StandardCharsets.UTF_8)
                    
                    withContext(Dispatchers.Main) {
                        updateStatusMessage("Wallet address received. Preparing payment...")
                    }

                    continuePaymentFlow(isoDep, walletAddress, amount)
                } else {
                    logError("Failed to get wallet address from customer device", null)
                    withContext(Dispatchers.Main) {
                        handlePaymentError("Failed to get wallet address. Device might not be configured.")
                    }
                }
            } else {
                logError("Failed to select AID", null)
                withContext(Dispatchers.Main) {
                    handlePaymentError("Failed to connect to wallet app. Ensure it's active.")
                }
            }
        } catch (e: Exception) {
            logError("IsoDep processing error", e)
            throw e
        }
    }

    private suspend fun processWithNdef(ndef: Ndef, amount: BigDecimal) = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                updateStatusMessage("NDEF communication not yet supported")
                kotlinx.coroutines.delay(2000)
                handlePaymentError("NDEF not supported yet")
            }
        } catch (e: Exception) {
            logError("NDEF processing error", e)
            throw e
        }
    }

    private suspend fun continuePaymentFlow(isoDep: IsoDep, walletAddress: String, amount: BigDecimal) = withContext(Dispatchers.IO) {
        try {
            val parsedAddress = walletIntegrationHelper.parseWalletAddress(walletAddress)

            val selectedToken = walletIntegrationHelper.selectOptimalPaymentToken(parsedAddress, amount)
            if (selectedToken == null) {
                logError("No suitable token with sufficient funds found", null)
                withContext(Dispatchers.Main) {
                    handlePaymentError("Insufficient funds or no suitable token in customer wallet.")
                }
                return@withContext
            }
            
            withContext(Dispatchers.Main) {
                updateStatusMessage("Token selected: ${selectedToken.symbol}. Sending payment request...")
            }

            val paymentUri = walletIntegrationHelper.createEIP681URI(selectedToken, amount)
            val success = sendPaymentUriViaIsoDep(isoDep, paymentUri)

            if (success) {
                val merchantAddress = walletIntegrationHelper.getAddressForBlockchain(selectedToken.token.blockchainType)
                if (merchantAddress != null && ConfigManager.hasAlchemyApiKey()) {
                    startPaymentMonitoring(
                        chainId = selectedToken.chainId,
                        merchantAddress = merchantAddress,
                        expectedAmountWei = walletIntegrationHelper.toWei(amount, selectedToken.decimals),
                        expectedTokenAddress = if (selectedToken.address != "0x0000000000000000000000000000000000000000") selectedToken.address else null
                    )
                }
                
                withContext(Dispatchers.Main) {
                    updateStatusMessage("Payment initiated. Waiting for confirmation...")
                }
            } else {
                logError("Failed to send payment URI", null)
                withContext(Dispatchers.Main) {
                    handlePaymentError("Failed to send payment request to customer device.")
                }
            }
        } catch (e: Exception) {
            logError("Error in payment flow continuation", e)
            withContext(Dispatchers.Main) {
                handlePaymentError(e.message ?: "Payment processing error")
            }
        }
    }

    /**
     * Start monitoring blockchain for payment transaction confirmation
     */
    private fun startPaymentMonitoring(
        chainId: Int,
        merchantAddress: String,
        expectedAmountWei: String,
        expectedTokenAddress: String?
    ) {
        monitoringJob?.cancel()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val blockchainService = BlockchainService(App.instance)
                
                monitoringJob = blockchainService.monitorIncomingTransfers(
                    chainId = chainId,
                    merchantAddress = merchantAddress,
                    expectedAmountWei = expectedAmountWei,
                    expectedTokenAddress = expectedTokenAddress
                ) { transfer ->
                    viewModelScope.launch(Dispatchers.Main) {
                        completePayment(transfer.hash)
                        
                        kotlinx.coroutines.delay(3000)
                        resetAfterSuccess()
                    }
                }
                
            } catch (e: Exception) {
                logError("Error starting payment monitoring", e)
            }
        }
    }

    private suspend fun sendPaymentUriViaIsoDep(isoDep: IsoDep, paymentUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Create NDEF URI record for payment
            val uriBytes = paymentUri.toByteArray(StandardCharsets.UTF_8)
            val payloadLength = 1 + uriBytes.size
            val ndefRecord = byteArrayOf(
                0xD1.toByte(), // Header: MB=1, ME=1, SR=1, TNF=001 (Well-Known)
                0x01,          // Type Length = 1
                payloadLength.toByte(), // Payload Length
                0x55,          // Type = 'U' (URI)
                0x00           // URI ID Code = 0x00 (no prefix)
            ) + uriBytes

            // Build PAYMENT_URI command: CLA INS P1 P2 Lc Data
            val command = byteArrayOf(
                0x80.toByte(), // CLA
                0xD0.toByte(), // INS (PAYMENT_URI)
                0x00,          // P1
                0x00,          // P2
                ndefRecord.size.toByte() // Lc
            ) + ndefRecord

            val response = isoDep.transceive(command)

            return@withContext response.size >= 2 && 
                   response[response.size - 2] == 0x90.toByte() && 
                   response[response.size - 1] == 0x00.toByte()
        } catch (e: Exception) {
            logError("Error sending payment URI via IsoDep", e)
            return@withContext false
        }
    }

    override fun onCleared() {
        super.onCleared()
        monitoringJob?.cancel()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun updateAmount(amountCents: Long) {
        val amountInDollars = BigDecimal(amountCents).divide(BigDecimal(100))
        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
        val formatted = formatter.format(amountInDollars)

        uiState = uiState.copy(
            amountCents = amountCents,
            amount = amountInDollars,
            formattedAmount = formatted,
            chargeEnabled = amountCents >= MIN_AMOUNT_CENTS
        )
    }

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

/**
 * UI state for NFC Receive screen
 */
data class NFCReceiveUiState(
    val amountCents: Long = 0L,
    val amount: BigDecimal = BigDecimal.ZERO,
    val formattedAmount: String = "$0.00",
    val chargeEnabled: Boolean = false,
    val isProcessing: Boolean = false,
    val isPaymentConfirmed: Boolean = false,
    val transactionHash: String? = null,
    val statusMessage: String = ""
)

