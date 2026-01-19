package io.horizontalsystems.bankwallet.modules.nfc.core

import android.util.Log
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.IAccountManager
import io.horizontalsystems.bankwallet.core.IAdapterManager
import io.horizontalsystems.bankwallet.core.IWalletManager
import io.horizontalsystems.bankwallet.core.adapters.EvmAdapter
import io.horizontalsystems.bankwallet.core.managers.EvmBlockchainManager
import io.horizontalsystems.bankwallet.entities.Account
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import java.math.BigDecimal
import java.math.RoundingMode

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
     * Get BlockchainType from blockchain UID (string).
     * This is the preferred dynamic method - works for any blockchain.
     */
    fun getBlockchainTypeFromUid(blockchainUid: String): BlockchainType? {
        return try {
            // BlockchainType.fromUid is the dynamic way to convert UID to type
            BlockchainType.fromUid(blockchainUid)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown blockchain UID: $blockchainUid")
            null
        }
    }
    
    /**
     * Get BlockchainType from EVM chain ID.
     * Uses EvmBlockchainManager to dynamically find the blockchain.
     */
    fun getBlockchainTypeFromEvmChainId(chainId: Int): BlockchainType? {
        if (chainId <= 0) return null
        return try {
            App.evmBlockchainManager.getBlockchain(chainId)?.type
        } catch (e: Exception) {
            Log.w(TAG, "Could not find blockchain for chain ID: $chainId")
            null
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
                val chainId = getEvmChainIdDynamic(usdtToken.blockchainType) ?: 1
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
                val chainId = getEvmChainIdDynamic(stablecoin.token.blockchainType) ?: 1
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
                val chainId = getEvmChainIdDynamic(ethToken.token.blockchainType) ?: 1
                return PaymentToken(
                    token = ethToken.token,
                    balance = BigDecimal.ZERO,
                    symbol = "ETH",
                    chainId = chainId,
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
     * Get all enabled tokens in the receiver wallet with converted amounts.
     * Uses all tokens enabled in the wallet manager for the receiver account.
     * Dynamically supports any blockchain - no hardcoded chain IDs.
     * 
     * @param fiatAmount Amount in fiat currency
     * @param fiatCurrencyCode Fiat currency code (e.g., "BRL", "USD")
     * @param receiverAccountId Account ID of the receiver
     * @return List of AcceptedToken with converted amounts
     */
    suspend fun getEnabledTokensForPayment(
        fiatAmount: BigDecimal,
        fiatCurrencyCode: String,
        receiverAccountId: String?
    ): List<AcceptedToken> {
        val result = mutableListOf<AcceptedToken>()
        val processedTokens = mutableSetOf<String>() // To avoid duplicates
        
        try {
            val account = receiverAccountId?.let { accountManager.account(it) } ?: accountManager.activeAccount
            if (account == null) {
                logError("No receiver account found", null)
                return emptyList()
            }
            
            // Get all wallets for this account
            val wallets = walletManager.activeWallets.filter { it.account == account }
            Log.d(TAG, "Found ${wallets.size} wallets for receiver account")
            
            for (wallet in wallets) {
                try {
                    // Create unique key to avoid duplicates (same coin on same chain)
                    val uniqueKey = "${wallet.token.coin.uid}_${wallet.token.blockchainType.uid}"
                    if (processedTokens.contains(uniqueKey)) {
                        continue
                    }
                    processedTokens.add(uniqueKey)
                    
                    val address = getWalletAddress(wallet)
                    if (address == null) {
                        Log.w(TAG, "Skipping ${wallet.token.coin.code}: could not get address")
                        continue
                    }
                    
                    // Get EVM chain ID dynamically if this is an EVM chain
                    val evmChainId = getEvmChainIdDynamic(wallet.token.blockchainType)
                    
                    // Convert fiat to crypto amount
                    var cryptoAmount = convertFiatToCrypto(fiatAmount, fiatCurrencyCode, wallet.token)
                    if (cryptoAmount == null || cryptoAmount <= BigDecimal.ZERO) {
                        Log.w(TAG, "Could not get rate for ${wallet.token.coin.code} in $fiatCurrencyCode, trying USD fallback")
                        // Try USD fallback if merchant currency fails
                        if (fiatCurrencyCode != "USD") {
                            cryptoAmount = convertFiatToCrypto(fiatAmount, "USD", wallet.token)
                        }
                        
                        if (cryptoAmount == null || cryptoAmount <= BigDecimal.ZERO) {
                            Log.w(TAG, "Could not get rate for ${wallet.token.coin.code} even with USD fallback, skipping")
                            continue
                        }
                        Log.d(TAG, "Using USD fallback for ${wallet.token.coin.code}: $cryptoAmount")
                    }
                    
                    // Get token address based on token type
                    val tokenAddress = getTokenAddress(wallet.token.type)
                    if (tokenAddress == null) {
                        Log.d(TAG, "Skipping ${wallet.token.coin.code}: unsupported token type ${wallet.token.type}")
                        continue
                    }
                    
                    val finalCryptoAmount = cryptoAmount // Smart cast
                    result.add(AcceptedToken(
                        token = wallet.token,
                        wallet = wallet,
                        receiverAddress = address,
                        evmChainId = evmChainId,
                        tokenAddress = tokenAddress,
                        amountInToken = finalCryptoAmount,
                        amountInWei = toWei(finalCryptoAmount, wallet.token.decimals)
                    ))
                    
                    Log.d(TAG, "Added token ${wallet.token.coin.code} on ${wallet.token.blockchainType.uid}, evmChainId=$evmChainId")
                } catch (e: Exception) {
                    logError("Error processing token ${wallet.token.coin.code}", e)
                }
            }
        } catch (e: Exception) {
            logError("Error getting enabled tokens for payment", e)
        }
        
        Log.d(TAG, "Returning ${result.size} tokens for payment: ${result.map { it.token.coin.code }}")
        return result
    }
    
    /**
     * Get token contract address from token type.
     * Returns "native" for native tokens, contract address for ERC20/SPL/etc., null for unsupported types.
     */
    private fun getTokenAddress(tokenType: TokenType): String? {
        return when (tokenType) {
            is TokenType.Eip20 -> tokenType.address
            is TokenType.Native -> "native"
            is TokenType.Derived -> "native" // Bitcoin, etc.
            is TokenType.AddressTyped -> "native" // Litecoin, etc.
            is TokenType.Spl -> tokenType.address // Solana SPL tokens
            is TokenType.Jetton -> "native" // TON tokens
            is TokenType.Asset -> "native" // Stellar assets
            else -> null
        }
    }
    
    /**
     * Get EVM chain ID dynamically using EvmBlockchainManager.
     * Returns the chain ID for EVM blockchains, or null for non-EVM chains.
     */
    private fun getEvmChainIdDynamic(blockchainType: BlockchainType): Int? {
        return try {
            val evmBlockchainManager = App.evmBlockchainManager
            // Check if this blockchain type is in the EVM list (companion object)
            if (blockchainType in EvmBlockchainManager.blockchainTypes) {
                val chain = evmBlockchainManager.getChain(blockchainType)
                chain.id
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "BlockchainType ${blockchainType.uid} is not an EVM chain")
            null
        }
    }
    
    /**
     * Convert fiat amount to crypto amount using market rates.
     * 
     * @param fiatAmount Amount in fiat
     * @param fiatCurrencyCode Fiat currency code
     * @param token Token to convert to
     * @return Amount in crypto, or null if rate not available
     */
    fun convertFiatToCrypto(fiatAmount: BigDecimal, fiatCurrencyCode: String, token: Token): BigDecimal? {
        return try {
            val marketKit = App.marketKit
            val coinPrice = marketKit.coinPrice(token.coin.uid, fiatCurrencyCode)
            
            if (coinPrice != null && coinPrice.value > BigDecimal.ZERO) {
                fiatAmount.divide(coinPrice.value, token.decimals, RoundingMode.CEILING)
            } else {
                null
            }
        } catch (e: Exception) {
            logError("Error converting fiat to crypto for ${token.coin.code}", e)
            null
        }
    }
    
    /**
     * Get wallet balance for a specific token in the sender account.
     * 
     * @param tokenUid Token UID to check
     * @param senderAccountId Sender account ID
     * @return Balance or null if not found
     */
    fun getTokenBalance(tokenUid: String, senderAccountId: String?): BigDecimal? {
        return try {
            val account = senderAccountId?.let { accountManager.account(it) } ?: accountManager.activeAccount
            if (account == null) return null
            
            val wallet = walletManager.activeWallets.firstOrNull { 
                it.account == account && it.token.coin.uid == tokenUid
            } ?: return null
            
            adapterManager.getBalanceAdapterForWallet(wallet)?.balanceData?.available
        } catch (e: Exception) {
            logError("Error getting token balance for $tokenUid", e)
            null
        }
    }
    
    /**
     * Check if customer has sufficient balance for a payment token.
     * 
     * @param acceptedToken Token with required amount
     * @param senderAccountId Sender account ID
     * @return true if balance is sufficient
     */
    fun hasSufficientBalance(acceptedToken: AcceptedToken, senderAccountId: String?): Boolean {
        val balance = getTokenBalance(acceptedToken.token.coin.uid, senderAccountId) ?: return false
        return balance >= acceptedToken.amountInToken
    }
    
    /**
     * Find wallet for a specific token in the sender account.
     * 
     * @param tokenUid Token UID
     * @param senderAccountId Sender account ID
     * @return Wallet or null if not found
     */
    fun findWalletForToken(tokenUid: String, senderAccountId: String?): Wallet? {
        val account = senderAccountId?.let { accountManager.account(it) } ?: accountManager.activeAccount
        if (account == null) return null
        
        return walletManager.activeWallets.firstOrNull { 
            it.account == account && it.token.coin.uid == tokenUid
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

/**
 * Data class representing an accepted token for NFC payment.
 * Contains all information needed for the customer to make a payment.
 * Uses blockchainUid as primary identifier, evmChainId only for EVM chains.
 */
data class AcceptedToken(
    val token: Token,
    val wallet: Wallet?, // The wallet associated with this token (for transaction monitoring)
    val receiverAddress: String,
    val evmChainId: Int?, // Only set for EVM chains, null for non-EVM
    val tokenAddress: String,
    val amountInToken: BigDecimal,
    val amountInWei: String
) {
    /**
     * Check if this is an EVM-compatible blockchain.
     */
    fun isEvmChain(): Boolean = evmChainId != null && evmChainId > 0
    
    /**
     * Create EIP-681 payment URI for this token (EVM only).
     */
    fun toPaymentUri(): String {
        val chainId = evmChainId ?: return ""
        return if (tokenAddress == "0x0000000000000000000000000000000000000000" || tokenAddress == "native") {
            "ethereum:$receiverAddress@$chainId?value=$amountInWei"
        } else {
            "ethereum:$tokenAddress@$chainId/transfer?address=$receiverAddress&uint256=$amountInWei"
        }
    }
    
    /**
     * Convert to JSON-serializable map for NFC transmission.
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            "symbol" to token.coin.code,
            "uid" to token.coin.uid,
            "blockchainUid" to token.blockchainType.uid,
            "chain" to (evmChainId ?: 0),
            "tokenAddress" to tokenAddress,
            "to" to receiverAddress,
            "amount" to amountInToken.toPlainString(),
            "amountWei" to amountInWei,
            "decimals" to token.decimals
        )
    }
}

