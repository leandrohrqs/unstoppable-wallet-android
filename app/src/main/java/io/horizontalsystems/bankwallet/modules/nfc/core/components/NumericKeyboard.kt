package io.horizontalsystems.bankwallet.modules.nfc.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme

/**
 * Numeric keyboard component for amount input.
 * Displays a calculator-style keyboard with digits 0-9 and clear button.
 * Optimized for small screens with compact layout.
 * 
 * @param onDigitClick Callback when a digit is clicked
 * @param onClearClick Callback when clear button is clicked
 * @param modifier Optional modifier
 */
@Composable
fun NumericKeyboard(
    onDigitClick: (String) -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyboardButton("1", onDigitClick, Modifier.weight(1f))
            KeyboardButton("2", onDigitClick, Modifier.weight(1f))
            KeyboardButton("3", onDigitClick, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyboardButton("4", onDigitClick, Modifier.weight(1f))
            KeyboardButton("5", onDigitClick, Modifier.weight(1f))
            KeyboardButton("6", onDigitClick, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyboardButton("7", onDigitClick, Modifier.weight(1f))
            KeyboardButton("8", onDigitClick, Modifier.weight(1f))
            KeyboardButton("9", onDigitClick, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ClearButton(onClearClick, Modifier.weight(1f))
            KeyboardButton("0", onDigitClick, Modifier.weight(1f))
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Individual keyboard button for digits
 * Compact version for small screens
 */
@Composable
private fun KeyboardButton(
    digit: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1.3f)
            .clip(RoundedCornerShape(8.dp))
            .background(ComposeAppTheme.colors.lawrence)
            .clickable { onClick(digit) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit,
            style = ComposeAppTheme.typography.headline2,
            color = ComposeAppTheme.colors.leah
        )
    }
}

/**
 * Clear button with icon
 * Compact version for small screens
 */
@Composable
private fun ClearButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1.3f)
            .clip(RoundedCornerShape(8.dp))
            .background(ComposeAppTheme.colors.lucian.copy(alpha = 0.2f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "C",
            style = ComposeAppTheme.typography.headline2,
            color = ComposeAppTheme.colors.lucian
        )
    }
}

