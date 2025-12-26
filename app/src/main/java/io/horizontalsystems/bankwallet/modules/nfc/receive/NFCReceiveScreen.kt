package io.horizontalsystems.bankwallet.modules.nfc.receive

import android.app.Activity
import android.media.MediaPlayer
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.modules.nfc.NFCModule
import io.horizontalsystems.bankwallet.modules.nfc.core.components.NumericKeyboard
import io.horizontalsystems.bankwallet.modules.nfc.receive.NFCReceiveViewModel
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * Screen for receiving NFC payments (POS/Merchant mode).
 * Shows amount input with numeric keyboard and initiates NFC reader mode.
 * 
 * @param navController Navigation controller
 */
@Composable
fun NFCReceiveScreen(
    navController: NavController,
    viewModel: NFCReceiveViewModel = viewModel(factory = NFCModule.Factory())
) {
    val uiState = viewModel.uiState
    val context = LocalContext.current
    val activity = context as? Activity
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(uiState.isProcessing) {
        if (uiState.isProcessing && activity != null && nfcAdapter != null) {
            nfcAdapter.enableReaderMode(
                activity,
                { tag ->
                    coroutineScope.launch(Dispatchers.IO) {
                        viewModel.handleNFCTag(tag)
                    }
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                Bundle().apply {
                    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
                }
            )
        }

        onDispose {
            if (activity != null && nfcAdapter != null) {
                nfcAdapter.disableReaderMode(activity)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler)
    ) {
        when {
            uiState.isPaymentConfirmed -> {
                NFCPaymentSuccessScreen(
                    transactionHash = uiState.transactionHash ?: "",
                    formattedAmount = uiState.formattedAmount
                )
            }
            uiState.isProcessing -> {
                NFCWaitingOverlay(
                    statusMessage = uiState.statusMessage,
                    onCancel = { viewModel.cancelPayment() }
                )
            }
            else -> {
                NFCAmountInputContent(
                    amount = uiState.amount,
                    formattedAmount = uiState.formattedAmount,
                    onDigitPressed = { digit -> viewModel.appendDigit(digit) },
                    onClearPressed = { viewModel.clearAmount() },
                    onChargePressed = { viewModel.startPayment() },
                    chargeEnabled = uiState.chargeEnabled
                )
            }
        }
    }
}

/**
 * Content for amount input with numeric keyboard
 * Optimized for small screens with adaptive layout
 */
@Composable
private fun NFCAmountInputContent(
    amount: BigDecimal,
    formattedAmount: String,
    onDigitPressed: (String) -> Unit,
    onClearPressed: () -> Unit,
    onChargePressed: () -> Unit,
    chargeEnabled: Boolean
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.tyler)
    ) {
        val isCompactScreen = maxHeight < 600.dp
        val topPadding = if (isCompactScreen) 8.dp else 16.dp
        val amountTextStyle = if (isCompactScreen) ComposeAppTheme.typography.headline1 else ComposeAppTheme.typography.title3
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .then(
                    if (isCompactScreen) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = topPadding)
            ) {
                Text(
                    text = stringResource(R.string.NFC_EnterAmount),
                    style = ComposeAppTheme.typography.micro,
                    color = ComposeAppTheme.colors.grey
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formattedAmount,
                    style = amountTextStyle,
                    color = ComposeAppTheme.colors.leah,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            NumericKeyboard(
                onDigitClick = onDigitPressed,
                onClearClick = onClearPressed,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.height(8.dp))

            ButtonPrimaryYellow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(bottom = 8.dp),
                title = stringResource(R.string.NFC_TapToReceive),
                onClick = onChargePressed,
                enabled = chargeEnabled
            )
        }
    }
}

/**
 * Success screen shown when payment is confirmed
 */
@Composable
private fun NFCPaymentSuccessScreen(
    transactionHash: String,
    formattedAmount: String
) {
    val context = LocalContext.current
    
    LaunchedEffect(transactionHash) {
        try {
            val mediaPlayer = MediaPlayer.create(context, R.raw.cashmachinesound)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            // Error playing sound - ignore silently
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.remus),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "✓",
                style = ComposeAppTheme.typography.headline1,
                color = Color.White,
                fontSize = 72.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "APPROVED",
                style = ComposeAppTheme.typography.headline1,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = formattedAmount,
                style = ComposeAppTheme.typography.title3,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Transaction: ${transactionHash.take(10)}...${transactionHash.takeLast(8)}",
                style = ComposeAppTheme.typography.caption,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Overlay shown while waiting for NFC device
 */
@Composable
private fun NFCWaitingOverlay(
    statusMessage: String,
    onCancel: () -> Unit
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
                text = statusMessage,
                style = ComposeAppTheme.typography.headline2,
                color = ComposeAppTheme.colors.leah,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.NFC_WaitingForDevice),
                style = ComposeAppTheme.typography.subhead,
                color = ComposeAppTheme.colors.grey,
                textAlign = TextAlign.Center
            )

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

