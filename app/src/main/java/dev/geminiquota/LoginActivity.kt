package dev.geminiquota

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.geminiquota.data.ConnectResult
import dev.geminiquota.data.CookieUtils
import dev.geminiquota.data.GeminiUsageClient
import dev.geminiquota.databinding.ActivityLoginBinding
import dev.geminiquota.ui.CookiePasteDialog
import dev.geminiquota.work.RefreshScheduler
import kotlinx.coroutines.launch

/**
 * In-app Google sign-in. Google sometimes blocks embedded WebViews, so a
 * cookie-paste fallback lives in the overflow menu.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var connecting = false

    private val repository get() = (application as GeminiQuotaApp).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.status.text = getString(R.string.login_blocked)
        binding.pasteInstead.setOnClickListener { openPasteDialog() }

        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        configureWebView()
        binding.webView.loadUrl(USAGE_URL)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_login, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_paste_cookies -> {
            openPasteDialog()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun openPasteDialog() {
        CookiePasteDialog.show(this) {
            setResult(RESULT_OK)
            finish()
        }
    }

    // Gemini's sign-in page requires JavaScript; there is deliberately no JS bridge.
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = binding.webView
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = GeminiUsageClient.DESKTOP_USER_AGENT
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progress.isVisible = false
                if (url?.contains("signin/rejected", ignoreCase = true) == true) {
                    showBlocked()
                    return
                }
                maybeConnect()
                if (url?.startsWith("https://accounts.google.com") == true) {
                    detectRejection(view)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.progress.isVisible = false
                    binding.status.text = getString(R.string.error_network)
                }
            }
        }
    }

    private fun maybeConnect() {
        if (connecting) return
        val cookies = CookieManager.getInstance().getCookie(USAGE_URL) ?: return
        if (!CookieUtils.looksLikeGeminiSession(cookies)) return

        connecting = true
        binding.progress.isVisible = true
        binding.status.text = getString(R.string.login_verifying)
        lifecycleScope.launch {
            when (repository.connect(cookies)) {
                is ConnectResult.Success -> {
                    RefreshScheduler.ensurePeriodic(this@LoginActivity)
                    setResult(RESULT_OK)
                    finish()
                }

                is ConnectResult.Failure -> {
                    connecting = false
                    binding.progress.isVisible = false
                    binding.status.text = getString(R.string.login_failed)
                }
            }
        }
    }

    /**
     * Google answers with a "browser or app may not be secure" page in WebViews.
     * Detect it so the cookie-import path is suggested right where it is needed.
     */
    private fun detectRejection(view: WebView?) {
        view?.evaluateJavascript(REJECTION_PROBE) { result ->
            if (result == "true") showBlocked()
        }
    }

    private fun showBlocked() {
        binding.status.text = getString(R.string.login_blocked_detected)
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val USAGE_URL = "https://gemini.google.com/usage"

        /** Looks for Google's WebView rejection page without reading any cookie data. */
        const val REJECTION_PROBE = "(function(){" +
            "var t=(document.body?document.body.innerText:'').toLowerCase();" +
            "return t.indexOf('may not be secure')>=0" +
            "||(t.indexOf('couldn')>=0&&t.indexOf('sign you in')>=0);" +
            "})()"
    }
}
