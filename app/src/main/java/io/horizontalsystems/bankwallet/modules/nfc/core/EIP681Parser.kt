package io.horizontalsystems.bankwallet.modules.nfc.core

import android.net.Uri
import android.util.Log
import io.horizontalsystems.marketkit.models.BlockchainType
import java.math.BigDecimal

/**
 * Parser for EIP-681 payment URIs.
 * Format: ethereum:<contract>@<chainId>/transfer?address=<recipient>&uint256=<amount>
 * Format: ethereum:<recipient>@<chainId>?value=<amount>
 * 
 * Examples:
 * - USDT: ethereum:0xdac17f958d2ee523a2206206994597c13d831ec7@1/transfer?address=0x...&uint256=1000000
 * - ETH: ethereum:0x...@1?value=1000000000000000000
 */
data class PaymentRequest(
    val token: TokenContract,
    val recipient: String,
    val amount: BigDecimal,
    val chainId: Int,
    val blockchainType: BlockchainType
)

data class TokenContract(
    val address: String,
    val decimals: Int,
    val symbol: String
)

object EIP681Parser {
    private const val TAG = "EIP681Parser"
    
    private val TOKEN_INFO = mapOf(
        "0xdac17f958d2ee523a2206206994597c13d831ec7" to TokenContract(
            address = "0xdAC17F958D2ee523a2206206994597C13D831ec7",
            decimals = 6,
            symbol = "USDT"
        ),
        "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to TokenContract(
            address = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            decimals = 6,
            symbol = "USDC"
        ),
        "0x6b175474e89094c44da98b954eedeac495271d0f" to TokenContract(
            address = "0x6B175474E89094C44Da98b954EedeAC495271d0F",
            decimals = 18,
            symbol = "DAI"
        )
    )
    
    /**
     * Parse an EIP-681 payment URI into a PaymentRequest object.
     * 
     * @param uri The payment URI (e.g., "ethereum:0xdac17f958d2ee523a2206206994597c13d831ec7@1/transfer?address=0x...&uint256=1000000")
     * @return PaymentRequest object or null if parsing fails
     */
    fun parse(uri: String): PaymentRequest? {
        try {
            if (!uri.startsWith("ethereum:")) {
                logError("Invalid URI scheme: must start with 'ethereum:'")
                return null
            }
            
            val uriWithoutScheme = uri.substring(9)
            
            val parts = uriWithoutScheme.split("@")
            if (parts.size != 2) {
                logError("Invalid URI format: missing @ separator")
                return null
            }
            
            val contractOrRecipient = parts[0].lowercase()
            val chainAndParams = parts[1]
            
            val chainIdEnd = chainAndParams.indexOfAny(charArrayOf('/', '?'))
            if (chainIdEnd == -1) {
                logError("Invalid URI format: missing chain parameters")
                return null
            }
            
            val chainId = chainAndParams.substring(0, chainIdEnd).toIntOrNull()
            if (chainId == null) {
                logError("Invalid chain ID")
                return null
            }
            
            val remainder = chainAndParams.substring(chainIdEnd)
            
            return if (remainder.startsWith("/transfer?")) {
                parseTokenTransfer(contractOrRecipient, chainId, remainder.substring(10))
            } else if (remainder.startsWith("?")) {
                parseNativeTransfer(contractOrRecipient, chainId, remainder.substring(1))
            } else {
                logError("Invalid URI format: unknown transfer type")
                null
            }
            
        } catch (e: Exception) {
            logError("Error parsing payment URI: ${e.message}")
            return null
        }
    }
    
    private fun parseTokenTransfer(contractAddress: String, chainId: Int, params: String): PaymentRequest? {
        try {
            val uri = Uri.parse("dummy://?$params")
            val recipient = uri.getQueryParameter("address")
            val amountStr = uri.getQueryParameter("uint256")
            
            if (recipient == null || amountStr == null) {
                logError("Missing required parameters: address or uint256")
                return null
            }
            
            val tokenInfo = TOKEN_INFO[contractAddress.lowercase()] ?: TOKEN_INFO[contractAddress] ?: run {
                logError("Unknown token contract: $contractAddress")
                return null
            }
            
            val amount = BigDecimal(amountStr).movePointLeft(tokenInfo.decimals)
            val blockchainType = getBlockchainType(chainId)
            
            return PaymentRequest(
                token = tokenInfo,
                recipient = recipient,
                amount = amount,
                chainId = chainId,
                blockchainType = blockchainType
            )
        } catch (e: Exception) {
            logError("Error parsing token transfer: ${e.message}")
            return null
        }
    }
    
    private fun parseNativeTransfer(recipient: String, chainId: Int, params: String): PaymentRequest? {
        try {
            val uri = Uri.parse("dummy://?$params")
            val amountStr = uri.getQueryParameter("value")
            
            if (amountStr == null) {
                logError("Missing required parameter: value")
                return null
            }
            
            val amount = BigDecimal(amountStr).movePointLeft(18)
            val blockchainType = getBlockchainType(chainId)
            
            val tokenInfo = TokenContract(
                address = "0x0000000000000000000000000000000000000000",
                decimals = 18,
                symbol = "ETH"
            )
            
            return PaymentRequest(
                token = tokenInfo,
                recipient = recipient,
                amount = amount,
                chainId = chainId,
                blockchainType = blockchainType
            )
        } catch (e: Exception) {
            logError("Error parsing native transfer: ${e.message}")
            return null
        }
    }
    
    private fun getBlockchainType(chainId: Int): BlockchainType {
        return when (chainId) {
            1 -> BlockchainType.Ethereum
            10 -> BlockchainType.Optimism
            137 -> BlockchainType.Polygon
            42161 -> BlockchainType.ArbitrumOne
            8453 -> BlockchainType.Base
            56 -> BlockchainType.BinanceSmartChain
            else -> BlockchainType.Ethereum
        }
    }
    
    private fun logError(message: String) {
        Log.e(TAG, message)
    }
}

