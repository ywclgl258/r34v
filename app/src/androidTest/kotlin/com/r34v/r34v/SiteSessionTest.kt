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
    @Test fun legacySessionUsesTheRequestedSiteBeforeAndAfterReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "legacy-session-${System.nanoTime()}"
        val originalSite = SiteRegistry.current
        try {
            val store = LibraryStore(context, name, "$name-legacy")
            store.prefs.edit().putString("r34v:auth_session_v1", "{\"c\":{\"PHPSESSID\":\"r34-legacy\"}}")
                .putString("hanime1:auth_session_v1", "{\"c\":{\"PHPSESSID\":\"other-site\"}}").commit()
            store.switchSite(SiteRegistry.HANIME1.id)
            val jar = SessionCookies(store)
            val url = SiteRegistry.R34V.base.toHttpUrl()
            assertEquals("r34-legacy", jar.loadForRequest(url).single { it.name == "PHPSESSID" }.value)
            jar.saveFromResponse(url, listOf(Cookie.Builder().name("extra").value("1").hostOnlyDomain(url.host).build()))
            val restored = SessionCookies(LibraryStore(context, name, "$name-legacy"))
            assertEquals("r34-legacy", restored.loadForRequest(url).single { it.name == "PHPSESSID" }.value)
            restored.clear(SiteRegistry.R34V)
            assertTrue(SessionCookies(LibraryStore(context, name, "$name-legacy")).loadForRequest(url).isEmpty())
            assertEquals(SiteRegistry.HANIME1.id, store.siteId)
        } finally {
            SiteRegistry.current = originalSite
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-legacy")
        }
    }
    @Test fun domainAndHostOnlyCookiesRestoreWithTheirOriginalScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mirror-session-${System.nanoTime()}"
        val legacyName = "$name-legacy"
        val site = SiteRegistry.MISSAV
        val mirror = "https://www.missav.media/account".toHttpUrl()
        val bareMirror = "https://missav.media/account".toHttpUrl()
        try {
            val store = LibraryStore(context, name, legacyName)
            store.prefs.edit().putStringSet("${site.id}:cookies", setOf(
                "legacyPrimary=1; Path=/; Secure",
                "legacyDomain=1; DOMAIN=.missav.media; Path=/account; Secure",
                "d\u0001" + "markedDomain=1; Domain=missav.media; Path=/account; Secure",
                "h\u0001" + "cdn.missav.media\u0001" + "unregisteredHost=1; Path=/",
                "h\u0001" + "www.missav.media\u0001" + "mislabelled=1; Domain=missav.media; Path=/",
                "outsideDomain=1; Domain=other.example; Path=/",
            )).commit()
            val jar = SessionCookies(store)
            assertEquals(setOf("legacyDomain", "markedDomain"), jar.loadForRequest(mirror).map { it.name }.toSet())
            jar.saveFromResponse(mirror, listOf(
                requireNotNull(Cookie.parse(mirror, "domain=1; Domain=missav.media; Path=/account; Secure; HttpOnly")),
                requireNotNull(Cookie.parse(mirror, "hostOnly=1; Path=/account; Secure")),
            ))
            val restored = SessionCookies(LibraryStore(context, name, legacyName))
            assertEquals(setOf("legacyDomain", "markedDomain", "domain", "hostOnly"), restored.loadForRequest(mirror).map { it.name }.toSet())
            val domain = restored.loadForRequest(bareMirror).single { it.name == "domain" }
            assertFalse(domain.hostOnly)
            assertEquals("missav.media", domain.domain)
            assertTrue(domain.secure && domain.httpOnly)
            assertEquals(setOf("legacyDomain", "markedDomain", "domain"), restored.loadForRequest(bareMirror).map { it.name }.toSet())
            assertEquals(listOf("legacyPrimary"), restored.loadForRequest(site.base.toHttpUrl()).map { it.name })
            assertTrue(restored.loadForRequest("https://cdn.missav.media/account".toHttpUrl()).isEmpty())
            assertTrue(restored.loadForRequest("http://www.missav.media/account".toHttpUrl()).isEmpty())
            assertTrue(restored.loadForRequest("https://www.missav.media/other".toHttpUrl()).isEmpty())
        } finally {
            context.deleteSharedPreferences(name); context.deleteSharedPreferences(legacyName)
        }
    }

    @Test fun sessionsSurviveReloadAndLogoutOnlyClearsTheSelectedSite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "site-session-test-${System.nanoTime()}"
        val legacyName = "$name-legacy"
        val originalSite = SiteRegistry.current
        try {
            val store = LibraryStore(context, name, legacyName)
            val jar = SessionCookies(store)
            for (site in SiteRegistry.all) {
                val url = site.base.toHttpUrl()
                jar.saveFromResponse(url, listOf(Cookie.Builder()
                    .name("session").value("${site.id}-session").hostOnlyDomain(url.host)
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
            // Iwara's www host issues the cookie, and its registered bare domain must not receive it.
            val iwaraBare = SiteRegistry.IWARA.base.toHttpUrl().newBuilder().host(SiteRegistry.IWARA.host).build()
            assertTrue(restored.loadForRequest(iwaraBare).isEmpty())
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
