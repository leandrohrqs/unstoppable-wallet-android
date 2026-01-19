package io.horizontalsystems.bankwallet.modules.nfc.send

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.modules.nfc.NFCModule
import io.horizontalsystems.bankwallet.modules.send.SendFragment
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryTransparent
import io.horizontalsystems.bankwallet.ui.compose.components.InfoText
import io.horizontalsystems.bankwallet.ui.extensions.BottomSheetHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen for sending NFC payments (Customer/Payment mode).
 * Activates HCE service to emulate an NFC card for payments.
 * 
 * @param navController Navigation controller
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NFCSendScreen(
    navController: NavController,
    viewModel: NFCSendViewModel = viewModel(factory = NFCModule.Factory())
) {
    val uiState = viewModel.uiState
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val paymentTitle = stringResource(R.string.NFC_PaymentTitle)

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Activate NFC HCE service when entering the screen
                    io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager.isSendScreenActive = true
                    viewModel.checkNFCStatus()
                    // Reset state if user returned from payment flow
                    viewModel.resetToWaitingIfNeeded()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Deactivate NFC HCE service when leaving the screen
                    io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager.isSendScreenActive = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Ensure HCE is deactivated when screen is disposed
            io.horizontalsystems.bankwallet.modules.nfc.core.NFCConfigManager.isSendScreenActive = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.navigationEvent) {
        when (val event = uiState.navigationEvent) {
            is NFCSendNavigationEvent.NavigateToSend -> {
                navController.slideFromRight(
                    R.id.sendXFragment,
                    SendFragment.Input(
                        wallet = event.wallet,
                        title = paymentTitle,
                        sendEntryPointDestId = -1,
                        address = Address(event.recipientAddress),
                        amount = event.amount,
                        hideAddress = false,
                        amountLocked = true
                    )
                )
                viewModel.clearNavigationEvent()
            }
            is NFCSendNavigationEvent.NavigateToNFCPayment -> {
                navController.slideFromRight(
                    R.id.nfcPaymentFragment,
                    io.horizontalsystems.bankwallet.modules.nfc.payment.NFCPaymentFragment.Input(
                        title = paymentTitle,
                        fiatAmount = event.fiatAmount,
                        fiatCurrency = event.fiatCurrency,
                        availableTokens = event.availableTokens,
                        sendEntryPointDestId = -1
                    )
                )
                viewModel.clearNavigationEvent()
            }
            null -> { /* No event */ }
        }
    }

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showEnableDialog by rememberSaveable { mutableStateOf(false) }

    // Show enable dialog automatically when NFC is enabled in system but not in app
    LaunchedEffect(uiState.nfcSystemEnabled, uiState.nfcAppEnabled, uiState.nfcAvailable) {
        if (uiState.nfcAvailable && uiState.nfcSystemEnabled && !uiState.nfcAppEnabled) {
            // Only show if user hasn't dismissed it before
            if (viewModel.shouldShowNFCEnableDialog()) {
                // Small delay to ensure UI is ready
                kotlinx.coroutines.delay(300)
                if (!showEnableDialog) {
                    showEnableDialog = true
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.lawrence)
    ) {
        when {
            !uiState.nfcAvailable -> {
                NFCNotAvailableScreen()
            }
            !uiState.nfcSystemEnabled -> {
                NFCSystemDisabledScreen(
                    onOpenSettings = { viewModel.requestNFCEnable() }
                )
            }
            !uiState.nfcAppEnabled -> {
                NFCPermissionRequestScreen(
                    onEnable = { 
                        viewModel.enableNFCInApp()
                        showEnableDialog = false
                    },
                    walletAddress = uiState.walletAddress
                )
            }
            uiState.isActive -> {
                NFCActiveScreen(
                    statusMessage = uiState.statusMessage,
                    paymentStatus = uiState.paymentStatus,
                    onResetStatus = { viewModel.resetTransactionStatus() }
                )
            }
        }
    }

    // Modal dialog for NFC activation request
    if (showEnableDialog && uiState.nfcSystemEnabled && !uiState.nfcAppEnabled) {
        NFCEnableRequestDialog(
            onEnable = {
                viewModel.enableNFCInApp()
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    showEnableDialog = false
                }
            },
            onCancel = {
                // User explicitly clicked Cancel - mark as dismissed
                viewModel.dismissNFCEnableDialog()
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    showEnableDialog = false
                }
            },
            onClose = {
                // User closed by dragging or clicking outside - don't mark as dismissed
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    showEnableDialog = false
                }
            },
            sheetState = sheetState
        )
    }
}

