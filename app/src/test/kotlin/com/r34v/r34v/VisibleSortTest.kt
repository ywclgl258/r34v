package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class VisibleSortTest {
    @Test fun everyHanimeListingSortChangesTheProductionRoute() {
        val request = FeedRequest(target = "recent")
        val routes = HanimeAdapter.availableSorts(request).map {
            HanimeAdapter.resolve(SiteRegistry.HANIME1, request.copy(sort = it)).toHttpUrl().queryParameter("sort")
        }
        assertEquals(listOf("最新上市", "觀看次數", "讚好比例", "時長最長"), routes)
        for (fixed in listOf(FeedRequest(target = "upload"), FeedRequest(FeedKind.SEARCH, "city"), FeedRequest(FeedKind.CATEGORY, "裏番")))
            assertEquals(1, HanimeAdapter.availableSorts(fixed).size)
        assertEquals("最新上傳", HanimeAdapter.resolve(SiteRegistry.HANIME1, FeedRequest(target = "upload")).toHttpUrl().queryParameter("sort"))
        assertEquals("本日排行", HanimeAdapter.resolve(SiteRegistry.HANIME1, FeedRequest(FeedKind.POPULAR)).toHttpUrl().queryParameter("sort"))
        for (legacy in listOf(Sort.RELEVANCE, Sort.TRENDING, Sort.POPULARITY))
            assertEquals("最新上市", HanimeAdapter.resolve(SiteRegistry.HANIME1, FeedRequest(sort = legacy)).toHttpUrl().queryParameter("sort"))
    }
    @Test fun fixedIwaraAndNhentaiChartsExposeOnlyTheirEffectiveSort() {
        for ((kind, sort, parameter) in listOf(Triple(FeedKind.POPULAR, Sort.POPULARITY, "popularity"), Triple(FeedKind.RATED, Sort.RATING, "likes"))) {
            val request = FeedRequest(kind)
            assertEquals(listOf(sort), IwaraProtocol.availableSorts(request))
            assertEquals(parameter, IwaraProtocol.resolve(SiteRegistry.IWARA, request.copy(sort = sort)).toHttpUrl().queryParameter("sort"))
        }
        assertEquals(listOf(Sort.POPULARITY), NhentaiProtocol.availableSorts(FeedRequest(FeedKind.POPULAR)))
        val request = FeedRequest(FeedKind.SEARCH, "city")
        assertEquals(2, NhentaiProtocol.availableSorts(request).map { NhentaiProtocol.resolve(request.copy(sort = it)) }.distinct().size)
    }
}
