package com.r34v.r34v

import com.google.gson.JsonParser
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.IwaraProtocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class IwaraGalleryContractTest {
    private val site = SiteRegistry.IWARA
    private val galleryJson = JsonParser.parseString("""{
      "id":"GallerySample01","slug":"neutral-set","title":"Neutral gallery","body":"Two images and one clip.","numImages":3,"numLikes":8,"numViews":20,
      "thumbnail":{"id":"cover-id","name":"cover.webp","width":800,"height":1200},
      "tags":[{"id":"city_walk"}],"user":{"id":"author-id","name":"Author","username":"author"},
      "files":[
        {"id":"image-one","type":"image","mime":"image/png","name":"one.png","width":800,"height":1200},
        {"id":"image-two","type":"image","mime":"image/webp","name":"two.webp","width":1200,"height":800},
        {"id":"clip-one","type":"video","mime":"video/webm","name":"clip.webm","width":1200,"height":800}
      ]
    }""").asJsonObject

    @Test fun imageRoutesKeepContentKindThroughListsAuthorsAndTags() {
        val request = FeedRequest(FeedKind.MODEL, "author-id", contentKind = MediaKind.GALLERY)
        val url = IwaraProtocol.resolve(site, request).toHttpUrl()
        assertEquals("/images", url.encodedPath); assertEquals("author-id", url.queryParameter("user"))
        val search = IwaraProtocol.resolve(site, FeedRequest(FeedKind.SEARCH, "city", contentKind = MediaKind.GALLERY)).toHttpUrl()
        assertEquals("images", search.queryParameter("type"))
        val gallery = IwaraProtocol.gallery(galleryJson, site)
        assertEquals(request, IwaraProtocol.linkRequest(site, gallery.groups.getValue("作者").single().url))
        assertEquals(MediaKind.GALLERY, IwaraProtocol.linkRequest(site, gallery.groups.getValue("标签").single().url)!!.contentKind)
        assertEquals("GallerySample01", IwaraProtocol.imageId(site, gallery.entry.url))
        assertNull(IwaraProtocol.imageId(site, "https://iwara.tv.attacker.invalid/image/GallerySample01"))
    }

    @Test fun listAndDetailKeepGalleryIdentityDimensionsAndReadablePages() {
        val list = IwaraProtocol.imageFeed(JsonParser.parseString("""{"page":0,"limit":20,"count":1,"results":[$galleryJson]}"""), site, FeedRequest(contentKind = MediaKind.GALLERY))
        val entry = list.videos.single()
        assertEquals(MediaKind.GALLERY, entry.mediaKind); assertEquals("iwara:gallery:GallerySample01", entry.entryKey)
        assertNotEquals(entry.copy(mediaKind = MediaKind.VIDEO).entryKey, entry.entryKey)
        assertEquals(800, entry.thumbWidth); assertEquals(1200, entry.thumbHeight)
        val detail = IwaraProtocol.gallery(galleryJson, site)
        assertEquals(2, detail.pageCount); assertEquals("2 张可读图片", detail.entry.duration)
        assertNotEquals(detail.pageLinks[0].url.substringBefore('?'), detail.pageLinks[1].url.substringBefore('?'))
        assertEquals(2, IwaraProtocol.image(galleryJson, site, 2).index)
        assertTrue(detail.description.contains("1 个视频文件"))
        assertEquals("https://i.iwara.tv/image/original/image-two/two.webp", IwaraProtocol.image(galleryJson, site, 2).imageUrl)
        assertEquals(1, IwaraProtocol.image(galleryJson, site, 1).index)
        assertThrows(IllegalStateException::class.java) { IwaraProtocol.image(galleryJson, site, 3) }
    }
}
