package io.horizontalsystems.bankwallet.modules.nfc.core

import android.content.Context
import android.util.Log
import java.util.Properties

/**
 * Configuration manager for reading settings from local.properties file.
 * Used primarily for NFC payment configuration (Alchemy API key).
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val LOCAL_PROPERTIES_FILE = "local.properties"
    
    private var properties: Properties? = null
    
    /**
     * Initialize the configuration manager with the application context.
     * Reads the local.properties file from assets if it exists.
     */
    fun initialize(context: Context) {
        try {
            properties = Properties()
            context.assets.open(LOCAL_PROPERTIES_FILE).use { inputStream ->
                properties?.load(inputStream)
            }
        } catch (e: Exception) {
            properties = Properties()
        }
    }
    
    /**
     * Get the Alchemy API key for blockchain transaction monitoring.
     * Returns empty string if not configured.
     */
    fun getAlchemyApiKey(): String {
        val key = properties?.getProperty("alchemy.api.key", "") ?: ""
        if (key.isEmpty() || key == "YOUR_ALCHEMY_API_KEY_HERE") {
            return ""
        }
        return key
    }
    
    /**
     * Check if Alchemy API key is properly configured.
     */
    fun hasAlchemyApiKey(): Boolean {
        val key = getAlchemyApiKey()
        return key.isNotEmpty() && key != "YOUR_ALCHEMY_API_KEY_HERE"
    }
}

