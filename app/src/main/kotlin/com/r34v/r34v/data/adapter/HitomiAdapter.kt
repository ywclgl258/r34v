package com.r34v.r34v.data.adapter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Reads the site's public indexes and JSON data. Remote JavaScript is never executed. */
class HitomiAdapter(private val network: Network) : SiteAdapter {
    override val site = SiteRegistry.HITOMI
    override val creatorTitle = "画师"
    override val catalogueTitle = "画廊分类"
    override val capabilities = setOf(Capability.DOWNLOAD, Capability.FEED, Capability.SEARCH, Capability.TAG, Capability.MODEL, Capability.CATEGORY, Capability.RANKING)
    override val sorts = listOf(Sort.NEWEST, Sort.POPULARITY)
    override val sections = listOf(Section("latest", "最新画廊", feed = FeedRequest()), Section("popular", "年度热门", feed = FeedRequest(FeedKind.POPULAR))) +
        listOf("doujinshi" to "同人志", "manga" to "漫画", "artistcg" to "画师 CG", "gamecg" to "游戏 CG").map { Section(it.first, it.second, feed = FeedRequest(FeedKind.CATEGORY, it.first)) }
    private val mutex = Mutex()
    private var routing: Pair<Long, HitomiProtocol.Routing>? = null
    private val details = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, JsonObject>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?) = size > 32
    })
    private val searches = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<Long, List<Int>>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Int>>>?) = size > 8
    })
    private data class Payload(val bytes: ByteArray, val total: Long)
    private class MissingGallery : IllegalStateException("Hitomi 画廊或索引不存在，请刷新后重试")
    private suspend fun fetch(url: String, offset: Long? = null, length: Int = 0, missing: Boolean = false): Payload {
        val maximum = if (offset != null) length else 16 * 1024 * 1024
        require(maximum in 1..16 * 1024 * 1024 && (offset == null || offset in 0..1_000_000_000_000L))
        val request = Request.Builder().url(url).tag(SiteContext::class.java, site).header("Cache-Control", "no-cache").apply { if (offset != null) header("Range", "bytes=$offset-${offset + length - 1}") }.build()
        return network.client.readResponse(request) { response ->
            if (missing && response.code in setOf(404, 416)) return@readResponse Payload(byteArrayOf(), 0)
            if (response.code == 429) throw GalleryAccessException("Hitomi 请求频率已受限，请稍后重试")
            if (response.code == 403) throw GalleryAccessException("Hitomi 暂时拒绝访问，请检查网络或重试此页")
            if (response.code == 404) throw MissingGallery()
            check(response.isSuccessful) { "Hitomi 读取失败：HTTP ${response.code}" }
            val range = response.header("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
            if (offset != null) check(response.code == 206 && range?.groupValues?.get(1)?.toLongOrNull() == offset) { "Hitomi 服务器未返回正确索引分段，请重试" }
            val body = response.body ?: error("Hitomi 响应为空")
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= maximum) { "Hitomi 索引过大，请使用更具体的查询" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (offset != null) check(range != null && bytes.size.toLong() == range.groupValues[2].toLong() - offset + 1) { "Hitomi 索引分段不完整" }
            Payload(bytes, range?.groupValues?.get(3)?.toLongOrNull() ?: bytes.size.toLong())
        }
    }
    private suspend fun route(refresh: Boolean = false): HitomiProtocol.Routing = mutex.withLock {
        routing?.takeIf { !refresh && System.currentTimeMillis() - it.first < 10 * 60 * 1000 }?.second ?: HitomiProtocol.routing(
            fetch("${HitomiProtocol.cdn}/gg.js").bytes.toString(Charsets.UTF_8)
        ).also { routing = System.currentTimeMillis() to it }
    }
    private suspend fun info(id: String, refresh: Boolean = false): JsonObject {
        if (!refresh) details[id]?.let { return it }
        return HitomiProtocol.info(fetch("${HitomiProtocol.cdn}/galleries/$id.js").bytes.toString(Charsets.UTF_8), id).also { details[id] = it }
    }
    override fun resolve(request: FeedRequest) = site.base.toHttpUrl().newBuilder().addPathSegment("search.html").addQueryParameter("query", HitomiProtocol.query(request))
        .addQueryParameter("page", request.page.toString()).addQueryParameter("order", if (request.kind == FeedKind.POPULAR || request.sort == Sort.POPULARITY) "popular" else "date").build().toString()
    override fun homeUrl() = resolve(homeRequest())
    override fun searchRequest(query: String, kind: FeedKind) = FeedRequest(kind, query)
    override fun feedTitle(request: FeedRequest) = if (request.kind == FeedKind.POPULAR) "年度热门" else if (request.kind == FeedKind.LATEST) "最新画廊" else request.target
    override fun linkRequest(url: String) = HitomiProtocol.linkRequest(url)

    override suspend fun feed(request: FeedRequest): FeedPage = coroutineScope {
        require(request.page in 1..1_000_000) { "页码无效" }
        val query = HitomiProtocol.query(request)
        val popular = request.kind == FeedKind.POPULAR || request.sort == Sort.POPULARITY
        val singleFacet = if (query.isBlank()) null else HitomiProtocol.terms(query).singleOrNull()?.takeIf { !it.first && it.second.contains(':') }?.second
        val ids: List<Int>; val total: Int
        var nextCursor: String? = null
        val languages = request.language.languages
        if (query.isBlank() && languages.size > 1) {
            val merged = combinedLanguageIds(request, popular)
            ids = merged.first; nextCursor = merged.second
            total = request.page + if (nextCursor != null) 1 else 0
        } else if (languages.isEmpty() && (query.isBlank() || singleFacet != null) || query.isBlank() && languages.size == 1) {
            val facet = languages.singleOrNull()?.let { "language:$it" } ?: singleFacet.orEmpty()
            val result = fetch(HitomiProtocol.nozomi(facet, popular), (request.page - 1L) * 48, 48, missing = true)
            ids = HitomiProtocol.ids(result.bytes)
            total = ((result.total / 4 + 11) / 12).toInt().coerceAtLeast(1)
        } else {
            val result = search(query, popular, request.language)
            ids = result.drop((request.page - 1) * 12).take(12)
            total = ((result.size + 11) / 12).coerceAtLeast(1)
        }
        val config = route(); val permits = Semaphore(3)
        val cards = ids.map { id -> async { permits.withPermit { try { HitomiProtocol.gallery(info(id.toString()), config).entry } catch (_: MissingGallery) { null } } } }.awaitAll().filterNotNull()
        FeedPage(cards, request.page, total, "画廊", nextCursor = nextCursor)
    }

    /** Read small index ranges: the Japanese index can contain millions of gallery IDs. */
    private suspend fun combinedLanguageIds(request: FeedRequest, popular: Boolean): Pair<List<Int>, String?> {
        val offsets = if (request.cursor.isBlank()) intArrayOf(0, 0) else request.cursor.split(',')
            .map { it.toIntOrNull() ?: error("语言翻页状态无效，请刷新列表") }.toIntArray()
        require(offsets.size == 2 && offsets.all { it in 0..250_000_000 }) { "语言翻页状态无效，请刷新列表" }
        val totals = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE)
        val buffers = arrayOf(emptyList<Int>(), emptyList())
        val starts = intArrayOf(-1, -1)
        suspend fun head(stream: Int): Int? {
            if (offsets[stream] >= totals[stream]) return null
            val buffered = buffers[stream].getOrNull(offsets[stream] - starts[stream])
            if (buffered != null) return buffered
            val language = request.language.languages[stream]
            val payload = fetch(HitomiProtocol.nozomi("language:$language", popular), offsets[stream] * 4L, 48, missing = true)
            totals[stream] = payload.total / 4
            starts[stream] = offsets[stream]; buffers[stream] = HitomiProtocol.ids(payload.bytes)
            return buffers[stream].firstOrNull()
        }
        val ids = mutableListOf<Int>()
        while (ids.size < 12) {
            val chinese = head(0); val japanese = head(1)
            if (chinese == null && japanese == null) break
            val stream = when {
                chinese == null -> 1
                japanese == null -> 0
                popular -> ids.size % 2
                else -> if (chinese >= japanese) 0 else 1
            }
            ids.add(if (stream == 0) chinese!! else japanese!!)
            if (chinese == japanese) { offsets[0]++; offsets[1]++ } else offsets[stream]++
        }
        val hasMore = head(0) != null || head(1) != null
        return ids to if (hasMore) offsets.joinToString(",") else null
    }
    private suspend fun search(query: String, popular: Boolean, language: GalleryLanguage): List<Int> {
        val key = "$query|$popular|${language.name}"
        searches[key]?.takeIf { System.currentTimeMillis() - it.first < 3 * 60 * 1000 }?.let { return it.second }
        val terms = if (query.isBlank()) emptyList() else HitomiProtocol.terms(query)
        var result: Set<Int>? = null
        val excluded = mutableSetOf<Int>()
        for ((negative, term) in terms.sortedBy { it.first }) {
            currentCoroutineContext().ensureActive()
            val ids = if (term.contains(':')) HitomiProtocol.ids(fetch(HitomiProtocol.nozomi(term), missing = true).bytes)
                else word(term)
            if (negative) excluded.addAll(ids) else result = result?.intersect(ids.toSet()) ?: ids.toSet()
            if (result?.isEmpty() == true) return emptyList()
        }
        if (language.languages.isNotEmpty()) {
            val candidates = result.orEmpty()
            result = buildSet {
                language.languages.forEach { addAll(HitomiProtocol.orderedMatches(fetch(HitomiProtocol.nozomi("language:$it"), missing = true).bytes, candidates)) }
            }
        }
        val ordered = if (popular) HitomiProtocol.orderedMatches(fetch(HitomiProtocol.nozomi("", true)).bytes, result.orEmpty())
            else result.orEmpty().sortedDescending()
        return ordered.filter { (result == null || it in result) && it !in excluded }.also { if (it.size <= 50_000) searches[key] = System.currentTimeMillis() to it }
    }
    private suspend fun word(term: String): List<Int> {
        val version = fetch("${HitomiProtocol.cdn}/galleriesindex/version").bytes.toString(Charsets.UTF_8).trim()
        check(version.matches(Regex("[0-9]{1,24}"))) { "Hitomi 搜索索引版本无效" }
        val base = "${HitomiProtocol.cdn}/galleriesindex/galleries.$version"
        val key = MessageDigest.getInstance("SHA-256").digest(term.toByteArray()).copyOf(4)
        var address = 0L; val seen = mutableSetOf<Long>()
        repeat(16) {
            check(seen.add(address)) { "Hitomi 搜索索引出现循环，请稍后重试" }
            val node = HitomiProtocol.node(fetch("$base.index", address, 464).bytes)
            var index = 0
            while (index < node.keys.size && HitomiProtocol.compare(key, node.keys[index]) > 0) index++
            if (index < node.keys.size && HitomiProtocol.compare(key, node.keys[index]) == 0) {
                val pointer = node.data[index]
                val bytes = fetch("$base.data", pointer.first, pointer.second).bytes
                check(bytes.size >= 4 && ByteBuffer.wrap(bytes).int.toLong() * 4 + 4 == bytes.size.toLong()) { "Hitomi 搜索结果不完整" }
                return HitomiProtocol.ids(bytes.copyOfRange(4, bytes.size))
            }
            val next = node.children.getOrNull(index) ?: 0
            if (next == 0L) return emptyList()
            address = next
        }
        error("Hitomi 搜索索引层级异常")
    }
    override suspend fun gallery(url: String): GalleryDetail {
        val id = HitomiProtocol.galleryId(url) ?: error("无法识别 Hitomi 画廊地址")
        return HitomiProtocol.gallery(info(id), route())
    }
    override suspend fun galleryImage(url: String): GalleryImage {
        val (id, page) = HitomiProtocol.pageIdentity(url) ?: error("无法识别 Hitomi 页码")
        val refresh = url.toHttpUrl().queryParameter("reload") == "1"
        return HitomiProtocol.image(info(id, refresh), page, route(refresh))
    }
    override suspend fun detail(url: String): Detail = error("请使用漫画阅读器")
    override suspend fun index(section: String): List<Link> = emptyList()
    override suspend fun login(network: Network, username: String, password: String): Unit = error("Hitomi 公开浏览无需登录")
}

