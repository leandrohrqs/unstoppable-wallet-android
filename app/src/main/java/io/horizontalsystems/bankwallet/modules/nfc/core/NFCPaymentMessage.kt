package io.horizontalsystems.bankwallet.modules.nfc.core

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/**
 * Data class representing the NFC payment request message.
 * This is sent from the receiver (merchant) to the customer via NFC.
 */
data class NFCPaymentRequest(
    @SerializedName("fiat")
    val fiat: FiatAmount,
    @SerializedName("tokens")
    val tokens: List<TokenPaymentOption>
) {
    companion object {
        private val gson = Gson()
        
        /**
         * Parse from JSON string received via NFC.
         */
        fun fromJson(json: String): NFCPaymentRequest? {
            return try {
                gson.fromJson(json, NFCPaymentRequest::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Convert to JSON string for NFC transmission.
     */
    fun toJson(): String {
        return gson.toJson(this)
    }
}

/**
 * Fiat amount with currency code.
 */
data class FiatAmount(
    @SerializedName("amount")
    val amount: String,
    @SerializedName("currency")
    val currency: String
) {
    fun toBigDecimal(): BigDecimal = BigDecimal(amount)
}

/**
 * Token payment option with all information needed for payment.
 * Uses blockchainUid (string) as primary identifier for any blockchain type.
 * chainId is kept for EVM compatibility (positive values only).
 */
@kotlinx.parcelize.Parcelize
data class TokenPaymentOption(
    @SerializedName("symbol")
    val symbol: String,
    @SerializedName("uid")
    val uid: String,
    @SerializedName("blockchainUid")
    val blockchainUid: String,
    @SerializedName("chain")
    val chainId: Int, // EVM chain ID (positive) or 0 for non-EVM
    @SerializedName("tokenAddress")
    val tokenAddress: String,
    @SerializedName("to")
    val receiverAddress: String,
    @SerializedName("amount")
    val amount: String,
    @SerializedName("amountWei")
    val amountWei: String,
    @SerializedName("decimals")
    val decimals: Int
) : android.os.Parcelable {
    fun getAmountBigDecimal(): BigDecimal = BigDecimal(amount)
    
    /**
     * Check if this is an EVM-compatible blockchain.
     */
    fun isEvmChain(): Boolean = chainId > 0
    
    /**
     * Create EIP-681 payment URI for this token option (EVM only).
     */
    fun toPaymentUri(): String {
        return if (tokenAddress == "0x0000000000000000000000000000000000000000" || tokenAddress == "native") {
            "ethereum:$receiverAddress@$chainId?value=$amountWei"
        } else {
            "ethereum:$tokenAddress@$chainId/transfer?address=$receiverAddress&uint256=$amountWei"
        }
    }
    
    companion object {
        /**
         * Create from AcceptedToken.
         */
        fun fromAcceptedToken(acceptedToken: AcceptedToken): TokenPaymentOption {
            return TokenPaymentOption(
                symbol = acceptedToken.token.coin.code,
                uid = acceptedToken.token.coin.uid,
                blockchainUid = acceptedToken.token.blockchainType.uid,
                chainId = acceptedToken.evmChainId ?: 0,
                tokenAddress = acceptedToken.tokenAddress,
                receiverAddress = acceptedToken.receiverAddress,
                amount = acceptedToken.amountInToken.toPlainString(),
                amountWei = acceptedToken.amountInWei,
                decimals = acceptedToken.token.decimals
            )
        }
    }
}

/**
 * Data class representing the customer's token selection response.
 * This is sent from the customer back to the receiver via NFC.
 */
data class NFCPaymentResponse(
    @SerializedName("selectedToken")
    val selectedToken: String, // Token UID
    @SerializedName("txHash")
    val transactionHash: String? = null
) {
    companion object {
        private val gson = Gson()
        
        fun fromJson(json: String): NFCPaymentResponse? {
            return try {
                gson.fromJson(json, NFCPaymentResponse::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun toJson(): String {
        return Gson().toJson(this)
    }
}
