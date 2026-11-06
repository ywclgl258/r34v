package com.r34v.r34v

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.GalleryEpicProfile
import com.r34v.r34v.data.adapter.SiteAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class GalleryEpicRoutingUiTest : ComposeUiCase() {
    private fun installDirectoryFixture(f: ComposeFixture): ConcurrentLinkedQueue<String> {
        val site = SiteRegistry.GALLERYEPIC
        val profile = GalleryEpicProfile()
        val field = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = field.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        val neutral = adapters.getValue(site.id)
        val indexes = ConcurrentLinkedQueue<String>()
        adapters[site.id] = object : SiteAdapter by neutral {
            override fun resolve(request: FeedRequest) = profile.resolve(site, request)
            override fun linkRequest(url: String) = profile.linkRequest(site, url)
            override fun indexSection(request: FeedRequest) = profile.indexSection(request)
            override fun feedTitle(request: FeedRequest) = profile.title(site, request)
            override suspend fun index(section: String): List<Link> {
                indexes.add(section)
                return when {
                    section == "series:108" -> listOf(Link("691", "中性角色", "${site.base}/en/character/691"))
                    section.startsWith("cosers:") -> listOf(Link("291", "中性 Coser", "${site.base}/en/coser/291/1")) +
                        if (section.startsWith("cosers:1:")) listOf(Link("page:2", "第 2 页", "${site.base}/en/cosers/2?coserName=sample%3Amodel")) else emptyList()
                    else -> error("Unexpected directory: $section")
                }
            }
        }
        return indexes
    }

    @Test fun incomingSeriesLinkOpensCharacterDirectoryThenCharacterFeed() {
        ComposeFixture().use { f ->
            val indexes = installDirectoryFixture(f)
            f.ins.startActivitySync(Intent(f.ins.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("link", "${SiteRegistry.GALLERYEPIC.base}/en/parody/108"))
            waitText("中性角色")
            assertEquals(listOf("series:108"), indexes.toList())
            assertTrue("A directory must not request a media feed", f.requests.isEmpty())
            ui.onAllNodesWithText("订阅").assertCountEquals(0)
            ui.onNodeWithText("中性角色").performClick()
            ui.waitUntil(15000) { f.requests.any { it.first == "galleryepic" && it.second.kind == FeedKind.CHARACTER && it.second.target == "691" } }
            waitText("角色写真")
            assertTrue(f.requests.all { it.second.kind == FeedKind.CHARACTER })
        }
    }

    @Test fun searchOpensPagedCoserDirectoryThenModelFeed() {
        ComposeFixture().use { f ->
            val indexes = installDirectoryFixture(f)
            f.app.library.switchSite("galleryepic")
            f.launch(); openSourceHome(); waitText("最新写真集")
            ui.onNodeWithContentDescription("搜索内容").performClick()
            ui.onNode(hasSetTextAction()).performTextInput("sample:model")
            ui.onNodeWithContentDescription("提交搜索").performClick()
            waitText("中性 Coser")
            assertTrue(indexes.contains("cosers:1:sample:model"))
            ui.onAllNodesWithText("订阅").assertCountEquals(0)
            ui.onNodeWithText("第 2 页").performClick()
            ui.waitUntil(15000) { indexes.contains("cosers:2:sample:model") }
            waitText("中性 Coser")
            assertTrue(f.requests.none { it.second.kind == FeedKind.SEARCH })
            ui.onNodeWithText("中性 Coser").performClick()
            ui.waitUntil(15000) { f.requests.any { it.first == "galleryepic" && it.second.kind == FeedKind.MODEL && it.second.target == "291" } }
            waitText("模特写真")
            assertTrue(f.requests.none { it.second.kind in listOf(FeedKind.SEARCH, FeedKind.CATEGORY, FeedKind.TAG) })
        }
    }
}
