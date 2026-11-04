---
project_id: r34v-android
updated: 2026-11-03
status: proposal
scope: read-only audit + data isolation design
---

# 多站点数据隔离方案

## 结论

数据隔离不是"顺手加个前缀"，是**六条独立的数据通道**，其中三条存在**数据正确性缺陷（不是体验缺陷）**：

| 通道 | 现状 | 风险等级 |
| --- | --- | --- |
| 观看历史 | 全局单 key | 串数据 |
| 收藏 | 全局单 key | 串数据 |
| 搜索历史 | 全局单 key | 串数据 |
| 屏蔽词 | 全局单 key | 语义错位（slug 不通用） |
| 下载缓存 | key = `id:quality`，无站点维度 | **架构债（P0 级，见 2.1）** |
| Cookie / 会话 | 只认单 host，`clear()` 清全部 | **P0 登录串号** |

账号、播放设置（清晰度/倍速/全屏/自动缓存）、代理、DoH 属于**用户偏好**，应当全局共享，不能按站点切。

---

## 一、现状证据

`LibraryStore.kt` 的 key 全是扁平的：

```kotlin
fun history()   = list("watch_history_v1", Watch::class.java)          // :64
fun favorites() = list("favorites_v1", Favorite::class.java)           // :69
fun searches()  = list("native_search_history", String::class.java)    // :76
fun blockedTags() = list("settings_v1_blocked_tags", String::class.java) // :79
fun localVideos() = list("video_cache_v1", LocalVideo::class.java)     // :89
```

`Network.kt` 的 Cookie 只对单一 host 收发：

```kotlin
override fun loadForRequest(url: HttpUrl): List<Cookie> =
    if (Site.isSite(url.host)) cookies.filter { it.matches(url) } else emptyList()   // :55
```

`PlaybackStore.kt` 的缓存 key 没有站点维度：

```kotlin
fun key(video: Video, quality: String) = "${video.id}:$quality"        // :66
```

---

## 二、关键缺陷的推演

### 2.1 下载缓存 key 没有站点维度

**先纠正一个我差点写进方案的夸大**：我原本推断"两站 ID 会撞号"，实测发现当前并不重叠——

```
hanime1 样本 ID 范围     408323 – 408990   （40 万级）
rule34video 样本 ID 范围 4702519 – 4703941 （470 万级）
```

两者差一个数量级，**当前不会碰撞**。所以这条不是"正在发生的损坏"，而是**架构缺陷**：`key()` 的正确性依赖了"两个站点的 ID 空间碰巧不重叠"这个偶然事实。

它会在三种情况下变成真问题：

1. 接入第三个站点（尤其 ID 从 1 开始编号的站点，如 WordPress 类型）。
2. hanime1 的 ID 增长到 400 万级（长期必然，只是时间问题）。
3. 任何站点重构 ID 方案。

碰撞发生时后果是**数据损坏而非体验问题**：
- `key("408990", "720p")` 在两站返回同一字符串
- Media3 `SimpleCache` 按 `customCacheKey` 索引，**第二个下载直接复用第一个的文件**
- `DownloadRequest.setCustomCacheKey` 也用它，**磁盘索引本身就会互相覆盖**

结论不变（必须加前缀），但**优先级从"正在损坏"降为"必须在上线前修掉的架构债"**——因为修它只需要一行，而错了不可逆。

### 2.2 Cookie 串号（登录态污染）

现在 `SessionCookies.cookies` 是一个不分域的 `MutableList<Cookie>`。一旦放开多站点：

- `saveFromResponse` 会存下两个站点的 Cookie
- `loadForRequest` 返回时靠 `it.matches(url)` 过滤，理论上能挡
- **但 `clear()` 是清空全部**（`Network.kt:56`）—— 退出 hanime1 会把 rule34video 的登录一起退掉
- 且 `store.logout()` 删的是全局 `native_cookies` 和 `auth_session_v1`

### 2.3 历史/收藏的 URL 重建错误

`Watch.video()` 和收藏列表都用 `Site.absolute(path)` 重建 URL：

```kotlin
fun video() = Video(id, title, Site.absolute(path), thumb)      // Models.kt:43
```

`Site.absolute` 拼的是**当前站点的 base**。若 hanime1 的收藏记录存了相对路径，切到 rule34video 后打开会拼成 `https://rule34video.com/watch?v=408990` —— 一个不存在的地址。

**这是设计层面的错**：跨站点的历史/收藏必须存**绝对 URL**，不能存"相对路径 + 全局 BASE"的组合。

---

## 三、设计方案

### 3.1 存储分层：全局 vs 站点

```
SharedPreferences "r34v_native"
├── [全局] settings_v1_*          清晰度/倍速/全屏/自动缓存/代理/DoH/自动更新
├── [全局] active_site            当前站点 id
├── [全局] site_registry_v1       站点列表与启用状态
└── [站点] <siteId>:<name>        以下全部加前缀
        ├── watch_history_v1
        ├── favorites_v1
        ├── native_search_history
        ├── blocked_tags
        ├── video_cache_v1
        ├── native_cookies
        ├── auth_session_v1
        ├── native_username
        └── site_ip_pool_v1
```

