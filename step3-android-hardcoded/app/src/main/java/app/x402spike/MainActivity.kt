package app.x402spike

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.math.BigInteger

class MainActivity : AppCompatActivity() {

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val payerLabel = findViewById<TextView>(R.id.payerLabel)
        val targetLabel = findViewById<TextView>(R.id.targetLabel)
        val status = findViewById<TextView>(R.id.status)
        val webview = findViewById<WebView>(R.id.webview)
        val getButton = findViewById<Button>(R.id.getButton)
        val payButton = findViewById<Button>(R.id.payButton)

        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.settings.mediaPlaybackRequiresUserGesture = false

        val pkHex = BuildConfig.PRIVATE_KEY
        val url = BuildConfig.DEMO_URL

        if (pkHex.isBlank()) {
            payerLabel.text = "PRIVATE_KEY not set"
            targetLabel.text = ""
            status.text = "Edit step3-android-hardcoded/local.properties, set PRIVATE_KEY=0x..., rebuild."
            getButton.isEnabled = false
            payButton.isEnabled = false
            return
        }

        val credentials = try {
            Credentials.create(ECKeyPair.create(BigInteger(pkHex.removePrefix("0x"), 16)))
        } catch (e: Throwable) {
            payerLabel.text = "PRIVATE_KEY invalid: ${e.message}"
            getButton.isEnabled = false
            payButton.isEnabled = false
            return
        }

        payerLabel.text = "payer:  ${Keys.toChecksumAddress(credentials.address)}"
        targetLabel.text = "target: $url"
        status.text = "Tap GET (no x402) to see what an unaware client sees, or Pay to settle and read."

        getButton.setOnClickListener {
            getButton.isEnabled = false
            payButton.isEnabled = false
            status.text = "GET ${url}  (no payment)..."
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
            getButton.isEnabled = false
            payButton.isEnabled = false
            status.text = "signing and POSTing..."
            webview.visibility = View.GONE
            lifecycleScope.launch {
                when (val result = X402Client.payX402(url, pkHex)) {
                    is PayResult.Success -> {
                        status.text = buildString {
                            append("OK 200  ")
                            append(if (result.basescanUrl.isNotEmpty()) result.basescanUrl else result.txHash)
                        }
                        showHtml(webview, result.body, result.contentType, result.resolvedUrl)
                    }
                    is PayResult.Failure -> {
                        status.text = buildString {
                            appendLine("FAIL: ${result.message}")
                            appendLine("http: ${result.httpStatus ?: "n/a"}")
                            if (!result.body.isNullOrBlank()) {
                                appendLine()
                                append(result.body.take(800))
                            }
                        }
                        webview.visibility = View.GONE
                    }
                }
                getButton.isEnabled = true
                payButton.isEnabled = true
            }
        }
    }

    private fun showRaw(status: TextView, webview: WebView, raw: RawResult) {
        status.text = "GET → HTTP ${raw.status}  (no x402 envelope sent)"
        val sections = buildString {
            appendLine("HTTP ${raw.status}")
            if (raw.contentType.isNotBlank()) {
                appendLine("Content-Type: ${raw.contentType}")
            }
            appendLine()
            if (raw.challenge != null) {
                appendLine("decoded payment-required header:")
                appendLine(pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), raw.challenge))
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
