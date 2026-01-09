package io.horizontalsystems.bankwallet.modules.nfc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.modules.nfc.NFCModule.NFCTab
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCStatus
import io.horizontalsystems.bankwallet.modules.nfc.receive.NFCReceiveScreen
import io.horizontalsystems.bankwallet.modules.nfc.send.NFCSendScreen
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.uiv3.components.HSScaffold
import io.horizontalsystems.bankwallet.uiv3.components.tabs.TabItem
import io.horizontalsystems.bankwallet.uiv3.components.tabs.TabsTop
import io.horizontalsystems.bankwallet.uiv3.components.tabs.TabsTopType
import kotlinx.coroutines.launch

/**
 * Main NFC screen with tabs for Receive and Send modes.
 * 
 * @param navController Navigation controller for fragment navigation
 */
@Composable
fun NFCMainScreen(
    navController: NavController,
    viewModel: NFCViewModel = viewModel(factory = NFCModule.Factory())
) {
    val context = LocalContext.current
    val tabs = listOf(NFCTab.RECEIVE, NFCTab.SEND)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    val nfcStatus = viewModel.nfcStatus
    val showNFCWarning = nfcStatus == NFCStatus.NOT_AVAILABLE || nfcStatus == NFCStatus.DISABLED

    HSScaffold(
        title = stringResource(R.string.NFC_Title),
        menuItems = listOf()
    ) {
        if (showNFCWarning && nfcStatus != null) {
            NFCWarningScreen(
                nfcStatus = nfcStatus,
                onOpenSettings = { viewModel.openNFCSettings() },
                onClose = { navController.popBackStack() },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeAppTheme.colors.lawrence)
            ) {
                val tabItems = tabs.mapIndexed { index, tab ->
                    val title = when (tab) {
                        NFCTab.RECEIVE -> stringResource(R.string.NFC_Receive)
                        NFCTab.SEND -> stringResource(R.string.NFC_Send)
                    }
                    TabItem(
                        title = title,
                        selected = pagerState.currentPage == index,
                        item = tab
                    )
                }

                TabsTop(
                    type = TabsTopType.Fitted,
                    tabs = tabItems
                ) { selectedTab ->
                    coroutineScope.launch {
                        val index = tabs.indexOf(selectedTab)
                        pagerState.animateScrollToPage(index)
                    }
                    viewModel.onTabSelect(selectedTab)
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    when (tabs[page]) {
                        NFCTab.RECEIVE -> NFCReceiveScreen(navController)
                        NFCTab.SEND -> NFCSendScreen(navController)
                    }
                }
            }
        }
    }
}

/**
 * Warning screen shown when NFC is not available or disabled
 */
@Composable
private fun NFCWarningScreen(
    nfcStatus: NFCStatus,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚠️",
            style = ComposeAppTheme.typography.title1,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = when (nfcStatus) {
                NFCStatus.NOT_AVAILABLE -> stringResource(R.string.NFC_NotSupported)
                NFCStatus.DISABLED -> stringResource(R.string.NFC_NotEnabled)
                else -> stringResource(R.string.NFC_NotAvailable)
            },
            style = ComposeAppTheme.typography.headline2,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when (nfcStatus) {
                NFCStatus.NOT_AVAILABLE -> "Your device does not support NFC functionality"
                NFCStatus.DISABLED -> "Please enable NFC in your device settings to use this feature"
                else -> "NFC is not available"
            },
            style = ComposeAppTheme.typography.subhead,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (nfcStatus == NFCStatus.DISABLED) {
            ButtonPrimaryYellow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                title = "Open Settings",
                onClick = onOpenSettings
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        ButtonPrimaryYellow(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            title = stringResource(R.string.Button_Close),
            onClick = onClose
        )
    }
}