**判断某条数据归哪类，只看一个问题：换站点后它还成立吗？**

- 清晰度偏好 720p → 换站点仍成立 → 全局
- 「屏蔽词 = 触手」→ hanime1 和 rule34 的标签体系不同 → 站点
- 「已看过第 3 集」→ 必须站点
- 「代理地址」→ 全局

### 3.2 LibraryStore 改造

```kotlin
class LibraryStore(context: Context, name: String = "r34v_native", legacyName: String = "FlutterSharedPreferences") {
    @Volatile var siteId: String = DEFAULT_SITE_ID
    private fun k(name: String) = "$siteId:$name"

    fun history()   = list(k("watch_history_v1"), Watch::class.java).filter { it.id.isNotBlank() }
    fun favorites() = list(k("favorites_v1"), Favorite::class.java).filter { it.target.isNotBlank() }
    fun searches()  = list(k("native_search_history"), String::class.java)
    fun blockedTags() = list(k("blocked_tags"), String::class.java)
    fun localVideos() = list(k("video_cache_v1"), LocalVideo::class.java)

    // 全局：不加前缀
    fun string(n: String, d: String = "") = prefs.getString("settings_v1_$n", d) ?: d
    fun bool(n: String, d: Boolean = false) = prefs.getBoolean("settings_v1_$n", d)
}
```

**注意 `clearHistory()` 这类删除方法也要走 `k()`**，否则会删错站点。

### 3.3 一次性迁移（必须可回滚）

沿用现有 `kotlin_migration_v1` 的风格：**复制而非搬移**，原件保留。

```kotlin
private fun migrateToSiteNamespaced() {
    if (prefs.getBoolean("site_namespace_v1", false)) return
    val edit = prefs.edit()
    val moved = listOf("watch_history_v1", "favorites_v1", "native_search_history",
                       "native_cookies", "auth_session_v1", "native_username", "video_cache_v1")
    for (key in moved) {
        // 只复制到第一个站点（r34v），不删除原 key
        val value = prefs.all[key] ?: continue
        val target = "$DEFAULT_SITE_ID:$key"
        if (prefs.contains(target)) continue
        when (value) {
            is String -> edit.putString(target, value)
            is Set<*> -> edit.putStringSet(target, value.filterIsInstance<String>().toSet())
            is Boolean -> edit.putBoolean(target, value as Boolean)
        }
    }
    // 旧的 settings_v1_blocked_tags 也要挂到 r34v 名下，然后从全局移除
    prefs.getString("settings_v1_blocked_tags", null)?.let {
        if (!prefs.contains("$DEFAULT_SITE_ID:blocked_tags")) edit.putString("$DEFAULT_SITE_ID:blocked_tags", it)
    }
    edit.putBoolean("site_namespace_v1", true)
    check(edit.commit()) { "无法迁移站点数据，请检查存储空间" }
}
```

**回滚**：原 key 全部保留，只要把 `site_namespace_v1` 置 false 并让 `k()` 退化成恒等函数即可。

### 3.4 模型加站点字段

```kotlin
data class Watch(..., @SerializedName("site") val site: String = DEFAULT_SITE_ID)
data class Favorite(..., @SerializedName("site") val site: String = DEFAULT_SITE_ID)
data class LocalVideo(..., @SerializedName("site") val site: String = DEFAULT_SITE_ID)
data class DownloadMeta(val video: Video, val quality: String, val site: String = DEFAULT_SITE_ID)
```

Gson 对缺字段自动取默认值，老数据无需改写。已有先例：`LibraryStore.kt:59` 就在给老 `Favorite` 补 `t` 字段。

**双保险**：前缀保证读写不串，字段保证即使前缀失效也能过滤。冗余，但这类数据错了不可逆。

### 3.5 存绝对 URL，废弃相对路径

```kotlin
// 旧：Watch.path 存 "/video/42/example/"，靠 Site.absolute 重建
// 新：写入时即存绝对 URL
store.saveWatch(Watch(id, title, thumb, absoluteUrl, position, duration, quality = label, site = siteId))
```

`Site.absolute()` 在读取侧应当**只在 path 已是相对路径时**才拼，且拼到自己站点的 base：

```kotlin
fun resolveVideo(watch: Watch): Video {
    val url = if (watch.path.startsWith("http")) watch.path
              else SiteRegistry.byId(watch.site)?.let { "${it.base}${watch.path}" } ?: watch.path
    return Video(watch.id, watch.title, url, watch.thumb)
}
```

### 3.6 Cookie 按 host 分桶

参考项目的 `HCookieJar` 就是按 `url.host` 分桶，这个设计是对的：

