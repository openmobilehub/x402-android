package app.x402spike

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Path A M2.1: passkey + counterfactual smart-wallet address as ONE flow.
 *
 * The passkey and the smart-wallet address are not two separate concepts —
 * one derives from the other. The UI reflects that:
 *
 *   • "Create wallet" provisions a passkey in StrongBox AND derives the
 *     smart-wallet address it controls. One tap, one biometric, both pieces
 *     of state shown together.
 *   • "Re-check deployment status" lives below as a secondary action. It's
 *     disabled until a wallet exists, then becomes useful in M2.2 onward
 *     to flip the displayed `deployed: NO/YES` after the first UserOp
 *     deploys the smart wallet on-chain.
 *
 * The status text uses `android:autoLink="web"` so the BaseScan link is
 * tappable — verifies that the address shown is actually queryable on Base
 * Sepolia (returns "Address not found" until the wallet is deployed; that's
 * the expected pre-M2.2 state).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var wallet: PasskeyWallet
    private lateinit var status: TextView
    private lateinit var refreshButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wallet = PasskeyWallet(this)

        status = findViewById(R.id.status)
        val createButton = findViewById<MaterialButton>(R.id.createButton)
        refreshButton = findViewById(R.id.refreshButton)

        // If a passkey already exists from a previous launch, skip straight
        // to enabling refresh — the user shouldn't have to re-create.
        refreshButton.isEnabled = wallet.hasPasskey()

        createButton.setOnClickListener {
            createButton.isEnabled = false
            refreshButton.isEnabled = false
            status.text = "Creating wallet…\n\n" +
                "1. provisioning passkey in StrongBox\n" +
                "2. deriving smart-wallet address"
            lifecycleScope.launch {
                runCatching {
                    val passkey = wallet.createPasskey()
                    // Without the cooldown, eth_call sometimes races with the
                    // immediately-following GPM index update. 1.5s is plenty
                    // even on the slower Pixel 9 Pro XL.
                    delay(GPM_SYNC_COOLDOWN_MS)
                    val sw = SmartWallet(passkey.publicKeyX, passkey.publicKeyY)
                    val address = sw.computeAddress()
                    val deployed = sw.isDeployed()
                    WalletState(passkey, sw, address, deployed)
                }
                    .onSuccess { state -> renderWalletState(state, freshlyCreated = true) }
                    .onFailure { e ->
                        status.text = "FAIL: ${e::class.simpleName}\n${e.message ?: "(no message)"}"
                    }
                createButton.isEnabled = true
                refreshButton.isEnabled = wallet.hasPasskey()
            }
        }

        refreshButton.setOnClickListener {
            createButton.isEnabled = false
            refreshButton.isEnabled = false
            status.text = "Re-checking on-chain status…"
            lifecycleScope.launch {
                runCatching {
                    val pkX = wallet.publicKeyX() ?: error("no passkey cached")
                    val pkY = wallet.publicKeyY() ?: error("no passkey cached")
                    val credId = wallet.lastCredentialId() ?: ""
                    val secLevel = wallet.lastSecurityLevel() ?: "?"
                    val sw = SmartWallet(pkX, pkY)
                    val address = sw.computeAddress()
                    val deployed = sw.isDeployed()
                    WalletState(
                        passkey = PasskeyWallet.CreateResult(credId, pkX, pkY, secLevel),
                        smartWallet = sw,
                        address = address,
                        deployed = deployed,
                    )
                }
                    .onSuccess { state -> renderWalletState(state, freshlyCreated = false) }
                    .onFailure { e ->
                        status.text = "FAIL: ${e::class.simpleName}\n${e.message ?: "(no message)"}"
                    }
                createButton.isEnabled = true
                refreshButton.isEnabled = true
            }
        }
    }

    private fun renderWalletState(state: WalletState, freshlyCreated: Boolean) {
        val baseScanUrl = "https://sepolia.basescan.org/address/${state.address}"
        status.text = buildString {
            appendLine(if (freshlyCreated) "✓ Wallet created" else "✓ Status refreshed")
            appendLine()
            appendLine("Smart-wallet address (Base Sepolia):")
            appendLine("  ${state.address}")
            appendLine()
            appendLine("Verify on BaseScan:")
            appendLine("  $baseScanUrl")
            appendLine()
            appendLine("Deployed:")
            appendLine(
                if (state.deployed) {
                    "  YES — wallet contract is on-chain"
                } else {
                    "  NO — counterfactual until first UserOp (M2.2)"
                },
            )
            appendLine()
            appendLine("Controlled by passkey:")
            appendLine("  credentialId: ${state.passkey.credentialId}")
            appendLine("  pubkey x: ${hex(state.passkey.publicKeyX)}")
            appendLine("  pubkey y: ${hex(state.passkey.publicKeyY)}")
            appendLine()
            appendLine("Security level:")
            appendLine("  ${state.passkey.securityLevel}")
            appendLine()
            appendLine("Factory (Coinbase Smart Wallet, Base Sepolia):")
            appendLine("  ${SmartWallet.COINBASE_SMART_WALLET_FACTORY}")
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private data class WalletState(
        val passkey: PasskeyWallet.CreateResult,
        val smartWallet: SmartWallet,
        val address: String,
        val deployed: Boolean,
    )

    companion object {
        // GPM's queryable index lags createCredential's promise by ~1s. We
        // don't actually use getCredential here (M2.1 is offline-derive only),
        // but bumping past the lag avoids confusion if M2.2 sign-as-wallet
        // hits this same timing window.
        private const val GPM_SYNC_COOLDOWN_MS = 1500L
    }
}
