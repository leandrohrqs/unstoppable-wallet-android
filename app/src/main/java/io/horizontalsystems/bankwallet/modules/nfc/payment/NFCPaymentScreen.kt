package io.horizontalsystems.bankwallet.modules.nfc.payment

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.modules.nfc.send.CustomerTokenOption
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme
import io.horizontalsystems.bankwallet.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.bankwallet.ui.compose.components.VSpacer
import io.horizontalsystems.bankwallet.uiv3.components.HSScaffold
import io.horizontalsystems.core.helpers.HudHelper

/**
 * NFC Payment Screen - shows token selection for multi-token NFC payments.
 * Displays merchant accepted tokens, customer balances, and allows selection before confirmation.
 */
@Composable
fun NFCPaymentScreen(
    title: String,
    navController: NavController,
    fiatAmount: String,
    fiatCurrency: String,
    availableTokens: List<CustomerTokenOption>,
    onTokenSelected: (CustomerTokenOption) -> Unit,
    onConfirm: (CustomerTokenOption) -> Unit,
    onCancel: () -> Unit,
    sendEntryPointDestId: Int
) {
    var selectedToken by remember { mutableStateOf<CustomerTokenOption?>(null) }
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    
    // Auto-select first valid token if only one option with sufficient balance
    LaunchedEffect(availableTokens) {
        val validTokens = availableTokens.filter { it.hasSufficientBalance }
        if (validTokens.size == 1 && selectedToken == null) {
            selectedToken = validTokens.first()
        }
    }

    // Calculate max height for token list based on screen size
    // Each item is approximately 56dp (48dp height + 8dp spacing) for compact cells
    // Small screens (<640dp): show 2.5 items = ~140dp
    // Medium screens (640-800dp): show 4 items = ~224dp
    // Large screens (>800dp): show 5 items = ~280dp
    val isSmallScreen = configuration.screenHeightDp < 640
    val tokenListMaxHeight = when {
        configuration.screenHeightDp < 640 -> 140.dp  // Small screens: ~2.5 items
        configuration.screenHeightDp < 800 -> 224.dp  // Medium screens: 4 items
        else -> 280.dp                                 // Large screens: 5 items
    }
    
    ComposeAppTheme {
        HSScaffold(
            title = title,
            onBack = onCancel,
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Payment amount header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ComposeAppTheme.colors.tyler)
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.NFC_PaymentAmount),
                        style = ComposeAppTheme.typography.subhead,
                        color = ComposeAppTheme.colors.grey
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$fiatCurrency $fiatAmount",
                        style = ComposeAppTheme.typography.headline1,
                        color = ComposeAppTheme.colors.leah
                    )
                }
                
                VSpacer(12.dp)
                
                // Section title with count
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.NFC_SelectPaymentMethod),
                        style = ComposeAppTheme.typography.subhead,
                        color = ComposeAppTheme.colors.grey
                    )
                    if (availableTokens.size > 5) {
                        Text(
                            text = "${availableTokens.size} tokens",
                            style = ComposeAppTheme.typography.caption,
                            color = ComposeAppTheme.colors.grey
                        )
                    }
                }
                
                VSpacer(8.dp)
                
                // Token list with fixed max height for scroll
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = tokenListMaxHeight),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(availableTokens) { option ->
                        val isSelected = selectedToken == option
                        NFCTokenCell(
                            option = option,
                            isSelected = isSelected,
                            onClick = { 
                                if (option.hasSufficientBalance) {
                                    selectedToken = option
                                    onTokenSelected(option)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
                
                VSpacer(12.dp)
                
                // Bottom section with selected token and confirm button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ComposeAppTheme.colors.tyler)
                        .padding(16.dp)
                ) {
                    // Selected token summary
                    selectedToken?.let { token ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    ComposeAppTheme.colors.jacob.copy(alpha = 0.1f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.NFC_SelectedPayment),
                                    style = ComposeAppTheme.typography.micro,
                                    color = ComposeAppTheme.colors.grey
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${formatAmount(token.tokenOption.amount)} ${token.tokenOption.symbol}",
                                    style = ComposeAppTheme.typography.headline2,
                                    color = ComposeAppTheme.colors.jacob
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.icon_20_check_1),
                                contentDescription = null,
                                tint = ComposeAppTheme.colors.jacob,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        VSpacer(12.dp)
                    }
                    
                    // Confirm button
                    ButtonPrimaryYellow(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Button_Next),
                        onClick = {
                            val token = selectedToken
                            if (token != null) {
                                onConfirm(token)
                            } else {
                                HudHelper.showErrorMessage(view, R.string.NFC_SelectPaymentMethod)
                            }
                        },
                        enabled = selectedToken != null
                    )
                }
            }
        }
    }
}

