package app.x402spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigInteger

/**
 * Path A M2.1: counterfactual smart-wallet address from a passkey owner.
 *
 * Coinbase Smart Wallet's factory at `0x0BA5ED0c6AA8c49038F819E587E2633c4A9F428a`
 * (same address on Base mainnet and Base Sepolia) deploys a deterministic
 * smart wallet whose address is a function of:
 *   - the owners array (each owner is either a 20-byte EOA address or a
 *     64-byte packed P-256 pubkey for a passkey owner)
 *   - a uint256 nonce (we use 0 for the first wallet)
 *
 * The factory exposes `getAddress(bytes[] owners, uint256 nonce) view returns (address)`
 * which we call via `eth_call` (no gas, no signer, no API key — Base Sepolia
 * has a public RPC endpoint). The factory replicates Solady's LibClone
 * predictDeterministicAddress on the immutable implementation it was deployed
 * with; we trust the on-chain math rather than reimplementing it locally.
 *
 * Why eth_call and not local CREATE2:
 *   - LibClone uses Solady's clone-with-immutable-args (cwia) layout, not the
 *     standard ERC-1167 minimal proxy. Replicating cwia init code locally is
 *     ~50 lines of bytecode-stitching that's a footgun for a small benefit.
 *   - eth_call is free, deterministic, and the same operation the bundler /
 *     paymaster will do anyway. Single source of truth.
 */
class SmartWallet(
    private val passkeyPubX: ByteArray,
    private val passkeyPubY: ByteArray,
    private val nonce: BigInteger = BigInteger.ZERO,
    private val rpcUrl: String = DEFAULT_BASE_SEPOLIA_RPC,
    private val factoryAddress: String = COINBASE_SMART_WALLET_FACTORY,
) {
    init {
        require(passkeyPubX.size == 32) { "X must be 32 bytes (got ${passkeyPubX.size})" }
        require(passkeyPubY.size == 32) { "Y must be 32 bytes (got ${passkeyPubY.size})" }
    }

    /**
     * The owner-bytes blob the factory expects. For a passkey owner, this is
     * the 64-byte uncompressed public key X || Y. For an EOA owner it would
     * be the 20-byte address; we don't use that path here.
     */
    val ownerBytes: ByteArray = passkeyPubX + passkeyPubY

    /**
     * Compute the counterfactual address of the smart wallet that would be
     * created by `factory.createAccount([passkeyPubX || passkeyPubY], nonce)`.
     *
     * Suspends because it makes one RPC eth_call. Read-only; no signer needed.
     * The returned address is deterministic — same inputs always yield the
     * same address, whether or not the wallet has been deployed yet.
     */
    suspend fun computeAddress(): String = withContext(Dispatchers.IO) {
        val function = Function(
            "getAddress",
            listOf(
                DynamicArray(DynamicBytes::class.java, DynamicBytes(ownerBytes)),
                Uint256(nonce),
            ),
            emptyList(), // we ABI-decode the response manually below
        )
        val callData = DefaultFunctionEncoder().encodeFunction(function)

        val rpcRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "eth_call")
            putJsonArray("params") {
                addJsonObject {
                    put("to", factoryAddress)
                    put("data", callData)
                }
                add("latest")
            }
        }.toString()

        val response = http.newCall(
            Request.Builder()
                .url(rpcUrl)
                .post(rpcRequest.toRequestBody(jsonMediaType))
                .build(),
        ).execute()

        response.use {
            check(it.isSuccessful) { "RPC HTTP ${it.code}: ${it.message}" }
            val body = it.body!!.string()
            val parsed = Json.parseToJsonElement(body).jsonObject
            val error = parsed["error"]
            check(error == null) { "RPC error: $error" }
            val resultHex = parsed["result"]!!.jsonPrimitive.content
            // ABI-encoded address: 32 bytes, last 20 are the address.
            // Hex string is "0x" + 64 hex chars; the address is the last 40.
            require(resultHex.startsWith("0x") && resultHex.length == 66) {
                "unexpected result shape: $resultHex"
            }
            "0x" + resultHex.substring(26)
        }
    }

    /**
     * Convenience: query whether the smart wallet is already deployed at the
     * counterfactual address. M2.1 expectation: NO — it's counterfactual.
     * After M2.2's first UserOp, this returns YES.
     */
    suspend fun isDeployed(): Boolean = withContext(Dispatchers.IO) {
        val address = computeAddress()
        val rpcRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "eth_getCode")
            putJsonArray("params") {
                add(address)
                add("latest")
            }
        }.toString()
        val response = http.newCall(
            Request.Builder()
                .url(rpcUrl)
                .post(rpcRequest.toRequestBody(jsonMediaType))
                .build(),
        ).execute()
        response.use {
            check(it.isSuccessful) { "RPC HTTP ${it.code}" }
            val parsed = Json.parseToJsonElement(it.body!!.string()).jsonObject
            val code = parsed["result"]?.jsonPrimitive?.content ?: "0x"
            // No code at the address → not deployed yet (counterfactual).
            code != "0x" && code != "0x0"
        }
    }

    companion object {
        // Coinbase Smart Wallet factory v1, deterministic-deployed at the same
        // address on Base mainnet, Base Sepolia, OP Mainnet, etc.
        // https://github.com/coinbase/smart-wallet
        const val COINBASE_SMART_WALLET_FACTORY = "0x0BA5ED0c6AA8c49038F819E587E2633c4A9F428a"

        // Public Base Sepolia RPC. No API key. Rate-limited; fine for spike
        // usage. Pimlico / Alchemy / etc. can drop in here for higher RPS.
        const val DEFAULT_BASE_SEPOLIA_RPC = "https://sepolia.base.org"

        private val http = OkHttpClient()
        private val jsonMediaType = "application/json".toMediaType()
    }
}
