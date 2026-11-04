package com.r34v.r34v

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Original gallery data and city-sketch image; real adapters run against this transport. */
internal class ComicFixtures {
    val requests = CopyOnWriteArrayList<Request>()
    var offline = false
    var nhStatus = 200
    var listGate: java.util.concurrent.CountDownLatch? = null
    var nhLanguages: Map<String, List<Int>>? = null
    var indexes: Map<String, List<Int>> = emptyMap()
    private val hash = "0".repeat(61) + "abc"
    private val png by lazy { EhFixtures().respond(Request.Builder().url("https://ehgt.org/cover.png").build()).body!!.bytes() }
    private fun height(id: Int) = listOf(900, 1200, 1600)[id % 3]
    private fun coverHash(id: Int) = if (id == 8800) hash else id.toString(16).padStart(64, '0')
    private val art = mutableMapOf<Int, ByteArray>()
    @Synchronized private fun picture(height: Int) = art.getOrPut(height) {
        val bitmap = android.graphics.Bitmap.createBitmap(800, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.rgb(210 + height % 29, 220, 205 + height % 33))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35,45,55); textSize = 54f }
        canvas.drawText("CITY SKETCHBOOK", 45f, 115f, paint)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 5f
        canvas.drawRect(45f, 230f, 755f, height - 60f, paint)
        for (i in 0..8) canvas.drawLine(65f+i*80f, height-100f, 65f+i*80f, 280f+i*31f, paint)
        java.io.ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,this); bitmap.recycle() }.toByteArray()
    }
    fun nhCard(id: Int = 8800) = """{"id":$id,"media_id":"9900","english_title":"City sketchbook","thumbnail":"galleries/9900/thumb.webp","num_pages":2,"num_favorites":7}"""
    fun nhDetail() = """{"id":8800,"media_id":"9900","title":{"english":"City sketchbook","japanese":"城市速写"},"thumbnail":{"path":"galleries/9900/thumb.webp"},"num_pages":2,"num_favorites":7,"tags":[{"type":"artist","name":"City Artist"},{"type":"tag","name":"city sketch"}],"pages":[{"number":1,"path":"galleries/9900/1.webp"},{"number":2,"path":"galleries/9900/2.png"}]}"""
    fun hitomiInfo(id: String) = """var galleryinfo={"id":"$id","title":"City sketchbook","japanese_title":"城市速写","type":"manga","language":"english","files":[{"hash":"${coverHash(id.toInt())}","width":800,"height":${height(id.toInt())}},{"hash":"${"f".repeat(64)}"}],"artists":[{"artist":"City Artist"}],"tags":[{"tag":"city sketch","female":"0"}]};"""
    private fun node(key: ByteArray, child: Long = 0): ByteArray = ByteBuffer.allocate(464).apply {
        putInt(1); putInt(4); put(key); putInt(1); putLong(0); putInt(8)
        repeat(17) { putLong(if (it == 0) child else 0) }
    }.array()
    fun respond(request: Request): Response {
        requests.add(request)
        if (offline) throw IOException("fixture offline")
        val url = request.url; val path = url.encodedPath
        if ((path == "/api/v2/galleries" || path == "/index-all.nozomi") && listGate?.await(25, java.util.concurrent.TimeUnit.SECONDS) == false) throw IOException("Fixture list gate timed out")
        var code = 200; var type = "application/json"; var total = 0; var offset = 0
        val bytes: ByteArray = when {
            url.host == "rule34video.com" -> { type = "text/html"; "<div class='thumbs'></div><button class='page-of'>Page 1 of 1</button>".toByteArray() }
            url.host == "nhentai.net" && nhStatus != 200 -> { code = nhStatus; "{\"detail\":\"fixture denied\"}".toByteArray() }
            path == "/api/v2/cdn" -> """{"image_servers":["https://i1.nhentai.net"],"thumb_servers":["https://t1.nhentai.net"]}""".toByteArray()
            path == "/api/v2/galleries/8800" -> nhDetail().toByteArray()
            path == "/api/v2/galleries/popular" -> "[${nhCard()}]".toByteArray()
            path == "/api/v2/search" && nhLanguages != null -> {
                val query = url.queryParameter("query").orEmpty()
                val language = if (query.contains("language:japanese")) "japanese" else "chinese"
                val ids = nhLanguages!![language].orEmpty()
                val page = url.queryParameter("page")!!.toInt()
                """{"result":[${ids.drop((page - 1) * 12).take(12).joinToString(",") { nhCard(it) }}],"num_pages":${(ids.size + 11) / 12}}""".toByteArray()
            }
            url.host == "nhentai.net" -> """{"result":[${nhCard()}],"num_pages":${if (url.queryParameter("page") == "2") 2 else 3}}""".toByteArray()
            path == "/gg.js" -> { type = "text/javascript"; "gg={m:function(g){var o=0;switch(g){case 3243:o=1;break;}return o;},b:'123456/'};".toByteArray() }
            path.endsWith(".nozomi") || path.endsWith(".index") || path.endsWith(".data") -> {
                type = "application/octet-stream"
                val all = when {
                    path in indexes -> ByteBuffer.allocate(indexes.getValue(path).size * 4).apply { indexes.getValue(path).forEach(::putInt) }.array()
                    path.endsWith(".index") -> node(byteArrayOf(-1,-1,-1,-1), 464) + node(MessageDigest.getInstance("SHA-256").digest("city".toByteArray()).copyOf(4))
                    path.endsWith(".data") -> ByteBuffer.allocate(8).putInt(1).putInt(8800).array()
                    path.contains("index-all") -> ByteBuffer.allocate(52).apply { (8800..8812).forEach(::putInt) }.array()
                    else -> ByteBuffer.allocate(4).putInt(8800).array()
                }
                total = all.size
                request.header("Range")?.let {
                    val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(it)!!
                    offset = range.groupValues[1].toInt(); val end = (range.groupValues[2].toInt()+1).coerceAtMost(all.size)
                    if (offset >= all.size) { code = 416; byteArrayOf() } else { code = 206; all.copyOfRange(offset, end) }
                } ?: all
            }
            path == "/galleriesindex/version" -> { type = "text/plain"; "123456".toByteArray() }
            path.endsWith(".js") -> { type = "text/javascript"; hitomiInfo(path.substringAfterLast('/').removeSuffix(".js")).toByteArray() }
            else -> {
                type = "image/png"
                val value = path.substringAfterLast('/').substringBefore('.')
                val id = if (value == hash) 8800 else value.takeIf { it.length == 64 }?.takeLast(4)?.toIntOrNull(16)
                if (id in 8800..8812) picture(height(id!!)) else png
            }
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Neutral comic fixture")
            .apply { if (code == 206) header("Content-Range", "bytes $offset-${offset+bytes.size-1}/$total"); if (code == 429) header("Retry-After", "60") }
            .body(bytes.toResponseBody(type.toMediaType())).build()
    }
}
