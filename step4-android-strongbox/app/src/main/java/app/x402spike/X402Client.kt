package app.x402spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

sealed interface PayResult {
    data class Success(
        val txHash: String,
        val basescanUrl: String,
        val body: String,
        val contentType: String,
        val resolvedUrl: String,
    ) : PayResult

    data class Failure(
        val message: String,
        val httpStatus: Int? = null,
        val body: String? = null,
    ) : PayResult
}

data class RawResult(
    val status: Int,
    val contentType: String,
    val body: String,
    val challenge: JsonObject?,
    val resolvedUrl: String,
)

/**
 * Step 4 protocol surface: split the work so the biometric-unlocked window stays
 * small.
 *   - [getRaw] / [fetchChallenge] hit the network, no biometric needed.
 *   - [createEnvelope] runs *inside* SecureWallet.signWithSeed { } in MainActivity,
 *     which means it has the seed in scope for exactly the duration of one EIP-712
 *     sign. After it returns, the seed ByteArray is zeroed.
 *   - [sendEnvelope] posts the pre-built envelope to the facilitator.
 *
 * X402Client itself never sees the seed or a private-key string. That's the
 * whole architectural change between Step 3 and Step 4.
 */
object X402Client {

    private const val NETWORK = "eip155:84532"
    private const val CHAIN_ID = 84532L
    private const val BASESCAN = "https://sepolia.basescan.org/tx/"

    suspend fun getRaw(url: String): RawResult = withContext(Dispatchers.IO) {
        val http = httpClient()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val challengeHeader = resp.header("payment-required") ?: resp.header("Payment-Required")
            val challenge = when {
                challengeHeader != null -> runCatching {
                    Json.parseToJsonElement(String(Base64.getDecoder().decode(challengeHeader))).jsonObject
                }.getOrNull()
                resp.code == 402 && body.isNotBlank() -> runCatching {
                    Json.parseToJsonElement(body).jsonObject
                }.getOrNull()
                else -> null
            }
            RawResult(
                status = resp.code,
                contentType = resp.header("Content-Type") ?: "",
                body = body,
                challenge = challenge,
                resolvedUrl = url,
            )
        }
    }

    /**
     * GET the URL once. Returns the parsed challenge (if 402) plus the raw
     * response. Used by MainActivity before prompting biometric — we don't
     * want to ask for a fingerprint for a request that hasn't even returned 402.
     */
    suspend fun fetchChallenge(url: String): Pair<JsonObject?, RawResult> {
        val raw = getRaw(url)
        return raw.challenge to raw
    }

    /**
     * Build the X-PAYMENT envelope from the parsed challenge + chosen accept
     * entry + a freshly-unwrapped seed. Pure function, called inside
     * SecureWallet.signWithSeed { } where the seed is in scope.
     */
    fun createEnvelope(
        challenge: JsonObject,
        accept: JsonObject,
        seed: ByteArray,
    ): JsonObject {
        val payTo = accept["payTo"]!!.jsonPrimitive.content
        val asset = accept["asset"]!!.jsonPrimitive.content
        val amount = accept["amount"]!!.jsonPrimitive.content
        val maxTimeout = accept["maxTimeoutSeconds"]?.jsonPrimitive?.long ?: 300L
        val extra = accept["extra"]?.jsonObject
        val tokenName = extra?.get("name")?.jsonPrimitive?.content ?: "USDC"
        val tokenVersion = extra?.get("version")?.jsonPrimitive?.content ?: "2"

        val ecKeyPair = ECKeyPair.create(BigInteger(1, seed))
        val from = Keys.toChecksumAddress(Credentials.create(ecKeyPair).address)

        val nonceHex = "0x" + ByteArray(32).also(SecureRandom()::nextBytes).toHex()
        val nowSecs = System.currentTimeMillis() / 1000
        val authorization = linkedMapOf(
            "from" to from,
            "to" to Keys.toChecksumAddress(payTo),
            "value" to amount,
            "validAfter" to (nowSecs - 600).toString(),
            "validBefore" to (nowSecs + maxTimeout).toString(),
            "nonce" to nonceHex,
        )

        val signature = signTransferWithAuthorization(
            tokenName, tokenVersion, asset, authorization, ecKeyPair,
        )

        return buildJsonObject {
            put("x402Version", 2)
            putJsonObject("payload") {
                putJsonObject("authorization") {
                    authorization.forEach { (k, v) -> put(k, v) }
                }
                put("signature", signature)
            }
            challenge["resource"]?.let { put("resource", it) }
            put("extensions", challenge["extensions"] ?: JsonObject(emptyMap()))
            put("accepted", accept)
        }
    }

    suspend fun sendEnvelope(url: String, envelope: JsonObject): PayResult = withContext(Dispatchers.IO) {
        val http = httpClient()
        val paymentSignatureHeader = Base64.getEncoder()
            .encodeToString(envelope.toString().toByteArray())
        val resp = http.newCall(
            Request.Builder().url(url).header("PAYMENT-SIGNATURE", paymentSignatureHeader).build(),
        ).execute()

        if (resp.code != 200) {
            return@withContext PayResult.Failure(
                "retry got ${resp.code}", resp.code, resp.body?.string()?.take(500),
            )
        }
        val pr = resp.header("Payment-Response") ?: resp.header("X-PAYMENT-RESPONSE")
        val tx = pr?.let {
            runCatching { decodeBase64Json(it)["transaction"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
        } ?: "(no payment-response header)"

        PayResult.Success(
            txHash = tx,
            basescanUrl = if (tx.startsWith("0x")) "$BASESCAN$tx" else "",
            body = resp.body?.string().orEmpty(),
            contentType = resp.header("Content-Type") ?: "text/plain",
            resolvedUrl = url,
        )
    }

    private fun decodeBase64Json(b64: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getDecoder().decode(b64))).jsonObject

    private fun signTransferWithAuthorization(
        tokenName: String,
        tokenVersion: String,
        verifyingContract: String,
        auth: Map<String, String>,
        keyPair: ECKeyPair,
    ): String {
        val typedData = buildJsonObject {
            putJsonObject("types") {
                putJsonArray("EIP712Domain") {
                    add(field("name", "string"))
                    add(field("version", "string"))
                    add(field("chainId", "uint256"))
                    add(field("verifyingContract", "address"))
                }
                putJsonArray("TransferWithAuthorization") {
                    add(field("from", "address"))
                    add(field("to", "address"))
                    add(field("value", "uint256"))
                    add(field("validAfter", "uint256"))
                    add(field("validBefore", "uint256"))
                    add(field("nonce", "bytes32"))
                }
            }
            put("primaryType", "TransferWithAuthorization")
            putJsonObject("domain") {
                put("name", tokenName)
                put("version", tokenVersion)
                put("chainId", CHAIN_ID)
                put("verifyingContract", verifyingContract)
            }
            putJsonObject("message") {
                auth.forEach { (k, v) -> put(k, v) }
            }
        }.toString()

        val hash = StructuredDataEncoder(typedData).hashStructuredData()
        val sig = Sign.signMessage(hash, keyPair, false)

        return "0x" + leftPad(sig.r, 32).toHex() +
            leftPad(sig.s, 32).toHex() +
            "%02x".format(sig.v[0])
    }

    private fun field(name: String, type: String) = buildJsonObject {
        put("name", name)
        put("type", type)
    }

    private fun leftPad(b: ByteArray, n: Int) =
        if (b.size >= n) b.copyOfRange(b.size - n, b.size) else ByteArray(n - b.size) + b

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun httpClient() = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofSeconds(45))
        .callTimeout(java.time.Duration.ofSeconds(60))
        .build()
}
