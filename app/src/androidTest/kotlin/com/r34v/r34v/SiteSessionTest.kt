package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.SessionCookies
import com.r34v.r34v.data.SiteRegistry
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteSessionTest {
    @Test fun sessionsSurviveReloadAndLogoutOnlyClearsTheSelectedSite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "site-session-test-${System.nanoTime()}"
        val legacyName = "$name-legacy"
        val originalSite = SiteRegistry.current
        try {
            val store = LibraryStore(context, name, legacyName)
            val jar = SessionCookies(store)
            for (site in SiteRegistry.all) {
                jar.saveFromResponse(site.base.toHttpUrl(), listOf(Cookie.Builder()
                    .name("session").value("${site.id}-session").hostOnlyDomain(site.host)
                    .secure().httpOnly().expiresAt(System.currentTimeMillis() + 60000).build()))
            }
            store.switchSite(SiteRegistry.HANIME1.id)
            // Recreate both storage and the jar: checking the same in-memory bucket misses a bad key.
            val restoredStore = LibraryStore(context, name, legacyName)
            assertEquals(SiteRegistry.HANIME1.id, restoredStore.siteId)
            val restored = SessionCookies(restoredStore)
            for (site in SiteRegistry.all) {
                assertEquals(listOf("${site.id}-session"), restored.loadForRequest(site.base.toHttpUrl()).filter { it.name == "session" }.map { it.value })
            }
            assertTrue(restored.loadForRequest("https://cdn.invalid/video.mp4".toHttpUrl()).isEmpty())
            restored.clear(SiteRegistry.HANIME1)
            val afterLogout = SessionCookies(LibraryStore(context, name, legacyName))
            assertTrue(afterLogout.loadForRequest(SiteRegistry.HANIME1.base.toHttpUrl()).isEmpty())
            assertEquals("r34v-session", afterLogout.loadForRequest(SiteRegistry.R34V.base.toHttpUrl()).single().value)
        } finally {
            SiteRegistry.current = originalSite
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(legacyName)
        }
    }
}
