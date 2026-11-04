package com.r34v.r34v

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*

/** Official forum login only. Gallery browsing and reading remain native. */
class SiteAuthActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private lateinit var site: SiteContext
    private lateinit var status: TextView
    private var browser: WebView? = null
    private var cookies: CookieManager? = null
    private var openedTarget = false
    private var verifying = false

    @SuppressLint("SetJavaScriptEnabled") // Required by the official login and browser challenge; no JS bridge is exposed.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = SiteRegistry.byId(intent.getStringExtra("site").orEmpty())?.takeIf { it.id == "ehentai" || it.id == "exhentai" } ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = column().apply { setBackgroundColor(Palette.canvas) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        root.addView(row().apply {
            addView(iconAction(R.drawable.ic_back, "取消登录") { finish() })
            addView(label("${site.name} 官方登录", 17f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(label("在官方论坛完成登录后，应用会验证本站权限。ExHentai 仍要求账号具备访问资格。", 13f, Palette.muted).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) })
        status = label("正在打开 forums.e-hentai.org…", 12f, Palette.muted).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        root.addView(status)
        root.addView(FlowLayout(this).apply {
            addView(quietAction("验证登录") { completeLogin() })
            addView(quietAction("重新登录") { openedTarget = false; cookies?.removeAllCookies { browser?.loadUrl(EhLogin.officialUrl) } })
            addView(quietAction("用浏览器打开") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EhLogin.officialUrl))) }
                    .onFailure { toast("没有可用浏览器，请更新系统 WebView 或导入 Cookie") }
            })
        })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { val web = browser; if (web?.canGoBack() == true) web.goBack() else finish() }
        })
        try {
            browser = WebView(this).apply {
                isSaveEnabled = false
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame && !EhLogin.allowed(request.url.toString())
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!EhLogin.allowed(url)) return
                        if (EhLogin.hasIdentity(collected()) && (!openedTarget || Uri.parse(url).host == site.host)) completeLogin()
                        // A late forum callback must not replace a permission error or verification status.
                        else if (!openedTarget && !status.text.toString().contains("HTTP")) status.text = "请在官方页面登录，然后点击“验证登录”"
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) status.text = "官方页面返回 HTTP ${response.statusCode}。可重试，或用浏览器登录后在站点设置中导入 Cookie。"
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) status.text = "官方页面暂时无法加载，请检查系统代理或用浏览器打开。"
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                        handler.cancel(); status.text = "官方页面证书验证失败，已停止连接。"
                    }
                }
            }
            root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
            cookies = CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(browser!!, false) }
            cookies!!.removeAllCookies { browser?.loadUrl(EhLogin.officialUrl) }
        } catch (_: RuntimeException) {
            status.text = "系统 WebView 不可用，请更新 WebView，或用浏览器完成官方登录后导入 Cookie。"
        }
    }
    private fun collected() = EhLogin.credentials(EhLogin.cookieOrigins.map { cookies?.getCookie(it) })

    private fun completeLogin() {
        if (verifying || isFinishing || isDestroyed) return
        val values = collected()
        if (!EhLogin.hasIdentity(values)) { status.text = "尚未取得登录会话，请先在官方页面完成登录。"; return }
        if (!openedTarget) {
            openedTarget = true
            status.text = "正在检查 ${site.name} 访问权限…"
            val identity = values.filterKeys { it == "ipb_member_id" || it == "ipb_pass_hash" }
            var pending = identity.size
            identity.forEach { (name, value) -> cookies!!.setCookie(site.base, "$name=$value; Path=/; Secure; HttpOnly") {
                pending--; if (pending == 0) browser?.loadUrl("${site.base}/")
            } }
            return
        }
        verifying = true; status.text = "正在验证 ${site.name} 会话…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { AdapterRegistry.adapter(site, app.network).importSession(values.entries.joinToString("; ") { "${it.key}=${it.value}" }) }
                setResult(RESULT_OK); finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                status.text = if (error is GalleryLoginRequiredException) "官方登录已完成，但会话无效或此账号尚无 ${site.name} 访问权限。可重新登录或导入有效 Cookie。"
                    else error.message ?: "会话验证失败，请重试"
            } finally { verifying = false }
        }
    }
    override fun onDestroy() {
        browser?.apply { stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); clearHistory(); clearCache(true); destroy() }; browser = null
        cookies?.removeAllCookies { cookies?.flush() }
        super.onDestroy()
    }
}
