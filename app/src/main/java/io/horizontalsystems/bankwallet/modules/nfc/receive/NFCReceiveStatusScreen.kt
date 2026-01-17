package io.horizontalsystems.bankwallet.modules.nfc.receive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import kotlinx.coroutines.delay

/**
 * Status screen for NFC Receive (Merchant) with dynamic animations.
 * Shows progress through payment processing states.
 */
@Composable
fun NFCReceiveStatusScreen(
    status: ReceivePaymentStatus,
    onCancel: () -> Unit
) {
    val showCancelButton = status != ReceivePaymentStatus.CONFIRMED && 
                          status != ReceivePaymentStatus.FAILED
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatusIcon(status = status)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            StatusText(status = status)
            
            if (showCancelButton) {
                Spacer(modifier = Modifier.height(48.dp))
                
                ButtonPrimaryYellow(
                    modifier = Modifier
                        .width(200.dp)
                        .height(50.dp),
                    title = stringResource(R.string.Button_Cancel),
                    onClick = onCancel
                )
            }
        }
    }
}

/**
 * Display icon based on payment status
 */
@Composable
private fun StatusIcon(status: ReceivePaymentStatus) {
    val iconRes = when (status) {
        ReceivePaymentStatus.WAITING_FOR_CUSTOMER -> R.drawable.ic_nfc_24
        ReceivePaymentStatus.CONNECTING -> R.drawable.ic_nfc_24
        ReceivePaymentStatus.CONNECTED -> R.drawable.icon_20_check_1
        ReceivePaymentStatus.SEARCHING -> R.drawable.binocular_24
        ReceivePaymentStatus.FOUND -> R.drawable.binocular_24
        ReceivePaymentStatus.WAITING_CONFIRMATION -> R.drawable.binocular_24
        ReceivePaymentStatus.CONFIRMED -> R.drawable.icon_20_check_1
        ReceivePaymentStatus.FAILED -> R.drawable.icon_warning_2_20
    }
    
    val iconColor = when (status) {
        ReceivePaymentStatus.WAITING_FOR_CUSTOMER -> ComposeAppTheme.colors.jacob
        ReceivePaymentStatus.CONNECTING -> ComposeAppTheme.colors.jacob
        ReceivePaymentStatus.CONNECTED -> ComposeAppTheme.colors.greenD
        ReceivePaymentStatus.SEARCHING -> ComposeAppTheme.colors.jacob
        ReceivePaymentStatus.FOUND -> ComposeAppTheme.colors.jacob
        ReceivePaymentStatus.WAITING_CONFIRMATION -> ComposeAppTheme.colors.jacob
        ReceivePaymentStatus.CONFIRMED -> ComposeAppTheme.colors.greenD
        ReceivePaymentStatus.FAILED -> ComposeAppTheme.colors.lucian
    }
    
    val scale by animateFloatAsState(
        targetValue = if (status == ReceivePaymentStatus.CONFIRMED) 1.2f else 1f,
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
private fun StatusText(status: ReceivePaymentStatus) {
    val baseText = when (status) {
        ReceivePaymentStatus.WAITING_FOR_CUSTOMER -> stringResource(R.string.NFC_ReceiveStatus_WaitingForCustomer)
        ReceivePaymentStatus.CONNECTING -> stringResource(R.string.NFC_ReceiveStatus_Connecting)
        ReceivePaymentStatus.CONNECTED -> stringResource(R.string.NFC_ReceiveStatus_Connected)
        ReceivePaymentStatus.SEARCHING -> stringResource(R.string.NFC_ReceiveStatus_Searching)
        ReceivePaymentStatus.FOUND -> stringResource(R.string.NFC_ReceiveStatus_Found)
        ReceivePaymentStatus.WAITING_CONFIRMATION -> stringResource(R.string.NFC_ReceiveStatus_WaitingConfirmation)
        ReceivePaymentStatus.CONFIRMED -> stringResource(R.string.NFC_ReceiveStatus_Confirmed)
        ReceivePaymentStatus.FAILED -> stringResource(R.string.NFC_ReceiveStatus_Failed)
    }
    
    val shouldShowDots = status == ReceivePaymentStatus.WAITING_FOR_CUSTOMER ||
                         status == ReceivePaymentStatus.CONNECTING || 
                         status == ReceivePaymentStatus.SEARCHING || 
                         status == ReceivePaymentStatus.WAITING_CONFIRMATION
    
    var dots by remember { mutableStateOf("") }
    
    LaunchedEffect(status) {
        if (shouldShowDots) {
            while (true) {
                dots = ""
                delay(500)
                dots = "."
                delay(500)
                dots = ".."
                delay(500)
                dots = "..."
                delay(500)
            }
        } else {
            dots = ""
        }
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
            color = when (status) {
                ReceivePaymentStatus.WAITING_FOR_CUSTOMER -> ComposeAppTheme.colors.jacob
                ReceivePaymentStatus.CONNECTING -> ComposeAppTheme.colors.jacob
                ReceivePaymentStatus.CONNECTED -> ComposeAppTheme.colors.greenD
                ReceivePaymentStatus.SEARCHING -> ComposeAppTheme.colors.jacob
                ReceivePaymentStatus.FOUND -> ComposeAppTheme.colors.jacob
                ReceivePaymentStatus.WAITING_CONFIRMATION -> ComposeAppTheme.colors.jacob
                ReceivePaymentStatus.CONFIRMED -> ComposeAppTheme.colors.greenD
                ReceivePaymentStatus.FAILED -> ComposeAppTheme.colors.lucian
            },
            textAlign = TextAlign.Center
        )
    }
}