```kotlin
class SessionCookies(private val store: LibraryStore) : CookieJar {
    private val buckets = mutableMapOf<String, MutableList<Cookie>>()

    private fun registered(host: String) = SiteRegistry.all().any { it.host == host || host == "www.${it.host}" }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!registered(url.host)) return emptyList()          // CDN 一律不发送
        return (buckets[url.host].orEmpty() + persisted(url.host))
            .filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!registered(url.host)) return
        val bucket = buckets.getOrPut(url.host) { mutableListOf() }
        for (cookie in cookies) {
            bucket.removeAll { it.name == cookie.name && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) bucket.add(cookie)
        }
        persist(url.host, bucket)
    }

    fun clear(host: String) {
        buckets.remove(host)
        store.prefs.edit().remove("$host:native_cookies").apply()
    }
}
```

**退出登录只清当前站点**，这是"隔离"最容易被忽略的一条。

### 3.7 下载与缓存

```kotlin
fun key(siteId: String, video: Video, quality: String) = "$siteId:${video.id}:$quality"
```

**不能按站点分 `SimpleCache` 目录**：Media3 的 `SimpleCache` 同一目录不允许开两个实例，而 `DownloadManager` 是应用级单例。一个目录 + key 前缀是唯一正确解。

`PlaybackStore.kt:33-34` 的两个 cache 目录保持不变，只改 key 生成处和 `DownloadRequest.Builder(key, uri)`。

### 3.8 网络记忆按站点分

```kotlin
// Network.kt:76-77
loadSitePool = { loadPool(Site.current.host) }
saveSitePool = { ips -> savePool(Site.current.host, ips) }
```

IP 池必须按 host 存，否则 hanime1 学的 Cloudflare 边缘 IP 会被当成 rule34video 的地址池，反过来也一样——这是"页面打不开但诊断显示成功"的直接成因。

---

## 四、验证方案

### 4.1 JVM 单测（可离线）

```kotlin
@Test fun sameVideoIdAcrossSitesDoesNotShareCacheKey() {
    assertEquals("r34v:408990:720p", PlaybackStore.key("r34v", Video(id = "408990"), "720p"))
    assertEquals("hanime1:408990:720p", PlaybackStore.key("hanime1", Video(id = "408990"), "720p"))
    assertNotEquals(
        PlaybackStore.key("r34v", Video(id = "408990"), "720p"),
        PlaybackStore.key("hanime1", Video(id = "408990"), "720p"),
    )
}

@Test fun historyIsNamespacedPerSite() {
    store.siteId = "r34v";    store.saveWatch(Watch("1", "A", ...))
    store.siteId = "hanime1"; assertEquals(0, store.history().size)
    store.saveWatch(Watch("1", "B", ...))
    store.siteId = "r34v";    assertEquals("A", store.history().single().title)
}

@Test fun legacyDataMigratesToDefaultSiteOnly() { /* 造旧 key，迁移后 r34v 有、hanime1 空、原 key 仍在 */ }

@Test fun logoutOnlyClearsCurrentSite() { /* 两站点各存 Cookie，清一个，另一个还在 */ }
```

### 4.2 真机验收（不可跳过）

1. 装 2.1.3，造历史+收藏+下载。
2. 覆盖升级到新版本。
3. 检查：r34v 的历史/收藏/下载**全部还在**，hanime1 是空的。
4. 切到 hanime1，收藏一个片子。
5. 切回 r34v，确认 r34v 的收藏列表**没有多出** hanime1 那条。
6. 两个站点各下一个**同 ID** 视频（可以用改包名的调试版构造），确认磁盘上没有互相覆盖。

第 3 步是最容易出事的地方，**必须真机覆盖升级**，模拟器干净安装测不出来。

---

## 五、执行顺序与依赖

数据隔离的三条（LibraryStore / SessionCookies / PlaybackStore）**必须同一个提交内完成**。中间状态会导致：写历史用新前缀、读下载用旧 key，数据在半空中分裂。

```
SiteContext 落地
      ↓
┌─────────────────────────────────┐
│ 同一个改动批次                    │
│ 1. LibraryStore 命名空间+迁移     │
│ 2. SessionCookies 按 host 分桶   │
│ 3. PlaybackStore key 前缀        │
│ 4. 模型加 site 字段              │
│ 5. 绝对 URL 改造                  │
└─────────────────────────────────┘
      ↓
  真机覆盖升级验收
      ↓
   接 HanimeAdapter
```

---

## 六、明确不做

- **不做跨站点聚合视图**（"全部历史/全部收藏"）。技术上可行（按 `site` 字段合并再分组），但会让列表语义混乱，且要重做 UI。后续独立做。
- **不迁移到数据库**。当前 SharedPreferences + Gson 在 500 条历史上没有性能问题，换 Room 是纯重构，无收益。
- **不做站点数据导出/导入**。属于备份功能，与本次无关。
- **不动签名、包名、版本号规则**。
