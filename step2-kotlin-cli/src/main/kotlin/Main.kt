import io.github.cdimascio.dotenv.Dotenv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

private const val NETWORK = "eip155:84532"
private const val CHAIN_ID = 84532L
private const val BASESCAN = "https://sepolia.basescan.org/tx/"

fun main(args: Array<String>) {
    val url = args.firstOrNull() ?: errExit("usage: ./gradlew run --args=\"<url>\"")
    val credentials = Credentials.create(ECKeyPair.create(loadPrivateKeyHex()))

    println("payer:   ${Keys.toChecksumAddress(credentials.address)}")
    println("network: $NETWORK (Base Sepolia)")
    println("target:  $url")

    val http = OkHttpClient()
    val challenge = fetchChallenge(http, url) ?: return  // 200 already, nothing to pay

    val accept = challenge["accepts"]!!.jsonArray
        .map { it.jsonObject }
        .firstOrNull {
            it["network"]?.jsonPrimitive?.content == NETWORK &&
                it["scheme"]?.jsonPrimitive?.content == "exact"
        }
        ?: errExit("no eip155:84532 + exact scheme in challenge accepts")

    val envelope = buildPaymentEnvelope(accept, credentials, challenge)
    val paymentSignature = Base64.getEncoder().encodeToString(envelope.toString().toByteArray())

    if (System.getenv("X402_DEBUG") != null) {
        println("\n--- envelope (decoded) ---")
        println(pretty.encodeToString(JsonObject.serializer(), envelope))
        println("--- end envelope ---\n")
    }

    val resp = http.newCall(
        Request.Builder().url(url).header("PAYMENT-SIGNATURE", paymentSignature).build(),
    ).execute()

    println("\nstatus:  ${resp.code}")

    if (resp.code != 200 && System.getenv("X402_DEBUG") != null) {
        println("--- response headers ---")
        resp.headers.forEach { (k, v) -> println("  $k: ${v.take(200)}") }
        println("--- end response headers ---")
    }

    val paymentResponse = resp.header("Payment-Response") ?: resp.header("X-PAYMENT-RESPONSE")
    if (paymentResponse != null) {
        val decoded = String(Base64.getDecoder().decode(paymentResponse))
        val obj = Json.parseToJsonElement(decoded).jsonObject
        println("payment: ${pretty.encodeToString(JsonObject.serializer(), obj)}")
        val tx = obj["transaction"]?.jsonPrimitive?.contentOrNull
            ?: obj["txHash"]?.jsonPrimitive?.contentOrNull
        if (tx != null) println("\nbasescan: $BASESCAN$tx")
    } else {
        println("(no payment-response header — endpoint served without settlement metadata)")
    }

    val body = resp.body?.string().orEmpty()
    val truncated = body.take(500)
    println("\nbody:\n$truncated")
    if (body.length > truncated.length) println("... (${body.length - truncated.length} more bytes)")
}

private val pretty = Json { prettyPrint = true }

private fun loadPrivateKeyHex(): BigInteger {
    val raw = System.getenv("PRIVATE_KEY")
        ?: Dotenv.configure().ignoreIfMissing().load()["PRIVATE_KEY"]
        ?: errExit("PRIVATE_KEY missing — set env var or step2-kotlin-cli/.env")
    return BigInteger(raw.removePrefix("0x"), 16)
}

private fun fetchChallenge(http: OkHttpClient, url: String): JsonObject? {
    val resp = http.newCall(Request.Builder().url(url).build()).execute()
    if (resp.code == 200) {
        println("\nstatus:  200 (no payment required)")
        println("body:\n${resp.body?.string()}")
        return null
    }
    require(resp.code == 402) { "expected 402, got ${resp.code}: ${resp.body?.string()}" }

    // x402 v2: the challenge JSON travels in a base64 `payment-required` header.
    // Some servers also put it in the body. Header wins when both exist.
    val header = resp.header("payment-required") ?: resp.header("Payment-Required")
    val raw = if (header != null) String(Base64.getDecoder().decode(header)) else resp.body!!.string()
    return Json.parseToJsonElement(raw).jsonObject
}

private fun buildPaymentEnvelope(
    accept: JsonObject,
    credentials: Credentials,
    challenge: JsonObject,
): JsonObject {
    val payTo = accept.string("payTo")
    val asset = accept.string("asset")
    val amount = accept.string("amount")
    val maxTimeout = accept["maxTimeoutSeconds"]?.jsonPrimitive?.long ?: 300L
    val extra = accept["extra"]?.jsonObject
    val tokenName = extra?.get("name")?.jsonPrimitive?.content ?: "USDC"
    val tokenVersion = extra?.get("version")?.jsonPrimitive?.content ?: "2"

    val nonceHex = "0x" + ByteArray(32).also(SecureRandom()::nextBytes).toHex()
    val nowSecs = System.currentTimeMillis() / 1000

    // viem's getAddress (EIP-55 checksum) is what the v2 reference client uses for both fields.
    // Some facilitators do a strict string compare of the JSON `from` against the recovered signer,
    // which they format as checksummed — so case matters off-chain even if the on-chain hash is byte-equal.
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

    // v2 server matches via deepEqual(requirements, paymentPayload.accepted).
    // We must echo back the chosen accepts[] entry verbatim; resource and extensions
    // travel alongside per the v2 reference client envelope.
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
    require(sig.v.size == 1) { "expected single-byte v, got ${sig.v.size}" }

    return "0x" + leftPad(sig.r, 32).toHex() + leftPad(sig.s, 32).toHex() + "%02x".format(sig.v[0])
}

private fun field(name: String, type: String) = buildJsonObject {
    put("name", name)
    put("type", type)
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.content ?: error("missing $key in $this")

private fun leftPad(bytes: ByteArray, len: Int): ByteArray =
    if (bytes.size >= len) bytes.copyOfRange(bytes.size - len, bytes.size)
    else ByteArray(len - bytes.size) + bytes

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun errExit(msg: String): Nothing {
    System.err.println(msg)
    kotlin.system.exitProcess(1)
}
