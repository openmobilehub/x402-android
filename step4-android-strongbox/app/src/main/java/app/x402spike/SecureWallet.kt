package app.x402spike

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
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
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.math.BigInteger
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class WalletEntry(
    val id: String,
    val address: String,
    val createdAt: Long,
)

/**
 * Multi-wallet variant. Each wallet has its own AES-256-GCM key alias in
 * AndroidKeyStore (StrongBox-backed), its own ciphertext + IV, and its own
 * derived address. The list and the "active" pointer live in SharedPreferences.
 *
 * Why per-wallet aliases instead of one wrap key + many seeds: deleting a
 * wallet is then a single keystore.deleteEntry call that invalidates that
 * wallet's biometric binding without touching the others. Re-using one wrap
 * key would tie the lifecycle of all wallets together.
 *
 * Storage shape (SharedPreferences "x402_strongbox_prefs"):
 *   wallets         -> JSON array of {id, address, createdAt}
 *   active_wallet_id -> string
 *   last_sec_level  -> "STRONGBOX" | "TRUSTED_ENVIRONMENT" | ...   (cached for UI)
 *   ct_<id>         -> base64(ciphertext)
 *   iv_<id>         -> base64(iv)
 */
class SecureWallet(private val activity: FragmentActivity) {

    fun listWallets(): List<WalletEntry> {
        val raw = prefs.getString(PREF_WALLETS, null) ?: return emptyList()
        val arr = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            WalletEntry(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                address = o["address"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    fun activeWalletId(): String? = prefs.getString(PREF_ACTIVE, null)

    fun activeWallet(): WalletEntry? {
        val id = activeWalletId() ?: return null
        return listWallets().firstOrNull { it.id == id }
    }

    fun setActive(id: String) {
        prefs.edit().putString(PREF_ACTIVE, id).apply()
    }

    fun lastSecurityLevel(): String? = prefs.getString(PREF_LAST_SEC_LEVEL, null)

    suspend fun createWallet(): WalletEntry {
        val id = "w_${System.currentTimeMillis()}_${"%04x".format((0..0xFFFF).random())}"
        val alias = aliasFor(id)
        val key = generateWrapKey(alias)
        val secLevel = describeSecurityLevel(key)
        Log.i(TAG, "wrap key generated: $secLevel (alias=$alias)")
        prefs.edit().putString(PREF_LAST_SEC_LEVEL, secLevel).apply()

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val authedCipher = promptBiometric(
            cipher,
            activity.getString(R.string.biometric_setup_title),
            activity.getString(R.string.biometric_setup_subtitle),
        )

        val seed = ByteArray(SEED_BYTES).also(SecureRandom()::nextBytes)
        val address: String
        val ciphertextB64: String
        val ivB64: String
        try {
            val ciphertext = authedCipher.doFinal(seed)
            ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            ivB64 = Base64.encodeToString(authedCipher.iv, Base64.NO_WRAP)
            val ec = ECKeyPair.create(BigInteger(1, seed))
            address = Keys.toChecksumAddress(Credentials.create(ec).address)
        } finally {
            seed.fill(0)
        }

        val newEntry = WalletEntry(id, address, System.currentTimeMillis())
        val updated = listWallets().toMutableList().apply { add(newEntry) }
        prefs.edit()
            .putString("ct_$id", ciphertextB64)
            .putString("iv_$id", ivB64)
            .putString(PREF_WALLETS, walletsJson(updated))
            .putString(PREF_ACTIVE, id)
            .apply()

        Log.i(TAG, "wallet created: $newEntry")
        return newEntry
    }

    suspend fun <T> signWithSeed(walletId: String, block: (ByteArray) -> T): T {
        val ciphertext = Base64.decode(prefs.getString("ct_$walletId", null), Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString("iv_$walletId", null), Base64.NO_WRAP)
        val alias = aliasFor(walletId)
        val key = (keyStore.getKey(alias, null) as SecretKey)

        val secLevel = describeSecurityLevel(key)
        Log.i(TAG, "signing with key: $secLevel (alias=$alias)")
        prefs.edit().putString(PREF_LAST_SEC_LEVEL, secLevel).apply()

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val authedCipher = promptBiometric(
            cipher,
            activity.getString(R.string.biometric_sign_title),
            activity.getString(R.string.biometric_sign_subtitle),
        )

        val seed = authedCipher.doFinal(ciphertext)
        return try {
            block(seed)
        } finally {
            seed.fill(0)
        }
    }

    fun deleteWallet(walletId: String) {
        runCatching { keyStore.deleteEntry(aliasFor(walletId)) }
        val updated = listWallets().filterNot { it.id == walletId }
        val edits = prefs.edit()
            .remove("ct_$walletId")
            .remove("iv_$walletId")
            .putString(PREF_WALLETS, walletsJson(updated))
        if (activeWalletId() == walletId) {
            edits.putString(PREF_ACTIVE, updated.firstOrNull()?.id)
        }
        edits.apply()
    }

    fun resetAll() {
        listWallets().forEach { runCatching { keyStore.deleteEntry(aliasFor(it.id)) } }
        prefs.edit().clear().apply()
    }

    // --- internals ---

    private val prefs: SharedPreferences =
        activity.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyStore: KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun aliasFor(id: String): String = "${KEY_ALIAS_PREFIX}$id"

    private fun walletsJson(entries: List<WalletEntry>): String =
        buildJsonArray {
            entries.forEach { e ->
                add(buildJsonObject {
                    put("id", e.id)
                    put("address", e.address)
                    put("createdAt", e.createdAt)
                })
            }
        }.toString()

    private fun generateWrapKey(alias: String): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            builder.setIsStrongBoxBacked(true)
        } else {
            Log.w(TAG, "device lacks FEATURE_STRONGBOX_KEYSTORE — wrap key will be TEE-backed")
        }
        gen.init(builder.build())
        return gen.generateKey()
    }

    private fun describeSecurityLevel(key: SecretKey): String {
        return try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT (fell back from STRONGBOX!)"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE (key is NOT in secure hardware!)"
                    else -> "UNKNOWN(${info.securityLevel})"
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) {
                    "INSIDE_SECURE_HARDWARE (API < 31 — can't distinguish StrongBox from TEE)"
                } else {
                    "NOT_INSIDE_SECURE_HARDWARE (key is in software!)"
                }
            }
        } catch (e: Throwable) {
            "<unknown: ${e.message}>"
        }
    }

    private suspend fun promptBiometric(
        cipher: Cipher,
        title: String,
        subtitle: String,
    ): Cipher = suspendCoroutine { cont ->
        val executor = activity.mainExecutor
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) {
                        cont.resumeWithException(IllegalStateException("biometric callback gave us a null cipher"))
                    } else {
                        cont.resume(c)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    cont.resumeWithException(BiometricCancelled(errorCode, errString.toString()))
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(activity.getString(R.string.biometric_negative))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    class BiometricCancelled(val code: Int, message: String) : RuntimeException(message)

    companion object {
        private const val TAG = "SecureWallet"
        private const val PREFS_NAME = "x402_strongbox_prefs"
        private const val PREF_WALLETS = "wallets"
        private const val PREF_ACTIVE = "active_wallet_id"
        private const val PREF_LAST_SEC_LEVEL = "last_sec_level"
        private const val KEY_ALIAS_PREFIX = "x402_seed_wrap_"
        private const val SEED_BYTES = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
