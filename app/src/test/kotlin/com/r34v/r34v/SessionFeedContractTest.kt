package com.r34v.r34v

import com.google.gson.Gson
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.EhLogin
import com.r34v.r34v.data.adapter.IwaraProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class SessionFeedContractTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun galleryTagApiLinksRoundTripWithoutAddingOtherApiRoutes() {
        val site = SiteRegistry.IWARA
        val request = FeedRequest(FeedKind.TAG, "night_walk", Sort.RATING, page = 3,
            includeTags = listOf("city"), contentKind = MediaKind.GALLERY)
        val resolved = IwaraProtocol.resolve(site, request).toHttpUrl()
        assertEquals("/images", resolved.encodedPath)
        for (host in site.apiHosts) {
            val link = resolved.newBuilder().host(host).build().toString()
            assertEquals(request, IwaraProtocol.linkRequest(site, link))
            assertEquals(IncomingLink(site, link), incomingLink("图库 $link"))
        }
        assertEquals(MediaKind.VIDEO, IwaraProtocol.linkRequest(site, "${site.apiBase}/videos?tags=city")?.contentKind)
        assertEquals(MediaKind.GALLERY, IwaraProtocol.linkRequest(site, "${site.base}/images?tags=city")?.contentKind)
        assertNull(SiteRegistry.forHost(resolved.host))
        for (url in listOf(
            "${site.apiBase}/images/private?tags=city", "${site.apiBase}/auth/login?tags=city",
            "${site.apiBase}/subscriptions/videos?tags=city", "${site.apiBase}/images?page=-1&tags=city",
            "https://apiq.iwara.tv:9443/images?tags=city", "https://apiq.iwara.tv.example.invalid/images?tags=city",
            "https://alice@apiq.iwara.tv/images?tags=city", "http://apiq.iwara.tv/images?tags=city",
        )) {
            assertNull(url, IwaraProtocol.linkRequest(site, url))
            assertTrue(url, runCatching { incomingLink(url) }.isFailure)
        }
    }

    @Test fun snapshotsKeepDifferentLocalFiltersCursorsAndMediaSeparate() = runBlocking {
        val root = files.newFolder()
        val snapshots = FeedSnapshots(root, Gson())
        val site = SiteRegistry.IWARA
        val original = FeedRequest(FeedKind.SEARCH, "city")
        val originalPage = FeedPage(listOf(Video(id = "unfiltered")), 1, 2, "city")
        val variants = listOf(
            original.copy(filter = AdvancedFilter(minDurationSec = 60)),
            original.copy(excludeTags = listOf("blocked")),
            original.copy(includeTags = listOf("city")),
            original.copy(cursor = "next-token"),
            original.copy(language = GalleryLanguage.CHINESE),
            original.copy(contentKind = MediaKind.GALLERY),
            original.copy(page = 2),
            original.copy(sort = Sort.RATING),
        )
        val url = { "https://example.invalid/feed" }
        val failure = IOException("offline")
        snapshots.load(site, original, url) { originalPage }
        for ((index, request) in variants.withIndex()) {
            assertSame(failure, runCatching { snapshots.load(site, request, url) { throw failure } }.exceptionOrNull())
            val filtered = originalPage.copy(videos = listOf(Video(id = "variant-$index")))
            snapshots.load(site, request, url) { filtered }
            assertEquals(filtered.copy(cached = true), snapshots.load(site, request, url) { throw failure })
        }
        assertEquals(originalPage.copy(cached = true), snapshots.load(site, original, url) { throw failure })
        assertSame(failure, runCatching { snapshots.load(SiteRegistry.HANIME1, original, url) { throw failure } }.exceptionOrNull())
    }

    @Test fun accountAndSynthesizedPagesIgnoreLegacySnapshotsAndDoNotWrite() = runBlocking {
        val folder = files.newFolder()
        val gson = Gson()
        val snapshots = FeedSnapshots(folder, gson)
        val prior = FeedPage(listOf(Video(id = "previous-account")), 1, 1, "previous")
        val current = FeedPage(listOf(Video(id = "current-account")), 1, 1, "current")
        val failure = GalleryResponseException(429, "0", "limited")
        val cases = listOf(
            SiteRegistry.IWARA to FeedRequest(FeedKind.SUBSCRIPTIONS),
            SiteRegistry.EXHENTAI to FeedRequest(),
            SiteRegistry.R34V to FeedRequest(FeedKind.FOR_YOU),
        )
        for ((site, request) in cases) {
            val url = "${site.base}/fixture"
            val legacyName = MessageDigest.getInstance("SHA-256").digest("${site.id}|$url".toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(folder, legacyName).writeText(gson.toJson(prior))
            val before = folder.listFiles()!!.associate { it.name to it.readText() }
            assertSame(failure, runCatching { snapshots.load(site, request, { url }) { throw failure } }.exceptionOrNull())
            assertEquals(current, snapshots.load(site, request, { url }) { current })
            assertEquals(before, folder.listFiles()!!.associate { it.name to it.readText() })
        }
    }

    @Test fun iwaraPostBodyIsNotRepeatable() {
        val body = SingleUseBody(IwaraProtocol.loginBody("fixture", "sample"))
        assertTrue(body.isOneShot())
        assertEquals(Buffer().apply { IwaraProtocol.loginBody("fixture", "sample").writeTo(this) }.readUtf8(),
            Buffer().apply { body.writeTo(this) }.readUtf8())
    }

    @Test fun subscriptionInterceptorPreservesExistingGenerationTagAndTagsUntaggedRequests() {
        val url = "${SiteRegistry.IWARA.apiBase}/subscriptions/videos?page=0"
        val tagged = Request.Builder().url(url)
            .tag(IwaraSubscriptionRequest::class.java, IwaraSubscriptionRequest(1L)).build()
        val taggedBuilder = tagged.newBuilder()
        bindIwaraSubscriptionRequest(tagged, taggedBuilder, currentEpoch = 5L)
        val preserved = taggedBuilder.build()
        assertEquals(IwaraSubscriptionRequest(1L), preserved.tag(IwaraSubscriptionRequest::class.java))
        assertTrue(preserved.cacheControl.noStore)
        assertTrue(preserved.cacheControl.noCache)

        val untagged = Request.Builder().url(url).build()
        val untaggedBuilder = untagged.newBuilder()
        bindIwaraSubscriptionRequest(untagged, untaggedBuilder, currentEpoch = 5L)
        val bound = untaggedBuilder.build()
        assertEquals(IwaraSubscriptionRequest(5L), bound.tag(IwaraSubscriptionRequest::class.java))
        assertTrue(bound.cacheControl.noStore)
        assertTrue(bound.cacheControl.noCache)

        val publicVideos = Request.Builder().url("${SiteRegistry.IWARA.apiBase}/videos?page=0").build()
        val publicBuilder = publicVideos.newBuilder()
        bindIwaraSubscriptionRequest(publicVideos, publicBuilder, currentEpoch = 5L)
        assertNull(publicBuilder.build().tag(IwaraSubscriptionRequest::class.java))
    }

    @Test fun verificationGatesForumCallbacksTargetNavigationAndRecreation() {
        val site = SiteRegistry.EXHENTAI
        val forum = EhLogin.officialUrl
        val target = "https://www.exhentai.org/"
        val downgraded = "http://exhentai.org/"
        val untrusted = "https://exhentai.org.example.invalid/"
        assertTrue(SiteAuthPhase.FORUM.acceptsPage(site, forum))
        for (phase in listOf(SiteAuthPhase.RESETTING, SiteAuthPhase.COPYING_IDENTITY, SiteAuthPhase.VERIFYING)) {
            assertFalse(phase.canVerify)
            assertFalse(phase.acceptsPage(site, forum))
            assertFalse(phase.acceptsPage(site, target))
            assertEquals(SiteAuthNavigation.BLOCK, phase.onMainFrameNavigation(site, forum))
            assertEquals(SiteAuthNavigation.BLOCK, phase.onMainFrameNavigation(site, untrusted))
        }
        assertTrue(SiteAuthPhase.LOADING_TARGET.acceptsPage(site, target))
        assertFalse(SiteAuthPhase.LOADING_TARGET.canVerify)
        assertTrue(SiteAuthPhase.LOADING_TARGET.canRestart)
        assertTrue(SiteAuthPhase.TARGET_FAILED.canVerify)
        assertTrue(SiteAuthPhase.TARGET_FAILED.canRestart)
        assertFalse(SiteAuthPhase.RESETTING.canRestart)
        assertFalse(SiteAuthPhase.VERIFYING.canRestart)
        assertFalse(SiteAuthPhase.LOADING_TARGET.acceptsPage(site, forum))
        assertFalse(SiteAuthPhase.LOADING_TARGET.acceptsPage(site, SiteRegistry.EHENTAI.base))
        assertFalse(SiteAuthPhase.LOADING_TARGET.acceptsPage(site, untrusted))

        // Redirects during LOADING_TARGET fail cleanly instead of hanging or bypassing official HTTPS hosts.
        assertEquals(SiteAuthNavigation.ALLOW, SiteAuthPhase.LOADING_TARGET.onMainFrameNavigation(site, target))
        assertEquals(SiteAuthNavigation.FAIL_TARGET, SiteAuthPhase.LOADING_TARGET.onMainFrameNavigation(site, forum))
        assertEquals(SiteAuthNavigation.FAIL_TARGET, SiteAuthPhase.LOADING_TARGET.onMainFrameNavigation(site, SiteRegistry.EHENTAI.base))
        assertEquals(SiteAuthNavigation.FAIL_TARGET, SiteAuthPhase.LOADING_TARGET.onMainFrameNavigation(site, downgraded))
        assertEquals(SiteAuthNavigation.FAIL_TARGET, SiteAuthPhase.LOADING_TARGET.onMainFrameNavigation(site, untrusted))
        assertEquals(SiteAuthNavigation.ALLOW, SiteAuthPhase.FORUM.onMainFrameNavigation(site, forum))
        assertEquals(SiteAuthNavigation.BLOCK, SiteAuthPhase.FORUM.onMainFrameNavigation(site, downgraded))
        assertEquals(SiteAuthNavigation.BLOCK, SiteAuthPhase.FORUM.onMainFrameNavigation(site, untrusted))
        assertEquals(SiteAuthNavigation.BLOCK, SiteAuthPhase.TARGET_FAILED.onMainFrameNavigation(site, target))
        assertEquals(SiteAuthNavigation.BLOCK, SiteAuthPhase.TARGET_FAILED.onMainFrameNavigation(site, untrusted))

        // Pre-onPageStarted network, HTTP, TLS errors and unexpected page starts fail target cleanly.
        assertTrue(SiteAuthPhase.LOADING_TARGET.handlesMainFrameError(site, target, targetVisitStarted = false))
        assertTrue(SiteAuthPhase.LOADING_TARGET.handlesMainFrameError(site, "chrome-error://chromewebdata/", targetVisitStarted = false))
        assertFalse(SiteAuthPhase.LOADING_TARGET.handlesMainFrameError(site, forum, targetVisitStarted = false))
        assertTrue(SiteAuthPhase.LOADING_TARGET.handlesMainFrameError(site, forum, targetVisitStarted = true))
        assertTrue(SiteAuthPhase.LOADING_TARGET.handlesSslError(site, target, currentUrl = forum, targetVisitStarted = false))
        assertFalse(SiteAuthPhase.LOADING_TARGET.handlesSslError(site, forum, currentUrl = forum, targetVisitStarted = false))
        assertFalse(SiteAuthPhase.LOADING_TARGET.handlesSslError(site, untrusted, currentUrl = target, targetVisitStarted = true))
        assertTrue(SiteAuthPhase.FORUM.handlesSslError(site, forum, currentUrl = null, targetVisitStarted = false))
        assertFalse(SiteAuthPhase.FORUM.handlesSslError(site, untrusted, currentUrl = forum, targetVisitStarted = false))
        assertFalse(SiteAuthPhase.LOADING_TARGET.failsOnPageStarted(site, target))
        assertTrue(SiteAuthPhase.LOADING_TARGET.failsOnPageStarted(site, forum))
        assertTrue(SiteAuthPhase.LOADING_TARGET.failsOnPageStarted(site, "chrome-error://chromewebdata/"))
        assertTrue(SiteAuthPhase.LOADING_TARGET.failsOnPageStarted(site, untrusted))

        assertEquals(SiteAuthPhase.COPYING_IDENTITY, SiteAuthPhase.COPYING_IDENTITY.afterRecreation())
        assertEquals(SiteAuthPhase.LOADING_TARGET, SiteAuthPhase.LOADING_TARGET.afterRecreation())
        assertEquals(SiteAuthPhase.TARGET_FAILED, SiteAuthPhase.TARGET_FAILED.afterRecreation())
        assertEquals(SiteAuthPhase.TARGET_READY, SiteAuthPhase.VERIFYING.afterRecreation())
    }
}
