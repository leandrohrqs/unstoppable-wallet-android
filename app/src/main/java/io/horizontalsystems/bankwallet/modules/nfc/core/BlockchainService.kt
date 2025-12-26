package io.horizontalsystems.bankwallet.modules.nfc.core

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * Blockchain service for monitoring transactions using Alchemy API.
 * Adapted from FreePay merchant app for Unstoppable Wallet NFC payments.
 */
class BlockchainService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    init {
        ConfigManager.initialize(context)
    }
    
    private val alchemyApiKey: String = ConfigManager.getAlchemyApiKey()
    
    companion object {
        private const val TAG = "BlockchainService"
        
        private val CHAIN_CONFIGS = mapOf(
            1 to ChainConfig("ethereum", "https://eth-mainnet.g.alchemy.com/v2/"),
            10 to ChainConfig("optimism", "https://opt-mainnet.g.alchemy.com/v2/"),
            137 to ChainConfig("polygon", "https://polygon-mainnet.g.alchemy.com/v2/"),
            42161 to ChainConfig("arbitrum", "https://arb-mainnet.g.alchemy.com/v2/"),
            8453 to ChainConfig("base", "https://base-mainnet.g.alchemy.com/v2/")
        )
    }
    
    data class ChainConfig(val name: String, val rpcUrl: String)
    
    enum class TransactionStatus {
        PENDING,
        SUCCESS,
        FAILED,
        UNKNOWN
    }
    
    data class AssetTransfer(
        val hash: String,
        val from: String,
        val to: String,
        val value: BigDecimal,
        val asset: String?,
        val tokenAddress: String?,
        val decimals: Int,
        val category: String,
        val blockNum: String,
        val rawValue: Any? = null
    )
    
    /**
     * Get transaction status by hash with confirmation check.
     * For NFC payments, minConfirmations=1 is used for faster confirmation.
     * For higher security, use minConfirmations=12 (BaseEvmAdapter.confirmationsThreshold).
     */
    suspend fun getTransactionStatus(txHash: String, chainId: Int, minConfirmations: Int = 1): TransactionStatus = 
        withContext(Dispatchers.IO) {
        try {
            val config = CHAIN_CONFIGS[chainId] ?: return@withContext TransactionStatus.UNKNOWN
            if (alchemyApiKey.isEmpty()) {
                return@withContext TransactionStatus.UNKNOWN
            }
            
            val url = "${config.rpcUrl}$alchemyApiKey"
            
            val receiptJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_getTransactionReceipt",
                    "params": ["$txHash"],
                    "id": 1
                }
            """.trimIndent()
            
            val receiptRequest = Request.Builder()
                .url(url)
                .post(receiptJson.toRequestBody("application/json".toMediaType()))
                .build()
            
            val receiptResponse = client.newCall(receiptRequest).execute()
            val receiptBody = receiptResponse.body?.string() ?: return@withContext TransactionStatus.PENDING
            
            val receiptResult = gson.fromJson(receiptBody, Map::class.java)["result"] as? Map<*, *>
            if (receiptResult == null) {
                return@withContext TransactionStatus.PENDING
            }
            
            val status = receiptResult["status"] as? String
            if (status == "0x0") {
                return@withContext TransactionStatus.FAILED
            }
            if (status != "0x1") {
                return@withContext TransactionStatus.PENDING
            }
            
            val blockNumberHex = receiptResult["blockNumber"] as? String ?: return@withContext TransactionStatus.PENDING
            val blockNumber = blockNumberHex.removePrefix("0x").toLongOrNull(16) ?: return@withContext TransactionStatus.PENDING
            
            val blockNumberJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_blockNumber",
                    "params": [],
                    "id": 2
                }
            """.trimIndent()
            
            val blockNumberRequest = Request.Builder()
                .url(url)
                .post(blockNumberJson.toRequestBody("application/json".toMediaType()))
                .build()
            
            val blockNumberResponse = client.newCall(blockNumberRequest).execute()
            val blockNumberBody = blockNumberResponse.body?.string() ?: return@withContext TransactionStatus.PENDING
            
            val currentBlockResult = gson.fromJson(blockNumberBody, Map::class.java)["result"] as? String
            val currentBlockNumber = currentBlockResult?.removePrefix("0x")?.toLongOrNull(16) ?: return@withContext TransactionStatus.PENDING
            
            val confirmations = (currentBlockNumber - blockNumber + 1).toInt()
            
            Log.d(TAG, "Transaction $txHash: blockNumber=$blockNumber, currentBlock=$currentBlockNumber, confirmations=$confirmations, minRequired=$minConfirmations")
            
            return@withContext if (confirmations >= minConfirmations) {
                TransactionStatus.SUCCESS
            } else {
                TransactionStatus.PENDING
            }
        } catch (e: Exception) {
            logError("Error checking transaction status", e)
        }
        
        TransactionStatus.PENDING
    }
    
    /**
     * Monitor incoming transfers to a specific address.
     * Polls the blockchain every 3 seconds until a matching transfer is found.
     */
    suspend fun monitorIncomingTransfers(
        chainId: Int,
        merchantAddress: String,
        expectedAmountWei: String,
        expectedTokenAddress: String?,
        callback: (AssetTransfer) -> Unit
    ): Job = withContext(Dispatchers.IO) {
        val config = CHAIN_CONFIGS[chainId] ?: throw IllegalArgumentException("Unsupported chain: $chainId")
        
        if (alchemyApiKey.isEmpty()) {
            throw IllegalStateException("Alchemy API key not configured")
        }
        
        var lastCheckedBlock = getCurrentBlockNumber(chainId, config)
        
        launch {
            while (isActive) {
                try {
                    delay(3000) // Poll every 3 seconds
                    
                    val currentBlock = getCurrentBlockNumber(chainId, config)
                    val safeToBlock = maxOf(currentBlock - 1, lastCheckedBlock)
                    
                    if (safeToBlock > lastCheckedBlock) {
                        val transfers = getAssetTransfers(
                            chainId = chainId,
                            config = config,
                            fromBlock = "0x${lastCheckedBlock.toString(16)}",
                            toBlock = "0x${safeToBlock.toString(16)}",
                            toAddress = merchantAddress
                        )
                        
                        for (transfer in transfers) {
                            val isCorrectToken = if (expectedTokenAddress == null) {
                                transfer.category == "external" && transfer.tokenAddress == null
                            } else {
                                transfer.tokenAddress?.equals(expectedTokenAddress, ignoreCase = true) == true
                            }
                            
                            if (isCorrectToken) {
                                val rawValue = when (transfer.rawValue) {
                                    is String -> {
                                        transfer.rawValue.removePrefix("0x").toBigIntegerOrNull(16) ?: BigInteger.ZERO
                                    }
                                    is Number -> {
                                        BigInteger(transfer.rawValue.toString())
                                    }
                                    else -> {
                                        if (transfer.category == "external") {
                                            transfer.value.multiply(BigDecimal("1000000000000000000")).toBigInteger()
                                        } else {
                                            BigInteger.ZERO
                                        }
                                    }
                                }
                                val expectedValueWei = BigInteger(expectedAmountWei)
                                
                                if (rawValue == expectedValueWei) {
                                    callback(transfer)
                                    return@launch
                                }
                            }
                        }
                        
                        lastCheckedBlock = safeToBlock
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("past head") != true) {
                        logError("Error monitoring transfers", e)
                    }
                }
            }
        }
    }
    
    private suspend fun getCurrentBlockNumber(chainId: Int, config: ChainConfig): Long = 
        withContext(Dispatchers.IO) {
        try {
            val url = "${config.rpcUrl}$alchemyApiKey"
            val json = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_blockNumber",
                    "params": [],
                    "id": 1
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext 0L
            
            val result = gson.fromJson(responseBody, Map::class.java)["result"] as? String
            result?.let {
                it.removePrefix("0x").toLong(16)
            } ?: 0L
        } catch (e: Exception) {
            logError("Error getting block number", e)
            0L
        }
    }
    
    private suspend fun getAssetTransfers(
        chainId: Int,
        config: ChainConfig,
        fromBlock: String,
        toBlock: String,
        toAddress: String
    ): List<AssetTransfer> = withContext(Dispatchers.IO) {
        try {
            val url = "${config.rpcUrl}$alchemyApiKey"
            val json = """
                {
                    "jsonrpc": "2.0",
                    "method": "alchemy_getAssetTransfers",
                    "params": [{
                        "toAddress": "$toAddress",
                        "fromBlock": "$fromBlock",
                        "toBlock": "$toBlock",
                        "category": ["external", "erc20"],
                        "withMetadata": true,
                        "excludeZeroValue": true
                    }],
                    "id": 1
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            
            val result = gson.fromJson(responseBody, Map::class.java)["result"] as? Map<*, *>
            val transfers = result?.get("transfers") as? List<*> ?: return@withContext emptyList()
            
            transfers.mapNotNull { transferData ->
                val transfer = transferData as? Map<*, *> ?: return@mapNotNull null
                
                val rawContract = transfer["rawContract"] as? Map<*, *>
                
                AssetTransfer(
                    hash = transfer["hash"] as? String ?: "",
                    from = transfer["from"] as? String ?: "",
                    to = transfer["to"] as? String ?: "",
                    value = BigDecimal(transfer["value"]?.toString() ?: "0"),
                    asset = transfer["asset"] as? String,
                    tokenAddress = rawContract?.get("address") as? String,
                    decimals = 18,
                    category = transfer["category"] as? String ?: "",
                    blockNum = transfer["blockNum"] as? String ?: "",
                    rawValue = rawContract?.get("value") ?: transfer["value"]
                )
            }
        } catch (e: Exception) {
            logError("Error getting asset transfers", e)
            emptyList()
        }
    }
    
    private fun logError(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}

