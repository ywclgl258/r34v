package com.r34v.r34v

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Original neutral HTML and page art for gallery tests; never included in the release APK. */
internal class EhFixtures {
    val requests = CopyOnWriteArrayList<Request>()
    var offline = false
    var rejectEx = false
    var quotaPage = 0
    var resetImagePage = 0
    var resetImageFailures = 0
    var certificateImagePage = 0
    var redirectImagePage = 0
    val cookie = "ipb_member_id=12345; ipb_pass_hash=0123456789abcdef0123456789abcdef; igneous=fixture"
    private val pictures = (1..5).associateWith(::picture)
    private fun picture(page: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(224 - page * 5, 220 + page * 2, 200 + page * 5))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 44, 55); textSize = 56f }
        canvas.drawText("CITY SKETCHBOOK", 55f, 120f, paint); canvas.drawText("PAGE $page", 55f, 205f, paint)
        paint.strokeWidth = 6f; paint.style = Paint.Style.STROKE
        canvas.drawRect(55f, 280f, 745f, 1090f, paint)
        for (i in 0..8) canvas.drawLine(70f + i * 75f, 1000f - i * 25f, 70f + i * 75f, 450f + (i * page * 31 % 200), paint)
        return ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this); bitmap.recycle() }.toByteArray()
    }
    fun listing(base: String, next: Boolean = true): String = """<!doctype html><html><body>
      <div class="searchnav">${if (next) "<a id=\"unext\" href=\"$base/?next=8887\">Next &gt;</a>" else ""}</div>
      <table class="itg gltc"><tr><td class="gl1c glcat"><div class="cn">Non-H</div></td><td><div class="glthumb"><img src="https://ehgt.org/cover.png"></div><div>5 pages</div></td>
      <td class="gl3c glname"><a href="$base/g/8888/abcdef1234/"><div class="glink">City sketchbook</div></a></td></tr></table></body></html>"""
    private fun detail(base: String, preview: Int): String {
        val indexes = (preview * 2 + 1..(preview * 2 + 2).coerceAtMost(5)).filter { it in 1..5 }
        return """<!doctype html><html><body><h1 id="gn">City sketchbook</h1><h1 id="gj">城市速写</h1>
          <div id="gd1"><div style="background:url(https://ehgt.org/cover.png)"></div></div><div id="gdc"><div class="cn">Non-H</div></div>
          <div id="gdn"><a href="$base/uploader/sample_author">Sample Author</a></div><div id="rating_label">Average: 4.50</div>
          <div id="gdd"><table><tr><td>Length:</td><td>5 pages</td></tr><tr><td>Language:</td><td>English</td></tr><tr><td>Posted:</td><td>2026-11-03</td></tr></table></div>
          <div id="taglist"><table><tr><td>artist:</td><td><a href="$base/tag/artist:city+sketch">city sketch</a></td></tr></table></div>
          <div id="gdt">${indexes.joinToString("") { "<a href=\"$base/s/a$it/8888-$it\"><div title=\"Page $it\">$it</div></a>" }}</div></body></html>"""
    }
    fun respond(request: Request): Response {
        requests.add(request)
        if (offline) throw IOException("fixture offline")
        val base = "https://${request.url.host}"
        val path = request.url.encodedPath
        val bytes: ByteArray
        val type: String
        when {
            request.url.host == "rule34video.com" -> { bytes = "<div class='thumbs'></div><button class='page-of'>Page 1 of 1</button>".toByteArray(); type = "text/html" }
            request.url.host == "sample.hath.network" || request.url.host == "ehgt.org" -> {
                val index = Regex("page(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                when {
                    certificateImagePage == index -> throw javax.net.ssl.SSLHandshakeException("fixture untrusted chain").apply {
                        initCause(java.security.cert.CertPathValidatorException("fixture PKIX failure"))
                    }
                    resetImagePage == index && resetImageFailures > 0 -> {
                        resetImageFailures--
                        throw javax.net.ssl.SSLHandshakeException("Read error: ssl=0x1: I/O error during system call, Connection reset by peer")
                    }
                    redirectImagePage == index -> {
                        redirectImagePage = 0
                        return Response.Builder().request(request.newBuilder().url("https://sample.hath.network/redirected.png").build())
                            .protocol(Protocol.HTTP_1_1).code(200).message("Unexpected redirect").body(pictures.getValue(index).toResponseBody("image/png".toMediaType())).build()
                    }
                }
                bytes = pictures[index] ?: pictures.getValue(1); type = "image/png"
            }
            rejectEx && request.url.host == "exhentai.org" -> { bytes = "GIF89a".toByteArray(); type = "image/gif" }
            path.startsWith("/g/") -> { bytes = detail(base, request.url.queryParameter("p")?.toIntOrNull() ?: 0).toByteArray(); type = "text/html; charset=utf-8" }
            path.startsWith("/s/") -> {
                val page = path.substringAfterLast('-').toInt()
                val image = if (page == quotaPage) "https://ehgt.org/g/509.gif" else "https://sample.hath.network/page$page.png"
                bytes = """<!doctype html><html><body><a id="prev" href="$base/s/a${(page-1).coerceAtLeast(1)}/8888-${(page-1).coerceAtLeast(1)}">Prev</a>
                  <img id="img" src="$image" style="width:800px;height:1200px" onerror="nl('reload-$page')">
                  <a id="next" href="$base/s/a${(page+1).coerceAtMost(5)}/8888-${(page+1).coerceAtMost(5)}">Next</a></body></html>""".toByteArray()
                type = "text/html; charset=utf-8"
            }
            else -> { bytes = listing(base, request.url.queryParameter("next") == null).toByteArray(); type = "text/html; charset=utf-8" }
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Gallery neutral fixture")
            .body(bytes.toResponseBody(type.toMediaType())).build()
    }
}
