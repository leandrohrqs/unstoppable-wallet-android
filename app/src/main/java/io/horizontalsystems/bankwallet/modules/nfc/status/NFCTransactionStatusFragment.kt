package io.horizontalsystems.bankwallet.modules.nfc.status

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.BaseComposeFragment
import kotlinx.parcelize.Parcelize

class NFCTransactionStatusFragment : BaseComposeFragment() {
    
    @Composable
    override fun GetContent(navController: NavController) {
        withInput<Input>(navController) { input ->
            val currentBackStackEntry = remember(navController.currentBackStackEntry) {
                navController.getBackStackEntry(R.id.nfcTransactionStatusFragment)
            }
            val viewModel = viewModel<NFCTransactionStatusViewModel>(
                viewModelStoreOwner = currentBackStackEntry
            )
            
            NFCTransactionStatusScreen(
                navController = navController,
                viewModel = viewModel,
                transactionHash = input.transactionHash,
                chainId = input.chainId
            )
        }
    }

    @Parcelize
    data class Input(
        val transactionHash: String,
        val chainId: Int
    ) : Parcelable
}
