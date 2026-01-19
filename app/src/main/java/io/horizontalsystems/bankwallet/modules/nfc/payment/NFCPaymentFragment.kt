package io.horizontalsystems.bankwallet.modules.nfc.payment

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.BaseFragment
import io.horizontalsystems.bankwallet.core.requireInput
import io.horizontalsystems.bankwallet.core.slideFromRight
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.modules.nfc.send.CustomerTokenOption
import io.horizontalsystems.bankwallet.modules.send.SendFragment
import io.horizontalsystems.core.findNavController
import kotlinx.parcelize.Parcelize
import java.math.BigDecimal
import java.math.RoundingMode

class NFCPaymentFragment : BaseFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(viewLifecycleOwner)
            )
            try {
                val navController = findNavController()
                val input = navController.requireInput<Input>()
                
                // Get customer's base currency
                val customerCurrency = App.currencyManager.baseCurrency
                
                // Convert merchant fiat amount to customer's currency
                val merchantAmount = BigDecimal(input.fiatAmount)
                val merchantCurrency = input.fiatCurrency
                
                val displayAmount: String
                val displayCurrency: String
                
                if (merchantCurrency == customerCurrency.code) {
                    // Same currency, no conversion needed
                    displayAmount = input.fiatAmount
                    displayCurrency = merchantCurrency
                } else {
                    // Convert from merchant currency to customer currency
                    val convertedAmount = convertCurrency(
                        amount = merchantAmount,
                        fromCurrency = merchantCurrency,
                        toCurrency = customerCurrency.code
                    )
                    
                    android.util.Log.d("NFCPaymentFragment", "Currency conversion: $merchantAmount $merchantCurrency -> $convertedAmount ${customerCurrency.code}")
                    
                    // Use CEILING to always round up - ensures customer never pays less than required
                    displayAmount = convertedAmount?.setScale(2, RoundingMode.CEILING)?.toPlainString() ?: input.fiatAmount
                    displayCurrency = if (convertedAmount != null) customerCurrency.code else merchantCurrency
                }

                setContent {
                    NFCPaymentScreen(
                        title = input.title,
                        navController = navController,
                        fiatAmount = displayAmount,
                        fiatCurrency = displayCurrency,
                        availableTokens = input.availableTokens,
                        onTokenSelected = { /* Token selection is handled internally */ },
                        onConfirm = { selectedToken ->
                            // Navigate to SendFragment with the selected token
                            val wallet = selectedToken.wallet
                            if (wallet != null) {
                                navController.slideFromRight(
                                    R.id.sendXFragment,
                                    SendFragment.Input(
                                        wallet = wallet,
                                        title = input.title,
                                        sendEntryPointDestId = input.sendEntryPointDestId,
                                        address = Address(selectedToken.tokenOption.receiverAddress),
                                        amount = selectedToken.tokenOption.getAmountBigDecimal(),
                                        hideAddress = false,
                                        amountLocked = true
                                    )
                                )
                            }
                        },
                        onCancel = {
                            navController.popBackStack()
                        },
                        sendEntryPointDestId = input.sendEntryPointDestId
                    )
                }
            } catch (t: Throwable) {
                findNavController().popBackStack()
            }
        }
    }
    
    /**
     * Convert amount from one fiat currency to another using stablecoin rates.
     * Uses USDT as reference since it's pegged to USD and provides accurate fiat conversion.
     */
    private fun convertCurrency(amount: BigDecimal, fromCurrency: String, toCurrency: String): BigDecimal? {
        return try {
            val marketKit = App.marketKit
            
            // Use USDT (stablecoin pegged to USD) for more accurate fiat conversion
            val usdtUid = "tether"
            
            // Get USDT price in source currency (e.g., USDT in BRL = ~5.37)
            val usdtPriceFrom = marketKit.coinPrice(usdtUid, fromCurrency)?.value
            // Get USDT price in target currency (e.g., USDT in USD = ~1.00)
            val usdtPriceTo = marketKit.coinPrice(usdtUid, toCurrency)?.value
            
            android.util.Log.d("NFCPaymentFragment", "USDT prices - From ($fromCurrency): $usdtPriceFrom, To ($toCurrency): $usdtPriceTo")
            
            if (usdtPriceFrom != null && usdtPriceTo != null && 
                usdtPriceFrom > BigDecimal.ZERO && usdtPriceTo > BigDecimal.ZERO) {
                // Convert using USDT as stable reference
                // amount in fromCurrency / usdtPriceFrom = amount in USDT
                // amount in USDT * usdtPriceTo = amount in toCurrency
                val amountInUsdt = amount.divide(usdtPriceFrom, 18, RoundingMode.HALF_UP)
                val result = amountInUsdt.multiply(usdtPriceTo)
                android.util.Log.d("NFCPaymentFragment", "Conversion via USDT: $amount $fromCurrency -> $amountInUsdt USDT -> $result $toCurrency")
                return result
            }
            
            // Fallback to BTC if USDT not available
            val btcUid = "bitcoin"
            val btcPriceFrom = marketKit.coinPrice(btcUid, fromCurrency)?.value ?: return null
            val btcPriceTo = marketKit.coinPrice(btcUid, toCurrency)?.value ?: return null
            
            if (btcPriceFrom <= BigDecimal.ZERO || btcPriceTo <= BigDecimal.ZERO) {
                return null
            }
            
            val exchangeRate = btcPriceTo.divide(btcPriceFrom, 18, RoundingMode.HALF_UP)
            amount.multiply(exchangeRate)
        } catch (e: Exception) {
            android.util.Log.e("NFCPaymentFragment", "Error converting currency: $fromCurrency -> $toCurrency", e)
            null
        }
    }

    @Parcelize
    data class Input(
        val title: String,
        val fiatAmount: String,
        val fiatCurrency: String,
        val availableTokens: List<CustomerTokenOption>,
        val sendEntryPointDestId: Int
    ) : Parcelable
}
