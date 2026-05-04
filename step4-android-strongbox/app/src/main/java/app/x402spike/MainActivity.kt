package app.x402spike

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MainActivity : AppCompatActivity() {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private lateinit var wallet: SecureWallet

    private enum class Screen { PAY, WALLETS }
    private var currentScreen = Screen.PAY

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wallet = SecureWallet(this)

        val payContent = findViewById<View>(R.id.payContent)
        val walletsContent = findViewById<View>(R.id.walletsContent)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        val payerLabel = findViewById<TextView>(R.id.payerLabel)
        val targetLabel = findViewById<TextView>(R.id.targetLabel)
        val status = findViewById<TextView>(R.id.status)
        val webview = findViewById<WebView>(R.id.webview)
        val getButton = findViewById<MaterialButton>(R.id.getButton)
        val payButton = findViewById<MaterialButton>(R.id.payButton)

        val walletList = findViewById<LinearLayout>(R.id.walletList)
        val newWalletFab = findViewById<ExtendedFloatingActionButton>(R.id.newWalletFab)

        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.settings.mediaPlaybackRequiresUserGesture = false

        val url = BuildConfig.DEMO_URL
        targetLabel.text = "target: $url"

        fun applyScreen() {
            when (currentScreen) {
                Screen.PAY -> {
                    payContent.visibility = View.VISIBLE
                    walletsContent.visibility = View.GONE
                    renderHomeState(payerLabel, getButton, payButton)
                }
                Screen.WALLETS -> {
                    payContent.visibility = View.GONE
                    walletsContent.visibility = View.VISIBLE
                    renderWalletList(walletList, payerLabel, getButton, payButton)
                }
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            currentScreen = when (item.itemId) {
                R.id.nav_pay -> Screen.PAY
                R.id.nav_wallets -> Screen.WALLETS
                else -> currentScreen
            }
            applyScreen()
            true
        }
        bottomNav.selectedItemId = R.id.nav_pay

        getButton.setOnClickListener {
            getButton.isEnabled = false
            payButton.isEnabled = false
            status.text = "GET $url  (no payment)..."
            webview.visibility = View.GONE
            lifecycleScope.launch {
                runCatching { X402Client.getRaw(url) }
                    .onSuccess { showRaw(status, webview, it) }
                    .onFailure {
                        status.text = "FAIL: ${it::class.simpleName}: ${it.message}"
                    }
                getButton.isEnabled = true
                payButton.isEnabled = true
            }
        }

        payButton.setOnClickListener {
            val activeId = wallet.activeWalletId()
            if (activeId == null) {
                Snackbar.make(payButton, "No active wallet — open the Wallets tab", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            getButton.isEnabled = false
            payButton.isEnabled = false
            status.text = "GET $url to fetch challenge..."
            webview.visibility = View.GONE
            lifecycleScope.launch {
                runCatching {
                    val (challenge, raw) = X402Client.fetchChallenge(url)
                    if (raw.status == 200) {
                        return@runCatching PayResult.Success(
                            txHash = "(no payment needed — endpoint returned 200)",
                            basescanUrl = "",
                            body = raw.body,
                            contentType = raw.contentType,
                            resolvedUrl = url,
                        )
                    }
                    requireNotNull(challenge) { "no payment-required header on 402 response" }
                    val acceptObj = (challenge["accepts"] as? JsonArray)
                        ?.firstOrNull { entry ->
                            val o = entry as? JsonObject ?: return@firstOrNull false
                            (o["network"] as? JsonPrimitive)?.content == "eip155:84532" &&
                                (o["scheme"] as? JsonPrimitive)?.content == "exact"
                        } as? JsonObject
                        ?: error("no eip155:84532 + exact in accepts[]")

                    status.text = "Challenge OK. Prompting biometric to unwrap seed..."
                    val envelope = wallet.signWithSeed(activeId) { seed ->
                        X402Client.createEnvelope(challenge, acceptObj, seed)
                    }
                    status.text = "Seed re-zeroed. Posting envelope..."
                    X402Client.sendEnvelope(url, envelope)
                }
                    .onSuccess { result ->
                        when (result) {
                            is PayResult.Success -> {
                                status.text = buildString {
                                    append("OK 200  ")
                                    append(if (result.basescanUrl.isNotEmpty()) result.basescanUrl else result.txHash)
                                    append("\n  key: ${wallet.lastSecurityLevel() ?: "?"}")
                                }
                                showHtml(webview, result.body, result.contentType, result.resolvedUrl)
                            }
                            is PayResult.Failure -> {
                                status.text = "FAIL: ${result.message}\nhttp: ${result.httpStatus ?: "n/a"}"
                            }
                        }
                    }
                    .onFailure {
                        status.text = if (it is SecureWallet.BiometricCancelled) {
                            "Biometric cancelled (code ${it.code})."
                        } else {
                            "FAIL: ${it::class.simpleName}: ${it.message}"
                        }
                    }
                getButton.isEnabled = true
                payButton.isEnabled = true
            }
        }

        newWalletFab.setOnClickListener {
            newWalletFab.isEnabled = false
            val first = wallet.listWallets().isEmpty()
            Snackbar.make(
                newWalletFab,
                if (first) "Generating in StrongBox..." else "Adding wallet...",
                Snackbar.LENGTH_SHORT,
            ).show()
            lifecycleScope.launch {
                runCatching { wallet.createWallet() }
                    .onSuccess { entry ->
                        Snackbar.make(
                            newWalletFab,
                            "Wallet ${entry.address.take(10)}… ready",
                            Snackbar.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure {
                        val msg = if (it is SecureWallet.BiometricCancelled) {
                            "Biometric cancelled (${it.code})"
                        } else {
                            "${it::class.simpleName}: ${it.message}"
                        }
                        Snackbar.make(newWalletFab, msg, Snackbar.LENGTH_LONG).show()
                    }
                newWalletFab.isEnabled = true
                renderWalletList(walletList, payerLabel, getButton, payButton)
            }
        }

        applyScreen()
    }

    private fun renderHomeState(
        payerLabel: TextView,
        getButton: MaterialButton,
        payButton: MaterialButton,
    ) {
        val active = wallet.activeWallet()
        if (active == null) {
            payerLabel.text = "(no active wallet — open the Wallets tab)"
            getButton.isEnabled = false
            payButton.isEnabled = false
        } else {
            payerLabel.text = "payer:  ${active.address}"
            getButton.isEnabled = true
            payButton.isEnabled = true
        }
    }

    private fun renderWalletList(
        walletList: LinearLayout,
        payerLabel: TextView,
        getButton: MaterialButton,
        payButton: MaterialButton,
    ) {
        walletList.removeAllViews()
        val wallets = wallet.listWallets()
        val activeId = wallet.activeWalletId()

        if (wallets.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No wallets yet. Tap the + button to generate one in StrongBox."
                textSize = 14f
                setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(48), 0, 0)
                gravity = android.view.Gravity.CENTER
            }
            walletList.addView(empty)
            return
        }

        wallets.forEach { entry ->
            walletList.addView(buildWalletCard(entry, isActive = entry.id == activeId, walletList, payerLabel, getButton, payButton))
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun buildWalletCard(
        entry: WalletEntry,
        isActive: Boolean,
        walletList: LinearLayout,
        payerLabel: TextView,
        getButton: MaterialButton,
        payButton: MaterialButton,
    ): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(if (isActive) 2 else 0).toFloat()
            strokeWidth = dp(1)
            strokeColor = resolveAttrColor(
                if (isActive) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutlineVariant,
            )
            isClickable = true
            isFocusable = true
            isCheckable = false
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val addressView = TextView(this).apply {
            text = entry.address
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                resolveAttrColor(
                    if (isActive) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurface,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        titleRow.addView(addressView)

        if (isActive) {
            val badge = TextView(this).apply {
                text = "ACTIVE"
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnPrimary))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(resolveAttrColor(com.google.android.material.R.attr.colorPrimary))
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            titleRow.addView(badge)
        }

        inner.addView(titleRow)

        val secLevel = wallet.lastSecurityLevel()
        val meta = TextView(this).apply {
            text = "id: ${entry.id}\ncreated: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAt))}" +
                if (isActive && secLevel != null) "\nlast seen wrap-key level: $secLevel" else ""
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(8), 0, 0)
        }
        inner.addView(meta)

        // On-chain status row: balance + tx count, fetched async from Blockscout.
        val statusRow = TextView(this).apply {
            text = "on-chain: checking…"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(8), 0, dp(2))
            setTypeface(typeface, Typeface.BOLD)
        }
        inner.addView(statusRow)

        // Action buttons: open BaseScan, copy, faucet.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }

        // Helper to keep three small action buttons on one line on a phone width.
        fun smallActionButton(label: String, iconRes: Int, onClick: () -> Unit): MaterialButton {
            return MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                insetTop = 0
                insetBottom = 0
                setPadding(dp(10), dp(6), dp(10), dp(6))
                icon = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, iconRes)
                iconSize = dp(14)
                iconPadding = dp(4)
                cornerRadius = dp(20)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { setMargins(0, 0, dp(6), 0) }
            }
        }

        val basescanBtn = smallActionButton(
            "BaseScan",
            R.drawable.ic_open_in_new_24,
        ) { openUrl("https://sepolia.basescan.org/address/${entry.address}") }
        actionRow.addView(basescanBtn)

        val copyBtn = smallActionButton(
            "Copy",
            R.drawable.ic_content_copy_24,
        ) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("address", entry.address))
            Snackbar.make(actionRow, "Copied ${entry.address.take(10)}…", Snackbar.LENGTH_SHORT).show()
        }
        actionRow.addView(copyBtn)

        val rechargeBtn = smallActionButton(
            "Recharge",
            R.drawable.ic_open_in_new_24,
        ) {
            // Copy address first so user only has to paste at faucet.circle.com.
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("address", entry.address))
            Snackbar.make(actionRow, "Address copied — paste at faucet.circle.com", Snackbar.LENGTH_LONG).show()
            openUrl("https://faucet.circle.com")
        }
        // remove right margin on the last button so the row balances
        (rechargeBtn.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        actionRow.addView(rechargeBtn)

        inner.addView(actionRow)

        card.addView(inner)

        // Kick off the on-chain probe. Address is mathematically valid the moment
        // it's derived; "exists on-chain" really means "has activity / balance".
        lifecycleScope.launch {
            val s = BlockchainClient.describe(entry.address)
            val txPart = when (s.txCount) {
                null -> ""
                0L -> "  ·  no on-chain activity yet"
                1L -> "  ·  1 tx"
                else -> "  ·  ${s.txCount} txs"
            }
            statusRow.text = "${s.usdcDisplay}$txPart"
        }

        card.setOnClickListener {
            wallet.setActive(entry.id)
            Snackbar.make(card, "Active: ${entry.address.take(10)}…", Snackbar.LENGTH_SHORT).show()
            renderWalletList(walletList, payerLabel, getButton, payButton)
            renderHomeState(payerLabel, getButton, payButton)
        }
        card.setOnLongClickListener {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Delete wallet?")
                .setMessage("${entry.address}\n\nWipes the StrongBox wrap key and the encrypted seed. Cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    wallet.deleteWallet(entry.id)
                    Snackbar.make(walletList, "Deleted ${entry.address.take(10)}…", Snackbar.LENGTH_SHORT).show()
                    renderWalletList(walletList, payerLabel, getButton, payButton)
                    renderHomeState(payerLabel, getButton, payButton)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        return card
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showRaw(status: TextView, webview: WebView, raw: RawResult) {
        status.text = "GET → HTTP ${raw.status}  (no x402 envelope sent)"
        val sections = buildString {
            appendLine("HTTP ${raw.status}")
            if (raw.contentType.isNotBlank()) appendLine("Content-Type: ${raw.contentType}")
            appendLine()
            if (raw.challenge != null) {
                appendLine("decoded payment-required header:")
                appendLine(pretty.encodeToString(JsonObject.serializer(), raw.challenge))
                appendLine()
            }
            appendLine("response body:")
            append(if (raw.body.isBlank()) "(empty)" else raw.body)
        }
        showPlain(webview, sections, raw.resolvedUrl)
    }

    private fun showHtml(webview: WebView, body: String, contentType: String, baseUrl: String) {
        if (contentType.startsWith("text/html", ignoreCase = true)) {
            webview.visibility = View.VISIBLE
            webview.loadDataWithBaseURL(baseUrl, body, "text/html", "UTF-8", null)
        } else {
            showPlain(webview, body, baseUrl)
        }
    }

    private fun showPlain(webview: WebView, content: String, baseUrl: String) {
        val escaped = content
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width\">" +
            "<style>body{margin:8px;}pre{font-family:monospace;font-size:12px;white-space:pre-wrap;word-break:break-all;}</style>" +
            "<pre>" + escaped + "</pre>"
        webview.visibility = View.VISIBLE
        webview.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }
}
