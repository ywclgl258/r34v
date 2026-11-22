package com.r34v.r34v

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.SiteAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalPlayerUiTest : ComposeUiCase() {
    @Test fun browserOnlyDetailsOfferABrowserActionWithoutPlaybackOrDownload() = ComposeFixture(awaitUi = { ui.waitForIdle() }).use { f ->
        val source = SiteRegistry.R34V.id
        f.app.library.switchSite(source)
        val label = "在浏览器打开示例影片"
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val original = adapters.getValue(source)
        adapters[source] = object : SiteAdapter by original {
            override suspend fun detail(url: String) = original.detail(url).copy(streams = emptyList(),
                externalPlayers = listOf(Link("browser", label, "https://example.invalid/neutral")))
        }
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }, Instrumentation.ActivityResult(0, null), true)
        f.ins.addMonitor(monitor)
        try {
            val activity = f.launchPlayer()
            waitText(label)
            ui.onAllNodesWithText("下载").assertCountEquals(0)
            ui.onAllNodesWithText("播放失败", substring = true).assertCountEquals(0)
            val player = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            f.ins.runOnMainSync { assertNull(player.get(activity)) }
            ui.onNodeWithText(label).performScrollTo().performClick()
            ui.waitUntil(5000) { monitor.hits > 0 }
            assertEquals(1, monitor.hits)
        } finally { f.ins.removeMonitor(monitor) }
    }
}
