package io.horizontalsystems.bankwallet.modules.nfc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.entities.Account
import io.horizontalsystems.bankwallet.entities.Currency
import io.horizontalsystems.bankwallet.modules.nfc.NFCModule.NFCTab
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager
import io.horizontalsystems.bankwallet.modules.nfc.core.NFCStatus
import io.horizontalsystems.bankwallet.modules.nfc.receive.NFCReceiveScreen
import io.horizontalsystems.bankwallet.modules.nfc.send.NFCSendScreen
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.CellUniversalLawrenceSection
import io.horizontalsystems.bankwallet.ui.compose.components.HeaderText
import io.horizontalsystems.bankwallet.ui.compose.components.MenuItem
import io.horizontalsystems.bankwallet.ui.compose.components.RowUniversal
import io.horizontalsystems.bankwallet.ui.compose.components.VSpacer
import io.horizontalsystems.bankwallet.ui.compose.components.headline2_leah
import io.horizontalsystems.bankwallet.ui.compose.components.subhead2_grey
import io.horizontalsystems.bankwallet.uiv3.components.HSScaffold
import io.horizontalsystems.bankwallet.uiv3.components.tabs.TabItem
import io.horizontalsystems.bankwallet.uiv3.components.tabs.TabsTop
import io.horizontalsystems.bankwallet.uiv3.components.tabs.TabsTopType
import kotlinx.coroutines.launch

/**
 * Enum for NFC settings menu navigation
 */
private enum class NFCSettingsScreen {
    MENU,
    CURRENCY,
    RECEIVER_WALLET,
    SENDER_WALLET
}

/**
 * Main NFC screen with tabs for Receive and Send modes.
 * 
 * @param navController Navigation controller for fragment navigation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCMainScreen(
    navController: NavController,
    viewModel: NFCViewModel = viewModel(factory = NFCModule.Factory())
) {
    val context = LocalContext.current
    val tabs = listOf(NFCTab.RECEIVE, NFCTab.SEND)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    
    // Settings menu state
    var showSettingsMenu by remember { mutableStateOf(false) }
    var currentSettingsScreen by remember { mutableStateOf(NFCSettingsScreen.MENU) }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currencyManager = remember { App.currencyManager }
    var selectedCurrency by remember { mutableStateOf(currencyManager.baseCurrency) }
    
    // NFC Config state
    val accountManager = remember { App.accountManager }
    val walletManager = remember { App.walletManager }
    val receiverAccountId by NFCConfigManager.receiverAccountIdFlow.collectAsState()
    val senderAccountId by NFCConfigManager.senderAccountIdFlow.collectAsState()
    val currentTab = tabs.getOrNull(pagerState.currentPage) ?: NFCTab.RECEIVE

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    val nfcStatus = viewModel.nfcStatus
    val showNFCWarning = nfcStatus == NFCStatus.NOT_AVAILABLE || nfcStatus == NFCStatus.DISABLED

    HSScaffold(
        title = stringResource(R.string.NFC_Title),
        menuItems = listOf(
            MenuItem(
                title = TranslatableString.ResString(R.string.NFC_Settings),
                icon = R.drawable.ic_manage_2,
                onClick = { 
                    currentSettingsScreen = NFCSettingsScreen.MENU
                    showSettingsMenu = true 
                }
            )
        )
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
    
    // Settings bottom sheet
    if (showSettingsMenu) {
        ModalBottomSheet(
            onDismissRequest = {
                coroutineScope.launch { settingsSheetState.hide() }.invokeOnCompletion {
                    showSettingsMenu = false
                    currentSettingsScreen = NFCSettingsScreen.MENU
                }
            },
            sheetState = settingsSheetState,
            containerColor = ComposeAppTheme.colors.lawrence
        ) {
            when (currentSettingsScreen) {
                NFCSettingsScreen.MENU -> {
                    // Count tokens in receiver wallet
                    val receiverAccount = receiverAccountId?.let { accountManager.account(it) } ?: accountManager.activeAccount
                    val tokensInWallet = walletManager.activeWallets.count { it.account == receiverAccount }
                    
                    NFCSettingsMenu(
                        currentTab = currentTab,
                        selectedCurrency = selectedCurrency,
                        receiverAccountId = receiverAccountId,
                        senderAccountId = senderAccountId,
                        tokensInWallet = tokensInWallet,
                        accountManager = accountManager,
                        onCurrencyClick = { currentSettingsScreen = NFCSettingsScreen.CURRENCY },
                        onReceiverWalletClick = { currentSettingsScreen = NFCSettingsScreen.RECEIVER_WALLET },
                        onSenderWalletClick = { currentSettingsScreen = NFCSettingsScreen.SENDER_WALLET },
                        onManageCoinsClick = {
                            // Close the bottom sheet and navigate to manage wallets
                            coroutineScope.launch { settingsSheetState.hide() }.invokeOnCompletion {
                                showSettingsMenu = false
                            }
                            navController.navigate(R.id.manageWalletsFragment)
                        }
                    )
                }
                NFCSettingsScreen.CURRENCY -> {
                    NFCCurrencySelectorContent(
                        currencyManager = currencyManager,
                        selectedCurrency = selectedCurrency,
                        onCurrencySelected = { currency ->
                            selectedCurrency = currency
                            currencyManager.baseCurrency = currency
                            currentSettingsScreen = NFCSettingsScreen.MENU
                        },
                        onBack = { currentSettingsScreen = NFCSettingsScreen.MENU }
                    )
                }
                NFCSettingsScreen.RECEIVER_WALLET -> {
                    NFCWalletSelectorContent(
                        title = stringResource(R.string.NFC_ReceiverWallet),
                        accounts = accountManager.accounts.filter { !it.isWatchAccount },
                        selectedAccountId = receiverAccountId ?: accountManager.activeAccount?.id,
                        onAccountSelected = { account ->
                            NFCConfigManager.receiverAccountId = account.id
                            currentSettingsScreen = NFCSettingsScreen.MENU
                        },
                        onBack = { currentSettingsScreen = NFCSettingsScreen.MENU }
                    )
                }
                NFCSettingsScreen.SENDER_WALLET -> {
                    NFCWalletSelectorContent(
                        title = stringResource(R.string.NFC_SenderWallet),
                        accounts = accountManager.accounts.filter { !it.isWatchAccount },
                        selectedAccountId = senderAccountId ?: accountManager.activeAccount?.id,
                        onAccountSelected = { account ->
                            NFCConfigManager.senderAccountId = account.id
                            currentSettingsScreen = NFCSettingsScreen.MENU
                        },
                        onBack = { currentSettingsScreen = NFCSettingsScreen.MENU }
                    )
                }
            }
        }
    }
}

/**
 * Settings menu with list of options
 */
