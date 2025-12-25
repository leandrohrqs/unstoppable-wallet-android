package io.horizontalsystems.bankwallet.modules.nfc.core

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import android.util.Log

/**
 * Manager for NFC availability and status checking.
 * Provides utilities to check if NFC is available and enabled on the device.
 */
class NFCManager(private val context: Context) {

    companion object {
        private const val TAG = "NFCManager"
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    /**
     * Check if NFC hardware is available on this device
     */
    fun isNFCAvailable(): Boolean {
        return nfcAdapter != null
    }

    /**
     * Check if NFC is currently enabled
     */
    fun isNFCEnabled(): Boolean {
        return nfcAdapter?.isEnabled == true
    }

    /**
     * Get NFC status information
     */
    fun getNFCStatus(): NFCStatus {
        return when {
            !isNFCAvailable() -> NFCStatus.NOT_AVAILABLE
            !isNFCEnabled() -> NFCStatus.DISABLED
            else -> NFCStatus.ENABLED
        }
    }

    /**
     * Open Android settings to enable NFC
     */
    fun openNFCSettings() {
        try {
            val intent = Intent(Settings.ACTION_NFC_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            logError("Failed to open NFC settings", e)
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                logError("Failed to open wireless settings", e)
            }
        }
    }

    /**
     * Check if HCE (Host Card Emulation) is supported
     */
    fun isHCESupported(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT
    }

    /**
     * Get user-friendly error message for current NFC status
     */
    fun getStatusMessage(): String {
        return when (getNFCStatus()) {
            NFCStatus.NOT_AVAILABLE -> "NFC is not available on this device"
            NFCStatus.DISABLED -> "NFC is disabled. Please enable it in Settings"
            NFCStatus.ENABLED -> "NFC is ready"
        }
    }

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

/**
 * NFC status enum
 */
enum class NFCStatus {
    NOT_AVAILABLE,
    DISABLED,
    ENABLED
}

