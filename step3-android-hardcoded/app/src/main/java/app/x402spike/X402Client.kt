package app.x402spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
 * Lifted from step2-kotlin-cli/Main.kt with two changes:
 *   1. Wrapped in a suspend fun on Dispatchers.IO so callers can launch from a coroutine scope.
 *   2. Errors come back as PayResult.Failure instead of System.err + exitProcess.
 *
 * Step 4 will swap the Credentials.create(...) line for a StrongBox-unwrap path
 * gated by BiometricPrompt. Everything else here stays.
 */
object X402Client {

    private const val NETWORK = "eip155:84532"
    private const val CHAIN_ID = 84532L
    private const val BASESCAN = "https://sepolia.basescan.org/tx/"

    /**
     * Plain HTTP GET — no x402 envelope, no payment, no signing. Demonstrates what an
     * x402-unaware client sees: 402 Payment Required with an empty body and a base64
     * challenge in the `payment-required` header. The whole point of x402 is that this
     * is a *normal* HTTP response, just with a status code most clients ignore.
     */
    suspend fun getRaw(url: String): RawResult = withContext(Dispatchers.IO) {
        val http = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .readTimeout(java.time.Duration.ofSeconds(45))
            .callTimeout(java.time.Duration.ofSeconds(60))
            .build()
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

    suspend fun payX402(url: String, privateKeyHex: String): PayResult = withContext(Dispatchers.IO) {
        runCatching { doPay(url, privateKeyHex) }
            .getOrElse { e ->
                PayResult.Failure("exception: ${e::class.simpleName}: ${e.message}")
            }
    }

    private fun doPay(url: String, privateKeyHex: String): PayResult {
        val credentials = Credentials.create(
            ECKeyPair.create(BigInteger(privateKeyHex.removePrefix("0x"), 16)),
        )
        // Default OkHttp timeouts are 10s — too tight for the facilitator round-trip
        // on first call from a cold app on Wi-Fi. Bump generous; we still cap overall.
        val http = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .readTimeout(java.time.Duration.ofSeconds(45))
            .callTimeout(java.time.Duration.ofSeconds(60))
            .build()

        val first = http.newCall(Request.Builder().url(url).build()).execute()
        if (first.code == 200) {
            return PayResult.Success(
                txHash = "(no payment needed — endpoint returned 200)",
                basescanUrl = "",
                body = first.body?.string().orEmpty(),
                contentType = first.header("Content-Type") ?: "text/plain",
                resolvedUrl = url,
            )
        }
        if (first.code != 402) {
            return PayResult.Failure(
                "expected 402, got ${first.code}",
                first.code,
                first.body?.string()?.take(500),
            )
        }

        val challenge = parseChallenge(first)
            ?: return PayResult.Failure("missing payment-required header")

        val accept = challenge["accepts"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull {
                it["network"]?.jsonPrimitive?.content == NETWORK &&
                    it["scheme"]?.jsonPrimitive?.content == "exact"
            }
            ?: return PayResult.Failure("no eip155:84532 + exact in accepts[]")

        val envelope = buildPaymentEnvelope(accept, credentials, challenge)
        val paymentSignatureHeader = Base64.getEncoder()
            .encodeToString(envelope.toString().toByteArray())

        val resp = http.newCall(
            Request.Builder().url(url).header("PAYMENT-SIGNATURE", paymentSignatureHeader).build(),
        ).execute()

        if (resp.code != 200) {
            return PayResult.Failure(
                "retry got ${resp.code}",
                resp.code,
                resp.body?.string()?.take(500),
            )
        }

        val pr = resp.header("Payment-Response") ?: resp.header("X-PAYMENT-RESPONSE")
        val tx = pr?.let {
            runCatching { decodeBase64Json(it)["transaction"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
        } ?: "(no payment-response header)"

        return PayResult.Success(
            txHash = tx,
            basescanUrl = if (tx.startsWith("0x")) "$BASESCAN$tx" else "",
            body = resp.body?.string().orEmpty(),
            contentType = resp.header("Content-Type") ?: "text/plain",
            resolvedUrl = url,
        )
    }

    private fun parseChallenge(resp: Response): JsonObject? {
        val header = resp.header("payment-required") ?: resp.header("Payment-Required")
        val raw = if (header != null) {
            String(Base64.getDecoder().decode(header))
        } else {
            resp.body?.string().orEmpty()
        }
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun decodeBase64Json(b64: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getDecoder().decode(b64))).jsonObject

    private fun buildPaymentEnvelope(
        accept: JsonObject,
        credentials: Credentials,
        challenge: JsonObject,
    ): JsonObject {
        val payTo = accept["payTo"]!!.jsonPrimitive.content
        val asset = accept["asset"]!!.jsonPrimitive.content
        val amount = accept["amount"]!!.jsonPrimitive.content
        val maxTimeout = accept["maxTimeoutSeconds"]?.jsonPrimitive?.long ?: 300L
        val extra = accept["extra"]?.jsonObject
        val tokenName = extra?.get("name")?.jsonPrimitive?.content ?: "USDC"
        val tokenVersion = extra?.get("version")?.jsonPrimitive?.content ?: "2"

        val nonceHex = "0x" + ByteArray(32).also(SecureRandom()::nextBytes).toHex()
        val nowSecs = System.currentTimeMillis() / 1000

        val authorization = linkedMapOf(
            "from" to Keys.toChecksumAddress(credentials.address),
            "to" to Keys.toChecksumAddress(payTo),
            "value" to amount,
            "validAfter" to (nowSecs - 600).toString(),
            "validBefore" to (nowSecs + maxTimeout).toString(),
            "nonce" to nonceHex,
        )

        val signature = signTransferWithAuthorization(
            tokenName, tokenVersion, asset, authorization, credentials.ecKeyPair,
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
}
