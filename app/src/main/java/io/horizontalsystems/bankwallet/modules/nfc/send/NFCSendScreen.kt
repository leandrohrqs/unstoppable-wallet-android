package io.horizontalsystems.bankwallet.modules.nfc.send

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.modules.nfc.NFCModule
import io.horizontalsystems.bankwallet.modules.send.SendFragment
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.InfoText

/**
 * Screen for sending NFC payments (Customer/Payment mode).
 * Activates HCE service to emulate an NFC card for payments.
 * 
 * @param navController Navigation controller
 */
@Composable
fun NFCSendScreen(
    navController: NavController,
    viewModel: NFCSendViewModel = viewModel(factory = NFCModule.Factory())
) {
    val uiState = viewModel.uiState

    LaunchedEffect(uiState.navigationEvent) {
        when (val event = uiState.navigationEvent) {
            is NFCSendNavigationEvent.NavigateToSend -> {
                try {
                    navController.slideFromRight(
                        R.id.sendXFragment,
                        SendFragment.Input(
                            wallet = event.wallet,
                            title = "NFC Payment",
                            sendEntryPointDestId = -1,
                            address = Address(event.recipientAddress),
                            amount = event.amount,
                            hideAddress = false
                        )
                    )
                    viewModel.clearNavigationEvent()
                } catch (e: Exception) {
                    viewModel.clearNavigationEvent()
                }
            }
            null -> { /* No event */ }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler)
    ) {
        if (uiState.isActive) {
            NFCActiveOverlay(
                statusMessage = uiState.statusMessage,
                onDeactivate = { viewModel.deactivateNFC() }
            )
        } else {
            NFCInactiveContent(
                onActivate = { viewModel.activateNFC() },
                walletAddress = uiState.walletAddress
            )
        }
        
        NFCTopNotification(
            isWaitingForConfirmation = uiState.isWaitingForConfirmation,
            isPaymentConfirmed = uiState.isPaymentConfirmed,
            onReset = { viewModel.resetTransactionStatus() },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Content when NFC is inactive
 */
@Composable
private fun NFCInactiveContent(
    onActivate: () -> Unit,
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
                text = "NFC",
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
            text = "Tap your device on a merchant terminal to pay",
            style = ComposeAppTheme.typography.subhead,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center
        )

        if (walletAddress != null) {
            Spacer(modifier = Modifier.height(24.dp))

            InfoText(
                text = "Active Wallet:\n${walletAddress.take(10)}...${walletAddress.takeLast(8)}"
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        ButtonPrimaryYellow(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            title = stringResource(R.string.NFC_EnableHCE),
            onClick = onActivate
        )

        Spacer(modifier = Modifier.height(16.dp))

        InfoText(
            text = "⚠️ Important: This app must be in the foreground (not minimized) for NFC payments to work."
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "After enabling, hold your phone near the merchant's terminal to complete payment",
            style = ComposeAppTheme.typography.micro,
            color = ComposeAppTheme.colors.grey,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/**
 * Overlay shown when NFC is active
 */
@Composable
private fun NFCActiveOverlay(
    statusMessage: String,
    onDeactivate: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
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
                    text = "NFC",
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
                text = statusMessage,
                style = ComposeAppTheme.typography.subhead,
                color = ComposeAppTheme.colors.grey,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            ButtonPrimaryYellow(
                modifier = Modifier
                    .width(200.dp)
                    .height(50.dp),
                title = stringResource(R.string.Button_Disable),
                onClick = onDeactivate
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "NFC card emulation is active. Hold your phone near the terminal.",
                style = ComposeAppTheme.typography.micro,
                color = ComposeAppTheme.colors.grey,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

/**
 * Top notification banner for transaction status
 * Cannot be closed by user - only disappears when transaction is confirmed and reset
 */
@Composable
private fun NFCTopNotification(
    isWaitingForConfirmation: Boolean,
    isPaymentConfirmed: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(isPaymentConfirmed) {
        if (isPaymentConfirmed) {
            kotlinx.coroutines.delay(5000)
            onReset()
        }
    }
    
    val checkIconScale by animateFloatAsState(
        targetValue = if (isPaymentConfirmed) 1f else 0f,
        animationSpec = tween(durationMillis = 300)
    )
    
    AnimatedVisibility(
        visible = isWaitingForConfirmation || isPaymentConfirmed,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (isPaymentConfirmed) {
                        ComposeAppTheme.colors.greenD
                    } else {
                        ComposeAppTheme.colors.jacob
                    },
                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = if (isPaymentConfirmed) 16.dp else 12.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPaymentConfirmed) {
                    Icon(
                        painter = painterResource(R.drawable.icon_20_check_1),
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                scaleX = checkIconScale
                                scaleY = checkIconScale
                            },
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                } else if (isWaitingForConfirmation) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                
                Text(
                    text = if (isPaymentConfirmed) {
                        stringResource(R.string.NFC_PaymentConfirmed)
                    } else {
                        stringResource(R.string.NFC_WaitingForPayment)
                    },
                    style = if (isPaymentConfirmed) {
                        ComposeAppTheme.typography.headline2.copy(fontWeight = FontWeight.Bold)
                    } else {
                        ComposeAppTheme.typography.subhead
                    },
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

