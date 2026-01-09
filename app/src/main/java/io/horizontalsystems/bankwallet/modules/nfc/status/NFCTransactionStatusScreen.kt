package io.horizontalsystems.bankwallet.modules.nfc.status

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
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import kotlinx.coroutines.delay

/**
 * Screen displaying NFC transaction status with dynamic animations.
 * Shows progress from SENT -> FOUND -> CONFIRMED states.
 */
@Composable
fun NFCTransactionStatusScreen(
    navController: NavController,
    viewModel: NFCTransactionStatusViewModel,
    transactionHash: String,
    chainId: Int
) {
    val uiState = viewModel.uiState

    LaunchedEffect(transactionHash, chainId) {
        viewModel.setNavController(navController)
        viewModel.initialize(transactionHash, chainId)
    }

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
            StatusIcon(status = uiState.status)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            StatusText(status = uiState.status)
        }
    }
}

/**
 * Display icon based on transaction status
 */
@Composable
private fun StatusIcon(status: TransactionStatus) {
    val iconRes = when (status) {
        TransactionStatus.SENT -> R.drawable.arrow_m_up_24
        TransactionStatus.SEARCHING -> R.drawable.binocular_24
        TransactionStatus.FOUND -> R.drawable.binocular_24
        TransactionStatus.WAITING -> R.drawable.binocular_24
        TransactionStatus.CONFIRMED -> R.drawable.icon_20_check_1
        TransactionStatus.FAILED -> R.drawable.icon_warning_2_20
    }
    
    val iconColor = when (status) {
        TransactionStatus.SENT -> ComposeAppTheme.colors.jacob
        TransactionStatus.SEARCHING -> ComposeAppTheme.colors.jacob
        TransactionStatus.FOUND -> ComposeAppTheme.colors.jacob
        TransactionStatus.WAITING -> ComposeAppTheme.colors.jacob
        TransactionStatus.CONFIRMED -> ComposeAppTheme.colors.greenD
        TransactionStatus.FAILED -> ComposeAppTheme.colors.lucian
    }
    
    val scale by animateFloatAsState(
        targetValue = if (status == TransactionStatus.CONFIRMED) 1.2f else 1f,
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
private fun StatusText(status: TransactionStatus) {
    val baseText = when (status) {
        TransactionStatus.SENT -> stringResource(R.string.NFC_TransactionStatus_Sent)
        TransactionStatus.SEARCHING -> stringResource(R.string.NFC_TransactionStatus_Searching)
        TransactionStatus.FOUND -> stringResource(R.string.NFC_TransactionStatus_Found)
        TransactionStatus.WAITING -> stringResource(R.string.NFC_TransactionStatus_Waiting)
        TransactionStatus.CONFIRMED -> stringResource(R.string.NFC_TransactionStatus_Confirmed)
        TransactionStatus.FAILED -> "Transaction failed"
    }
    
    val shouldShowDots = status == TransactionStatus.SEARCHING || status == TransactionStatus.WAITING
    
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
                TransactionStatus.SENT -> ComposeAppTheme.colors.jacob
                TransactionStatus.SEARCHING -> ComposeAppTheme.colors.jacob
                TransactionStatus.FOUND -> ComposeAppTheme.colors.jacob
                TransactionStatus.WAITING -> ComposeAppTheme.colors.jacob
                TransactionStatus.CONFIRMED -> ComposeAppTheme.colors.greenD
                TransactionStatus.FAILED -> ComposeAppTheme.colors.lucian
            },
            textAlign = TextAlign.Center
        )
    }
}

