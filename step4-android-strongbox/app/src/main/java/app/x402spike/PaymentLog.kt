package app.x402spike

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class PaymentRecord(
    val id: String,
    val walletId: String,
    val txHash: String,
    val timestamp: Long,
    val resource: String,
    val amountBaseUnits: String,
    val asset: String,
    val recipient: String,
    val securityLevel: String,
    val basescanUrl: String,
)

/**
 * Step 4.2 addition. Local payment history, captured per successful x402
 * settlement. Lives in the same SharedPreferences as SecureWallet so the
 * "wipe app data" reset story still holds: clearing app data zeroes both
 * the wallet ciphertexts and the payment log together.
 *
 * Storage shape:
 *   payments -> JSON array, newest first, capped at MAX_RECORDS to keep
 *               render time bounded and prefs file small.
 *
 * Why local-only and not on-chain: the chain doesn't know which x402 URL
 * the payment was for or which security level signed it. Those are
 * app-side facts that vanish if we don't capture them at the moment of
 * settlement. On-chain reconciliation is a separate Step 4.3 if we want it.
 */
class PaymentLog(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("x402_strongbox_prefs", Context.MODE_PRIVATE)

    fun record(
        walletId: String,
        txHash: String,
        resource: String,
        amountBaseUnits: String,
        asset: String,
        recipient: String,
        securityLevel: String,
        basescanUrl: String,
    ): PaymentRecord {
        val now = System.currentTimeMillis()
        val record = PaymentRecord(
            id = "p_${now}_${"%04x".format((0..0xFFFF).random())}",
            walletId = walletId,
            txHash = txHash,
            timestamp = now,
            resource = resource,
            amountBaseUnits = amountBaseUnits,
            asset = asset,
            recipient = recipient,
            securityLevel = securityLevel,
            basescanUrl = basescanUrl,
        )
        val updated = (listOf(record) + listAll()).take(MAX_RECORDS)
        prefs.edit().putString(PREF_PAYMENTS, toJson(updated)).apply()
        return record
    }

    fun listAll(): List<PaymentRecord> {
        val raw = prefs.getString(PREF_PAYMENTS, null) ?: return emptyList()
        val arr = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            PaymentRecord(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                walletId = o["walletId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                txHash = o["txHash"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                timestamp = o["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                resource = o["resource"]?.jsonPrimitive?.contentOrNull ?: "",
                amountBaseUnits = o["amountBaseUnits"]?.jsonPrimitive?.contentOrNull ?: "0",
                asset = o["asset"]?.jsonPrimitive?.contentOrNull ?: "",
                recipient = o["recipient"]?.jsonPrimitive?.contentOrNull ?: "",
                securityLevel = o["securityLevel"]?.jsonPrimitive?.contentOrNull ?: "",
                basescanUrl = o["basescanUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }

    fun clearAll() {
        prefs.edit().remove(PREF_PAYMENTS).apply()
    }

    private fun toJson(records: List<PaymentRecord>): String =
        buildJsonArray {
            records.forEach { r ->
                add(buildJsonObject {
                    put("id", r.id)
                    put("walletId", r.walletId)
                    put("txHash", r.txHash)
                    put("timestamp", r.timestamp)
                    put("resource", r.resource)
                    put("amountBaseUnits", r.amountBaseUnits)
                    put("asset", r.asset)
                    put("recipient", r.recipient)
                    put("securityLevel", r.securityLevel)
                    put("basescanUrl", r.basescanUrl)
                })
            }
        }.toString()

    companion object {
        private const val PREF_PAYMENTS = "payments"
        private const val MAX_RECORDS = 200
    }
}

/**
 * USDC has 6 decimals. Anything else this app would care about (USDT,
 * other stablecoins on Base) also uses 6. Hardcoding is fine for the
 * spike; production would call decimals() on the asset contract.
 */
fun formatUsdcAmount(baseUnits: String): String {
    val n = baseUnits.toLongOrNull() ?: return baseUnits
    val whole = n / 1_000_000
    val frac = n % 1_000_000
    return if (frac == 0L) {
        "$whole USDC"
    } else {
        val fracStr = "%06d".format(frac).trimEnd('0')
        "$whole.$fracStr USDC"
    }
}