/**
 * Format crypto amount intelligently based on the value.
 * - Values >= 1: show up to 6 significant decimal places
 * - Values < 1 but >= 0.001: show up to 6 decimal places
 * - Very small values: show up to 8 decimal places
 * Always removes trailing zeros.
 */
private fun formatAmount(amount: String): String {
    return try {
        val value = amount.toBigDecimal()
        
        val maxDecimals = when {
            value >= java.math.BigDecimal.ONE -> 6           // >= 1: up to 6 decimals
            value >= java.math.BigDecimal("0.001") -> 6      // >= 0.001: up to 6 decimals
            value >= java.math.BigDecimal("0.000001") -> 8   // >= 0.000001: up to 8 decimals
            else -> 10                                        // Very small: up to 10 decimals
        }
        
        if (value.scale() > maxDecimals) {
            value.setScale(maxDecimals, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        } else {
            value.stripTrailingZeros().toPlainString()
        }
    } catch (e: Exception) {
        amount
    }
}

/**
 * Get coin icon URL from coin UID
 */
private fun getCoinIconUrl(coinUid: String): String {
    return "https://cdn.blocksdecoded.com/coin-icons/32px/$coinUid@3x.png"
}

/**
 * Get network name from blockchain UID.
 * Uses the blockchain UID to display a user-friendly network name.
 */
private fun getNetworkName(blockchainUid: String): String {
    // Common blockchain UIDs from MarketKit
    return when (blockchainUid) {
        "ethereum" -> "Ethereum"
        "binance-smart-chain" -> "BNB Chain"
        "polygon-pos" -> "Polygon"
        "avalanche" -> "Avalanche"
        "optimistic-ethereum" -> "Optimism"
        "arbitrum-one" -> "Arbitrum"
        "gnosis" -> "Gnosis"
        "fantom" -> "Fantom"
        "base" -> "Base"
        "zksync" -> "zkSync"
        "bitcoin" -> "Bitcoin"
        "bitcoin-cash" -> "Bitcoin Cash"
        "litecoin" -> "Litecoin"
        "dash" -> "Dash"
        "zcash" -> "Zcash"
        "ecash" -> "eCash"
        "solana" -> "Solana"
        "the-open-network" -> "TON"
        "tron" -> "Tron"
        "monero" -> "Monero"
        "stellar" -> "Stellar"
        else -> blockchainUid.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Cell for displaying a token option in NFC payment selection
 * Compact design for better fit on small screens
 */
@Composable
private fun NFCTokenCell(
    option: CustomerTokenOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (option.hasSufficientBalance) 1f else 0.5f
    val iconUrl = getCoinIconUrl(option.tokenOption.uid)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isSelected) ComposeAppTheme.colors.jacob.copy(alpha = 0.1f) 
                       else ComposeAppTheme.colors.lawrence,
                shape = RoundedCornerShape(8.dp)
            )
            .then(
                if (option.hasSufficientBalance) {
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .graphicsLayer(alpha = alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection indicator - smaller
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    color = if (isSelected) ComposeAppTheme.colors.jacob 
                           else ComposeAppTheme.colors.grey.copy(alpha = 0.2f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    painter = painterResource(R.drawable.icon_20_check_1),
                    contentDescription = null,
                    tint = ComposeAppTheme.colors.white,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Token icon from network - smaller
        Image(
            painter = rememberAsyncImagePainter(
                model = iconUrl,
                error = painterResource(R.drawable.coin_placeholder)
            ),
            contentDescription = option.tokenOption.symbol,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Token info - more compact
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.tokenOption.symbol,
                    style = ComposeAppTheme.typography.captionSB,
                    color = ComposeAppTheme.colors.leah
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = getNetworkName(option.tokenOption.blockchainUid),
                    style = ComposeAppTheme.typography.micro,
                    color = ComposeAppTheme.colors.grey
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${option.balance?.stripTrailingZeros()?.toPlainString() ?: "0"}",
                    style = ComposeAppTheme.typography.micro,
                    color = if (option.hasSufficientBalance) ComposeAppTheme.colors.remus 
                           else ComposeAppTheme.colors.lucian,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!option.hasSufficientBalance) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.NFC_InsufficientBalance),
                        style = ComposeAppTheme.typography.micro,
                        color = ComposeAppTheme.colors.lucian
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(6.dp))
        
        // Amount to pay - right aligned, compact
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(R.string.NFC_ToPay),
                style = ComposeAppTheme.typography.micro,
                color = ComposeAppTheme.colors.grey
            )
            Text(
                text = formatAmount(option.tokenOption.amount),
                style = ComposeAppTheme.typography.caption,
                color = ComposeAppTheme.colors.leah,
                maxLines = 1
            )
        }
    }
}