/**
 * Screen when NFC is not available on device
 */
@Composable
private fun NFCNotAvailableScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
            text = stringResource(R.string.NFC_NotSupported),
            style = ComposeAppTheme.typography.headline2,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.NFC_DeviceNotSupported),
            style = ComposeAppTheme.typography.subhead,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Screen when NFC is disabled in system settings
 */
@Composable
private fun NFCSystemDisabledScreen(
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    ComposeAppTheme.colors.jacob.copy(alpha = 0.1f),
                    RoundedCornerShape(60.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.NFC_Title),
                style = ComposeAppTheme.typography.title1,
                color = ComposeAppTheme.colors.jacob
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.NFC_NotEnabled),
            style = ComposeAppTheme.typography.headline1,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.NFC_EnableInSettings),
            style = ComposeAppTheme.typography.subhead,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        ButtonPrimaryYellow(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            title = stringResource(R.string.NFC_OpenSettings),
            onClick = onOpenSettings
        )
    }
}

/**
 * Screen requesting NFC activation in app
 */
@Composable
private fun NFCPermissionRequestScreen(
    onEnable: () -> Unit,
    walletAddress: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    ComposeAppTheme.colors.jacob.copy(alpha = 0.1f),
                    RoundedCornerShape(60.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.NFC_Title),
                style = ComposeAppTheme.typography.title1,
                color = ComposeAppTheme.colors.jacob
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.NFC_Send_Title),
            style = ComposeAppTheme.typography.headline1,
            color = ComposeAppTheme.colors.leah,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.NFC_TapToPayDescription),
            style = ComposeAppTheme.typography.subhead,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center
        )

        if (walletAddress != null) {
            Spacer(modifier = Modifier.height(24.dp))

            InfoText(
                text = "${stringResource(R.string.NFC_ActiveWallet)}\n${walletAddress.take(10)}...${walletAddress.takeLast(8)}"
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        ButtonPrimaryYellow(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            title = stringResource(R.string.NFC_EnablePayment),
            onClick = onEnable
        )

        Spacer(modifier = Modifier.height(16.dp))

        InfoText(
            text = stringResource(R.string.NFC_ImportantWarning)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.NFC_AfterEnabling),
            style = ComposeAppTheme.typography.micro,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/**
 * Screen when NFC is active (no disable button)
 * Shows payment status dynamically when payment is in progress
 */
@Composable
private fun NFCActiveScreen(
    statusMessage: String,
    paymentStatus: io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus?,
    onResetStatus: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.lawrence),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Show status icon and text if payment is in progress
            if (paymentStatus != null && paymentStatus != io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING) {
                PaymentStatusIcon(status = paymentStatus)
                
                Spacer(modifier = Modifier.height(32.dp))
                
                PaymentStatusText(status = paymentStatus)
                
                // Play success sound and auto-reset after showing confirmed status
                if (paymentStatus == io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONFIRMED) {
                    val context = LocalContext.current
                    LaunchedEffect(paymentStatus) {
                        try {
                            val mediaPlayer = android.media.MediaPlayer.create(context, R.raw.nfctransfercomplete)
                            mediaPlayer?.setOnCompletionListener { it.release() }
                            mediaPlayer?.start()
                        } catch (e: Exception) {
                            // Error playing sound - ignore silently
                        }
                        kotlinx.coroutines.delay(3000)
                        onResetStatus()
                    }
                }
            } else {
                // Default ready state
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            ComposeAppTheme.colors.remus.copy(alpha = 0.2f),
                            RoundedCornerShape(60.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.NFC_Title),
                        style = ComposeAppTheme.typography.title1,
                        color = ComposeAppTheme.colors.remus
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.NFC_ReadyToSend),
                    style = ComposeAppTheme.typography.headline1,
                    color = ComposeAppTheme.colors.remus,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = statusMessage.ifEmpty { stringResource(R.string.NFC_HoldDevice) },
                    style = ComposeAppTheme.typography.subhead,
                    color = ComposeAppTheme.colors.grey,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.NFC_CardEmulationActive),
                    style = ComposeAppTheme.typography.micro,
                    color = ComposeAppTheme.colors.grey,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }

            // Only show disable info when not in active payment status
            if (paymentStatus == null || paymentStatus == io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING) {
                Spacer(modifier = Modifier.height(16.dp))

                InfoText(
                    text = stringResource(R.string.NFC_DisableInfo)
                )
            }
        }
    }
}

