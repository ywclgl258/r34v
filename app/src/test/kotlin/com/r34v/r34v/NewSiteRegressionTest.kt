package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import org.junit.Assert.*
import org.junit.Test

class NewSiteRegressionTest {
    @Test fun jableSearchLinksRoundTripWithHyphenatedQueriesAndPaging() {
        for (sort in listOf(Sort.NEWEST, Sort.POPULARITY)) for (page in listOf(1, 2, 18)) {
            val request = FeedRequest(FeedKind.SEARCH, "sample-code", sort, page)
            val url = JableProtocol.resolve(SiteRegistry.JABLE, request)
            assertEquals(request, JableProtocol.linkRequest(SiteRegistry.JABLE, url))
        }
    }

    @Test fun modelPaginationHasAQueryDelimiter() {
        assertEquals("https://www.pornhub.com/pornstar/sample-model?page=2",
            PornhubProtocol.resolve(SiteRegistry.PORNHUB, FeedRequest(FeedKind.MODEL, "sample-model", page = 2)))
    }

    @Test fun rouSeriesLinksReturnToTheSameSeriesAndKeepPaging() {
        val url = "https://rou.video/s/abcdefghijklmnop"
        val request = requireNotNull(RouVideoProtocol.linkRequest(SiteRegistry.ROUVIDEO, url))
        assertEquals(url, RouVideoProtocol.resolve(SiteRegistry.ROUVIDEO, request))
        assertEquals("$url?page=2", RouVideoProtocol.resolve(SiteRegistry.ROUVIDEO, request.copy(page = 2)))
        val tag = RouVideoProtocol.linkRequest(SiteRegistry.ROUVIDEO, "https://rou.video/v?tag=A%2BB")!!
        assertEquals("A+B", tag.target)
    }

    @Test fun kbjFreeUsesTheActualPaginationLinksAndRecognisesOnlyExplicitEmptyResults() {
        val html = "<a href='/videos?sort=recent&amp;page=2'>2</a><a href='/videos?sort=recent&amp;page=32135'>Last</a>"
        assertEquals(32135, KbjFreeProtocol.lastPage(html, 1))
        assertEquals(32135, KbjFreeProtocol.lastPage("", 32135))
        assertFalse(KbjFreeProtocol.looksEmpty("<html>upstream unavailable</html>"))
    }

    @Test fun supportedSortChoicesChangeTheServerRequest() {
        val ph = SiteRegistry.PORNHUB
        assertTrue(PornhubProtocol.resolve(ph, FeedRequest(sort = Sort.VIEWS)).endsWith("?o=mv"))
        assertTrue(PornhubProtocol.resolve(ph, FeedRequest(sort = Sort.RATING)).endsWith("?o=tr"))
        assertTrue(KbjFreeProtocol.resolve(SiteRegistry.KBJFREE, FeedRequest(sort = Sort.POPULARITY)).endsWith("?sort=popular"))
        assertEquals(listOf(Sort.NEWEST), JableProtocol.sorts(FeedRequest(FeedKind.CATEGORY, "28")))
    }

    @Test fun missavDecodesBase62AndDoesNotReplaceExpandedWordsAgain() {
        val words = MutableList(37) { "" }.apply { this[0] = "https"; this[36] = "cdn" }
        val source = "eval(function(p,a,c,k,e,d){return p}('0://A.invalid/file',62,37,'${words.joinToString("|")}'.split('|'),0,{}))"
        assertEquals("https://cdn.invalid/file", MissavProtocol.unpack(source))
        val collision = "eval(function(p,a,c,k,e,d){return p}('1',16,2,'wrong|0'.split('|'),0,{}))"
        assertEquals("0", MissavProtocol.unpack(collision))
    }
}
