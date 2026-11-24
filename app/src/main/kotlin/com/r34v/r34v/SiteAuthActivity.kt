package com.r34v.r34v

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.EhLogin
import com.r34v.r34v.data.adapter.GalleryLoginRequiredException
import com.r34v.r34v.ui.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SiteAuthNavigation { ALLOW, BLOCK, FAIL_TARGET }

internal enum class SiteAuthPhase(val canVerify: Boolean = false, val canRestart: Boolean = true) {
    RESETTING(canVerify = false, canRestart = false),
    FORUM(canVerify = true),
    COPYING_IDENTITY,
    LOADING_TARGET,
    TARGET_FAILED(canVerify = true),
    TARGET_READY(canVerify = true),
    VERIFYING(canVerify = false, canRestart = false);

    fun acceptsPage(site: SiteContext, url: String): Boolean {
        if (!EhLogin.allowed(url)) return false
        return when (this) {
            FORUM -> true
            LOADING_TARGET -> url.toHttpUrlOrNull()?.host?.let(site::isSite) == true
            else -> false
        }
    }

    /**
     * Main-frame navigation and redirect policy. Official HTTPS hosts are never bypassed; during
     * [LOADING_TARGET], any off-target redirect (before or after onPageStarted) transitions to
     * [TARGET_FAILED] so the user can retry opening the target or restart back to the forum.
     */
    fun onMainFrameNavigation(site: SiteContext, url: String): SiteAuthNavigation {
        if (!EhLogin.allowed(url)) {
            return if (this == LOADING_TARGET) SiteAuthNavigation.FAIL_TARGET else SiteAuthNavigation.BLOCK
        }
        return when (this) {
            LOADING_TARGET -> if (acceptsPage(site, url)) SiteAuthNavigation.ALLOW else SiteAuthNavigation.FAIL_TARGET
            FORUM -> SiteAuthNavigation.ALLOW
            TARGET_READY -> if (url.toHttpUrlOrNull()?.host?.let(site::isSite) == true) SiteAuthNavigation.ALLOW else SiteAuthNavigation.BLOCK
            RESETTING, COPYING_IDENTITY, TARGET_FAILED, VERIFYING -> SiteAuthNavigation.BLOCK
        }
    }

    /**
     * Whether a main-frame network or HTTP error should be handled in this phase.
     * In [LOADING_TARGET], errors for the target host (or non-official URLs, or after
     * [targetVisitStarted]) fail immediately even before onPageStarted; only stale forum errors
     * prior to [targetVisitStarted] are ignored.
     */
    fun handlesMainFrameError(site: SiteContext, url: String, targetVisitStarted: Boolean): Boolean = when (this) {
        LOADING_TARGET -> acceptsPage(site, url) || targetVisitStarted || !EhLogin.allowed(url)
        FORUM -> acceptsPage(site, url)
        else -> false
    }

    /** Whether a cancelled TLS error belongs to the active main-frame navigation. */
    fun handlesSslError(site: SiteContext, errorUrl: String, currentUrl: String?, targetVisitStarted: Boolean): Boolean = when (this) {
        LOADING_TARGET -> acceptsPage(site, errorUrl) || (targetVisitStarted && errorUrl == currentUrl)
        FORUM -> acceptsPage(site, errorUrl) && (errorUrl == currentUrl || currentUrl.isNullOrBlank() || errorUrl == EhLogin.officialUrl)
        else -> false
    }

    fun failsOnPageStarted(site: SiteContext, url: String): Boolean =
        this == LOADING_TARGET && !acceptsPage(site, url)

    fun afterRecreation() = if (this == VERIFYING) TARGET_READY else this
}

