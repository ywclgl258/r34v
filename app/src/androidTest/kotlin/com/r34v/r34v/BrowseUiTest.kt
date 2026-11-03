@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Real native navigation against isolated neutral network fixtures; never shipped in the app. */
@RunWith(AndroidJUnit4::class)
class BrowseUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as R34Application
    private val titles = listOf("夜色之下 · 城市漫游", "山海之间", "沿着海岸线", "落日之后", "远方的灯火", "穿过旷野")
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun activity(): ComponentActivity {
        var result: Activity? = null
        ins.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        return result as? ComponentActivity ?: error("No resumed activity")
    }
    private fun waitUi(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 18000
        while (System.currentTimeMillis() < deadline) {
            var pass = false; ins.runOnMainSync { pass = condition() }
            if (pass) { ins.waitForIdleSync(); return }; Thread.sleep(100)
        }
        error(message)
    }
    private fun hasText(activity: Activity, value: String) = nodes(activity.window.decorView).any { it is TextView && it.text.toString() == value && it.isShown }
    private fun click(description: String) {
        val a = activity()
        var clicked = false
        ins.runOnMainSync {
            val match = nodes(a.window.decorView).firstOrNull { it.contentDescription?.toString() == description && it.isShown && it.isClickable }
                ?: nodes(a.window.decorView).firstOrNull { it is TextView && it.text.toString() == description && it.isShown && it.isClickable }
            clicked = match?.performClick() == true
        }
        if (!clicked) {
            val root = ins.uiAutomation.rootInActiveWindow ?: error("No active window")
            var node = root.findAccessibilityNodeInfosByText(description).firstOrNull { it.text?.toString() == description } ?: error("Missing clickable: $description")
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        ins.waitForIdleSync(); Thread.sleep(240)
    }
    private fun shot(name: String) {
        Thread.sleep(350); ins.waitForIdleSync()
        val suffix = if (ins.targetContext.resources.configuration.fontScale >= 1.9f) "-200" else ""
        val dir = File(ins.targetContext.getExternalFilesDir(null), "ui-shots").apply { mkdirs() }
        val bitmap = ins.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(dir, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun cards() = titles.mapIndexed { i, title -> """<div data-video-card-id="${900001+i}"><a class="th" href="/video/${900001+i}/sample/"><img src="https://ui-fixture.invalid/cover-$i.png"><span class="thumb_title">$title</span></a><span class="time">12:48</span><span class="video-views-count">8.2万次观看</span><span class="rating">98%</span></div>""" }.joinToString("")
    private fun cover(index: Int): ByteArray {
        val b = Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val warm = index % 3 == 1
        p.shader = LinearGradient(0f,0f,960f,540f,if(warm) 0xffb88d66.toInt() else 0xff253f58.toInt(),if(warm) 0xff354856.toInt() else 0xff090e20.toInt(),Shader.TileMode.CLAMP); c.drawRect(0f,0f,960f,540f,p); p.shader=null
        p.color = if(warm) 0xffe4b585.toInt() else 0xffc5bfad.toInt(); c.drawCircle(670f,160f,if(warm) 56f else 30f,p)
        if (index % 3 == 0) {
            for(i in 0..19) {
                val x=i*53f; val top=150f+(i*71%180); p.color=0xff101d2b.toInt(); c.drawRect(x,top,x+47,540f,p)
                for(j in 0..5) { p.color=if((i+j)%3==0) 0xffd7ae76.toInt() else 0xff274459.toInt(); c.drawRect(x+9,top+j*38+14,x+15,top+j*38+24,p); c.drawRect(x+28,top+j*38+14,x+34,top+j*38+24,p) }
            }
        } else {
            for(layer in 0..2) { p.color=listOf(0xff586c72.toInt(),0xff354a53.toInt(),0xff152c39.toInt())[layer]; val path=Path(); path.moveTo(0f,540f); path.lineTo(0f,350f+layer*45); for(i in 0..8) path.lineTo(i*140f,240f+layer*60+(i*89%125)); path.lineTo(960f,540f); path.close(); c.drawPath(path,p) }
        }
        p.shader=LinearGradient(0f,330f,0f,540f,Color.TRANSPARENT,0x66000000,Shader.TileMode.CLAMP); c.drawRect(0f,0f,960f,540f,p)
        return ByteArrayOutputStream().apply { b.compress(Bitmap.CompressFormat.PNG,100,this); b.recycle() }.toByteArray()
    }
    @Test fun browseSearchLibraryAndPlayback() {
        val store=app.library
        val keys=listOf("watch_history_v1","favorites_v1","native_search_history","settings_v1_blocked_tags","settings_v1_native_speed","settings_v1_quality")
        val original=keys.associateWith { store.prefs.getString(it,null) }
        val originalFlags=listOf("auto_fullscreen","auto_download").associateWith { if(store.prefs.contains("settings_v1_$it")) store.bool(it) else null }
        val originalCache=app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        store.set("auto_fullscreen",false); store.set("auto_download",false); store.set("native_speed","1.0"); store.set("quality","720p")
        val field=Network::class.java.getDeclaredField("current").apply { isAccessible=true }
        val oldClient=app.network.client
        val clip=ins.context.assets.open("test-pattern.mp4").use { it.readBytes() }
        val covers=(0..5).map(::cover)
        val listing="<div class='thumbs'>${cards()}</div><button class='page-of'>Page 1 of 1</button>"
        val detail="""<h1>${titles.first()}</h1><script>var flashvars={video_id:'900001',video_title:'${titles.first()}',video_url:'https://ui-fixture.invalid/movie.mp4',video_url_text:'720p',preview_url:'https://ui-fixture.invalid/cover-0.png'};</script><span class='item_info'>12:48</span><div class='vp-desc'>走进夜色，沿街灯与天际线缓缓前行。用一段安静的影像，记录城市的另一面。</div><h3 class='vp-group__title'>Tags</h3><div><a href='/tags/city/'>城市</a><a href='/tags/night/'>夜景</a></div><div id='custom_list_videos_related_videos_items'>${cards()}</div>"""
        val client=oldClient.newBuilder().addInterceptor { chain ->
            val request=chain.request(); val path=request.url.encodedPath
            val (body,type)=when {
                path.endsWith(".png") -> covers[(Regex("cover-(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0)%6] to "image/png"
                path.endsWith(".mp4") -> clip to "video/mp4"
                path.startsWith("/video/") -> detail.toByteArray() to "text/html; charset=utf-8"
                else -> listing.toByteArray() to "text/html; charset=utf-8"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("UI fixture").body(body.toResponseBody(type.toMediaType())).build()
        }.build()
        field.set(app.network,client)
        store.prefs.edit().putString("watch_history_v1",store.gson.toJson(listOf(Watch("900002",titles[1],"https://ui-fixture.invalid/cover-1.png","/video/900002/sample/",180,768),Watch("900003",titles[2],"https://ui-fixture.invalid/cover-2.png","/video/900003/sample/",240,768))))
            .putString("favorites_v1","[]").putString("native_search_history",store.gson.toJson(listOf("城市","风景","旅行"))).putString("settings_v1_blocked_tags","[]").commit()
        try {
            val main=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitUi("Home did not load") { hasText(main,"今日精选") }
            shot("home")
            ins.runOnMainSync { nodes(main.window.decorView).filterIsInstance<ScrollView>().first().fullScroll(View.FOCUS_DOWN) }; shot("home-shelves")
            ins.runOnMainSync { nodes(main.window.decorView).filterIsInstance<ScrollView>().first().fullScroll(View.FOCUS_UP) }
            click("＋ 收藏"); assertTrue(store.isFavorite("video","900001"))
            Thread.sleep(2200); click("发现"); shot("discover")
            click("搜索")
            ins.runOnMainSync { nodes(main.window.decorView).filterIsInstance<EditText>().single().setText("night city") }
            click("作者"); shot("search")
            click("提交搜索")
            waitUi("Author search did not open") { hasText(main,"night-city") }
            ins.runOnMainSync { main.onBackPressedDispatcher.onBackPressed() }; ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals("night city",nodes(main.window.decorView).filterIsInstance<EditText>().single().text.toString())
                assertTrue(nodes(main.window.decorView).any { it is Button && it.text=="作者" && it.isSelected })
            }
            click("片库"); click("收藏"); shot("library")
            click("${titles.first()} 的更多操作"); click("取消收藏"); assertFalse(store.isFavorite("video","900001"))
            click("下载"); shot("downloads")
            click("设置"); shot("settings")
            click("首页")
            waitUi("Home did not reload") { hasText(main,"今日精选") }
            click("▶  播放")
            var playerActivity: PlayerActivity?=null
            val engineField=PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible=true }
            val deadline=System.currentTimeMillis()+15000
            while(System.currentTimeMillis()<deadline) {
                playerActivity=activity() as? PlayerActivity
                var ready=false; ins.runOnMainSync { ready=playerActivity?.let { (engineField.get(it) as? ExoPlayer)?.playbackState==Player.STATE_READY } ?: false }
                if(ready) break; Thread.sleep(100)
            }
            val player=playerActivity ?: error("Player was not opened")
            ins.runOnMainSync { val engine=engineField.get(player) as ExoPlayer; assertEquals(Player.STATE_READY,engine.playbackState); engine.pause() }
            shot("player")
            click("1.0×"); ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ins.waitForIdleSync()
            ins.runOnMainSync { player.finish() }
        } finally {
            ins.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() } }
            field.set(app.network,oldClient)
            store.prefs.edit().apply {
                original.forEach { (key,value) -> if(value==null) remove(key) else putString(key,value) }
                originalFlags.forEach { (name,value) -> if(value==null) remove("settings_v1_$name") else putBoolean("settings_v1_$name",value) }
            }.commit()
            app.network.feedCacheDirectory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            originalCache.forEach { (name,bytes) -> File(app.network.feedCacheDirectory,name).writeBytes(bytes) }
        }
    }
}
