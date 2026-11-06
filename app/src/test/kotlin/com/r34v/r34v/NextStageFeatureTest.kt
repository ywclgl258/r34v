package com.r34v.r34v

import com.google.gson.Gson
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.EhProtocol
import com.r34v.r34v.data.adapter.HanimeAdapter
import com.r34v.r34v.data.adapter.IwaraProtocol
import com.r34v.r34v.data.adapter.NhentaiProtocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NextStageFeatureTest {
    @Test fun hanimeAdvancedFiltersUseVerifiedSourceParametersAndRepeatTags() {
        val request = FeedRequest(FeedKind.SEARCH, "city", includeTags = listOf("中文字幕", "AI生成"),
            filter = AdvancedFilter(year = 2025, month = 3, minDurationSec = 600, brand = "Brand", category = "2D動畫"))
        val url = HanimeAdapter.resolve(SiteRegistry.HANIME1, request).toHttpUrl()
        assertEquals("2025 年", url.queryParameter("year")); assertEquals("3 月", url.queryParameter("month"))
        assertEquals("10 分鐘 +", url.queryParameter("duration")); assertEquals("Brand", url.queryParameter("brands[]"))
        assertEquals(listOf("中文字幕", "AI生成"), url.queryParameterValues("tags[]")); assertEquals("2D動畫", url.queryParameter("genre"))
    }

    @Test fun galleryAdvancedFiltersSurviveEhCursorPagination() {
        val request = FeedRequest(FeedKind.SEARCH, "city", cursor = "${SiteRegistry.EHENTAI.base}/?next=42",
            includeTags = listOf("artist:sample"), excludeTags = listOf("english"), filter = AdvancedFilter(minRating = 4, minPages = 10, maxPages = 30, category = "4"))
        val url = EhProtocol.resolve(SiteRegistry.EHENTAI, request).toHttpUrl()
        assertEquals("42", url.queryParameter("next")); assertEquals("city artist:sample -english", url.queryParameter("f_search"))
        assertEquals("10", url.queryParameter("f_spf")); assertEquals("30", url.queryParameter("f_spt")); assertEquals("4", url.queryParameter("f_srdd"))
        assertEquals("1019", url.queryParameter("f_cats")); assertEquals("1", url.queryParameter("advsearch"))
    }

    @Test fun nhentaiAndIwaraKeepTheirOwnFilterContracts() {
        val nh = NhentaiProtocol.resolve(FeedRequest(FeedKind.SEARCH, "city", includeTags = listOf("tag:sample"), excludeTags = listOf("tag:other"),
            filter = AdvancedFilter(minPages = 5, maxPages = 20, category = "manga"))).toHttpUrl()
        assertEquals("city tag:sample -tag:other pages:>=5 pages:<=20 category:manga", nh.queryParameter("query"))
        val iwara = IwaraProtocol.resolve(SiteRegistry.IWARA, FeedRequest(FeedKind.SEARCH, "city", includeTags = listOf("mmd", "blender"))).toHttpUrl()
        assertEquals("mmd,blender", iwara.queryParameter("tags")); assertEquals("city", iwara.queryParameter("query"))
    }

    @Test fun newPersonalObjectsRoundTripAndMergeWithoutOldFieldLoss() {
        val video = Video("1", "City", "https://rule34video.com/videos/1/city/", site = "r34v")
        val search = SavedSearch(name = "advanced", query = "", sources = listOf("r34v"), includeTags = listOf("city"), filter = AdvancedFilter(minDurationSec = 60))
        val personal = PersonalLibrary(savedSearches = listOf(search), timeBookmarks = listOf(TimeBookmark(video = video, positionMs = 65_000, note = "intro")),
            playbackPreferences = mapOf("r34v" to PlaybackPreferences(defaultRate = 1.5f, holdRate = 3f)),
            readerPreferences = mapOf(video.entryKey to ReaderPreferences(cropPercent = 5)),
            readingBehaviors = mapOf("r34v" to ReadingBehavior(autoPageSeconds = 8)),
            imageFavorites = listOf(ImageFavorite(video, 1, video.url)), channels = listOf(DiscoveryChannel(name = "channel", savedSearch = search)))
        val backup = LibraryBackup(personal = personal)
        val decoded = Gson().fromJson(Gson().toJson(backup), LibraryBackup::class.java)
        validateBackup(decoded)
        assertEquals(personal, decoded.personal)
        assertEquals(personal, mergeBackups(LibraryBackup(), backup).personal)
    }

    @Test fun timestampsAreBoundedByRealDurationAndKeepTextOffsets() {
        val text = "Opening 0:05, scene 01:02:03, invalid 9:99 and date 2026-11-06"
        val spans = timestampSpans(text, 3_800_000)
        assertEquals(listOf(5_000L, 3_723_000L), spans.map { it.positionMs })
        assertEquals(listOf("0:05", "01:02:03"), spans.map { text.substring(it.start, it.end) })
        assertTrue(timestampSpans("10:00", 5_000).isEmpty())
    }

    @Test fun pageRangesAreSortedDistinctAndStrictlyBounded() {
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 9), parsePageSelection("1-3,5–7, 3,9", 10))
        assertThrows(IllegalArgumentException::class.java) { parsePageSelection("8-2", 10) }
        assertThrows(IllegalArgumentException::class.java) { parsePageSelection("0,11", 10) }
        assertThrows(IllegalArgumentException::class.java) { parsePageSelection("", 10) }
    }

    @Test fun spreadRulesKeepEveryLogicalPageExactlyOnce() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), readerSheets(5, 2, true))
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5)), readerSheets(5, 2, false))
        assertEquals(listOf(listOf(1), listOf(2)), readerSheets(2, 2, false))
        assertEquals((1..5).toList(), readerSheets(5, 2, false).flatten().sorted())
    }

    @Test fun oldPersonalJsonReceivesEveryNewDefault() {
        val data = Gson().fromJson("{\"shelves\":[{\"id\":\"watch-later\",\"name\":\"稍后看\",\"entries\":[]}]}", PersonalLibrary::class.java)
        assertTrue(data.timeBookmarks.isEmpty()); assertTrue(data.imageFavorites.isEmpty()); assertTrue(data.channels.isEmpty())
        assertTrue(data.playbackPreferences.isEmpty()); assertTrue(data.readerPreferences.isEmpty()); assertTrue(data.readingBehaviors.isEmpty())
    }
}
