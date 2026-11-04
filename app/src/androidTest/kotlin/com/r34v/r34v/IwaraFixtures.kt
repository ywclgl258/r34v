package com.r34v.r34v

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CopyOnWriteArrayList

/** Neutral API-shaped data used only by instrumentation; no content enters the app APK. */
internal class IwaraFixtures(private val clip: ByteArray = byteArrayOf()) {
    val requests = CopyOnWriteArrayList<Request>()
    var refreshSalt: String? = null
    var failRefresh = false
    val metadata = """{
      "id":"IwaraSample01","slug":"city-walk","title":"Iwara city walk","body":"A quiet walk through the city.",
      "private":false,"embedUrl":null,"numLikes":12,"numViews":345,"thumbnail":3,
      "file":{"id":"01234567-89ab-cdef-0123-456789abcdef","height":720,"duration":8},
      "fileUrl":"https://filesq.iwara.tv/file/01234567-89ab-cdef-0123-456789abcdef?expires=1999999999999&hash=fixture",
      "tags":[{"id":"city_walk","type":"general"}],
      "user":{"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","name":"Sample author","username":"sample_author"}
    }"""

    fun respond(request: Request): Response {
        requests.add(request)
        val path = request.url.encodedPath
        val type: String
        val bytes: ByteArray
        when {
            path.endsWith(".mp4") -> { type = "video/mp4"; bytes = clip }
            path.startsWith("/latest-updates/") || path.startsWith("/most-popular/") || path.startsWith("/top-rated/") -> {
                type = "text/html"; bytes = """<div class='thumbs'><div data-video-card-id='900001'><a class='th' href='/video/900001/sample/'><span class='thumb_title'>R34V sample</span></a></div></div><button class='page-of'>Page 1 of 1</button>""".toByteArray()
            }
            path.endsWith("/related") || path.endsWith("/comments") -> {
                type = "application/json"; bytes = """{"page":0,"limit":12,"count":0,"results":[]}""".toByteArray()
            }
            path == "/videos" || path == "/search" -> {
                val page = request.url.queryParameter("page")?.toIntOrNull() ?: 0
                type = "application/json"; bytes = """{"page":$page,"limit":20,"count":${page * 20 + 1},"results":[$metadata]}""".toByteArray()
            }
            path.startsWith("/profile/") -> {
                type = "application/json"; bytes = """{"user":{"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}}""".toByteArray()
            }
            path.startsWith("/video/") -> { type = "application/json"; bytes = metadata.toByteArray() }
            path.startsWith("/file/") -> {
                if (failRefresh && requests.count { it.url.encodedPath.startsWith("/file/") } > 1) throw java.io.IOException("fixture refresh unavailable")
                val expected = refreshSalt?.let { com.r34v.r34v.data.adapter.IwaraSignature.headers(request.url.toString(), it)["X-Version"] }
                val name = if (refreshSalt == null || request.header("X-Version") == expected) "Source" else "360"
                type = "application/json"; bytes = """[{"name":"$name","src":{"view":"//himeko.iwara.tv/fixture.mp4","download":"//himeko.iwara.tv/fixture.mp4?download=1"}}]""".toByteArray()
            }
            path == "/" -> { type = "text/html"; bytes = """<script src="/main.abcdef012345.js"></script>""".toByteArray() }
            path.endsWith(".js") -> { type = "text/javascript"; bytes = """const signature=id+"_"+e.expires+"_${refreshSalt ?: "unrelated"}";""".toByteArray() }
            else -> { type = "image/png"; bytes = byteArrayOf() }
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Iwara neutral fixture")
            .body(bytes.toResponseBody(type.toMediaType())).build()
    }
}
