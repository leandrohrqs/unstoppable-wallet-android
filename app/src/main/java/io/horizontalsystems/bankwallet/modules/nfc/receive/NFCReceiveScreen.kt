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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.navigationBarsPadding
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
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
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
            .background(ComposeAppTheme.colors.lawrence)
    ) {
        when {
            uiState.status == ReceivePaymentStatus.CONFIRMED -> {
                val context = LocalContext.current
                
                LaunchedEffect(uiState.status, uiState.transactionHash) {
                    if (uiState.status == ReceivePaymentStatus.CONFIRMED && uiState.transactionHash != null) {
                        try {
                            val mediaPlayer = MediaPlayer.create(context, R.raw.cashmachinesound)
                            mediaPlayer?.setOnCompletionListener { it.release() }
                            mediaPlayer?.start()
                        } catch (e: Exception) {
                            // Error playing sound - ignore silently
                        }
                        
                        // Reset after 3 seconds to allow retry
                        delay(3000)
                        viewModel.resetAfterSuccess()
                    }
                }
                
                NFCReceiveStatusScreen(
                    status = ReceivePaymentStatus.CONFIRMED,
                    onCancel = { viewModel.resetAfterSuccess() }
                )
            }
            uiState.isProcessing && uiState.status != null -> {
                // Play sound when NFC connection is established
                LaunchedEffect(uiState.status) {
                    if (uiState.status == ReceivePaymentStatus.CONNECTED) {
                        try {
                            val mediaPlayer = MediaPlayer.create(context, R.raw.nfctransferinitiated)
                            mediaPlayer?.setOnCompletionListener { it.release() }
                            mediaPlayer?.start()
                        } catch (e: Exception) {
                            // Error playing sound - ignore silently
                        }
                    }
                }
                
                NFCReceiveStatusScreen(
                    status = uiState.status!!,
                    onCancel = { viewModel.cancelPayment() }
                )
            }
            else -> {
                NFCAmountInputContent(
                    amount = uiState.amount,
                    formattedAmount = uiState.formattedAmount,
                    onDigitPressed = { digit -> viewModel.appendDigit(digit) },
                    onClearPressed = { viewModel.clearAmount() },
                    onBackspacePressed = { viewModel.removeLastDigit() },
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
    onBackspacePressed: () -> Unit,
    onChargePressed: () -> Unit,
    chargeEnabled: Boolean
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeAppTheme.colors.lawrence)
    ) {
        val isCompactScreen = maxHeight < 600.dp
        val topPadding = if (isCompactScreen) 8.dp else 16.dp
        val amountTextStyle = if (isCompactScreen) ComposeAppTheme.typography.headline1 else ComposeAppTheme.typography.title3
        val amountContainerPadding = if (isCompactScreen) 12.dp else 16.dp
        
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
                onBackspaceClick = onBackspacePressed,
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