/**
 * Display icon based on payment status
 */
@Composable
private fun PaymentStatusIcon(status: io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus) {
    val iconRes = when (status) {
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONNECTED -> R.drawable.ic_nfc_24
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SENT -> R.drawable.arrow_m_up_24
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SEARCHING -> R.drawable.binocular_24
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FOUND -> R.drawable.binocular_24
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING_CONFIRMATION -> R.drawable.binocular_24
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONFIRMED -> R.drawable.icon_20_check_1
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FAILED -> R.drawable.icon_warning_2_20
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING -> R.drawable.ic_nfc_24
    }
    
    val iconColor = when (status) {
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONNECTED -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SENT -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SEARCHING -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FOUND -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING_CONFIRMATION -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONFIRMED -> ComposeAppTheme.colors.greenD
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FAILED -> ComposeAppTheme.colors.lucian
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING -> ComposeAppTheme.colors.remus
    }
    
    val scale by animateFloatAsState(
        targetValue = if (status == io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONFIRMED) 1.2f else 1f,
        animationSpec = tween(durationMillis = 300)
    )
    
    Box(
        modifier = Modifier
            .size(120.dp)
            .background(
                iconColor.copy(alpha = 0.1f),
                RoundedCornerShape(60.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            tint = iconColor
        )
    }
}

/**
 * Display status text with animated dots
 */
@Composable
private fun PaymentStatusText(status: io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus) {
    val baseText = when (status) {
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONNECTED -> stringResource(R.string.NFC_ReceiveStatus_Connected)
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SENT -> stringResource(R.string.NFC_TransactionStatus_Sent)
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SEARCHING -> stringResource(R.string.NFC_TransactionStatus_Searching)
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FOUND -> stringResource(R.string.NFC_TransactionStatus_Found)
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING_CONFIRMATION -> stringResource(R.string.NFC_TransactionStatus_Waiting)
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONFIRMED -> stringResource(R.string.NFC_TransactionStatus_Confirmed)
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FAILED -> "Transaction failed"
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING -> stringResource(R.string.NFC_ReadyToSend)
    }
    
    val shouldShowDots = status == io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SEARCHING || 
                         status == io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING_CONFIRMATION
    
    var dots by remember { mutableStateOf("") }
    
    LaunchedEffect(status) {
        if (shouldShowDots) {
            while (true) {
                dots = ""
                kotlinx.coroutines.delay(500)
                dots = "."
                kotlinx.coroutines.delay(500)
                dots = ".."
                kotlinx.coroutines.delay(500)
                dots = "..."
                kotlinx.coroutines.delay(500)
            }
        } else {
            dots = ""
        }
    }
    
    val textColor = when (status) {
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONNECTED -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SENT -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.SEARCHING -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FOUND -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING_CONFIRMATION -> ComposeAppTheme.colors.jacob
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.CONFIRMED -> ComposeAppTheme.colors.greenD
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.FAILED -> ComposeAppTheme.colors.lucian
        io.horizontalsystems.bankwallet.modules.nfc.send.SendPaymentStatus.WAITING -> ComposeAppTheme.colors.remus
    }
    
    AnimatedContent(
        targetState = "$baseText$dots",
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "status_text"
    ) { text ->
        Text(
            text = text,
            style = ComposeAppTheme.typography.headline1,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Modal dialog requesting NFC activation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NFCEnableRequestDialog(
    onEnable: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    sheetState: androidx.compose.material3.SheetState
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = ComposeAppTheme.colors.transparent
    ) {
        BottomSheetHeader(
            iconPainter = painterResource(id = R.drawable.ic_nfc_24),
            title = stringResource(R.string.NFC_EnableRequestTitle),
            iconTint = ColorFilter.tint(ComposeAppTheme.colors.jacob),
            onCloseClick = onClose
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 12.dp, horizontal = 24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.NFC_EnableRequestMessage),
                    style = ComposeAppTheme.typography.body,
                    color = ComposeAppTheme.colors.leah,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                ButtonPrimaryYellow(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.NFC_EnablePayment),
                    onClick = onEnable
                )

                Spacer(modifier = Modifier.height(12.dp))

                ButtonPrimaryTransparent(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.Button_Cancel),
                    onClick = onCancel
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

