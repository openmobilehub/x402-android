package app.x402spike

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.fragment.app.FragmentActivity
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.ByteString
import co.nstant.`in`.cbor.model.NegativeInteger
import co.nstant.`in`.cbor.model.UnicodeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Path A M1 — passkey hello world.
 *
 * Replaces Step 4's `SecureWallet` (secp256k1 seed wrapped under a StrongBox
 * AES key) with a P-256 passkey held by Credential Manager. On a Pixel with
 * StrongBox the underlying private key lives in Titan M2 and never appears
 * in app RAM — at no point does this class see the private key bytes.
 *
 * M1 scope: create one passkey, sign one 32-byte challenge, return the
 * WebAuthn assertion + the public key (extracted at create time). No on-chain
 * involvement, no smart wallet, no x402 envelope. M2-M4 build on top.
 *
 * Storage shape (SharedPreferences `x402_passkey_prefs`):
 *   credential_id   -> base64url(rawId)              the WebAuthn credentialId
 *   public_key_x    -> base64url(32-byte X coord)    P-256 uncompressed pubkey
 *   public_key_y    -> base64url(32-byte Y coord)
 *   created_at      -> long (ms epoch)
 *   last_sec_level  -> string (best-effort, see describeSecurityLevel)
 *
 * Three corrections from PATH_A_NEXT.md flagged in the M1 plan:
 *   1. KeyInfo.getSecurityLevel() doesn't apply to passkey-managed keys; we
 *      best-effort infer via FEATURE_STRONGBOX_KEYSTORE. M2 will upgrade by
 *      requesting attestation: "direct" and parsing OID 1.3.6.1.4.1.11129.2.1.17.
 *   2. We use SHA-256 (not keccak256) for the test challenge — web3j is
 *      dropped along with secp256k1, and any 32-byte input serves the test.
 *   3. RP-ID is the registrable host (openmobilehub.github.io), not a path.
 */
class PasskeyWallet(private val activity: FragmentActivity) {

    data class CreateResult(
        val credentialId: String,
        val publicKeyX: ByteArray,
        val publicKeyY: ByteArray,
        val securityLevel: String,
    )

    data class Assertion(
        val signatureDer: ByteArray,
        val signatureR: ByteArray,
        val signatureS: ByteArray,
        val authenticatorData: ByteArray,
        val clientDataJson: ByteArray,
        val challenge: ByteArray,
    )

    suspend fun createPasskey(): CreateResult {
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val userId = ByteArray(16).also(SecureRandom()::nextBytes)
        val rpId = activity.getString(R.string.rp_id)
        val rpName = activity.getString(R.string.rp_name)

        val requestJson = buildJsonObject {
            put("challenge", base64Url(challenge))
            putJsonObject("rp") {
                put("id", rpId)
                put("name", rpName)
            }
            putJsonObject("user") {
                put("id", base64Url(userId))
                put("name", "x402-spike-user")
                put("displayName", "x402 Spike")
            }
            putJsonArray("pubKeyCredParams") {
                // ES256 = ECDSA with P-256 + SHA-256. The only algorithm we
                // accept; RIP-7212 (Path A's M2 on-chain verifier) is P-256 only.
                addJsonObject {
                    put("type", "public-key")
                    put("alg", -7)
                }
            }
            putJsonObject("authenticatorSelection") {
                // requireResidentKey:true + residentKey:"required" together are
                // load-bearing on current GMS (26.x). Setting only residentKey
                // returns a successful registrationResponseJson but the passkey
                // never lands in GPM's queryable index — getCredential then
                // fails with NoCredentialException. The deprecated
                // requireResidentKey field is what makes GMS persist the
                // credential, matching Google's own docs example.
                put("authenticatorAttachment", "platform")
                put("requireResidentKey", true)
                put("residentKey", "required")
                put("userVerification", "required")
            }
            // M1 doesn't need an attestation chain — we just want the public
            // key from the authData. M2 will switch to "direct" and parse the
            // attestation extension for definitive securityLevel.
            put("attestation", "none")
            put("timeout", 60000)
        }.toString()

        if (BuildConfig.DEBUG) Log.i(TAG, "create request JSON: $requestJson")
        val response = CredentialManager.create(activity).createCredential(
            activity,
            CreatePublicKeyCredentialRequest(requestJson),
        ) as CreatePublicKeyCredentialResponse

        if (BuildConfig.DEBUG) Log.i(TAG, "create response JSON: ${response.registrationResponseJson}")
        val parsed = parseRegistrationResponse(response.registrationResponseJson)
        val secLevel = describeSecurityLevel()

        prefs.edit()
            .putString(PREF_CREDENTIAL_ID, parsed.credentialId)
            .putString(PREF_PUBKEY_X, base64Url(parsed.publicKeyX))
            .putString(PREF_PUBKEY_Y, base64Url(parsed.publicKeyY))
            .putLong(PREF_CREATED_AT, System.currentTimeMillis())
            .putString(PREF_LAST_SEC_LEVEL, secLevel)
            .apply()

        Log.i(TAG, "passkey created: credentialId=${parsed.credentialId} secLevel=$secLevel")
        return CreateResult(parsed.credentialId, parsed.publicKeyX, parsed.publicKeyY, secLevel)
    }

