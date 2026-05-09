package app.x402spike

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.fragment.app.FragmentActivity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPrivateKey

/**
 * Probe: does Pixel 9 Pro XL's KeyMint accept secp256k1 with
 * setIsStrongBoxBacked(true)?
 *
 * If YES → we can build x402 with secp256k1 signing entirely inside StrongBox,
 *         no seed in RAM ever. Best of both worlds.
 * If NO  → it'll either be rejected outright, or silently fall back to TEE.
 *         CLAUDE.md mandates we surface that fallback explicitly.
 *
 * No biometric here — this is just a key-spec acceptance test. Generation is
 * cheap; we delete the probe key after introspection so we don't pollute
 * AndroidKeyStore.
 */
object SecpStrongboxProbe {

    private const val TAG = "SecpStrongboxProbe"
    private const val PROBE_ALIAS = "x402_secp_strongbox_probe"

    fun run(activity: FragmentActivity) {
        val pm = activity.packageManager
        val hasStrongBox = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        Log.i(TAG, "device hasFeature STRONGBOX_KEYSTORE = $hasStrongBox  (Build: ${Build.MODEL}, sdk=${Build.VERSION.SDK_INT})")

        // Clean any leftover probe key
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(PROBE_ALIAS)
        }

        // Try with StrongBox
        val secLevelStrongBox = tryGenerate(setStrongBox = true)
        Log.i(TAG, "secp256k1 with setIsStrongBoxBacked(true)  → $secLevelStrongBox")

        // Clean and try without StrongBox
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(PROBE_ALIAS)
        }
        val secLevelTee = tryGenerate(setStrongBox = false)
        Log.i(TAG, "secp256k1 without StrongBox flag           → $secLevelTee")

        // Clean and try P-256 with StrongBox (control)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(PROBE_ALIAS)
        }
        val secLevelP256 = tryGenerate(setStrongBox = true, curve = "secp256r1")
        Log.i(TAG, "secp256r1 with setIsStrongBoxBacked(true)  → $secLevelP256  (control)")

        // Final cleanup
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(PROBE_ALIAS)
        }
    }

    private fun tryGenerate(setStrongBox: Boolean, curve: String = "secp256k1"): String {
        return try {
            val spec = KeyGenParameterSpec.Builder(PROBE_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec(curve))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .apply { if (setStrongBox) setIsStrongBoxBacked(true) }
                .build()
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            kpg.initialize(spec)
            val pair = kpg.generateKeyPair()
            val priv = pair.private as ECPrivateKey
            val keyFactory = java.security.KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore")
            val info = keyFactory.getKeySpec(priv, KeyInfo::class.java) as KeyInfo
            describeSecurityLevel(info)
        } catch (t: Throwable) {
            "REJECTED (${t::class.simpleName}: ${t.message?.take(120)})"
        }
    }

    private fun describeSecurityLevel(info: KeyInfo): String {
        // KeyInfo.getSecurityLevel() exists from API 31; on older devices use the legacy boolean
        return if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                KeyProperties.SECURITY_LEVEL_UNKNOWN -> "UNKNOWN"
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "UNKNOWN_SECURE"
                else -> "level=${info.securityLevel}"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "SECURE_HW (legacy flag)" else "SOFTWARE"
        }
    }
}
