package io.horizontalsystems.bankwallet.modules.nfc.status

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.modules.main.MainModule
import io.horizontalsystems.bankwallet.modules.main.MainViewModel
import io.horizontalsystems.bankwallet.modules.nfc.core.BlockchainService
import io.horizontalsystems.bankwallet.modules.transactions.FilterTransactionType
import io.horizontalsystems.bankwallet.modules.transactions.TransactionsModule
import io.horizontalsystems.bankwallet.modules.transactions.TransactionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for NFC Transaction Status screen.
 * Manages transaction monitoring and status updates.
 */
class NFCTransactionStatusViewModel : ViewModel() {

    companion object {
        private const val MONITORING_INTERVAL_MS = 3000L
        private const val MAX_RETRIES = 20
    }

    var uiState by mutableStateOf(NFCTransactionStatusUiState())
        private set

    private var monitoringJob: Job? = null
    private var navController: NavController? = null
    private val navigationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Initialize transaction monitoring with hash and chain ID
     */
    fun initialize(transactionHash: String, chainId: Int) {
        if (uiState.transactionHash != null) {
            return
        }

        uiState = uiState.copy(
            transactionHash = transactionHash,
            chainId = chainId,
            status = TransactionStatus.SENT
        )

        viewModelScope.launch {
            delay(1500)
            if (uiState.status == TransactionStatus.SENT) {
                updateStatus(TransactionStatus.SEARCHING)
            }
        }

        startMonitoring(transactionHash, chainId)
    }

    /**
     * Start monitoring transaction status on blockchain
     */
    private fun startMonitoring(transactionHash: String, chainId: Int) {
        monitoringJob?.cancel()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val blockchainService = BlockchainService(App.instance)
                var retries = 0
                var foundStatusShownLocal = false
                
                while (retries < MAX_RETRIES) {
                    delay(MONITORING_INTERVAL_MS)
                    
                    val status = blockchainService.getTransactionStatus(transactionHash, chainId, minConfirmations = 1)
                    
                    when (status) {
                        BlockchainService.TransactionStatus.SUCCESS -> {
                            withContext(Dispatchers.Main) {
                                updateStatus(TransactionStatus.CONFIRMED)
                                navigateToTransactions()
                            }
                            break
                        }
                        BlockchainService.TransactionStatus.FAILED -> {
                            withContext(Dispatchers.Main) {
                                uiState = uiState.copy(
                                    status = TransactionStatus.FAILED,
                                    errorMessage = "Transaction failed on blockchain"
                                )
                                navigateToTransactions()
                            }
                            break
                        }
                        BlockchainService.TransactionStatus.PENDING -> {
                            if (!foundStatusShownLocal) {
                                foundStatusShownLocal = true
                                withContext(Dispatchers.Main) {
                                    updateStatus(TransactionStatus.FOUND)
                                    viewModelScope.launch {
                                        delay(2000)
                                        updateStatus(TransactionStatus.WAITING)
                                    }
                                }
                            }
                            retries++
                        }
                        BlockchainService.TransactionStatus.UNKNOWN -> {
                            retries++
                            if (retries >= 2 && !foundStatusShownLocal) {
                                foundStatusShownLocal = true
                                withContext(Dispatchers.Main) {
                                    updateStatus(TransactionStatus.FOUND)
                                    viewModelScope.launch {
                                        delay(2000)
                                        updateStatus(TransactionStatus.WAITING)
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (retries >= MAX_RETRIES && uiState.status != TransactionStatus.CONFIRMED) {
                    withContext(Dispatchers.Main) {
                        if (uiState.status == TransactionStatus.SENT || uiState.status == TransactionStatus.SEARCHING) {
                            updateStatus(TransactionStatus.FOUND)
                            viewModelScope.launch {
                                delay(2000)
                                updateStatus(TransactionStatus.WAITING)
                            }
                        }
                        navigateToTransactions()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (uiState.status == TransactionStatus.SENT || uiState.status == TransactionStatus.SEARCHING) {
                        updateStatus(TransactionStatus.FOUND)
                        viewModelScope.launch {
                            delay(2000)
                            updateStatus(TransactionStatus.WAITING)
                        }
                    }
                    navigateToTransactions()
                }
            }
        }
    }

    /**
     * Update transaction status
     */
    private fun updateStatus(status: TransactionStatus) {
        uiState = uiState.copy(status = status)
    }

    /**
     * Set NavController for navigation
     */
    fun setNavController(navController: NavController) {
        this.navController = navController
    }

    /**
     * Mark transaction as confirmed and ready for navigation
     */
    private fun navigateToTransactions() {
        uiState = uiState.copy(shouldNavigateToTransactions = true)
        
        navController?.let { nav ->
            navigationScope.launch {
                delay(1500)
                navigateToTransactionsTab(nav)
            }
        }
    }
    
    /**
     * Navigate to Transactions tab in MainFragment and select "All" filter
     */
    private suspend fun navigateToTransactionsTab(navController: NavController) {
        try {
            navController.popBackStack(R.id.mainFragment, false)
            delay(1200)
            
            var retries = 30
            var success = false
            while (retries > 0 && !success) {
                try {
                    val mainBackStackEntry = navController.getBackStackEntry(R.id.mainFragment)
                    
                    val mainViewModelProvider = ViewModelProvider(
                        mainBackStackEntry.viewModelStore,
                        MainModule.Factory()
                    )
                    val mainViewModel = mainViewModelProvider[MainViewModel::class.java]
                    
                    val transactionsNav = MainModule.MainNavigation.Transactions
                    mainViewModel.onSelect(transactionsNav)
                    delay(500)
                    mainViewModel.onSelect(transactionsNav)
                    delay(500)
                    
                    val transactionsViewModelProvider = ViewModelProvider(
                        mainBackStackEntry.viewModelStore,
                        TransactionsModule.Factory()
                    )
                    val transactionsViewModel = transactionsViewModelProvider[TransactionsViewModel::class.java]
                    transactionsViewModel.setFilterTransactionType(FilterTransactionType.All)
                    
                    delay(1500)
                    success = true
                } catch (e: IllegalArgumentException) {
                    retries--
                    if (retries > 0) {
                        delay(500)
                    }
                } catch (e: Exception) {
                    retries--
                    if (retries > 0) {
                        delay(500)
                    } else {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Navigation failed
        }
    }

    override fun onCleared() {
        super.onCleared()
        monitoringJob?.cancel()
        // Don't cancel navigationScope here - navigation needs to complete even after ViewModel is cleared
    }
}

/**
 * Transaction status enum
 */
enum class TransactionStatus {
    SENT,
    SEARCHING,
    FOUND,
    WAITING,
    CONFIRMED,
    FAILED
}

/**
 * UI state for NFC Transaction Status screen
 */
data class NFCTransactionStatusUiState(
    val status: TransactionStatus = TransactionStatus.SENT,
    val transactionHash: String? = null,
    val chainId: Int? = null,
    val errorMessage: String? = null,
    val shouldNavigateToTransactions: Boolean = false
)