/** Official forum login only. Gallery browsing and reading remain native. */
class SiteAuthActivity : ComponentActivity() {
    private val app get() = application as R34Application
    private lateinit var site: SiteContext
    private var status by mutableStateOf(OPENING_FORUM)
    private var phase by mutableStateOf(SiteAuthPhase.RESETTING)
    private var browserVersion by mutableIntStateOf(0)
    private var browser: WebView? = null
    private var cookies: CookieManager? = null
    private var savedBrowser: Bundle? = null
    private var resumeVerification = false
    private var forumMessage = false
    private var targetVisitStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = SiteRegistry.byId(intent.getStringExtra("site").orEmpty())
            ?.takeIf { it.id == SiteRegistry.EHENTAI.id || it.id == SiteRegistry.EXHENTAI.id }
            ?: run { finish(); return }
        val savedPhase = SiteAuthPhase.entries.firstOrNull { it.name == savedInstanceState?.getString("phase") }
        phase = savedPhase?.afterRecreation() ?: SiteAuthPhase.RESETTING
        resumeVerification = savedPhase == SiteAuthPhase.VERIFYING
        savedBrowser = savedInstanceState?.getBundle("browser")
        status = savedInstanceState?.getString("status") ?: OPENING_FORUM
        forumMessage = savedInstanceState?.getBoolean("forumMessage") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { R34Theme {
            val palette = remember(site) { artworkPalette(site.category) }
            ArtworkColors(palette) {
                Column(Modifier.fillMaxSize().artworkBackground(palette).safeDrawingPadding().imePadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconAction(R.drawable.ic_back, "取消登录") { finish() }
                        Title("${site.name} 官方登录")
                    }
                    Copy("在官方论坛完成登录后，应用会验证本站权限。ExHentai 仍要求账号具备访问资格。", Modifier.padding(16.dp))
                    Copy(status, Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                        Action("验证登录", enabled = phase.canVerify, primary = true, click = ::completeLogin)
                        TextAction(::restart, enabled = phase.canRestart) { Text("重新登录") }
                        TextAction({ runCatching { startActivity(Intent(Intent.ACTION_VIEW, EhLogin.officialUrl.toUri())) }
                            .onFailure { toast("没有可用浏览器，请更新系统 WebView 或导入 Cookie") } }) { Text("用浏览器打开") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    key(browserVersion) {
                        AndroidView(factory = { context -> FrameLayout(context).apply {
                            createBrowser()?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) }
                        } }, modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { frame ->
                            (frame.getChildAt(0) as? WebView)?.let(::releaseBrowser)
                        })
                    }
                }
            }
        } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = browser
                if (phase == SiteAuthPhase.FORUM && web?.canGoBack() == true) web.goBack() else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled") // The official forum needs JavaScript. The app exposes no native bridge.
    private fun createBrowser(): WebView? = try {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (!isCurrent(view)) return true
                    val url = request.url.toString()
                    return when (phase.onMainFrameNavigation(site, url)) {
                        SiteAuthNavigation.ALLOW -> false
                        SiteAuthNavigation.BLOCK -> true
                        SiteAuthNavigation.FAIL_TARGET -> {
                            view.stopLoading()
                            failTarget(if (EhLogin.allowed(url)) "未能打开 ${site.name} 权限验证页，可重试或重新登录。"
                                else "目标页面跳转到非官方地址，已停止加载。可重试或重新登录。")
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (!isCurrent(view) || phase != SiteAuthPhase.LOADING_TARGET) return
                    if (phase.failsOnPageStarted(site, url)) {
                        view.stopLoading()
                        failTarget(if (EhLogin.allowed(url)) "未能打开 ${site.name} 权限验证页，可重试或重新登录。"
                            else "目标页面跳转到非官方地址，已停止加载。可重试或重新登录。")
                    } else targetVisitStarted = true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!isCurrent(view) || view.url?.let { it != url } == true) return
                    when (phase) {
                        SiteAuthPhase.LOADING_TARGET -> {
                            if (!targetVisitStarted) {
                                if (!EhLogin.allowed(url)) failTarget("未能打开 ${site.name} 权限验证页，可重试或重新登录。")
                                return
                            }
                            if (phase.acceptsPage(site, url)) {
                                phase = SiteAuthPhase.TARGET_READY
                                verifyTarget(view)
                            } else failTarget("未能打开 ${site.name} 权限验证页，可重试或重新登录。")
                        }
                        SiteAuthPhase.FORUM -> {
                            if (!phase.acceptsPage(site, url)) return
                            val values = forumCredentials()
                            if (EhLogin.hasIdentity(values)) copyIdentity(view, values)
                            else if (!forumMessage) status = "请在官方页面登录，然后点击“验证登录”"
                        }
                        else -> Unit
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) pageError(view, request.url.toString(), "官方页面返回 HTTP ${response.statusCode}。可重试或重新登录，或用浏览器登录后导入 Cookie。")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) pageError(view, request.url.toString(), "官方页面暂时无法加载，请检查系统代理，或重试、重新登录。")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    if (!isCurrent(view)) return
                    val url = error.url.orEmpty()
                    if (phase.handlesSslError(site, url, view.url, targetVisitStarted)) {
                        pageError(view, url, "官方页面证书验证失败，已停止连接。可重试或重新登录。")
                    }
                }
            }
        }
        browser = web
        cookies = CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, false) }
        restoreOrStart(web)
        web
    } catch (_: RuntimeException) {
        browser?.let(::releaseBrowser)
        phase = SiteAuthPhase.FORUM
        status = "系统 WebView 不可用，请更新 WebView，或用浏览器完成官方登录后导入 Cookie。"
        null
    }

    private fun restoreOrStart(web: WebView) {
        val history = savedBrowser?.takeUnless { phase == SiteAuthPhase.RESETTING }
            ?.let { runCatching { web.restoreState(it) }.getOrNull() }
        savedBrowser = null
        when (phase) {
            SiteAuthPhase.RESETTING -> cookies?.removeAllCookies {
                if (isCurrent(web) && phase == SiteAuthPhase.RESETTING) {
                    phase = SiteAuthPhase.FORUM
                    web.loadUrl(EhLogin.officialUrl)
                }
            }
            SiteAuthPhase.FORUM -> if (history?.currentItem?.url?.let(EhLogin::allowed) != true) web.loadUrl(EhLogin.officialUrl)
            SiteAuthPhase.COPYING_IDENTITY -> copyIdentity(web, forumCredentials())
            SiteAuthPhase.LOADING_TARGET -> openTarget(web)
            SiteAuthPhase.TARGET_FAILED -> Unit
            SiteAuthPhase.TARGET_READY -> {
                if (history?.currentItem == null) web.loadUrl("${site.base}/")
                if (resumeVerification) {
                    resumeVerification = false
                    verifyTarget(web)
                }
            }
            SiteAuthPhase.VERIFYING -> Unit
        }
    }

    private fun isCurrent(web: WebView) = browser === web && !isFinishing && !isDestroyed

    private fun pageError(web: WebView, url: String, message: String) {
        if (!isCurrent(web) || !phase.handlesMainFrameError(site, url, targetVisitStarted)) return
        if (phase == SiteAuthPhase.LOADING_TARGET) failTarget(message)
        else {
            forumMessage = true
            status = message
        }
    }

    private fun failTarget(message: String) {
        targetVisitStarted = false
        phase = SiteAuthPhase.TARGET_FAILED
        status = message
    }

    private fun restart() {
        targetVisitStarted = false
        phase = SiteAuthPhase.RESETTING
        status = OPENING_FORUM
        forumMessage = false
        browserVersion++
    }

    private fun forumCredentials() = EhLogin.credentials(listOf(cookies?.getCookie(site.base), cookies?.getCookie(EhLogin.officialUrl)))

    private fun completeLogin() {
        val web = browser?.takeIf(::isCurrent) ?: return
        when (phase) {
            SiteAuthPhase.FORUM -> copyIdentity(web, forumCredentials())
            SiteAuthPhase.TARGET_FAILED -> retryTarget(web)
            SiteAuthPhase.TARGET_READY -> verifyTarget(web)
            else -> Unit
        }
    }

    private fun retryTarget(web: WebView) {
        val targetValues = EhLogin.credentials(listOf(cookies?.getCookie(site.base)))
        if (EhLogin.hasIdentity(targetValues)) openTarget(web)
        else {
            val forumValues = forumCredentials()
            if (EhLogin.hasIdentity(forumValues)) copyIdentity(web, forumValues)
            else {
                targetVisitStarted = false
                phase = SiteAuthPhase.FORUM
                forumMessage = true
                status = "尚未取得登录会话，请先在官方页面完成登录。"
                web.stopLoading()
                web.loadUrl(EhLogin.officialUrl)
            }
        }
    }

    private fun copyIdentity(web: WebView, values: Map<String, String>) {
        val manager = cookies ?: return
        if (!EhLogin.hasIdentity(values)) {
            phase = SiteAuthPhase.FORUM
            forumMessage = true
            status = "尚未取得登录会话，请先在官方页面完成登录。"
            return
        }
        phase = SiteAuthPhase.COPYING_IDENTITY
        status = "正在检查 ${site.name} 访问权限…"
        val identity = values.filterKeys { it == "ipb_member_id" || it == "ipb_pass_hash" }
        var pending = identity.size
        var writeFailed = false
        identity.forEach { (name, value) ->
            manager.setCookie(site.base, "$name=$value; Path=/; Secure; HttpOnly") { written ->
                if (!isCurrent(web) || phase != SiteAuthPhase.COPYING_IDENTITY) return@setCookie
                writeFailed = writeFailed || !written
                if (--pending == 0) {
                    if (writeFailed) {
                        phase = SiteAuthPhase.FORUM
                        forumMessage = true
                        status = "无法保存临时登录会话，请重试或用浏览器打开。"
                    } else openTarget(web)
                }
            }
        }
    }

    private fun openTarget(web: WebView) {
        targetVisitStarted = false
        phase = SiteAuthPhase.LOADING_TARGET
        status = "正在检查 ${site.name} 访问权限…"
        web.stopLoading()
        web.loadUrl("${site.base}/")
    }

    private fun verifyTarget(web: WebView) {
        val target = web.url?.takeIf { SiteAuthPhase.LOADING_TARGET.acceptsPage(site, it) }
        val values = EhLogin.credentials(listOf(cookies?.getCookie(site.base), target?.let { cookies?.getCookie(it) }))
        if (!EhLogin.hasIdentity(values)) {
            status = "尚未取得 ${site.name} 登录会话，请重新登录。"
            return
        }
        phase = SiteAuthPhase.VERIFYING
        status = "正在验证 ${site.name} 会话…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AdapterRegistry.adapter(site, app.network).importSession(values.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
                if (isCurrent(web)) { setResult(RESULT_OK); finish() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!isCurrent(web)) return@launch
                phase = SiteAuthPhase.TARGET_READY
                status = if (error is GalleryLoginRequiredException) "官方登录已完成，但会话无效或此账号尚无 ${site.name} 访问权限。可重新登录或导入有效 Cookie。"
                    else error.message ?: "会话验证失败，请重试"
            }
        }
    }

    private fun releaseBrowser(web: WebView) {
        if (browser === web) browser = null
        web.stopLoading()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("phase", phase.name)
        outState.putString("status", status)
        outState.putBoolean("forumMessage", forumMessage)
        val state = Bundle()
        if (browser?.saveState(state) != null) outState.putBundle("browser", state)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        browser?.let(::releaseBrowser)
        if (isFinishing) cookies?.let { manager -> manager.removeAllCookies { manager.flush() } }
        cookies = null
        super.onDestroy()
    }

    private companion object { const val OPENING_FORUM = "正在打开 forums.e-hentai.org…" }
}