    suspend fun signChallenge(hash: ByteArray): Assertion {
        require(hash.size == 32) { "challenge hash must be 32 bytes (got ${hash.size})" }
        val credId = prefs.getString(PREF_CREDENTIAL_ID, null)
            ?: error("no passkey yet — call createPasskey() first")
        val rpId = activity.getString(R.string.rp_id)

        // Pin to OUR specific credentialId: residentKey:required at create
        // time made the passkey discoverable, but if multiple passkeys exist
        // for the RP, GPM may pick a different one than the pubkey we cached.
        val requestJson = buildJsonObject {
            put("challenge", base64Url(hash))
            put("rpId", rpId)
            put("userVerification", "required")
            put("timeout", 60000)
            putJsonArray("allowCredentials") {
                addJsonObject {
                    put("type", "public-key")
                    put("id", credId)
                    putJsonArray("transports") {
                        add("internal")
                        add("hybrid")
                    }
                }
            }
        }.toString()

        if (BuildConfig.DEBUG) Log.i(TAG, "sign request JSON: $requestJson")
        val request = GetCredentialRequest(
            listOf(GetPublicKeyCredentialOption(requestJson)),
        )
        val response = CredentialManager.create(activity).getCredential(activity, request)
        val cred = response.credential as PublicKeyCredential

        Log.i(TAG, "passkey signed challenge with key: ${describeSecurityLevel()}")
        return parseAssertionResponse(cred.authenticationResponseJson, hash)
    }

    fun publicKeyX(): ByteArray? =
        prefs.getString(PREF_PUBKEY_X, null)?.let(::base64UrlDecode)

    fun publicKeyY(): ByteArray? =
        prefs.getString(PREF_PUBKEY_Y, null)?.let(::base64UrlDecode)

    fun hasPasskey(): Boolean =
        prefs.getString(PREF_CREDENTIAL_ID, null) != null

    fun lastCredentialId(): String? =
        prefs.getString(PREF_CREDENTIAL_ID, null)

    fun lastSecurityLevel(): String? =
        prefs.getString(PREF_LAST_SEC_LEVEL, null)