object HitomiProtocol {
    const val cdn = "https://ltn.gold-usergeneratedcontent.net"
    private val site = SiteRegistry.HITOMI
    private fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    data class Routing(val prefix: String, val default: Int, val overrides: Map<Int, Int>) {
        fun bucket(hash: String): Int { require(hash.matches(Regex("[0-9a-f]{64}"))) { "Hitomi 图片散列无效" }; return (hash.takeLast(1) + hash.takeLast(3).take(2)).toInt(16) }
        fun image(hash: String): String {
            val bucket = bucket(hash); val server = 1 + (overrides[bucket] ?: default)
            return "https://w$server.gold-usergeneratedcontent.net/$prefix$bucket/$hash.webp"
        }
        fun thumb(hash: String): String {
            val server = ('a'.code + (overrides[bucket(hash)] ?: default)).toChar()
            return "https://${server}tn.gold-usergeneratedcontent.net/webpbigtn/${hash.takeLast(1)}/${hash.takeLast(3).take(2)}/$hash.webp"
        }
    }
    fun routing(script: String): Routing {
        val prefix = Regex("""\bb\s*:\s*['"]([0-9]+/)['"]""").find(script)?.groupValues?.get(1) ?: error("Hitomi 图片路由格式已变化")
        val default = Regex("var\\s+o\\s*=\\s*([01])\\s*;").find(script)?.groupValues?.get(1)?.toInt() ?: error("Hitomi 图片路由格式已变化")
        val overrides = mutableMapOf<Int, Int>()
        Regex("((?:\\s*case\\s+\\d+\\s*:\\s*)+)o\\s*=\\s*([01])\\s*;\\s*break\\s*;").findAll(script).forEach { group ->
            Regex("case\\s+(\\d+)").findAll(group.groupValues[1]).forEach { match ->
                val key = match.groupValues[1].toInt(); require(key in 0..4095); overrides[key] = group.groupValues[2].toInt()
            }
        }
        check(overrides.isNotEmpty()) { "Hitomi 图片分片规则不可识别，请更新客户端" }
        return Routing(prefix, default, overrides)
    }
    fun ids(bytes: ByteArray): List<Int> {
        require(bytes.size % 4 == 0) { "Hitomi 索引长度无效" }
        check(bytes.size / 4 <= 500_000) { "Hitomi 查询结果过多，请使用更具体的关键词" }
        val buffer = ByteBuffer.wrap(bytes)
        return List(bytes.size / 4) { buffer.int.also { require(it > 0) { "Hitomi 索引编号无效" } } }
    }
    fun orderedMatches(bytes: ByteArray, matches: Set<Int>): List<Int> {
        require(bytes.size % 4 == 0) { "Hitomi 排行索引长度无效" }
        val buffer = ByteBuffer.wrap(bytes); val selected = ArrayList<Int>()
        while (buffer.hasRemaining()) {
            val id = buffer.int; require(id > 0) { "Hitomi 排行编号无效" }
            if (id in matches) selected.add(id)
        }
        return selected
    }
    data class Node(val keys: List<ByteArray>, val data: List<Pair<Long, Int>>, val children: List<Long>)
    fun node(bytes: ByteArray): Node {
        val b = ByteBuffer.wrap(bytes)
        fun count() = b.int.also { require(it in 0..16) { "Hitomi 搜索节点无效" } }
        try {
            val keys = List(count()) { val size = b.int; require(size in 1..32); ByteArray(size).also { b.get(it) } }
            val data = List(count()) { (b.long to b.int).also { require(it.first >= 0 && it.second in 1..16 * 1024 * 1024) } }
            require(keys.size == data.size)
            return Node(keys, data, List(17) { b.long.also { require(it >= 0) } })
        } catch (error: java.nio.BufferUnderflowException) { throw IllegalStateException("Hitomi 搜索节点不完整", error) }
    }
    fun compare(left: ByteArray, right: ByteArray): Int {
        for (i in 0 until minOf(left.size, right.size)) { val cmp = (left[i].toInt() and 255).compareTo(right[i].toInt() and 255); if (cmp != 0) return cmp }
        return left.size.compareTo(right.size)
    }
    fun query(request: FeedRequest) = when (request.kind) {
        FeedKind.MODEL -> "artist:${request.target.replace(' ', '_')}"
        FeedKind.CATEGORY -> "type:${request.target}"
        else -> request.target
    }
    fun terms(query: String): List<Pair<Boolean, String>> {
        val terms = Regex("-?(?:[^\\s\"]|\"[^\"]*\")+").findAll(query.lowercase()).map { it.value }.toList()
        require(terms.isNotEmpty() && terms.size <= 6) { "请使用 1–6 个查询词，带空格的标签可用下划线连接" }
        require(terms.any { !it.startsWith('-') }) { "请至少包含一个正向查询词" }
        return terms.map { it.startsWith('-') to it.removePrefix("-").replace("\"", "").replace('_', ' ') }.also {
            require(it.all { (_, term) -> term.isNotBlank() && term.length <= 200 && !term.contains('/') && !term.contains('#') }) { "Hitomi 查询格式无效" }
        }
    }
    fun nozomi(term: String, popular: Boolean = false): String {
        val b = cdn.toHttpUrl().newBuilder()
        if (term.isBlank()) return b.apply { if (popular) addPathSegment("popular").addPathSegment("year-all.nozomi") else addPathSegment("index-all.nozomi") }.build().toString()
        val field = term.substringBefore(':'); val value = term.substringAfter(':')
        require(field in setOf("artist", "group", "series", "character", "tag", "language", "type", "female", "male") && value.isNotBlank()) { "Hitomi 不支持此查询范围" }
        if (field == "language") {
            if (popular) b.addPathSegment("popular").addPathSegment("year-$value.nozomi") else b.addPathSegment("index-$value.nozomi")
        } else {
            b.addPathSegment(if (field == "female" || field == "male") "tag" else field)
            if (popular) b.addPathSegments("popular/year")
            b.addPathSegment("${if (field == "female" || field == "male") term else value}-all.nozomi")
        }
        return b.build().toString()
    }
    fun galleryId(raw: String): String? = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) }?.let {
        Regex("^/(?:galleries/(\\d+)|(?:doujinshi|manga|cg|gamecg|artistcg)/[^/]+-(\\d+))\\.html$").matchEntire(it.encodedPath)?.groupValues?.drop(1)?.firstOrNull { id -> id.isNotBlank() }
    }
    fun pageIdentity(raw: String): Pair<String, Int>? = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) }?.let { url ->
        Regex("^/reader/(\\d+)\\.html$").matchEntire(url.encodedPath)?.groupValues?.get(1)?.let { id -> url.fragment?.toIntOrNull()?.takeIf { it > 0 }?.let { id to it } }
    }
    fun linkRequest(raw: String): FeedRequest? {
        val url = raw.toHttpUrlOrNull()?.takeIf { it.isHttps && site.isSite(it.host) } ?: return null
        url.queryParameter("query")?.let { return FeedRequest(FeedKind.SEARCH, it) }
        return null
    }
    fun info(script: String, id: String): JsonObject {
        val match = Regex("^\\s*var\\s+galleryinfo\\s*=\\s*([\\s\\S]+?)\\s*;?\\s*$").matchEntire(script) ?: error("Hitomi 画廊信息格式已变化")
        val json = JsonParser.parseString(match.groupValues[1].removeSuffix(";")).asJsonObject
        check(json.text("id") == id) { "Hitomi 返回的画廊编号不一致" }
        if (json.get("blocked")?.toString() == "true") throw GalleryAccessException("原站不允许访问此画廊")
        return json
    }
    fun gallery(json: JsonObject, route: Routing): GalleryDetail {
        val id = json.text("id"); require(id.matches(Regex("[0-9]+")))
        val files = json.array("files") ?: error("Hitomi 未返回图页")
        check(files.size() > 0) { "此条目没有漫画图页" }
        val language = json.text("language_localname").ifBlank { json.text("language") }
        val first = files[0].asJsonObject
        val entry = Video(id, json.text("title").ifBlank { json.text("japanese_title") }, "${site.base}/galleries/$id.html", route.thumb(first.text("hash")), "${files.size()} 页", language, type = json.text("type"), site = site.id, mediaKind = MediaKind.GALLERY,
            thumbWidth = first.text("width").toIntOrNull() ?: 0, thumbHeight = first.text("height").toIntOrNull() ?: 0)
        val groups = linkedMapOf<String, MutableList<Link>>()
        for ((array, property, field) in listOf(Triple("artists", "artist", "artist"), Triple("groups", "group", "group"), Triple("parodys", "parody", "series"), Triple("characters", "character", "character"), Triple("tags", "tag", "tag"))) {
            json.array(array)?.forEach { raw ->
                val item = raw.asJsonObject; val name = item.text(property); if (name.isBlank()) return@forEach
                val prefix = if (array == "tags" && item.text("female") == "1") "female" else if (array == "tags" && item.text("male") == "1") "male" else field
                val query = "$prefix:${name.replace(' ', '_')}"
                val url = site.base.toHttpUrl().newBuilder().addPathSegment("search.html").addQueryParameter("query", query).build().toString()
                groups.getOrPut(if (array == "artists") "画师" else "标签") { mutableListOf() }.add(Link(if (array == "artists") name else query, name, url))
            }
        }
        return GalleryDetail(entry, files.size(), (1..files.size()).map { GalleryPageLink(it, "${site.base}/reader/$id.html#$it") }, files.size(), json.text("japanese_title"), language = language, description = listOf(json.text("type"), json.text("date")).filter { it.isNotBlank() }.joinToString(" · "), groups = groups)
    }
    fun image(json: JsonObject, page: Int, route: Routing): GalleryImage {
        val files = json.array("files") ?: error("Hitomi 未返回图页"); require(page in 1..files.size()) { "页码超出范围" }
        val id = json.text("id"); val base = "${site.base}/reader/$id.html"
        return GalleryImage(page, "$base#$page", route.image(files[page - 1].asJsonObject.text("hash")), if (page > 1) "$base#${page-1}" else null, if (page < files.size()) "$base#${page+1}" else null, "$base?reload=1#$page")
    }
}
