package com.r34v.r34v

import androidx.compose.ui.test.*
import com.r34v.r34v.data.Favorite
import com.r34v.r34v.data.MediaKind
import com.r34v.r34v.data.PersonalLibrary
import com.r34v.r34v.data.PersonalStore
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.ShelfEntry
import com.r34v.r34v.data.entryKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.List

@RunWith(AndroidJUnit4::class)
class FavoriteCollectionUiTest : ComposeUiCase() {
    @Test fun selectedFavoritesAppendToTheExistingShelfWithoutDuplicates() = ComposeFixture().use { f ->
        val store = f.app.library.personal
        store.shelves().filterNot { it.id == PersonalLibrary.WATCH_LATER }.forEach { store.removeShelf(it.id) }
        val site = SiteRegistry.IWARA
        val shared = f.video(site.id)
        val video = Favorite("video", shared.id, "视频多选", shared.url, shared.thumb, site = site.id)
        val gallery = Favorite("gallery", shared.id, "图库多选", shared.url, shared.thumb, site = site.id)
        val other = Favorite("gallery", "second", "另一漫画", shared.url, shared.thumb, site = SiteRegistry.EHENTAI.id)
        f.app.library.prefs.edit().putString("${site.id}:favorites_v1", f.app.library.gson.toJson(listOf(video, gallery))).commit()
        f.app.library.prefs.edit().putString("${other.site}:favorites_v1", f.app.library.gson.toJson(listOf(other))).commit()
        assertTrue(f.app.library.isFavorite("video", shared.id, site.id))
        assertTrue(f.app.library.isFavorite("gallery", shared.id, site.id))
        assertTrue(f.app.library.isFavorite("gallery", other.target, other.site))
        val shelf = store.createShelf("本站待看")
        val existingVideo = shared.copy(id = "existing-${shared.id}", title = "已在片单中的视频")
        store.add(existingVideo, shelf)
        val existing = store.shelves().single { it.id == shelf }.entries.single().copy(completed = true)
        store.complete(existing.video, shelf, true)

        f.app.library.switchSite(site.id)
        f.launch(); waitHome(); clickText("发现"); clickText("本站功能")
        ui.onNodeWithTag("site-functions-${site.id}").performScrollToNode(hasText("本站收藏") and hasClickAction())
        clickText("本站收藏")
        clickText("多选")
        val list = ui.onNodeWithTag("favorites-list")
        list.performScrollToNode(hasTestTag("favorite-${video.key}"))
        ui.onNodeWithTag("favorite-${video.key}").assertIsOn()
        list.performScrollToNode(hasTestTag("favorite-${gallery.key}"))
        ui.onNodeWithTag("favorite-${gallery.key}").assertIsOn().performClick().assertIsOff()
        ui.onNodeWithText("1 项").assertIsDisplayed()
        clickText("加入片单")
        ui.onAllNodes(hasText("本站待看") and hasAnyAncestor(isDialog()) and hasClickAction()).onFirst().performClick()
        ui.onNodeWithText("已加入片单").assertIsDisplayed()
        val final = store.shelves().single { it.id == shelf }
        assertEquals(store.shelves().joinToString { "${it.name}:${it.entries.size}" }, 2, final.entries.size)
        assertEquals(existing.video, final.entries.first().video)
        assertEquals(existing.completed, final.entries.first().completed)
        assertEquals(existing.addedAt, final.entries.first().addedAt)
        assertEquals("iwara:${shared.id}", final.entries.last().video.entryKey)
        assertEquals(MediaKind.VIDEO, final.entries.last().video.mediaKind)
        assertEquals(listOf(video, gallery, other).map { it.key }.toSet(),
            listOf(video, gallery, other).map { requireNotNull(it.takeIf { favorite -> f.app.library.isFavorite(favorite.kind, favorite.target, favorite.site) }).key }.toSet())
        f.shot("favorites-multi-select")
    }
}
