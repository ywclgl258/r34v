package com.r34v.r34v

import com.r34v.r34v.data.SiteContext
import com.r34v.r34v.data.SiteRegistry
import com.r34v.r34v.data.applyBrowserShape
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserShapeTest {
    private fun shaped(site: SiteContext?, url: String, vararg headers: Pair<String, String>): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val initial = builder.build()
        applyBrowserShape(builder, initial, site)
        return builder.build()
    }

    @Test fun pageRequestsLookLikeADocumentNavigation() {
        val r = shaped(SiteRegistry.R34V, "https://rule34video.com/latest-updates/")
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8", r.header("Accept"))
        assertEquals("document", r.header("Sec-Fetch-Dest"))
        assertEquals("navigate", r.header("Sec-Fetch-Mode"))
        assertEquals("same-origin", r.header("Sec-Fetch-Site"))
        assertEquals("1", r.header("Upgrade-Insecure-Requests"))
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", r.header("Accept-Language"))
    }

    @Test fun listCoversLookLikeImagesOnTheSiteHost() {
        val r = shaped(SiteRegistry.R34V, "https://rule34video.com/contents/videos_screenshots/1/1234/preview.jpg")
        assertEquals("image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8", r.header("Accept"))
        assertEquals("image", r.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", r.header("Sec-Fetch-Mode"))
        assertEquals("same-origin", r.header("Sec-Fetch-Site"))
        assertNull(r.header("Upgrade-Insecure-Requests"))
    }

    @Test fun cdnThumbsKeepTheImageShapeButReportCrossSite() {
        val r = shaped(SiteRegistry.EHENTAI, "https://ehgt.org/t/abc123.jpg")
        assertEquals("image", r.header("Sec-Fetch-Dest"))
        assertEquals("cross-site", r.header("Sec-Fetch-Site"))
    }

    @Test fun apiRequestsLookLikeCorsFetches() {
        val r = shaped(SiteRegistry.IWARA, "https://apiq.iwara.tv/videos?limit=12")
        assertEquals("application/json, text/plain, */*", r.header("Accept"))
        assertEquals("empty", r.header("Sec-Fetch-Dest"))
        assertEquals("cors", r.header("Sec-Fetch-Mode"))
        assertEquals("same-site", r.header("Sec-Fetch-Site"))
    }

    @Test fun explicitlySetHeadersAreNeverOverwritten() {
        val r = shaped(SiteRegistry.IWARA, "https://apiq.iwara.tv/videos",
            "Origin" to "https://www.iwara.tv", "x-site" to "www.iwara.tv")
        assertEquals("https://www.iwara.tv", r.header("Origin"))
        assertEquals("www.iwara.tv", r.header("x-site"))
    }

    @Test fun unknownHostsKeepTheMinimalShape() {
        val r = shaped(null, "https://example.org/update.json")
        assertNull(r.header("Accept"))
        assertNull(r.header("Sec-Fetch-Dest"))
        assertNull(r.header("Accept-Language"))
    }
}
