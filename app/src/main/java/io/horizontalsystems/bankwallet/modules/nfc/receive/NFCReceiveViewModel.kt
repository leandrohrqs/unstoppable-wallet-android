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
import io.horizontalsystems.bankwallet.core.ITransactionsAdapter
import io.horizontalsystems.bankwallet.core.managers.CurrencyManager
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.entities.transactionrecords.TransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.evm.EvmIncomingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.evm.ExternalContractCallTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.bitcoin.BitcoinIncomingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.tron.TronIncomingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.solana.SolanaIncomingTransactionRecord
import io.horizontalsystems.bankwallet.modules.nfc.core.AcceptedToken
import io.horizontalsystems.bankwallet.modules.nfc.core.FiatAmount
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCPaymentRequest
import io.horizontalsystems.bankwallet.modules.nfc.core.TokenPaymentOption
import io.horizontalsystems.bankwallet.modules.nfc.core.WalletIntegrationHelper
import io.horizontalsystems.bankwallet.modules.transactions.FilterTransactionType
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val disposables = CompositeDisposable()
    private var expectedPaymentAmount: BigDecimal? = null
    private var expectedReceiverAddress: String? = null
    private var monitoringStartTime: Long = 0L
    
    init {
        // Initialize with the correct currency format
        updateAmount(0L)
        
        // Observe currency changes and update formatted amount
        viewModelScope.launch {
            currencyManager.baseCurrencyUpdatedFlow.collect {
                // Re-format the current amount with the new currency
                updateAmount(uiState.amountCents)
            }
        }
    }

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
     * Remove the last digit from the current amount
     */
    fun removeLastDigit() {
        if (uiState.isProcessing) return

        val newAmountCents = uiState.amountCents / 10
        updateAmount(newAmountCents)
    }

    /**
     * Start payment process and enable NFC reader mode
     */
    fun startPayment() {
        if (uiState.amountCents < MIN_AMOUNT_CENTS) {
            logError("Amount too small: ${uiState.amountCents} cents", null)
            return
        }
        
        // Validate configuration - check if receiver wallet has any tokens
        if (!NFCConfigManager.isReceiverConfigValid()) {
            logError("Receiver wallet has no tokens enabled", null)
            // Allow to proceed - will show error later if no tokens available
        }
        
        // Mark payment as active (prevents config changes)
        NFCConfigManager.isPaymentActive = true

        uiState = uiState.copy(
            isProcessing = true,
            status = ReceivePaymentStatus.WAITING_FOR_CUSTOMER
        )
    }

    /**
     * Cancel the current payment
     */
    fun cancelPayment() {
        NFCConfigManager.isPaymentActive = false
        monitoringJob?.cancel()
        uiState = uiState.copy(
            isProcessing = false,
            status = null
        )
    }

    /**
     * Update payment status
     */
    private fun updateStatus(status: ReceivePaymentStatus) {
        uiState = uiState.copy(status = status)
    }

    /**
     * Complete payment successfully
     */
    fun completePayment(transactionHash: String) {
        Log.d(TAG, "✅ [MERCHANT] Payment confirmed! Transaction hash: $transactionHash")
        NFCConfigManager.isPaymentActive = false
        monitoringJob?.cancel()
        uiState = uiState.copy(
            status = ReceivePaymentStatus.CONFIRMED,
            transactionHash = transactionHash
        )
    }
    
    /**
     * Reset after showing success screen
     */
    fun resetAfterSuccess() {
        NFCConfigManager.isPaymentActive = false
        monitoringJob?.cancel()
        uiState = uiState.copy(
            isProcessing = false,
            status = null,
            transactionHash = null
        )
        clearAmount()
    }

    /**
     * Handle payment error
     */
    fun handlePaymentError(error: String) {
        uiState = uiState.copy(
            status = ReceivePaymentStatus.FAILED
        )

        logError("Payment error: $error", null)
        
        // Reset after 3 seconds to allow retry
        viewModelScope.launch {
            delay(3000)
            uiState = uiState.copy(
                isProcessing = false,
                status = null
            )
        }
    }
    
    /**
     * Silently reset state without showing error.
     * Used when the customer app is not ready (e.g., not on Send screen).
     * Keeps the status as WAITING_FOR_CUSTOMER so the merchant stays on the waiting screen.
     */
    private fun silentReset() {
        Log.d(TAG, "Silent reset - customer not ready, keeping WAITING_FOR_CUSTOMER status")
        // Don't change the status - keep waiting for customer
        // Only reset isProcessing flag if it was set
    }

    /**
     * Handle discovered NFC tag and process payment
     */
    fun handleNFCTag(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            var isoDep: IsoDep? = null
            var ndef: Ndef? = null
            
            try {
                // Don't show CONNECTING status yet - wait until AID is successfully selected
                // This prevents showing status changes when customer is not on Send screen
                
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
                    handlePaymentError("Device moved away. Please try again.")
                }
            } catch (e: SecurityException) {
                logError("Security exception during NFC communication", e)
                withContext(Dispatchers.Main) {
                    handlePaymentError("NFC permission error. Please try again.")
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
                // AID selected successfully - customer is on Send screen and ready
                // Now we can show status updates
                withContext(Dispatchers.Main) {
                    updateStatus(ReceivePaymentStatus.CONNECTING)
                }
                
                // Brief delay to show connecting status
                delay(100)
                
                withContext(Dispatchers.Main) {
                    updateStatus(ReceivePaymentStatus.CONNECTED)
                }
                
                // Small delay only for UI to update, then continue immediately
                delay(200)

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
                    
                    continuePaymentFlow(isoDep, walletAddress, amount)
                } else {
                    logError("Failed to get wallet address from customer device", null)
                    withContext(Dispatchers.Main) {
                        handlePaymentError("Failed to get wallet address. Device might not be configured.")
                    }
                }
            } else {
                // AID selection failed - this usually means the customer app is not on the Send screen
                // or doesn't have the wallet app installed. Don't show error, just reset silently.
                Log.d(TAG, "AID selection failed - customer may not be on Send screen")
                withContext(Dispatchers.Main) {
                    silentReset()
                }
            }
        } catch (e: android.nfc.TagLostException) {
            logError("Tag lost during IsoDep processing", e)
            withContext(Dispatchers.Main) {
                handlePaymentError("Device moved away. Please try again.")
            }
        } catch (e: SecurityException) {
            logError("Security exception during IsoDep processing", e)
            withContext(Dispatchers.Main) {
                handlePaymentError("NFC permission error. Please try again.")
            }
        } catch (e: Exception) {
            logError("IsoDep processing error", e)
            withContext(Dispatchers.Main) {
                handlePaymentError(e.message ?: "NFC communication error")
            }
        }
    }

    private suspend fun processWithNdef(ndef: Ndef, amount: BigDecimal) = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                updateStatus(ReceivePaymentStatus.FAILED)
                delay(2000)
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
            
            // Get receiver account - use all tokens enabled in this wallet
            val receiverAccountId = NFCConfigManager.receiverAccountId
            val currency = currencyManager.baseCurrency
            
            // Get all enabled tokens from the receiver wallet
            val acceptedTokens = walletIntegrationHelper.getEnabledTokensForPayment(
                fiatAmount = amount,
                fiatCurrencyCode = currency.code,
                receiverAccountId = receiverAccountId
            )
            
            if (acceptedTokens.isEmpty()) {
                logError("No tokens available for payment in receiver wallet", null)
                withContext(Dispatchers.Main) {
                    handlePaymentError("No tokens available. Add coins to your wallet via Coin Manager.")
                }
                return@withContext
            }
            
            // Create payment request with all accepted tokens
            val paymentRequest = NFCPaymentRequest(
                fiat = FiatAmount(
                    amount = amount.toPlainString(),
                    currency = currency.code
                ),
                tokens = acceptedTokens.map { TokenPaymentOption.fromAcceptedToken(it) }
            )
            
            Log.d(TAG, "📤 [MERCHANT] Sending payment request with ${acceptedTokens.size} tokens: ${paymentRequest.toJson()}")
            
            // Send payment request via NFC
            val success = sendPaymentRequestViaIsoDep(isoDep, paymentRequest)

            if (success) {
                withContext(Dispatchers.Main) {
                    updateStatus(ReceivePaymentStatus.SEARCHING)
                }
                
                // Start monitoring using the app's internal transaction system
                // This monitors ALL blockchains (EVM, Bitcoin, Tron, etc.) without external API calls
                startPaymentMonitoringInternal(acceptedTokens, amount)
            } else {
                logError("Failed to send payment request", null)
                withContext(Dispatchers.Main) {
                    handlePaymentError("Failed to send payment request to customer device.")
                }
            }
        } catch (e: android.nfc.TagLostException) {
            logError("Tag lost during payment flow", e)
            withContext(Dispatchers.Main) {
                handlePaymentError("Device moved away. Please try again.")
            }
        } catch (e: SecurityException) {
            logError("Security exception during payment flow", e)
            withContext(Dispatchers.Main) {
                handlePaymentError("NFC permission error. Please try again.")
            }
        } catch (e: Exception) {
            logError("Error in payment flow continuation", e)
            withContext(Dispatchers.Main) {
                handlePaymentError(e.message ?: "Payment processing error")
            }
        }
    }
    
    /**
     * Send multi-token payment request via IsoDep.
     * Uses chunked transfer for large payloads.
     */
    private suspend fun sendPaymentRequestViaIsoDep(isoDep: IsoDep, paymentRequest: NFCPaymentRequest): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonBytes = paymentRequest.toJson().toByteArray(StandardCharsets.UTF_8)
            Log.d(TAG, "📤 [MERCHANT] JSON payload size: ${jsonBytes.size} bytes")
            
            // Check max transceive length
            val maxLength = isoDep.maxTransceiveLength
            Log.d(TAG, "📤 [MERCHANT] Max transceive length: $maxLength bytes")
            
            // APDU header is 5 bytes (CLA INS P1 P2 Lc), so max data per chunk
            val maxDataPerChunk = minOf(240, maxLength - 10) // Conservative chunk size
            
            isoDep.timeout = 5000
            
            if (jsonBytes.size <= maxDataPerChunk) {
                // Single chunk - send directly
                val command = byteArrayOf(
                    0x80.toByte(), // CLA
                    0xD1.toByte(), // INS (PAYMENT_REQUEST)
                    0x00,          // P1 = 0 means single/final chunk
                    0x00,          // P2
                    jsonBytes.size.toByte() // Lc
                ) + jsonBytes
                
                Log.d(TAG, "📤 [MERCHANT] Sending single chunk of ${command.size} bytes")
                val response = isoDep.transceive(command)
                return@withContext checkSuccessResponse(response)
            } else {
                // Multi-chunk transfer
                val totalChunks = (jsonBytes.size + maxDataPerChunk - 1) / maxDataPerChunk
                Log.d(TAG, "📤 [MERCHANT] Splitting into $totalChunks chunks")
                
                // First, send header with total size
                val headerCommand = byteArrayOf(
                    0x80.toByte(), // CLA
                    0xD2.toByte(), // INS (PAYMENT_REQUEST_START) - new command for chunked start
                    ((totalChunks shr 8) and 0xFF).toByte(), // P1 = total chunks high byte
                    (totalChunks and 0xFF).toByte(),          // P2 = total chunks low byte
                    0x04,          // Lc = 4 bytes for total size
                    ((jsonBytes.size shr 24) and 0xFF).toByte(),
                    ((jsonBytes.size shr 16) and 0xFF).toByte(),
                    ((jsonBytes.size shr 8) and 0xFF).toByte(),
                    (jsonBytes.size and 0xFF).toByte()
                )
                
                Log.d(TAG, "📤 [MERCHANT] Sending chunk header")
                var response = isoDep.transceive(headerCommand)
                if (!checkSuccessResponse(response)) {
                    logError("Failed to send chunk header", null)
                    return@withContext false
                }
                
                // Send each chunk
                var offset = 0
                var chunkIndex = 0
                while (offset < jsonBytes.size) {
                    val remaining = jsonBytes.size - offset
                    val chunkSize = minOf(maxDataPerChunk, remaining)
                    val chunk = jsonBytes.copyOfRange(offset, offset + chunkSize)
                    val isLastChunk = (offset + chunkSize >= jsonBytes.size)
                    
                    val chunkCommand = byteArrayOf(
                        0x80.toByte(), // CLA
                        0xD3.toByte(), // INS (PAYMENT_REQUEST_CHUNK)
                        if (isLastChunk) 0x01 else 0x00, // P1 = 1 if last chunk
                        chunkIndex.toByte(),              // P2 = chunk index
                        chunk.size.toByte()               // Lc
                    ) + chunk
                    
                    Log.d(TAG, "📤 [MERCHANT] Sending chunk ${chunkIndex + 1}/$totalChunks (${chunk.size} bytes)")
                    response = isoDep.transceive(chunkCommand)
                    
                    if (!checkSuccessResponse(response)) {
                        logError("Failed to send chunk $chunkIndex", null)
                        return@withContext false
                    }
                    
                    offset += chunkSize
                    chunkIndex++
                }
                
                Log.d(TAG, "📤 [MERCHANT] All chunks sent successfully")
                return@withContext true
            }
        } catch (e: android.nfc.TagLostException) {
            logError("Tag lost while sending payment request", e)
            return@withContext false
        } catch (e: Exception) {
            logError("Error sending payment request via IsoDep", e)
            return@withContext false
        }
    }
    
    private fun checkSuccessResponse(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == 0x90.toByte() && 
               response[response.size - 1] == 0x00.toByte()
    }

    /**
     * Start monitoring for payment using the app's internal transaction system.
     * This uses the same adapters that power the Transactions screen, avoiding external API calls.
     */
    private fun startPaymentMonitoringInternal(
        acceptedTokens: List<AcceptedToken>,
        expectedAmount: BigDecimal
    ) {
        monitoringJob?.cancel()
        disposables.clear()
        
        this.expectedPaymentAmount = expectedAmount
        this.monitoringStartTime = System.currentTimeMillis()
        
        Log.d(TAG, "🔍 [MERCHANT] Starting internal payment monitoring for ${acceptedTokens.size} tokens")
        
        for (acceptedToken in acceptedTokens) {
            val wallet = acceptedToken.wallet ?: continue
            this.expectedReceiverAddress = acceptedToken.receiverAddress
            
            Log.d(TAG, "🔍 [MERCHANT] Monitoring wallet: ${wallet.token.coin.code} (${wallet.token.blockchainType.uid})")
            
            // Get the transactions adapter for this wallet using TransactionAdapterManager
            val transactionSource = wallet.transactionSource
            val transactionsAdapter: ITransactionsAdapter? = App.transactionAdapterManager.getAdapter(transactionSource)
            
            if (transactionsAdapter != null) {
                // Subscribe to new incoming transactions
                val disposable = transactionsAdapter.getTransactionRecordsFlowable(
                    token = wallet.token,
                    transactionType = FilterTransactionType.Incoming,
                    address = null
                ).subscribe(
                    { transactions ->
                        handleIncomingTransactions(transactions, acceptedToken, expectedAmount)
                    },
                    { error ->
                        Log.e(TAG, "Error monitoring transactions for ${wallet.token.coin.code}", error)
                    }
                )
                
                disposables.add(disposable)
            } else {
                Log.w(TAG, "⚠️ [MERCHANT] No transactions adapter available for ${wallet.token.coin.code}")
            }
        }
    }
    
    /**
     * Handle incoming transactions detected by the internal transaction system.
     */
    private fun handleIncomingTransactions(
        transactions: List<TransactionRecord>,
        acceptedToken: AcceptedToken,
        expectedAmount: BigDecimal
    ) {
        // Only consider transactions after monitoring started
        val recentTransactions = transactions.filter { tx ->
            (tx.timestamp * 1000) >= monitoringStartTime - 30000 // Allow 30s buffer for timestamp differences
        }
        
        if (recentTransactions.isEmpty()) return
        
        for (transaction in recentTransactions) {
            Log.d(TAG, "🔔 [MERCHANT] New transaction detected: ${transaction.transactionHash}")
            
            // Check if this transaction matches our expected payment
            val matchResult = checkTransactionMatch(transaction, acceptedToken, expectedAmount)
            
            if (matchResult.isMatch) {
                Log.d(TAG, "✅ [MERCHANT] Payment matched! Hash: ${transaction.transactionHash}, Amount: ${matchResult.receivedAmount}")
                
                viewModelScope.launch(Dispatchers.Main) {
                    updateStatus(ReceivePaymentStatus.FOUND)
                    
                    delay(1500)
                    updateStatus(ReceivePaymentStatus.WAITING_CONFIRMATION)
                    
                    delay(1500)
                    completePayment(transaction.transactionHash)
                }
                
                // Stop monitoring once we find a match
                disposables.clear()
                return
            }
        }
    }
    
    /**
     * Check if a transaction matches the expected payment.
     */
    private fun checkTransactionMatch(
        transaction: TransactionRecord,
        acceptedToken: AcceptedToken,
        expectedAmount: BigDecimal
    ): TransactionMatchResult {
        val expectedAmountToken = acceptedToken.amountInToken
        // Allow 1% tolerance for amount matching (due to rounding/fees)
        val tolerance = expectedAmountToken.multiply(BigDecimal("0.01"))
        val minAmount = expectedAmountToken.subtract(tolerance)
        
        when (transaction) {
            is EvmIncomingTransactionRecord -> {
                val receivedAmount = transaction.value.decimalValue ?: return TransactionMatchResult.noMatch()
                val isAmountMatch = receivedAmount >= minAmount
                
                Log.d(TAG, "📊 [MERCHANT] EVM Incoming - Received: $receivedAmount, Expected: $expectedAmountToken, Match: $isAmountMatch")
                
                if (isAmountMatch) {
                    return TransactionMatchResult(true, receivedAmount)
                }
            }
            
            is ExternalContractCallTransactionRecord -> {
                // For ERC-20 token transfers
                for (incomingEvent in transaction.incomingEvents) {
                    val receivedAmount = incomingEvent.value.decimalValue ?: continue
                    val isAmountMatch = receivedAmount >= minAmount
                    
                    Log.d(TAG, "📊 [MERCHANT] ERC-20 Transfer - Received: $receivedAmount, Expected: $expectedAmountToken, Match: $isAmountMatch")
                    
                    if (isAmountMatch) {
                        return TransactionMatchResult(true, receivedAmount)
                    }
                }
            }
            
            is BitcoinIncomingTransactionRecord -> {
                val receivedAmount = transaction.value.decimalValue ?: return TransactionMatchResult.noMatch()
                val isAmountMatch = receivedAmount >= minAmount
                
                Log.d(TAG, "📊 [MERCHANT] Bitcoin Incoming - Received: $receivedAmount, Expected: $expectedAmountToken, Match: $isAmountMatch")
                
                if (isAmountMatch) {
                    return TransactionMatchResult(true, receivedAmount)
                }
            }
            
            is TronIncomingTransactionRecord -> {
                val receivedAmount = transaction.value.decimalValue ?: return TransactionMatchResult.noMatch()
                val isAmountMatch = receivedAmount >= minAmount
                
                Log.d(TAG, "📊 [MERCHANT] Tron Incoming - Received: $receivedAmount, Expected: $expectedAmountToken, Match: $isAmountMatch")
                
                if (isAmountMatch) {
                    return TransactionMatchResult(true, receivedAmount)
                }
            }
            
            is SolanaIncomingTransactionRecord -> {
                val receivedAmount = transaction.value.decimalValue ?: return TransactionMatchResult.noMatch()
                val isAmountMatch = receivedAmount >= minAmount
                
                Log.d(TAG, "📊 [MERCHANT] Solana Incoming - Received: $receivedAmount, Expected: $expectedAmountToken, Match: $isAmountMatch")
                
                if (isAmountMatch) {
                    return TransactionMatchResult(true, receivedAmount)
                }
            }
            
            else -> {
                Log.d(TAG, "📊 [MERCHANT] Unknown transaction type: ${transaction::class.simpleName}")
            }
        }
        
        return TransactionMatchResult.noMatch()
    }
    
    private data class TransactionMatchResult(
        val isMatch: Boolean,
        val receivedAmount: BigDecimal?
    ) {
        companion object {
            fun noMatch() = TransactionMatchResult(false, null)
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
        } catch (e: android.nfc.TagLostException) {
            logError("Tag lost while sending payment URI", e)
            return@withContext false
        } catch (e: SecurityException) {
            logError("Security exception while sending payment URI", e)
            return@withContext false
        } catch (e: Exception) {
            logError("Error sending payment URI via IsoDep", e)
            return@withContext false
        }
    }

    override fun onCleared() {
        super.onCleared()
        monitoringJob?.cancel()
        disposables.clear()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun updateAmount(amountCents: Long) {
        val currency = currencyManager.baseCurrency
        val amountInCurrency = BigDecimal(amountCents).divide(BigDecimal(100))
        
        val formatter = NumberFormat.getCurrencyInstance().apply {
            this.currency = java.util.Currency.getInstance(currency.code)
        }
        val formatted = formatter.format(amountInCurrency)

        uiState = uiState.copy(
            amountCents = amountCents,
            amount = amountInCurrency,
            formattedAmount = formatted,
            chargeEnabled = amountCents >= MIN_AMOUNT_CENTS
        )
    }

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

/**
 * Payment status enum for NFC Receive (Merchant)
 */
enum class ReceivePaymentStatus {
    WAITING_FOR_CUSTOMER,
    CONNECTING,
    CONNECTED,
    SEARCHING,
    FOUND,
    WAITING_CONFIRMATION,
    CONFIRMED,
    FAILED
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
    val status: ReceivePaymentStatus? = null
)