    /**
     * Best-effort inference of the security level. PATH_A_NEXT.md's original
     * spec assumed `KeyInfo.getSecurityLevel()` would work, but Credential
     * Manager doesn't expose passkey-managed keys via AndroidKeyStore — the
     * key lives in the system passkey provider's namespace, not ours. So we
     * proxy via `FEATURE_STRONGBOX_KEYSTORE`. M2 will switch to attestation:
     * "direct" and read the attestation cert extension OID
     * 1.3.6.1.4.1.11129.2.1.17 for the definitive securityLevel field.
     *
     * Per CLAUDE.md ("log what matters; silent fallbacks are the enemy"), be
     * explicit about this being inference, not a definitive read.
     */
    fun describeSecurityLevel(): String {
        val hasStrongBox = activity.packageManager.hasSystemFeature(
            PackageManager.FEATURE_STRONGBOX_KEYSTORE,
        )
        return if (hasStrongBox) {
            "STRONGBOX (inferred — Credential Manager does not expose KeyInfo for " +
                "passkey-managed keys; M2 will read attestation cert extensions for " +
                "definitive security level)"
        } else {
            "TEE_OR_SOFTWARE (device lacks FEATURE_STRONGBOX_KEYSTORE)"
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
        Log.i(TAG, "passkey state cleared")
    }

    // --- internals ---

    private val prefs: SharedPreferences =
        activity.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class ParsedRegistration(
        val credentialId: String,
        val publicKeyX: ByteArray,
        val publicKeyY: ByteArray,
    )

    /**
     * Walk the WebAuthn registration response to extract the credentialId and
     * the P-256 public key (x, y) from the COSE_Key inside authData.
     *
     * Layout (WebAuthn §6.5.1, simplified):
     *   registrationResponseJson  =  { rawId, response: { attestationObject, ... } }
     *   attestationObject         =  CBOR { fmt, attStmt, authData }
     *   authData                  =  rpIdHash(32) || flags(1) || signCount(4)
     *                                || attestedCredentialData
     *   attestedCredentialData    =  aaguid(16) || credIdLen(2)
     *                                || credentialId(L) || credentialPublicKey
     *   credentialPublicKey (COSE) =  CBOR map with keys
     *                                  1: kty (=2 for EC2),
     *                                  3: alg (=-7 for ES256),
     *                                  -1: crv (=1 for P-256),
     *                                  -2: x (32 bytes),
     *                                  -3: y (32 bytes)
     *
     * For M1 with `attestation: "none"` and no extensions requested, the simple
     * walk below suffices. If we ever add extensions, the ED flag (0x80) on
     * authData.flags would indicate trailing extension data after the COSE_Key.
     */
    private fun parseRegistrationResponse(json: String): ParsedRegistration {
        val root = Json.parseToJsonElement(json).jsonObject
        val credentialId = root["rawId"]!!.jsonPrimitive.content // already base64url
        val attObjB64 = root["response"]!!.jsonObject["attestationObject"]!!.jsonPrimitive.content
        val attObj = base64UrlDecode(attObjB64)

        val items = CborDecoder(ByteArrayInputStream(attObj)).decode()
        val attMap = items[0] as co.nstant.`in`.cbor.model.Map
        val authData = (attMap[UnicodeString("authData")] as ByteString).bytes

        val buf = ByteBuffer.wrap(authData)
        // Skip rpIdHash (32) + flags (1) + signCount (4) = 37 bytes
        buf.position(37)
        // Skip aaguid (16)
        buf.position(buf.position() + 16)
        val credIdLen = buf.short.toInt() and 0xFFFF
        // Skip the credentialId; we already have it from the JSON
        buf.position(buf.position() + credIdLen)

        // The remainder of authData is the COSE_Key (and possibly extensions).
        val coseStart = buf.position()
        val coseBytes = authData.copyOfRange(coseStart, authData.size)
        val cose = CborDecoder(ByteArrayInputStream(coseBytes)).decode()[0] as co.nstant.`in`.cbor.model.Map
        // COSE keys are negative integers; the CBOR library represents them as NegativeInteger.
        // -2 (x) maps to NegativeInteger(-2). The library treats -2 as the value, not the key.
        val x = (cose[NegativeInteger(-2)] as ByteString).bytes
        val y = (cose[NegativeInteger(-3)] as ByteString).bytes

        require(x.size == 32 && y.size == 32) {
            "expected 32-byte P-256 coords, got x=${x.size} y=${y.size}"
        }
        return ParsedRegistration(credentialId, x, y)
    }

    private fun parseAssertionResponse(json: String, originalChallenge: ByteArray): Assertion {
        val root = Json.parseToJsonElement(json).jsonObject
        val resp = root["response"]!!.jsonObject
        val sigDer = base64UrlDecode(resp["signature"]!!.jsonPrimitive.content)
        val authData = base64UrlDecode(resp["authenticatorData"]!!.jsonPrimitive.content)
        val clientDataJson = base64UrlDecode(resp["clientDataJSON"]!!.jsonPrimitive.content)
        val (r, s) = P256Verify.derToRs(sigDer)
        return Assertion(sigDer, r, s, authData, clientDataJson, originalChallenge)
    }

    companion object {
        private const val TAG = "PasskeyWallet"
        private const val PREFS_NAME = "x402_passkey_prefs"
        private const val PREF_CREDENTIAL_ID = "credential_id"
        private const val PREF_PUBKEY_X = "public_key_x"
        private const val PREF_PUBKEY_Y = "public_key_y"
        private const val PREF_CREATED_AT = "created_at"
        private const val PREF_LAST_SEC_LEVEL = "last_sec_level"

        // Base64url with no padding — the WebAuthn JSON encoding throughout.
        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, B64_FLAGS)

        fun base64UrlDecode(s: String): ByteArray =
            Base64.decode(s, B64_FLAGS)
    }
}
