package com.joexblair.bosevolume

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.joexblair.bosevolume.core.BoseAuth
import kotlinx.coroutines.launch

/**
 * Bose account sign-in. Loads Bose's own sign-in page (Azure AD B2C) and catches the
 * `bosemusic://auth/callback?code=...` redirect that the Bose Music app would normally receive.
 */
class LoginActivity : ComponentActivity() {
    private val pkce = BoseAuth.Pkce.generate()
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().removeAllCookies(null)
        val auth = Bose.auth(this)
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (!url.toString().startsWith(BoseAuth.REDIRECT_URI)) return false
                    handleRedirect(url)
                    return true
                }
            }
        }
        setContentView(web)
        web.loadUrl(auth.authorizeUrl(pkce).toString())
    }

    private fun handleRedirect(url: Uri) {
        if (finishing) return
        finishing = true
        val code = url.getQueryParameter("code")
        if (code == null) {
            val error = url.getQueryParameter("error_description") ?: url.getQueryParameter("error") ?: "cancelled"
            Toast.makeText(this, "Sign-in failed: $error", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                Bose.auth(this@LoginActivity).completeSignIn(code, pkce)
                TokenHeartbeatWorker.schedule(this@LoginActivity)
                setResult(RESULT_OK)
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            VolumeWidgetProvider.requestRefresh(this@LoginActivity)
            finish()
        }
    }
}
