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
import io.horizontalsystems.bankwallet.modules.nfc.core.WalletIntegrationHelper
import io.horizontalsystems.marketkit.models.TokenType
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
        const val ACTION_TRANSACTION_SENT = "io.horizontalsystems.bankwallet.NFC_TRANSACTION_SENT"
        const val EXTRA_TRANSACTION_HASH = "transaction_hash"
        const val EXTRA_CHAIN_ID = "chain_id"
    }

    var uiState by mutableStateOf(NFCSendUiState())
        private set

    private val walletIntegrationHelper = WalletIntegrationHelper(accountManager, adapterManager, App.walletManager)
    private var monitoringJob: Job? = null

    private val paymentUriReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAYMENT_URI_RECEIVED -> {
                    val paymentUri = intent.getStringExtra(EXTRA_PAYMENT_URI)
                    if (paymentUri != null) {
                        handlePaymentUri(paymentUri)
                    }
                }
                ACTION_TRANSACTION_SENT -> {
                    val transactionHash = intent.getStringExtra(EXTRA_TRANSACTION_HASH)
                    val chainId = intent.getIntExtra(EXTRA_CHAIN_ID, -1)
                    if (transactionHash != null && chainId != -1) {
                        startTransactionMonitoring(transactionHash, chainId)
                    }
                }
            }
        }
    }

    init {
        loadWalletAddress()
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_PAYMENT_URI_RECEIVED)
            addAction(ACTION_TRANSACTION_SENT)
        }
        LocalBroadcastManager.getInstance(App.instance).registerReceiver(paymentUriReceiver, intentFilter)
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(App.instance).unregisterReceiver(paymentUriReceiver)
        monitoringJob?.cancel()
    }

    /**
     * Activate NFC card emulation
     */
    fun activateNFC() {
        uiState = uiState.copy(
            isActive = true,
            statusMessage = "Hold your device near the merchant's terminal"
        )
    }

    /**
     * Deactivate NFC card emulation
     */
    fun deactivateNFC() {
        uiState = uiState.copy(
            isActive = false,
            statusMessage = ""
        )
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paymentRequest = EIP681Parser.parse(paymentUri)
                if (paymentRequest == null) {
                    logError("Failed to parse payment URI", null)
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(
                            statusMessage = "Invalid payment request"
                        )
                    }
                    return@launch
                }

                val wallet = findWalletForToken(paymentRequest.token.address, paymentRequest.blockchainType)
                if (wallet == null) {
                    logError("No wallet found for token", null)
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(
                            statusMessage = "Token ${paymentRequest.token.symbol} not available in your wallet"
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
                        statusMessage = "Opening payment screen..."
                    )
                }

            } catch (e: Exception) {
                logError("Error handling payment URI", e)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(
                        statusMessage = "Error processing payment request: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Clear navigation event after it's been consumed
     */
    fun clearNavigationEvent() {
        uiState = uiState.copy(navigationEvent = null)
    }

    /**
     * Find wallet matching the token address and blockchain type.
     * For native tokens (address = 0x000...), matches by blockchain type.
     * For ERC-20 tokens, matches by contract address.
     */
    private suspend fun findWalletForToken(tokenAddress: String, blockchainType: io.horizontalsystems.marketkit.models.BlockchainType): Wallet? {
        return withContext(Dispatchers.IO) {
            try {
                val activeAccount = accountManager.activeAccount ?: return@withContext null
                val wallets = App.walletManager.activeWallets

                val isNativeToken = tokenAddress == "0x0000000000000000000000000000000000000000" ||
                        tokenAddress.lowercase() == "0x0000000000000000000000000000000000000000"

                if (isNativeToken) {
                    val wallet = wallets.firstOrNull { wallet ->
                        wallet.token.blockchainType == blockchainType &&
                        wallet.token.type is TokenType.Native
                    }
                    return@withContext wallet
                } else {
                    val wallet = wallets.firstOrNull { wallet ->
                        wallet.token.blockchainType == blockchainType &&
                        (wallet.token.type as? TokenType.Eip20)?.address?.equals(tokenAddress, ignoreCase = true) == true
                    }
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
            isPaymentConfirmed = false
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val blockchainService = BlockchainService(App.instance)
                
                while (true) {
                    delay(3000)
                    
                    val status = blockchainService.getTransactionStatus(transactionHash, chainId, minConfirmations = 1)
                    
                    when (status) {
                        BlockchainService.TransactionStatus.SUCCESS -> {
                            viewModelScope.launch(Dispatchers.Main) {
                                completePayment(transactionHash)
                            }
                            break
                        }
                        BlockchainService.TransactionStatus.FAILED -> {
                            viewModelScope.launch(Dispatchers.Main) {
                                uiState = uiState.copy(
                                    isWaitingForConfirmation = false,
                                    statusMessage = "Transaction failed"
                                )
                            }
                            break
                        }
                        BlockchainService.TransactionStatus.PENDING,
                        BlockchainService.TransactionStatus.UNKNOWN -> {
                        }
                    }
                }
            } catch (e: Exception) {
                logError("Error monitoring transaction", e)
                viewModelScope.launch(Dispatchers.Main) {
                    uiState = uiState.copy(
                        isWaitingForConfirmation = false,
                        statusMessage = "Error monitoring transaction"
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
            statusMessage = "Payment confirmed"
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
            chainId = null
        )
        monitoringJob?.cancel()
    }

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
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
    val chainId: Int? = null
)

/**
 * Navigation events for NFC Send screen
 */
sealed class NFCSendNavigationEvent {
    data class NavigateToSend(
        val wallet: Wallet,
        val recipientAddress: String,
        val amount: java.math.BigDecimal
    ) : NFCSendNavigationEvent()
}

