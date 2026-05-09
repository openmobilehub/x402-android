package app.x402spike

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Path A M1 — passkey hello world UI.
 *
 * Two buttons:
 *   1. Create passkey         — calls PasskeyWallet.createPasskey(), shows the
 *                                 credentialId, public key, and security level
 *   2. Sign test challenge    — signs SHA-256("hello world"), prints the
 *                                 assertion details, runs P256Verify off-chain
 *                                 to confirm the signature is valid
 *
 * Note on hash function: PATH_A_NEXT.md says "signs keccak256(\"hello world\")"
 * but web3j is dropped in M1 along with secp256k1. SHA-256 over "hello world"
 * is just as valid for the round-trip test — any 32-byte challenge serves.
 * Keccak comes back in M2 when the smart-wallet UserOp hash needs it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var wallet: PasskeyWallet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wallet = PasskeyWallet(this)

        val status = findViewById<TextView>(R.id.status)
        val createButton = findViewById<MaterialButton>(R.id.createButton)
        val signButton = findViewById<MaterialButton>(R.id.signButton)

        createButton.setOnClickListener {
            createButton.isEnabled = false
            signButton.isEnabled = false
            status.text = "Creating passkey... (system passkey UI will appear)"
            lifecycleScope.launch {
                runCatching { wallet.createPasskey() }
                    .onSuccess { res ->
                        status.text = buildString {
                            appendLine("✓ passkey created")
                            appendLine()
                            appendLine("credentialId:")
                            appendLine("  ${res.credentialId}")
                            appendLine()
                            appendLine("public key (P-256):")
                            appendLine("  x = ${hex(res.publicKeyX)}")
                            appendLine("  y = ${hex(res.publicKeyY)}")
                            appendLine()
                            appendLine("security level:")
                            appendLine("  ${res.securityLevel}")
                        }
                    }
                    .onFailure { e ->
                        status.text = "FAIL: ${e::class.simpleName}\n${e.message ?: "(no message)"}"
                    }
                createButton.isEnabled = true
                signButton.isEnabled = true
            }
        }

        signButton.setOnClickListener {
            createButton.isEnabled = false
            signButton.isEnabled = false
            status.text = "Signing... (biometric prompt will appear)"
            lifecycleScope.launch {
                runCatching {
                    val hash = MessageDigest.getInstance("SHA-256").digest(
                        "hello world".toByteArray(),
                    )
                    val assertion = wallet.signChallenge(hash)
                    val pkX = wallet.publicKeyX() ?: error("no passkey yet — tap Create first")
                    val pkY = wallet.publicKeyY() ?: error("no passkey yet — tap Create first")
                    val ok = P256Verify.verifyAssertion(
                        pkX, pkY,
                        assertion.authenticatorData,
                        assertion.clientDataJson,
                        assertion.signatureDer,
                    )
                    Triple(assertion, ok, hash)
                }
                    .onSuccess { (a, ok, hash) ->
                        status.text = buildString {
                            appendLine(if (ok) "✓ off-chain P-256 verify passed" else "✗ off-chain verify FAILED")
                            appendLine()
                            appendLine("input hash (sha256(\"hello world\")):")
                            appendLine("  ${hex(hash)}")
                            appendLine()
                            appendLine("authenticatorData (${a.authenticatorData.size} bytes):")
                            appendLine("  ${hex(a.authenticatorData)}")
                            appendLine()
                            appendLine("clientDataJSON:")
                            appendLine("  ${String(a.clientDataJson)}")
                            appendLine()
                            appendLine("signature DER (${a.signatureDer.size} bytes):")
                            appendLine("  ${hex(a.signatureDer)}")
                            appendLine()
                            appendLine("signature parts (raw):")
                            appendLine("  r = ${hex(a.signatureR)}")
                            appendLine("  s = ${hex(a.signatureS)}")
                            appendLine()
                            appendLine("security level: ${wallet.lastSecurityLevel() ?: "?"}")
                        }
                    }
                    .onFailure { e ->
                        status.text = "FAIL: ${e::class.simpleName}\n${e.message ?: "(no message)"}"
                    }
                createButton.isEnabled = true
                signButton.isEnabled = true
            }
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
