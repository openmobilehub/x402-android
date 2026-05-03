import io.github.cdimascio.dotenv.Dotenv
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import java.math.BigInteger

// Same fixed inputs as step1-node/sigtest.js. Run via:
//   ./gradlew runSigTest
fun runSigTest() {
    val raw = System.getenv("PRIVATE_KEY")
        ?: Dotenv.configure().ignoreIfMissing().load()["PRIVATE_KEY"]
        ?: error("PRIVATE_KEY missing")
    val pk = BigInteger(raw.removePrefix("0x"), 16)
    val credentials = Credentials.create(ECKeyPair.create(pk))

    val typed = buildJsonObject {
        putJsonObject("types") {
            putJsonArray("EIP712Domain") {
                add(buildJsonObject { put("name", "name"); put("type", "string") })
                add(buildJsonObject { put("name", "version"); put("type", "string") })
                add(buildJsonObject { put("name", "chainId"); put("type", "uint256") })
                add(buildJsonObject { put("name", "verifyingContract"); put("type", "address") })
            }
            putJsonArray("TransferWithAuthorization") {
                add(buildJsonObject { put("name", "from"); put("type", "address") })
                add(buildJsonObject { put("name", "to"); put("type", "address") })
                add(buildJsonObject { put("name", "value"); put("type", "uint256") })
                add(buildJsonObject { put("name", "validAfter"); put("type", "uint256") })
                add(buildJsonObject { put("name", "validBefore"); put("type", "uint256") })
                add(buildJsonObject { put("name", "nonce"); put("type", "bytes32") })
            }
        }
        put("primaryType", "TransferWithAuthorization")
        putJsonObject("domain") {
            put("name", "USDC")
            put("version", "2")
            put("chainId", 84532L)
            put("verifyingContract", "0x036CbD53842c5426634e7929541eC2318f3dCF7e")
        }
        putJsonObject("message") {
            put("from", "0xEf9966c76afCa07798A9A65B619d897D77a6a0F9")
            put("to", "0x209693Bc6afc0C5328bA36FaF03C514EF312287C")
            put("value", "10000")
            put("validAfter", "1000")
            put("validBefore", "9999999999")
            put("nonce", "0x1111111111111111111111111111111111111111111111111111111111111111")
        }
    }.toString()

    val hash = StructuredDataEncoder(typed).hashStructuredData()
    val sig = Sign.signMessage(hash, credentials.ecKeyPair, false)

    fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    fun pad(b: ByteArray, n: Int) =
        if (b.size >= n) b.copyOfRange(b.size - n, b.size) else ByteArray(n - b.size) + b

    val signature = "0x" + pad(sig.r, 32).hex() + pad(sig.s, 32).hex() + "%02x".format(sig.v[0])

    println("address:     ${credentials.address}")
    println("eip712_hash: 0x${hash.hex()}")
    println("signature:   $signature")
}

fun main() = runSigTest()
