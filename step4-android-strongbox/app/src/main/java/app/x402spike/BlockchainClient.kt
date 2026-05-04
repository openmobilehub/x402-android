package app.x402spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Read-only Base Sepolia explorer client. Used by the Wallets screen to verify
 * an address actually exists on-chain (i.e., what its USDC balance is) without
 * making the user leave the app to open a block explorer.
 *
 * Backed by Blockscout's open API: https://base-sepolia.blockscout.com/api-docs
 * Free tier, no auth required.
 */
object BlockchainClient {

    private const val USDC_ADDR = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
    private const val BASE_API = "https://base-sepolia.blockscout.com/api/v2"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(15))
            .build()
    }

    data class AddressStatus(
        val usdcDisplay: String,        // "20.00 USDC" or "0 USDC" or "—"
        val txCount: Long?,             // null on error
        val explorerUrl: String,
    )

    suspend fun describe(address: String): AddressStatus = withContext(Dispatchers.IO) {
        val explorerUrl = "https://sepolia.basescan.org/address/$address"
        val balance = fetchUsdcBalance(address) ?: "—"
        val txCount = fetchTxCount(address)
        AddressStatus(balance, txCount, explorerUrl)
    }

    private fun fetchUsdcBalance(address: String): String? {
        val req = Request.Builder()
            .url("$BASE_API/addresses/$address/token-balances")
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string().orEmpty()
                val arr = Json.parseToJsonElement(body) as? JsonArray ?: return null
                // Blockscout v2 returns the token contract under `address_hash`
                // (older revs used `address`). Accept either so we don't miss the entry.
                val entry = arr.firstOrNull { e ->
                    val tok = (e as? JsonObject)?.get("token") as? JsonObject ?: return@firstOrNull false
                    val addr = tok["address_hash"]?.jsonPrimitive?.contentOrNull
                        ?: tok["address"]?.jsonPrimitive?.contentOrNull
                    addr?.equals(USDC_ADDR, ignoreCase = true) == true
                } as? JsonObject ?: return "0 USDC"
                val rawValue = entry["value"]?.jsonPrimitive?.contentOrNull ?: return "0 USDC"
                val decimals = (entry["token"] as? JsonObject)
                    ?.get("decimals")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 6
                val divisor = BigDecimal(BigInteger.TEN.pow(decimals))
                val human = BigDecimal(rawValue).divide(divisor, 2, RoundingMode.HALF_UP)
                "$human USDC"
            }
        }.getOrNull()
    }

    private fun fetchTxCount(address: String): Long? {
        val req = Request.Builder()
            .url("$BASE_API/addresses/$address")
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string().orEmpty()
                val obj = Json.parseToJsonElement(body) as? JsonObject ?: return null
                obj["transactions_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: obj["transaction_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }
        }.getOrNull()
    }
}