@Composable
private fun NFCSettingsMenu(
    currentTab: NFCTab,
    selectedCurrency: Currency,
    receiverAccountId: String?,
    senderAccountId: String?,
    tokensInWallet: Int,
    accountManager: io.horizontalsystems.bankwallet.core.IAccountManager,
    onCurrencyClick: () -> Unit,
    onReceiverWalletClick: () -> Unit,
    onSenderWalletClick: () -> Unit,
    onManageCoinsClick: () -> Unit
) {
    val receiverAccount = receiverAccountId?.let { accountManager.account(it) } ?: accountManager.activeAccount
    val senderAccount = senderAccountId?.let { accountManager.account(it) } ?: accountManager.activeAccount
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_manage_2),
                contentDescription = null,
                tint = ComposeAppTheme.colors.jacob,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.NFC_Settings),
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.leah,
                modifier = Modifier.weight(1f)
            )
        }
        
        VSpacer(12.dp)
        
        // Currency settings (always visible)
        CellUniversalLawrenceSection(
            listOf {
                SettingsMenuItem(
                    title = stringResource(R.string.NFC_SelectCurrency),
                    value = selectedCurrency.code,
                    onClick = onCurrencyClick
                )
            }
        )
        
        VSpacer(16.dp)
        
        // Receiver settings (visible on Receive tab)
        if (currentTab == NFCTab.RECEIVE) {
            HeaderText(stringResource(R.string.NFC_Receive))
            CellUniversalLawrenceSection(
                listOf(
                    {
                        SettingsMenuItem(
                            title = stringResource(R.string.NFC_ReceiverWallet),
                            value = receiverAccount?.name ?: "-",
                            onClick = onReceiverWalletClick
                        )
                    },
                    {
                        SettingsMenuItem(
                            title = stringResource(R.string.NFC_AcceptedTokens),
                            value = if (tokensInWallet > 0) "$tokensInWallet" else "-",
                            onClick = onManageCoinsClick
                        )
                    }
                )
            )
        }
        
        // Sender settings (visible on Send tab)
        if (currentTab == NFCTab.SEND) {
            HeaderText(stringResource(R.string.NFC_Send))
            CellUniversalLawrenceSection(
                listOf {
                    SettingsMenuItem(
                        title = stringResource(R.string.NFC_SenderWallet),
                        value = senderAccount?.name ?: "-",
                        onClick = onSenderWalletClick
                    )
                }
            )
        }
        
        VSpacer(24.dp)
    }
}

