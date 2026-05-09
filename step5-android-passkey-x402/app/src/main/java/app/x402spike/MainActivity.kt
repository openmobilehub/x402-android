package app.x402spike

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Path A M2.1: passkey + counterfactual smart-wallet address.
 *
 * Two buttons:
 *   1. Create passkey — same as step5-android-passkey M1
 *   2. Compute smart-wallet address — derives the deterministic Coinbase
 *      Smart Wallet address from the passkey's P-256 pubkey via an eth_call
 *      to the factory on Base Sepolia
 *
 * No on-chain action yet. M2.2 will add UserOperation construction + Pimlico
 * submission. M3 will wire x402 protocol on top.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var wallet: PasskeyWallet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wallet = PasskeyWallet(this)

        val status = findViewById<TextView>(R.id.status)
        val createButton = findViewById<MaterialButton>(R.id.createButton)
        val addressButton = findViewById<MaterialButton>(R.id.addressButton)

        createButton.setOnClickListener {
            createButton.isEnabled = false
            addressButton.isEnabled = false
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
                            appendLine()
                            appendLine("Next: tap '${getString(R.string.show_address_button)}'")
                        }
                    }
                    .onFailure { e ->
                        status.text = "FAIL: ${e::class.simpleName}\n${e.message ?: "(no message)"}"
                    }
                createButton.isEnabled = true
                addressButton.isEnabled = true
            }
        }

        addressButton.setOnClickListener {
            val pkX = wallet.publicKeyX()
            val pkY = wallet.publicKeyY()
            if (pkX == null || pkY == null) {
                status.text = "No passkey yet — tap '${getString(R.string.create_passkey_button)}' first."
                return@setOnClickListener
            }

            createButton.isEnabled = false
            addressButton.isEnabled = false
            status.text = "Computing smart-wallet address... (eth_call to factory)"
            lifecycleScope.launch {
                runCatching {
                    val sw = SmartWallet(pkX, pkY)
                    val addr = sw.computeAddress()
                    val deployed = sw.isDeployed()
                    Triple(sw, addr, deployed)
                }
                    .onSuccess { (sw, addr, deployed) ->
                        status.text = buildString {
                            appendLine("✓ counterfactual smart-wallet address")
                            appendLine()
                            appendLine("address:")
                            appendLine("  $addr")
                            appendLine()
                            appendLine("deployed:")
                            appendLine("  ${if (deployed) "YES (already deployed on Base Sepolia)" else "NO (counterfactual — first UserOp will deploy)"}")
                            appendLine()
                            appendLine("BaseScan:")
                            appendLine("  https://sepolia.basescan.org/address/$addr")
                            appendLine()
                            appendLine("owner (passkey pubkey, packed):")
                            appendLine("  ${hex(sw.ownerBytes)}")
                            appendLine()
                            appendLine("Factory:")
                            appendLine("  ${SmartWallet.COINBASE_SMART_WALLET_FACTORY}")
                        }
                    }
                    .onFailure { e ->
                        status.text = "FAIL: ${e::class.simpleName}\n${e.message ?: "(no message)"}"
                    }
                createButton.isEnabled = true
                addressButton.isEnabled = true
            }
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
