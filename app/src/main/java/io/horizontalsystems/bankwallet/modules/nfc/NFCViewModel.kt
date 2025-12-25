package io.horizontalsystems.bankwallet.modules.nfc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.IAccountManager
import io.horizontalsystems.bankwallet.core.IAdapterManager
import io.horizontalsystems.bankwallet.core.managers.CurrencyManager
import io.horizontalsystems.bankwallet.modules.nfc.NFCModule.NFCTab
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCManager
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCStatus
import io.horizontalsystems.bankwallet.modules.nfc.core.WalletIntegrationHelper
import kotlinx.coroutines.launch

/**
 * ViewModel for NFC payment screen.
 * Manages state for both receive (POS) and send (customer) modes.
 */
class NFCViewModel(
    private val accountManager: IAccountManager,
    private val adapterManager: IAdapterManager,
    private val currencyManager: CurrencyManager
) : ViewModel() {

    private lateinit var nfcManager: NFCManager
    private lateinit var walletHelper: WalletIntegrationHelper

    var selectedTab by mutableStateOf(NFCTab.RECEIVE)
        private set

    var nfcStatus by mutableStateOf<NFCStatus?>(null)
        private set

    var hasWallet by mutableStateOf(false)
        private set

    /**
     * Initialize NFC manager (called from UI with context)
     */
    fun initialize(context: Context) {
        nfcManager = NFCManager(context)
        walletHelper = WalletIntegrationHelper(
            accountManager,
            App.adapterManager,
            App.walletManager
        )

        checkNFCStatus()
        checkWalletStatus()
    }

    /**
     * Switch between receive and send tabs
     */
    fun onTabSelect(tab: NFCTab) {
        selectedTab = tab
    }

    /**
     * Check NFC availability and status
     */
    fun checkNFCStatus() {
        if (!::nfcManager.isInitialized) return

        nfcStatus = nfcManager.getNFCStatus()
    }

    /**
     * Check if user has active wallets
     */
    private fun checkWalletStatus() {
        viewModelScope.launch {
            hasWallet = walletHelper.hasActiveWallets()
        }
    }

    /**
     * Open NFC settings
     */
    fun openNFCSettings() {
        if (::nfcManager.isInitialized) {
            nfcManager.openNFCSettings()
        }
    }

    /**
     * Get NFC status message
     */
    fun getNFCStatusMessage(): String {
        return if (::nfcManager.isInitialized) {
            nfcManager.getStatusMessage()
        } else {
            "Initializing..."
        }
    }
}

