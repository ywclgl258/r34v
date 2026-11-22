package com.r34v.r34v

import androidx.core.net.toUri

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
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
    private var status by mutableStateOf("正在打开 forums.e-hentai.org…")
    private var browser: WebView? = null
    private var cookies: CookieManager? = null
    private var openedTarget = false
    private var verifying by mutableStateOf(false)
    private var verificationMessage = false
    /** 只有全新进入时才清空临时 Cookie；Activity 重建时保留正在进行的登录会话。 */
    private var freshEntry = true

    @SuppressLint("SetJavaScriptEnabled") // Required by the official login and browser challenge; no JS bridge is exposed.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = SiteRegistry.byId(intent.getStringExtra("site").orEmpty())?.takeIf { it.id == "ehentai" || it.id == "exhentai" } ?: run { finish(); return }
        // 系统回收重建时不能清 Cookie：那样正在进行的论坛登录与目标站权限验证会全部作废。
        freshEntry = savedInstanceState == null
        openedTarget = savedInstanceState?.getBoolean("openedTarget") ?: false
        verificationMessage = savedInstanceState?.getBoolean("verificationMessage") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { R34Theme {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) { IconAction(R.drawable.ic_back, "取消登录") { finish() }; Title("${site.name} 官方登录") }
                Copy("在官方论坛完成登录后，应用会验证本站权限。ExHentai 仍要求账号具备访问资格。", Modifier.padding(16.dp))
                Copy(status, Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                    Action("验证登录", enabled = !verifying, primary = true, click = ::completeLogin)
                    TextAction({ openedTarget = false; verificationMessage = false; cookies?.removeAllCookies { browser?.loadUrl(EhLogin.officialUrl) } }, enabled = !verifying) { Text("重新登录") }
                    TextAction({ runCatching { startActivity(Intent(Intent.ACTION_VIEW, EhLogin.officialUrl.toUri())) }.onFailure { toast("没有可用浏览器，请更新系统 WebView 或导入 Cookie") } }) { Text("用浏览器打开") }
                }
                HorizontalDivider(color = RentalEdge)
                AndroidView(factory = { context -> FrameLayout(context).apply { createBrowser()?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) } } }, modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { releaseBrowser() })
            }
        } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { val web = browser; if (web?.canGoBack() == true) web.goBack() else finish() }
        })
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser(): WebView? {
        try {
            browser = WebView(this).apply {
                // 保留 WebView 自身状态：重建后回到同一登录页，而不是从空白开始。
                isSaveEnabled = true
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame && !EhLogin.allowed(request.url.toString())
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!EhLogin.allowed(url)) return
                        if (EhLogin.hasIdentity(collected()) && (!openedTarget || url.toUri().host == site.host)) completeLogin()
                        // A late forum callback must not replace a permission error or verification status.
                        else if (!openedTarget && !verificationMessage && !status.contains("HTTP")) status = "请在官方页面登录，然后点击“验证登录”"
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && !verificationMessage) status = "官方页面返回 HTTP ${response.statusCode}。可重试，或用浏览器登录后在站点设置中导入 Cookie。"
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && !verificationMessage) status = "官方页面暂时无法加载，请检查系统代理或用浏览器打开。"
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                        handler.cancel(); if (!verificationMessage) status = "官方页面证书验证失败，已停止连接。"
                    }
                }
            }
            cookies = CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(browser!!, false) }
            if (freshEntry) cookies!!.removeAllCookies { browser?.loadUrl(EhLogin.officialUrl) }
            else browser?.loadUrl(EhLogin.officialUrl)
        } catch (_: RuntimeException) {
            status = "系统 WebView 不可用，请更新 WebView，或用浏览器完成官方登录后导入 Cookie。"
        }
        return browser
    }
    private fun collected() = EhLogin.credentials(EhLogin.cookieOrigins.map { cookies?.getCookie(it) })

    private fun completeLogin() {
        if (verifying || isFinishing || isDestroyed) return
        val values = collected()
        if (!EhLogin.hasIdentity(values)) { verificationMessage = true; status = "尚未取得登录会话，请先在官方页面完成登录。"; return }
        verificationMessage = false
        if (!openedTarget) {
            openedTarget = true
            status = "正在检查 ${site.name} 访问权限…"
            val identity = values.filterKeys { it == "ipb_member_id" || it == "ipb_pass_hash" }
            var pending = identity.size
            identity.forEach { (name, value) -> cookies!!.setCookie(site.base, "$name=$value; Path=/; Secure; HttpOnly") {
                pending--; if (pending == 0) browser?.loadUrl("${site.base}/")
            } }
            return
        }
        verifying = true; status = "正在验证 ${site.name} 会话…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { AdapterRegistry.adapter(site, app.network).importSession(values.entries.joinToString("; ") { "${it.key}=${it.value}" }) }
                setResult(RESULT_OK); finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                status = if (error is GalleryLoginRequiredException) "官方登录已完成，但会话无效或此账号尚无 ${site.name} 访问权限。可重新登录或导入有效 Cookie。"
                    else error.message ?: "会话验证失败，请重试"
            } finally { verifying = false }
        }
    }
    private fun releaseBrowser() {
        browser?.apply { stopLoading(); (parent as? android.view.ViewGroup)?.removeView(this); clearHistory(); clearCache(true); destroy() }; browser = null
        // Cookie 只在 Activity 真正结束时清；配置变更/系统的 onRelease 不能打断正在进行的登录。
        if (isFinishing) {
            val manager = cookies; cookies = null
            manager?.removeAllCookies { manager.flush() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("openedTarget", openedTarget)
        outState.putBoolean("verificationMessage", verificationMessage)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { releaseBrowser(); super.onDestroy() }
}
