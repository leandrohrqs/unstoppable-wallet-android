package io.horizontalsystems.bankwallet.modules.nfc.core

import android.util.Log
import io.horizontalsystems.bankwallet.core.IAccountManager
import io.horizontalsystems.bankwallet.core.IAdapterManager
import io.horizontalsystems.bankwallet.core.IWalletManager
import io.horizontalsystems.bankwallet.core.adapters.EvmAdapter
import io.horizontalsystems.bankwallet.entities.Account
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import java.math.BigDecimal

/**
 * Helper class for integrating NFC functionality with Unstoppable Wallet.
 * Provides methods to access wallet addresses, adapters, and create transactions.
 */
class WalletIntegrationHelper(
    private val accountManager: IAccountManager,
    private val adapterManager: IAdapterManager,
    private val walletManager: IWalletManager
) {

    companion object {
        private const val TAG = "WalletIntegrationHelper"
    }

    /**
     * Get the currently active account
     */
    fun getActiveAccount(): Account? {
        return accountManager.activeAccount
    }

    /**
     * Get receive address for the primary wallet of the active account.
     * Returns the first available wallet address or null.
     */
    suspend fun getPrimaryWalletAddress(): String? {
        return try {
            val account = getActiveAccount() ?: return null

            val activeWallets = walletManager.activeWallets

            if (activeWallets.isEmpty()) {
                return null
            }

            val firstWallet = activeWallets.first()
            getWalletAddress(firstWallet)
        } catch (e: Exception) {
            logError("Error getting primary wallet address", e)
            null
        }
    }

    /**
     * Get receive address for a specific wallet
     */
    suspend fun getWalletAddress(wallet: Wallet): String? {
        return try {
            val adapter = adapterManager.getReceiveAdapterForWallet(wallet)
            if (adapter != null) {
                adapter.receiveAddress
            } else {
                null
            }
        } catch (e: Exception) {
            logError("Error getting wallet address for ${wallet.token.coin.code}", e)
            null
        }
    }

    /**
     * Get wallet address for a specific blockchain type
     */
    suspend fun getAddressForBlockchain(blockchainType: BlockchainType): String? {
        return try {
            val activeWallets = walletManager.activeWallets
            val wallet = activeWallets.firstOrNull { it.token.blockchainType == blockchainType }

            if (wallet != null) {
                getWalletAddress(wallet)
            } else {
                null
            }
        } catch (e: Exception) {
            logError("Error getting address for blockchain ${blockchainType.uid}", e)
            null
        }
    }

    /**
     * Get all active wallets with their addresses
     */
    suspend fun getAllWalletsWithAddresses(): Map<Wallet, String> {
        return try {
            val activeWallets = walletManager.activeWallets
            val walletsWithAddresses = mutableMapOf<Wallet, String>()

            for (wallet in activeWallets) {
                val address = getWalletAddress(wallet)
                if (address != null) {
                    walletsWithAddresses[wallet] = address
                }
            }

            walletsWithAddresses
        } catch (e: Exception) {
            logError("Error getting all wallets with addresses", e)
            emptyMap()
        }
    }

    /**
     * Check if the account has any active wallets
     */
    fun hasActiveWallets(): Boolean {
        return walletManager.activeWallets.isNotEmpty()
    }

    /**
     * Get the count of active wallets
     */
    fun getActiveWalletsCount(): Int {
        return walletManager.activeWallets.size
    }

    /**
     * Parse blockchain type from chain ID (for EVM chains)
     */
    fun getBlockchainTypeFromChainId(chainId: Int): BlockchainType? {
        return when (chainId) {
            1 -> BlockchainType.Ethereum
            10 -> BlockchainType.Optimism
            137 -> BlockchainType.Polygon
            42161 -> BlockchainType.ArbitrumOne
            8453 -> BlockchainType.Base
            56 -> BlockchainType.BinanceSmartChain
            43114 -> BlockchainType.Avalanche
            250 -> BlockchainType.Fantom
            100 -> BlockchainType.Gnosis
            else -> null
        }
    }

    /**
     * Parse wallet address from various formats (CAIP-10, raw address, etc.)
     * @param payload Raw string containing the address
     * @return Extracted wallet address (0x...)
     */
    fun parseWalletAddress(payload: String): String {
        return when {
            payload.contains(":") -> {
                payload.substringAfterLast(":")
            }
            payload.startsWith("0x") -> {
                payload
            }
            else -> {
                val addressRegex = "0x[a-fA-F0-9]{40}".toRegex()
                addressRegex.find(payload)?.value ?: payload
            }
        }
    }

    /**
     * Select optimal payment token - simplified version for MVP.
     * Creates payment request for USDT on Ethereum by default.
     * The customer wallet will handle the actual token selection and balance check.
     * 
     * @param walletAddress Customer's wallet address (for logging only)
     * @param requiredAmount Amount to be paid
     * @return PaymentToken for the payment request, or null if no suitable token definition found
     */
    suspend fun selectOptimalPaymentToken(walletAddress: String, requiredAmount: BigDecimal): PaymentToken? {
        try {
            val wallets = walletManager.activeWallets
            
            val usdtToken = wallets.firstOrNull { wallet ->
                wallet.token.coin.code == "USDT" && 
                wallet.token.blockchainType == BlockchainType.Ethereum
            }?.token
            
            if (usdtToken != null) {
                val chainId = getChainIdForBlockchain(usdtToken.blockchainType) ?: 1
                val address = when (val tokenType = usdtToken.type) {
                    is TokenType.Eip20 -> tokenType.address
                    else -> "0xdac17f958d2ee523a2206206994597c13d831ec7"
                }
                
                return PaymentToken(
                    token = usdtToken,
                    balance = BigDecimal.ZERO,
                    symbol = "USDT",
                    chainId = chainId,
                    address = address,
                    decimals = usdtToken.decimals
                )
            }
            
            val stablecoin = wallets.firstOrNull { wallet ->
                val code = wallet.token.coin.code
                (code == "USDC" || code == "DAI") && 
                wallet.token.blockchainType == BlockchainType.Ethereum
            }
            
            if (stablecoin != null) {
                val chainId = getChainIdForBlockchain(stablecoin.token.blockchainType) ?: 1
                val address = when (val tokenType = stablecoin.token.type) {
                    is TokenType.Eip20 -> tokenType.address
                    else -> "0x0000000000000000000000000000000000000000"
                }
                
                return PaymentToken(
                    token = stablecoin.token,
                    balance = BigDecimal.ZERO,
                    symbol = stablecoin.token.coin.code,
                    chainId = chainId,
                    address = address,
                    decimals = stablecoin.token.decimals
                )
            }
            
            val ethToken = wallets.firstOrNull { wallet ->
                wallet.token.coin.code == "ETH" && 
                wallet.token.blockchainType == BlockchainType.Ethereum
            }
            
            if (ethToken != null) {
                return PaymentToken(
                    token = ethToken.token,
                    balance = BigDecimal.ZERO,
                    symbol = "ETH",
                    chainId = 1,
                    address = "0x0000000000000000000000000000000000000000",
                    decimals = 18
                )
            }
            
            logError("No suitable tokens found in merchant wallet configuration", null)
            return null
            
        } catch (e: Exception) {
            logError("Error selecting payment token", e)
            return null
        }
    }

    /**
     * Create EIP-681 payment URI for a given token, amount, and merchant address.
     * Format: ethereum:{contract}@{chainId}/transfer?address={recipient}&uint256={amount}
     * For native tokens: ethereum:{recipient}@{chainId}?value={amount}
     * 
     * @param token Payment token to use
     * @param amount Amount to transfer
     * @return EIP-681 formatted URI string
     */
    suspend fun createEIP681URI(token: PaymentToken, amount: BigDecimal): String {
        val merchantAddress = getAddressForBlockchain(token.token.blockchainType)
            ?: throw IllegalStateException("Merchant address not available for ${token.token.blockchainType}")
        
        val amountInWei = toWei(amount, token.decimals)

        return if (token.address == "0x0000000000000000000000000000000000000000") {
            "ethereum:$merchantAddress@${token.chainId}?value=$amountInWei"
        } else {
            "ethereum:${token.address}@${token.chainId}/transfer?address=$merchantAddress&uint256=$amountInWei"
        }
    }

    /**
     * Convert human-readable amount to smallest unit (wei for Ethereum, etc.)
     * @param amount Human-readable amount
     * @param decimals Token decimals
     * @return Amount in smallest unit as string
     */
    fun toWei(amount: BigDecimal, decimals: Int): String {
        return amount.movePointRight(decimals).toBigInteger().toString()
    }

    /**
     * Get chain ID for a blockchain type
     */
    private fun getChainIdForBlockchain(blockchainType: BlockchainType): Int? {
        return when (blockchainType) {
            BlockchainType.Ethereum -> 1
            BlockchainType.BinanceSmartChain -> 56
            BlockchainType.Polygon -> 137
            BlockchainType.Avalanche -> 43114
            BlockchainType.Optimism -> 10
            BlockchainType.ArbitrumOne -> 42161
            BlockchainType.Gnosis -> 100
            BlockchainType.Fantom -> 250
            BlockchainType.Base -> 8453
            else -> null
        }
    }

    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

/**
 * Data class representing a payment token with balance and metadata
 */
data class PaymentToken(
    val token: Token,
    val balance: BigDecimal,
    val symbol: String,
    val chainId: Int,
    val address: String,
    val decimals: Int
)

