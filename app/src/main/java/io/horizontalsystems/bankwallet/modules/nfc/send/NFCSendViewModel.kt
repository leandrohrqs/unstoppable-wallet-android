package io.horizontalsystems.bankwallet.modules.nfc.send

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.IAccountManager
import io.horizontalsystems.bankwallet.core.IAdapterManager
import io.horizontalsystems.bankwallet.core.managers.CurrencyManager
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.nfc.core.BlockchainService
import io.horizontalsystems.bankwallet.modules.nfc.core.EIP681Parser
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCManager
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCPaymentRequest
import io.horizontalsystems.bankwallet.modules.nfc.core.TokenPaymentOption
import io.horizontalsystems.bankwallet.modules.nfc.core.WalletIntegrationHelper
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.TokenType
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for NFC Send (Customer/Payment) screen.
 * Manages HCE service activation and payment state.
 */
class NFCSendViewModel(
    private val accountManager: IAccountManager,
    private val adapterManager: IAdapterManager,
    private val currencyManager: CurrencyManager
) : ViewModel() {

    companion object {
        private const val TAG = "NFCSendViewModel"
        const val ACTION_PAYMENT_URI_RECEIVED = "io.horizontalsystems.bankwallet.PAYMENT_URI_RECEIVED"
        const val EXTRA_PAYMENT_URI = "payment_uri"
        const val ACTION_PAYMENT_REQUEST_RECEIVED = "io.horizontalsystems.bankwallet.PAYMENT_REQUEST_RECEIVED"
        const val EXTRA_PAYMENT_REQUEST = "payment_request"
        const val ACTION_TRANSACTION_SENT = "io.horizontalsystems.bankwallet.NFC_TRANSACTION_SENT"
        const val EXTRA_TRANSACTION_HASH = "transaction_hash"
        const val EXTRA_CHAIN_ID = "chain_id"
    }

    var uiState by mutableStateOf(NFCSendUiState())
        private set

    private val walletIntegrationHelper = WalletIntegrationHelper(accountManager, adapterManager, App.walletManager)
    private var monitoringJob: Job? = null
    private var nfcManager: NFCManager? = null

    private val paymentUriReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAYMENT_URI_RECEIVED -> {
                    val paymentUri = intent.getStringExtra(EXTRA_PAYMENT_URI)
                    if (paymentUri != null) {
                        handlePaymentUri(paymentUri)
                    }
                }
                ACTION_PAYMENT_REQUEST_RECEIVED -> {
                    val paymentRequest = intent.getStringExtra(EXTRA_PAYMENT_REQUEST)
                    if (paymentRequest != null) {
                        handlePaymentRequest(paymentRequest)
                    }
                }
                ACTION_TRANSACTION_SENT -> {
                    val transactionHash = intent.getStringExtra(EXTRA_TRANSACTION_HASH)
                    val chainId = intent.getIntExtra(EXTRA_CHAIN_ID, -1)
                    if (transactionHash != null && chainId != -1) {
                        onTransactionSent(transactionHash, chainId)
                    }
                }
            }
        }
    }

    init {
        loadWalletAddress()
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_PAYMENT_URI_RECEIVED)
            addAction(ACTION_PAYMENT_REQUEST_RECEIVED)
            addAction(ACTION_TRANSACTION_SENT)
        }
        LocalBroadcastManager.getInstance(App.instance).registerReceiver(paymentUriReceiver, intentFilter)
    }

    /**
     * Initialize NFC manager with context
     */
    fun initialize(context: Context) {
        nfcManager = NFCManager(context)
        checkNFCStatus()
    }

    /**
     * Check NFC status (system and app level)
     */
    fun checkNFCStatus() {
        val manager = nfcManager ?: return
        val nfcSystemEnabled = manager.isNFCEnabled()
        val nfcAppEnabled = App.localStorage.nfcEnabled
        val nfcAvailable = manager.isNFCAvailable()

        uiState = uiState.copy(
            nfcSystemEnabled = nfcSystemEnabled,
            nfcAppEnabled = nfcAppEnabled,
            nfcAvailable = nfcAvailable,
            isActive = nfcSystemEnabled && nfcAppEnabled
        )
    }

    /**
     * Request NFC enable in system settings
     */
    fun requestNFCEnable() {
        nfcManager?.openNFCSettings()
    }

    /**
     * Enable NFC in app (after system is enabled)
     */
    fun enableNFCInApp() {
        App.localStorage.nfcEnabled = true
        // Reset dismissed flag so modal can appear again if user disables and re-enables
        App.localStorage.nfcEnableDialogDismissed = false
        uiState = uiState.copy(
            nfcAppEnabled = true,
            isActive = uiState.nfcSystemEnabled && true
        )
    }

    /**
     * Mark the NFC enable dialog as dismissed (user canceled)
     */
    fun dismissNFCEnableDialog() {
        App.localStorage.nfcEnableDialogDismissed = true
    }

    /**
     * Check if NFC enable dialog should be shown
     */
    fun shouldShowNFCEnableDialog(): Boolean {
        return !App.localStorage.nfcEnableDialogDismissed
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(App.instance).unregisterReceiver(paymentUriReceiver)
        monitoringJob?.cancel()
    }


    /**
     * Update status message
     */
    fun updateStatusMessage(message: String) {
        uiState = uiState.copy(statusMessage = message)
    }

    /**
     * Handle payment URI received from merchant.
     * Parses the URI and navigates to Send screen with pre-filled data.
     */
    fun handlePaymentUri(paymentUri: String) {
        // Update status to show device was connected
        uiState = uiState.copy(
            paymentStatus = SendPaymentStatus.CONNECTED
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paymentRequest = EIP681Parser.parse(paymentUri)
                if (paymentRequest == null) {
                    logError("Failed to parse payment URI", null)
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(
                            statusMessage = "Invalid payment request",
                            paymentStatus = SendPaymentStatus.FAILED
                        )
                    }
                    return@launch
                }

                val wallet = findWalletForToken(paymentRequest.token.address, paymentRequest.blockchainType)
                if (wallet == null) {
                    logError("No wallet found for token", null)
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(
                            statusMessage = "Token ${paymentRequest.token.symbol} not available in your wallet",
                            paymentStatus = SendPaymentStatus.FAILED
                        )
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(
                        navigationEvent = NFCSendNavigationEvent.NavigateToSend(
                            wallet = wallet,
                            recipientAddress = paymentRequest.recipient,
                            amount = paymentRequest.amount
                        ),
                        paymentStatus = SendPaymentStatus.CONNECTED
                    )
                }

            } catch (e: Exception) {
                logError("Error handling payment URI", e)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(
                        statusMessage = "Error processing payment request: ${e.message}",
                        paymentStatus = SendPaymentStatus.FAILED
                    )
                }
            }
        }
    }
    
    /**
     * Handle multi-token payment request received from merchant.
     * Shows token selection screen to the customer.
     */
    fun handlePaymentRequest(paymentRequestJson: String) {
        Log.d(TAG, "📥 [CUSTOMER] Received payment request: $paymentRequestJson")
        
        // Update status to show device was connected
        uiState = uiState.copy(
            paymentStatus = SendPaymentStatus.CONNECTED
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paymentRequest = NFCPaymentRequest.fromJson(paymentRequestJson)
                if (paymentRequest == null) {
                    logError("Failed to parse payment request", null)
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(
                            statusMessage = "Invalid payment request",
                            paymentStatus = SendPaymentStatus.FAILED
                        )
                    }
                    return@launch
                }
                
                val senderAccountId = NFCConfigManager.senderAccountId
                
                // Process each token option and check balances
                val customerOptions = paymentRequest.tokens.map { tokenOption ->
                    val blockchainType = walletIntegrationHelper.getBlockchainTypeFromUid(tokenOption.blockchainUid)
                    val wallet = if (blockchainType != null) {
                        findWalletForToken(tokenOption.tokenAddress, blockchainType)
                    } else null
                    
                    val balance = wallet?.let { w ->
                        adapterManager.getBalanceAdapterForWallet(w)?.balanceData?.available
                    }
                    
                    val requiredAmount = tokenOption.getAmountBigDecimal()
                    val hasSufficientBalance = balance != null && balance >= requiredAmount
                    
                    CustomerTokenOption(
                        tokenOption = tokenOption,
                        balance = balance,
                        hasSufficientBalance = hasSufficientBalance,
                        wallet = wallet
                    )
                }.sortedWith(
                    // Sort: sufficient balance first, then by balance descending
                    compareByDescending<CustomerTokenOption> { it.hasSufficientBalance }
                        .thenByDescending { it.balance ?: BigDecimal.ZERO }
                )
                
                withContext(Dispatchers.Main) {
                    if (customerOptions.isEmpty()) {
                        uiState = uiState.copy(
                            statusMessage = "No payment tokens available",
                            paymentStatus = SendPaymentStatus.FAILED
                        )
                        return@withContext
                    }
                    
                    // Navigate to NFC Payment screen with all options
                    // The selection will be done on that screen
                    uiState = uiState.copy(
                        availableTokens = customerOptions,
                        fiatAmount = paymentRequest.fiat.amount,
                        fiatCurrency = paymentRequest.fiat.currency,
                        paymentStatus = SendPaymentStatus.CONNECTED,
                        navigationEvent = NFCSendNavigationEvent.NavigateToNFCPayment(
                            fiatAmount = paymentRequest.fiat.amount,
                            fiatCurrency = paymentRequest.fiat.currency,
                            availableTokens = customerOptions
                        )
                    )
                }
                
            } catch (e: Exception) {
                logError("Error handling payment request", e)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(
                        statusMessage = "Error processing payment request: ${e.message}",
                        paymentStatus = SendPaymentStatus.FAILED
                    )
                }
            }
        }
    }
    
    /**
     * Select a token (just marks it as selected, doesn't navigate yet)
     */
    fun selectToken(option: CustomerTokenOption) {
        if (!option.hasSufficientBalance) return
        
        uiState = uiState.copy(
            selectedToken = option
        )
    }
    
    /**
     * Confirm the selected token and navigate to payment
     */
    fun confirmSelectedToken() {
        val option = uiState.selectedToken ?: return
        
        val wallet = option.wallet
        if (wallet == null) {
            uiState = uiState.copy(
                statusMessage = "Token not available in your wallet",
                paymentStatus = SendPaymentStatus.FAILED,
                showTokenSelector = false,
                selectedToken = null
            )
            return
        }
        
        if (!option.hasSufficientBalance) {
            uiState = uiState.copy(
                statusMessage = "Insufficient balance for ${option.tokenOption.symbol}",
                paymentStatus = SendPaymentStatus.FAILED,
                showTokenSelector = false,
                selectedToken = null
            )
            return
        }
        
        uiState = uiState.copy(
            showTokenSelector = false,
            selectedToken = null,
            navigationEvent = NFCSendNavigationEvent.NavigateToSend(
                wallet = wallet,
                recipientAddress = option.tokenOption.receiverAddress,
                amount = option.tokenOption.getAmountBigDecimal()
            ),
            paymentStatus = SendPaymentStatus.CONNECTED
        )
    }
    
    /**
     * Called when customer selects a token for payment (legacy - direct selection).
     */
    fun selectTokenForPayment(option: CustomerTokenOption) {
        selectToken(option)
        confirmSelectedToken()
    }
    
    /**
     * Cancel token selection
     */
    fun cancelTokenSelection() {
        uiState = uiState.copy(
            showTokenSelector = false,
            availableTokens = emptyList(),
            selectedToken = null,
            paymentStatus = null
        )
    }
    
    /**
     * Called when transaction is sent - update status and start monitoring
     */
    fun onTransactionSent(transactionHash: String, chainId: Int) {
        uiState = uiState.copy(
            paymentStatus = SendPaymentStatus.SENT
        )
        
        viewModelScope.launch {
            delay(1500)
            if (uiState.paymentStatus == SendPaymentStatus.SENT) {
                uiState = uiState.copy(
                    paymentStatus = SendPaymentStatus.SEARCHING
                )
            }
        }
        
        startTransactionMonitoring(transactionHash, chainId)
    }

    /**
     * Clear navigation event after it's been consumed
     */
    fun clearNavigationEvent() {
        uiState = uiState.copy(navigationEvent = null)
    }
    
    /**
     * Reset to waiting state when user returns from payment flow.
     * Called when ON_RESUME is detected and we're in CONNECTED or later state.
     */
    fun resetToWaitingIfNeeded() {
        val currentStatus = uiState.paymentStatus
        // Reset if we're in a state that indicates we navigated away
        if (currentStatus == SendPaymentStatus.CONNECTED || 
            currentStatus == SendPaymentStatus.CONFIRMED ||
            currentStatus == SendPaymentStatus.FAILED) {
            Log.d(TAG, "Resetting to WAITING state from $currentStatus")
            uiState = uiState.copy(
                paymentStatus = SendPaymentStatus.WAITING,
                statusMessage = "Ready for NFC payment",
                availableTokens = emptyList(),
                selectedToken = null,
                fiatAmount = null,
                fiatCurrency = null,
                navigationEvent = null
            )
        }
    }

    /**
     * Find wallet matching the token address and blockchain type.
     * Uses the sender account configured in NFCConfigManager, falling back to active account.
     * For native tokens (address = "native" or 0x000...), matches by blockchain type.
     * For ERC-20 tokens, matches by contract address.
     */
    private suspend fun findWalletForToken(tokenAddress: String, blockchainType: io.horizontalsystems.marketkit.models.BlockchainType): Wallet? {
        return withContext(Dispatchers.IO) {
            try {
                // Use sender account from NFC config, fallback to active account
                val senderAccountId = NFCConfigManager.getSenderAccountOrDefault()
                val senderAccount = senderAccountId?.let { accountManager.account(it) } 
                    ?: accountManager.activeAccount 
                    ?: return@withContext null
                
                // Get wallets for the sender account specifically
                val wallets = App.walletManager.activeWallets.filter { it.account.id == senderAccount.id }
                
                Log.d(TAG, "Finding wallet for token $tokenAddress on $blockchainType in account ${senderAccount.name} (${wallets.size} wallets)")

                val isNativeToken = tokenAddress == "native" ||
                        tokenAddress == "0x0000000000000000000000000000000000000000" ||
                        tokenAddress.lowercase() == "0x0000000000000000000000000000000000000000"

                if (isNativeToken) {
                    // For native tokens, match by blockchain type and any native-like token type
                    val wallet = wallets.firstOrNull { wallet ->
                        wallet.token.blockchainType == blockchainType &&
                        (wallet.token.type is TokenType.Native ||
                         wallet.token.type is TokenType.Derived ||
                         wallet.token.type is TokenType.AddressTyped)
                    }
                    Log.d(TAG, "Native token wallet found: ${wallet?.token?.coin?.code}")
                    return@withContext wallet
                } else {
                    // For contract-based tokens, match by address
                    val wallet = wallets.firstOrNull { wallet ->
                        wallet.token.blockchainType == blockchainType &&
                        (wallet.token.type as? TokenType.Eip20)?.address?.equals(tokenAddress, ignoreCase = true) == true
                    }
                    Log.d(TAG, "ERC-20 token wallet found: ${wallet?.token?.coin?.code}")
                    return@withContext wallet
                }
            } catch (e: Exception) {
                logError("Error finding wallet for token", e)
                null
            }
        }
    }

    private fun loadWalletAddress() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val address = walletIntegrationHelper.getPrimaryWalletAddress()
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(walletAddress = address)
                }
            } catch (e: Exception) {
                logError("Error loading wallet address", e)
            }
        }
    }

    /**
     * Start monitoring transaction confirmation
     */
    fun startTransactionMonitoring(transactionHash: String, chainId: Int) {
        monitoringJob?.cancel()
        
        Log.d(TAG, "🔍 [CUSTOMER] Starting transaction monitoring. Hash: $transactionHash, ChainId: $chainId")
        
        uiState = uiState.copy(
            isWaitingForConfirmation = true,
            transactionHash = transactionHash,
            chainId = chainId,
            isPaymentConfirmed = false,
            paymentStatus = SendPaymentStatus.SEARCHING
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val blockchainService = BlockchainService(App.instance)
                var foundStatusShown = false
                
                while (true) {
                    delay(3000)
                    
                    val status = blockchainService.getTransactionStatus(transactionHash, chainId, minConfirmations = 1)
                    
                    when (status) {
                        BlockchainService.TransactionStatus.SUCCESS -> {
                            if (!foundStatusShown) {
                                withContext(Dispatchers.Main) {
                                    uiState = uiState.copy(paymentStatus = SendPaymentStatus.FOUND)
                                }
                                delay(500)
                            }
                            withContext(Dispatchers.Main) {
                                completePayment(transactionHash)
                            }
                            break
                        }
                        BlockchainService.TransactionStatus.FAILED -> {
                            withContext(Dispatchers.Main) {
                                uiState = uiState.copy(
                                    isWaitingForConfirmation = false,
                                    statusMessage = "Transaction failed",
                                    paymentStatus = SendPaymentStatus.FAILED
                                )
                            }
                            break
                        }
                        BlockchainService.TransactionStatus.PENDING,
                        BlockchainService.TransactionStatus.UNKNOWN -> {
                            withContext(Dispatchers.Main) {
                                if (!foundStatusShown) {
                                    // Transaction found but not yet confirmed
                                    uiState = uiState.copy(paymentStatus = SendPaymentStatus.FOUND)
                                    foundStatusShown = true
                                }
                            }
                            if (foundStatusShown) {
                                delay(1000)
                                withContext(Dispatchers.Main) {
                                    uiState = uiState.copy(paymentStatus = SendPaymentStatus.WAITING_CONFIRMATION)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logError("Error monitoring transaction", e)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(
                        isWaitingForConfirmation = false,
                        statusMessage = "Error monitoring transaction",
                        paymentStatus = SendPaymentStatus.FAILED
                    )
                }
            }
        }
    }
    
    /**
     * Complete payment successfully
     */
    fun completePayment(transactionHash: String) {
        Log.d(TAG, "✅ [CUSTOMER] Payment confirmed! Transaction hash: $transactionHash")
        uiState = uiState.copy(
            isWaitingForConfirmation = false,
            isPaymentConfirmed = true,
            transactionHash = transactionHash,
            paymentStatus = SendPaymentStatus.CONFIRMED
        )
    }
    
    /**
     * Reset transaction status after showing confirmation
     */
    fun resetTransactionStatus() {
        uiState = uiState.copy(
            isWaitingForConfirmation = false,
            isPaymentConfirmed = false,
            transactionHash = null,
            chainId = null,
            paymentStatus = null
        )
        monitoringJob?.cancel()
    }

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

/**
 * Payment status for NFC Send (Customer)
 */
enum class SendPaymentStatus {
    WAITING,
    CONNECTED,
    SENT,
    SEARCHING,
    FOUND,
    WAITING_CONFIRMATION,
    CONFIRMED,
    FAILED
}

/**
 * UI state for NFC Send screen
 */
data class NFCSendUiState(
    val isActive: Boolean = false,
    val statusMessage: String = "",
    val walletAddress: String? = null,
    val navigationEvent: NFCSendNavigationEvent? = null,
    val isWaitingForConfirmation: Boolean = false,
    val transactionHash: String? = null,
    val isPaymentConfirmed: Boolean = false,
    val chainId: Int? = null,
    val nfcSystemEnabled: Boolean = false,
    val nfcAppEnabled: Boolean = false,
    val nfcAvailable: Boolean = false,
    val paymentStatus: SendPaymentStatus? = null,
    // Multi-token payment selection
    val showTokenSelector: Boolean = false,
    val availableTokens: List<CustomerTokenOption> = emptyList(),
    val selectedToken: CustomerTokenOption? = null,
    val fiatAmount: String? = null,
    val fiatCurrency: String? = null
)

/**
 * Customer token option with balance information
 */
@kotlinx.parcelize.Parcelize
data class CustomerTokenOption(
    val tokenOption: TokenPaymentOption,
    val balanceString: String?,
    val hasSufficientBalance: Boolean,
    val wallet: Wallet?
) : android.os.Parcelable {
    val balance: BigDecimal?
        get() = balanceString?.let { BigDecimal(it) }
    
    constructor(
        tokenOption: TokenPaymentOption,
        balance: BigDecimal?,
        hasSufficientBalance: Boolean,
        wallet: Wallet?
    ) : this(
        tokenOption = tokenOption,
        balanceString = balance?.toPlainString(),
        hasSufficientBalance = hasSufficientBalance,
        wallet = wallet
    )
}

/**
 * Navigation events for NFC Send screen
 */
sealed class NFCSendNavigationEvent {
    data class NavigateToSend(
        val wallet: Wallet,
        val recipientAddress: String,
        val amount: java.math.BigDecimal
    ) : NFCSendNavigationEvent()
    
    data class NavigateToNFCPayment(
        val fiatAmount: String,
        val fiatCurrency: String,
        val availableTokens: List<CustomerTokenOption>
    ) : NFCSendNavigationEvent()
}