/**
 * Settings menu item with title, value and arrow
 */
@Composable
private fun SettingsMenuItem(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    RowUniversal(onClick = onClick) {
        Text(
            text = title,
            style = ComposeAppTheme.typography.body,
            color = ComposeAppTheme.colors.leah,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        )
        Text(
            text = value,
            style = ComposeAppTheme.typography.subhead,
            color = ComposeAppTheme.colors.grey,
            modifier = Modifier.padding(end = 8.dp)
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = ComposeAppTheme.colors.grey,
            modifier = Modifier.padding(end = 16.dp)
        )
    }
}

/**
 * Wallet selector content for receiver/sender wallet selection
 */
@Composable
private fun NFCWalletSelectorContent(
    title: String,
    accounts: List<Account>,
    selectedAccountId: String?,
    onAccountSelected: (Account) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = null,
                tint = ComposeAppTheme.colors.jacob,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.leah,
                modifier = Modifier.weight(1f)
            )
        }
        
        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            VSpacer(12.dp)
            if (accounts.isEmpty()) {
                Text(
                    text = "No wallets found",
                    style = ComposeAppTheme.typography.subhead,
                    color = ComposeAppTheme.colors.grey,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                CellUniversalLawrenceSection(accounts) { account ->
                    AccountCell(
                        name = account.name,
                        description = account.type.detailedDescription,
                        selected = account.id == selectedAccountId,
                        onClick = { onAccountSelected(account) }
                    )
                }
            }
        }
    }
}

/**
 * Cell for displaying an account option
 */
@Composable
private fun AccountCell(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    RowUniversal(onClick = onClick) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            headline2_leah(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            subhead2_grey(
                text = description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .width(52.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark_20),
                    tint = ComposeAppTheme.colors.jacob,
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * Currency selector content (without the ModalBottomSheet wrapper)
 */
@Composable
private fun NFCCurrencySelectorContent(
    currencyManager: io.horizontalsystems.bankwallet.core.managers.CurrencyManager,
    selectedCurrency: Currency,
    onCurrencySelected: (Currency) -> Unit,
    onBack: () -> Unit
) {
    val popularCurrencyCodes = listOf("USD", "EUR", "GBP", "JPY", "BRL")
    val allCurrencies = currencyManager.currencies
    
    val popularCurrencies = allCurrencies.filter { it.code in popularCurrencyCodes }
        .sortedBy { popularCurrencyCodes.indexOf(it.code) }
    val otherCurrencies = allCurrencies.filter { it.code !in popularCurrencyCodes }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = null,
                tint = ComposeAppTheme.colors.jacob,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.NFC_SelectCurrency),
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.leah,
                modifier = Modifier.weight(1f)
            )
        }
        
        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            VSpacer(12.dp)
            CellUniversalLawrenceSection(popularCurrencies) { currency ->
                CurrencyCell(
                    code = currency.code,
                    symbol = currency.symbol,
                    flag = currency.flag,
                    selected = currency.code == selectedCurrency.code,
                    onClick = { onCurrencySelected(currency) }
                )
            }
            VSpacer(24.dp)
            HeaderText(stringResource(R.string.SettingsCurrency_Other))
            CellUniversalLawrenceSection(otherCurrencies) { currency ->
                CurrencyCell(
                    code = currency.code,
                    symbol = currency.symbol,
                    flag = currency.flag,
                    selected = currency.code == selectedCurrency.code,
                    onClick = { onCurrencySelected(currency) }
                )
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

/**
 * Cell for displaying a currency option
 */
@Composable
private fun CurrencyCell(
    code: String,
    symbol: String,
    flag: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    RowUniversal(onClick = onClick) {
        Image(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .size(32.dp),
            painter = painterResource(flag),
            contentDescription = null
        )
        Column(modifier = Modifier.weight(1f)) {
            headline2_leah(
                text = code,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            subhead2_grey(
                text = symbol,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .width(52.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark_20),
                    tint = ComposeAppTheme.colors.jacob,
                    contentDescription = null,
                )
            }
        }
    }
}
