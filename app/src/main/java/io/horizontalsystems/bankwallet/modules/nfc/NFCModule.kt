package io.horizontalsystems.bankwallet.modules.nfc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager

/**
 * Module for NFC payment functionality.
 * Provides factory for creating NFCViewModel instances.
 */
object NFCModule {

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // Initialize NFCConfigManager if not already initialized
            NFCConfigManager.initialize(App.instance)
            
            return when {
                modelClass.isAssignableFrom(NFCViewModel::class.java) -> {
                    NFCViewModel(
                        App.accountManager,
                        App.adapterManager,
                        App.currencyManager
                    ) as T
                }
                modelClass.isAssignableFrom(io.horizontalsystems.bankwallet.modules.nfc.receive.NFCReceiveViewModel::class.java) -> {
                    io.horizontalsystems.bankwallet.modules.nfc.receive.NFCReceiveViewModel(
                        App.accountManager,
                        App.adapterManager,
                        App.currencyManager
                    ) as T
                }
                modelClass.isAssignableFrom(io.horizontalsystems.bankwallet.modules.nfc.send.NFCSendViewModel::class.java) -> {
                    io.horizontalsystems.bankwallet.modules.nfc.send.NFCSendViewModel(
                        App.accountManager,
                        App.adapterManager,
                        App.currencyManager
                    ) as T
                }
                else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    /**
     * Tab types for NFC screen
     */
    enum class NFCTab {
        RECEIVE,
        SEND
    }
}

