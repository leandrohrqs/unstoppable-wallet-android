package io.horizontalsystems.bankwallet.modules.nfc.core

import android.content.Context
import android.content.SharedPreferences
import io.horizontalsystems.bankwallet.core.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Configuration manager for NFC payment settings.
 * Persists receiver/sender wallet selections.
 * 
 * Note: Accepted tokens are now determined by the tokens enabled in the receiver wallet
 * via the Coin Manager, not by a separate configuration.
 */
object NFCConfigManager {
    private const val PREFS_NAME = "nfc_config_prefs"
    private const val KEY_RECEIVER_ACCOUNT_ID = "receiver_account_id"
    private const val KEY_SENDER_ACCOUNT_ID = "sender_account_id"
    private const val KEY_PAYMENT_ACTIVE = "payment_active"
    
    private var preferences: SharedPreferences? = null
    private var isInitialized = false
    
    // State flows for reactive updates
    private val _receiverAccountIdFlow = MutableStateFlow<String?>(null)
    val receiverAccountIdFlow: StateFlow<String?> = _receiverAccountIdFlow.asStateFlow()
    
    private val _senderAccountIdFlow = MutableStateFlow<String?>(null)
    val senderAccountIdFlow: StateFlow<String?> = _senderAccountIdFlow.asStateFlow()
    
    private val _paymentActiveFlow = MutableStateFlow(false)
    val paymentActiveFlow: StateFlow<Boolean> = _paymentActiveFlow.asStateFlow()
    
    // Track if the NFC Send screen is currently active (to control HCE service)
    private val _isSendScreenActive = MutableStateFlow(false)
    val isSendScreenActiveFlow: StateFlow<Boolean> = _isSendScreenActive.asStateFlow()
    
    /**
     * Initialize the config manager with context.
     * Safe to call multiple times - will only initialize once.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load initial values
        _receiverAccountIdFlow.update { preferences?.getString(KEY_RECEIVER_ACCOUNT_ID, null) }
        _senderAccountIdFlow.update { preferences?.getString(KEY_SENDER_ACCOUNT_ID, null) }
        _paymentActiveFlow.update { preferences?.getBoolean(KEY_PAYMENT_ACTIVE, false) ?: false }
        
        isInitialized = true
    }
    
    /**
     * Receiver account ID for receiving NFC payments.
     */
    var receiverAccountId: String?
        get() = _receiverAccountIdFlow.value
        set(value) {
            if (_paymentActiveFlow.value) return // Cannot change during active payment
            preferences?.edit()?.putString(KEY_RECEIVER_ACCOUNT_ID, value)?.apply()
            _receiverAccountIdFlow.update { value }
        }
    
    /**
     * Sender account ID for sending NFC payments.
     */
    var senderAccountId: String?
        get() = _senderAccountIdFlow.value
        set(value) {
            if (_paymentActiveFlow.value) return // Cannot change during active payment
            preferences?.edit()?.putString(KEY_SENDER_ACCOUNT_ID, value)?.apply()
            _senderAccountIdFlow.update { value }
        }
    
    /**
     * Whether a payment is currently active (prevents config changes).
     */
    var isPaymentActive: Boolean
        get() = _paymentActiveFlow.value
        set(value) {
            preferences?.edit()?.putBoolean(KEY_PAYMENT_ACTIVE, value)?.apply()
            _paymentActiveFlow.update { value }
        }
    
    /**
     * Whether the NFC Send screen is currently active.
     * HCE service should only process commands when this is true.
     */
    var isSendScreenActive: Boolean
        get() = _isSendScreenActive.value
        set(value) {
            _isSendScreenActive.update { value }
        }
    
    /**
     * Get the receiver account, or fallback to active account if not set.
     */
    fun getReceiverAccountOrDefault(): String? {
        return receiverAccountId ?: App.accountManager.activeAccount?.id
    }
    
    /**
     * Get the sender account, or fallback to active account if not set.
     */
    fun getSenderAccountOrDefault(): String? {
        return senderAccountId ?: App.accountManager.activeAccount?.id
    }
    
    /**
     * Check if configuration is valid for receiving payments.
     * Now checks if the receiver account has any enabled wallets.
     */
    fun isReceiverConfigValid(): Boolean {
        val accountId = getReceiverAccountOrDefault() ?: return false
        val account = App.accountManager.account(accountId) ?: return false
        // Check if account has any enabled wallets
        return App.walletManager.activeWallets.any { it.account == account }
    }
    
    /**
     * Check if configuration is valid for sending payments.
     */
    fun isSenderConfigValid(): Boolean {
        val accountId = getSenderAccountOrDefault() ?: return false
        val account = App.accountManager.account(accountId)
        return account != null
    }
    
    /**
     * Clear all NFC configuration (useful for testing or reset).
     */
    fun clearConfig() {
        if (_paymentActiveFlow.value) return
        preferences?.edit()?.clear()?.apply()
        _receiverAccountIdFlow.update { null }
        _senderAccountIdFlow.update { null }
        _paymentActiveFlow.update { false }
    }
}
