package com.pulse.player

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.pulse.player.databinding.ActivityCloudflareCoverAuthBinding

/** Uses Cloudflare Turnstile in an isolated WebView to authorize this device's cover uploads. */
class CloudflareCoverAuthActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCloudflareCoverAuthBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppPreferences.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityCloudflareCoverAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.verificationWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.verificationWebView, true)
        binding.verificationWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleCallback(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleCallback(Uri.parse(url))
        }
        binding.verificationWebView.loadUrl("https://pulse-cover-upload.penguinvok.workers.dev/authorize")
    }

    private fun handleCallback(uri: Uri): Boolean {
        if (uri.scheme != "pulse" || uri.host != "cloudflare-authorized") return false
        val session = uri.getQueryParameter("session").orEmpty()
        if (session.length in 20..2048) {
            AppPreferences.setCloudflareCoverSession(this, session)
            setResult(RESULT_OK)
            finish()
        }
        return true
    }

    override fun onDestroy() {
        binding.verificationWebView.destroy()
        super.onDestroy()
    }
}
