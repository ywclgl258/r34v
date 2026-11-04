---
project_id: r34v-android
updated: 2026-11-03
status: proposal
scope: read-only audit + architecture plan
---

# 多站点综合 APP：现状盘点与改造方案

结论先说：**当前工程是"单站点硬编码"，但所有接缝都已经存在**。解析、网络、存储、播放四层之间是干净的模型边界（`Repository` 出 `Video/Detail/FeedPage`，播放器只吃 URL），所以多站点不是重写，是一次**依赖反转 + 数据命名空间化**。真正的工作量不在"加站点"，而在"把编译期常量换成运行期上下文"。

---

## 一、现状盘点：单站点耦合清单

`object Site`（`data/Models.kt:7-14`）是整个应用的编译期单例，被 12 个文件 45 处引用。它看似只是四个常量，实际承担了"当前站点"的职责，但没有实例概念。

| # | 层次 | 位置 | 硬编码内容 | 切换站点后的直接后果 |
| --- | --- | --- | --- | --- |
| 1 | 站点常量 | `Models.kt:7-14` | `BASE` / `HOST` / `UA` / `absolute()` / `isSite()` | 全局只有一个 base，无法表达"另一个站点" |
| 2 | 路由模板 | `Models.kt:16-33` | `/latest-updates/`、`/most-popular/`、`/top-rated/`、`/for-you/`、`/search/<q>/<p>/`、`?sort_by=post_date\|video_viewed\|rating\|duration\|pseudo_rand` | 路径是 KVS 约定，别的站点路径结构完全不同 |
| 3 | HTML 解析 | `HtmlParser.kt:14-17` | `#custom_list_videos_latest_videos_list_items`、`.thumbs`、`div[data-video-card-id]`、`div.item.thumb` | 别的站点选不到卡片 |
| 4 | 字段解析 | `HtmlParser.kt:23-26` | `.thumb_title`、`.time`、`.video-views-count`、`.video-card-meta__rating`、`.futa`、`[data-preview]` | 字段全空 |
| 5 | 分页解析 | `HtmlParser.kt:34-42` | `button.page-of` 的 `Page X of Y` 文案 | 翻页失效 |
| 6 | 详情/播放源 | `HtmlParser.kt:73-142` | `kt_player` 的 `flashvars` JS 对象、`video_url`/`video_alt_url*`、`license_code`、`link[rel=canonical]`、`a[href*=download=true]` | 拿不到播放地址，直接抛"详情页没有播放地址" |
| 7 | 网络与 Cookie | `Network.kt:28-55,128` | Cookie 只对 `Site.isSite` 收发；每个请求强塞 `Site.UA` + `Referer: Site.BASE` | 跨站 Cookie 被丢弃；别的站 CDN 校验 Referer 会 403 |
| 8 | 登录 | `Network.kt:164-181` | KVS 表单：`remember_me_csrf_token`、`action=login`、`PHPSESSID` | 别的站点登录必失败 |
| 9 | DNS 路由 | `RoutedDns.kt:36-53,97-105` | 站点 key 固定为 `Site.HOST`；**硬编码兜底 IP `190.115.31.243`** | 切到别的站点时，这个 IP 指向错误主机 → 连接超时/串站 |
| 10 | 本地存储 | `LibraryStore.kt:64-104` | 扁平全局 key：`watch_history_v1`、`favorites_v1`、`native_search_history`、`settings_v1_*`、`native_cookies`、`site_ip_pool_v1` | **两个站点的历史、收藏、Cookie、画质设置混在一起** |
| 11 | 下载缓存 | `PlaybackStore.kt:66-67` | `key(video, quality) = "${video.id}:$quality"`；全局 `downloadCache`/`streamCache` | **两个站点的同 ID 视频互相覆盖缓存，播放出别人的片子**（数据正确性问题） |
| 12 | 媒体请求头 | `PlaybackStore.kt:38` | `DataSource.Factory` 全局写死 `Site.UA` + `Referer: Site.BASE` | 切站点后视频 403 |
| 13 | UI 语义 | `MainActivity.kt:107,436-498`、`PlayerActivity.kt:141-146,340-353` | 标题 "R34V"、清晰度档位 `360p..2160p`、排序项、分组名"角色/作者/分类/标签"、"登录站点账户" | 站点语义不一致时显示错误选项 |
| 14 | 标签词典 | `TagNames.kt` + `assets/tag-names.json` | 单一站点的 slug→中文映射 | 跨站标签翻译错位 |
| 15 | 深链 | `MainActivity.kt:76-81` | 只认 `/tags\|models\|categories/<slug>/` | 别的站点链接进不来 |

### 一个关键发现

第 3–6 条那些选择器**不是 rule34video 专属，而是 Kernel Video Sharing（KVS）平台的通用约定**：`kt_player.swf`、`flashvars` 对象、`video_alt_url`、`sort_by=post_date`、`/latest-updates/`、`/top-rated/`、`#list_categories_categories_list_items`、`data-video-card-id`，这些都是 KVS 全站统一的结构。

也就是说：**现有 `HtmlParser` 事实上已经是一个 KVS 平台解析器**。第一步"多站点"可以零新增解析器——注册多个同平台站点即可切换。verification 需要按具体 TARGET_HOST 实测确认（不是所有 tube 站都跑 KVS，也不是所有 KVS 站点都开了 `redesign:1` 的新模板）。

---

## 二、已经具备的可复用能力（为什么这不是重写）

- `Repository`（`Network.kt:184-205`）已经是"注入式"的：它只依赖 `Network`，返回纯模型。改成一个接口 + 多实现即可。
- `HtmlParser` 是纯函数：`String -> FeedPage/Detail`，不碰 Android API，可单测（现有 12 项 `ParserTest` 全部基于 HTML 样本）。
- `Video` / `Detail` / `FeedPage` / `Link` / `Stream` 是中性模型，足够表达任何视频站点。
- 播放器只吃 `Stream.url`（`PlayerActivity.kt:153-208`），Media3 原生支持直链 MP4/HLS，站点无关。
- 下载走 Media3 `DownloadManager`，按 `DownloadRequest.id` 索引，天然支持多来源。
- 网络层已把 DNS 路由抽象成 `RoutedDns(policy, providers, ...)`，把站点身份换成一个集合就能复用。

**障碍只有一条：所有站点身份都是编译期常量，且没有"站点"这个数据类型。**

---

## 三、三种可选方案

### 方案 A：多 Profile 原生适配器（推荐作为骨架）

每个站点一个 `SiteAdapter` 实现，返回统一模型。站点列表编译进 APK（后续可远程下发）。

- 优点：体验最好、最省流、可离线缓存、可定制字段、可做排序/筛选。
- 缺点：每个新站点要写适配器；站点改版要跟版。
- 适用：核心站点（3–5 个）。

### 方案 B：规则驱动（配置化刮削器）

站点定义成一份 JSON：base、列表选择器、卡片选择器、字段选择器、分页规则、详情页正则、播放源规则（`flashvars` / JSON-LD / `<video src>` / `.m3u8` 正则）。

- 优点：加站点不用发版，可做规则市场/用户导入。
- 缺点：表达力有限，遇到 JS 渲染、签名、加密源就无解；规则本身脆弱。
- 适用：A 的补充，作为"长尾站点"和"站点改版应急"的兜底。

### 方案 C：内嵌 WebView 站点模式（兜底层）

站点声明 `mode = WEB`，WebView 加载站点，注入脚本抓播放地址，再用原生 Media3 播放。

- 优点：**任意站点零成本接入**，"综合 APP"的野心靠它兑现；站点改版免疫。
- 缺点：广告、耗电、性能、Cloudflare 可能挡、登录态依赖 WebView 存储。
- 关键技术点：`shouldInterceptRequest` 抓 `.m3u8/.mp4`、`evaluateJavascript` 读 DOM/flashvars、`DownloadListener` 接管下载、自定义 UA、`CookieManager` 开启第三方 Cookie、`onPageFinished` 注入"用原生播放器打开"按钮。

### 推荐：A 作骨架 + B 作扩展 + C 作兜底

一个 `SiteProfile` 表里声明 `mode = NATIVE | RULE_BASED | WEB`，用户看到的是同一个站点列表，体验按能力分级降级。这样既深（原生站体验最好）又广（任何站点都能加）。

---

## 四、推荐架构

```
                    ┌──────────────────────────────┐
        UI 层        │ MainActivity / PlayerActivity │
                    │ 站点切换器 · 按站点记忆导航    │
                    └───────────────┬──────────────┘
                                    │ context 显式传入
                    ┌───────────────▼──────────────┐
       领域层        │ SiteRegistry  →  SiteAdapter │  ← 接口
                    │  feed/detail/index/search/login│
                    └───┬───────────┬───────────┬──┘
                        │           │           │
                  ┌─────▼────┐ ┌────▼─────┐ ┌───▼──────┐
                  │KvsAdapter│ │RuleAdapter│ │WebAdapter│
                  │(现有解析) │ │(JSON 规则)│ │(WebView) │
                  └─────┬────┘ └────┬─────┘ └───┬──────┘
                        └───────────┴───────────┘
                                    │
       基础设施      Network(site-aware) · RoutedDns(hosts)
                    LibraryStore(siteId 命名空间) · PlaybackStore(siteId 前缀)
```

### 4.1 站点上下文

```kotlin
// data/SiteContext.kt
enum class SiteMode { NATIVE, RULE_BASED, WEB }

data class SiteContext(
    val id: String,                     // "r34v" 稳定不变，用于存储命名空间
    val name: String,                   // "Rule34Video"
    val base: String,                   // "https://rule34video.com"
    val host: String,                   // "rule34video.com"
    val userAgent: String = DEFAULT_UA,
    val referer: String = "$base/",
    val mode: SiteMode = SiteMode.NATIVE,
    val accent: Int = 0xFFE50914.toInt(),
)
```

### 4.2 适配器契约

```kotlin
interface SiteAdapter {
    val site: SiteContext
    val capabilities: Set<Capability>          // FEED SEARCH TAG_INDEX RANKING LOGIN DOWNLOAD
    val sections: List<Section>                // 站点自己的入口清单，UI 不再写死
    val sorts: List<SortOption>
    val qualities: List<String>

    fun absolute(raw: String): String          // 站点内相对路径 → 绝对 URL
    suspend fun feed(request: FeedRequest): FeedPage
    suspend fun detail(url: String): Detail
    suspend fun index(section: String): List<Link>
    suspend fun login(user: String, pass: String)
}
```

`FeedKind` / `Sort` 从 `enum`（站点路由 + UI 文案糅在一起）拆成「抽象语义 + 每站点映射表」：

```kotlin
data class Section(val key: String, val title: String, val path: String)   // 站点自带
data class SortOption(val key: String, val title: String, val param: String)
```

`KvsAdapter` 的 `sections` / `sorts` 就是现在 `Models.kt:16-24` 那张表搬过去，行为完全不变。

### 4.3 兼容桥（第一步：零行为变化）

先让 45 处调用继续编译，再逐个改成显式传参：

```kotlin
object Site {
    @Volatile var current: SiteContext = SiteRegistry.entries.first().site
    val BASE get() = current.base
    val HOST get() = current.host
    val UA get() = current.userAgent
    fun absolute(raw: String): String =
        runCatching { URI(current.base).resolve(raw.trim()).toString() }.getOrDefault("")
    fun isSite(host: String): Boolean = SiteRegistry.entries.any {
        host == it.site.host || host == "www.${it.site.host}"
    }
}
```

### 4.4 存储命名空间

```kotlin
// 全局设置（代理、DoH、更新）保持单份；站点数据加前缀
class LibraryStore(...) {
    @Volatile var siteId: String = FIRST_SITE_ID
    private fun k(name: String) = "$siteId:$name"        // 只在站点数据上用

    fun history() = list(k("watch_history_v1"), Watch::class.java)
    fun favorites() = list(k("favorites_v1"), Favorite::class.java)
    fun searches() = list(k("native_search_history"), String::class.java)
    fun blockedTags() = list(k("blocked_tags"), String::class.java)
    fun string(n: String, d: String = "") = prefs.getString("settings_v1_$n", d) ?: d   // 全局不动
}
```

迁移规则：首次启动把所有**无前缀**的历史/收藏/搜索/屏蔽词 key 复制成 `r34v:<name>`，原件保留（与现有 `kotlin_migration_v1` 风格一致，可回滚）。

模型加字段：`Favorite.site`、`Watch.site`、`LocalVideo.site`、`DownloadMeta.site`，默认值 `"r34v"`，Gson 缺字段自动取默认（现有 `Favorite` 已有这种兼容先例，见 `LibraryStore.kt:59`）。

### 4.5 下载与缓存隔离

```kotlin
fun key(siteId: String, video: Video, quality: String) = "$siteId:${video.id}:$quality"
```

**不要按站点分 `SimpleCache` 目录**：Media3 的 `SimpleCache` 同一目录不能开两个实例，而 `DownloadManager` 是应用级单例。正确做法是**一个目录 + key 前缀**（`PlaybackStore.kt:33-34` 保持不变）。

### 4.6 媒体请求头按站点路由

`DownloadManager` 只接受一个 `DataSource.Factory`，但下载请求的 URI 是运行时才知道的。用一个按 host 路由的包装：

```kotlin
private val http = DataSource.Factory {
    object : HttpDataSource {
        private var impl: OkHttpDataSource? = null
        private fun implFor(host: String): OkHttpDataSource {
            val site = SiteRegistry.forHost(host)
            return OkHttpDataSource.Factory(network.mediaClient)
                .setUserAgent(site?.userAgent ?: DEFAULT_UA)
                .setDefaultRequestProperties(mapOf("Referer" to (site?.referer ?: "https://$host/")))
                .createDataSource()
        }
        override fun open(dataSpec: DataSpec): Long {
            impl = implFor(dataSpec.uri.host.orEmpty()); return impl!!.open(dataSpec)
        }
        // read/close/getUri/getResponseHeaders 转发
    }
}
```

`PlayerActivity` 更简单：每次进播放页都新建 `ExoPlayer`，直接用它所属站点的 `sourceFactory(site)` 即可。

### 4.7 DNS 与 IP 池

`RoutedDns` 需要三处改动：

1. `Site.HOST` → `SiteRegistry.hosts: Set<String>`；`isSite` 改成集合判断。
2. **硬编码兜底 IP `190.115.31.243` 必须变成 per-host 兜底表**（`RoutedDns.kt:53`）。这是切站点后"网页打不开"的头号原因——那个 IP 对任何其他站点都是错误引导。
3. IP 池 `site_ip_pool_v1` → `site_ip_pool_v1:<host>`，按站点独立记忆已验证 IP。

### 4.8 站点切换 UI

最小可行版本（不改导航结构）：

- 顶栏标题 `R34V`（`MainActivity.kt:107`）变成可点击的站点选择器，右侧加下拉箭头。
- 点击弹出 `BottomSheet`/`AlertDialog`：站点列表（图标 + 名称 + 类型标签 + 最近访问时间），底部「+ 添加站点」。
- 切换后：`store.siteId = id`、`Site.current = ctx`、`network.invalidate()`、`navigate("home", false)`、清 `backStack`。
- 站点选择持久化在全局设置 `settings_v1_active_site`。
- 底部四入口（首页/发现/搜索/片库）语义不变，内容随站点变化——这样用户不需要学第二套导航。

第二版再做"站点卡片墙"（第五个入口），把 logo、内容类型（视频/图集）、能力标签摆出来。

---

## 五、分阶段落地计划

### Phase 0 — 抽 SiteContext（零行为变化）

- 新增 `SiteContext` + `SiteRegistry`（只注册 rule34video）+ `Site` 改为委托。
- 改动：`Models.kt`、新增 2 文件。约 120 行。
- 验收：33 项 JVM 单测全绿；`assembleDebug` 通过；首页/详情/播放抽查无变化。
- 回滚：单文件级，删掉新文件还原。

### Phase 1 — 数据命名空间化（风险最高，必须独立做）

- `LibraryStore` 加 `siteId` 前缀 + 一次性迁移；`Favorite/Watch/LocalVideo/DownloadMeta` 加 `site` 字段。
- `PlaybackStore.key` 加站点前缀。
- `SessionCookies` 改成"对任一已注册站点 host 收发"，不再只认单 host。
- 改动：`LibraryStore.kt`、`Models.kt`、`PlaybackStore.kt`、`Network.kt`。约 400 行。
- 验收：**必须在模拟器上实测"从 2.1.3 覆盖升级后，历史/收藏/下载全部还在"**（这是最容易出事的一步）；新增 `SiteIsolationTest` 验证两个站点的同 ID 视频不串缓存。
- 回滚：迁移是复制而非搬移，旧 key 保留即可回退。

### Phase 2 — 适配器插件化 + 接入第二个站点

- `SiteAdapter` 接口；把 `HtmlParser` 重命名/包装成 `KvsAdapter`（行为不变）；`Repository` 委托到当前 adapter。
- 注册第二个 KVS 站点，`sections`/`sorts`/`qualities` 各自配置。
- 改动：`Network.kt`（Repository 段）、`HtmlParser.kt` 头部、新增 `adapter/` 包 3 文件。约 500 行。
- 验收：两个站点各跑一遍 首页 → 列表 → 详情 → 播放 → 收藏；`ParserTest` 扩成参数化，同一份 KVS 样本对两个 base 都能出结果。
- 风险：**`Site.absolute()` 在解析器和 UI 两处被用来重建 URL**（`HtmlParser.kt:20,117`、`MainActivity.kt:429`）。跨站点时会拼错 base。根治办法是**收藏/历史里只存绝对 URL，废弃"相对路径 + 全局 BASE"**。这件事建议在 Phase 1 一起做，否则 Phase 2 会埋隐性 bug。

### Phase 3 — Web 模式兜底

- `SiteWebActivity`：WebView + 自定义 UA + 第三方 Cookie + `shouldInterceptRequest` 抓 `.m3u8/.mp4` + 注入「用原生播放器打开」。
- 新增 2 文件 + manifest + 1 个图标。约 350 行。
- 验收：任意站点能打开、能起播、能返回；不注册任何新权限。
- 注意：`usesCleartextTraffic="false"` 已开（`AndroidManifest.xml`），只能加 HTTPS 站点；需要 HTTP 的话得单站点配 `networkSecurityConfig`。

### Phase 4 — 异构内容类型（图集/漫画/API 站点）

- 抽象 `MediaItem`（video / gallery / comic），播放器按类型分发到 `PlayerActivity` 或新的 `GalleryActivity`。
- 这一阶段是"综合"的真正门槛，也是唯一需要动模型层的地方。建议等前三阶段稳定后再单独立项。

### 规模与顺序

| 阶段 | 文件数 | 改动行数 | 风险 | 用户可见价值 |
| --- | --- | --- | --- | --- |
| 0 | 3 | ~120 | 极低 | 无（纯地基） |
| 1 | 4 | ~400 | **高**（迁移） | 无（防串数据） |
| 2 | 6 | ~500 | 中 | **高（可切换站点）** |
| 3 | 3 | ~350 | 中 | **高（任意站点）** |
| 4 | 8+ | ~800 | 高 | 高（真综合） |

先做 0+1+2 就能拿到 80% 的"多站点"体验；3 是广度保险；4 是第二期工程。

---

## 六、必须提前处理的坑

1. **缓存 key 冲突（数据正确性，P0）**：`PlaybackStore.kt:66` 的 `"${video.id}:$quality"` 在两个站点撞 ID 时会互相覆盖缓存，播放出别人的内容。必须先加站点前缀。
2. **`Site.absolute()` 跨站点拼错（P0）**：解析器把 `./x` 拼到"当前站点 base"上。若解析发生在切站瞬间或后台预取，URL 会指错站点。解法：模型里存绝对 URL。
3. **Referer 防盗链 403（P1）**：`PlaybackStore.kt:38` 与 `Network.kt:128` 都写死站点头。切站点后视频必挂。
4. **硬编码兜底 IP（P1）**：`RoutedDns.kt:53` 的 `190.115.31.243` 只对 rule34video 有效，对别的站点是错误引导，会造成"页面打不开但诊断显示成功"的假象。
5. **SimpleCache 单实例约束（P1）**：Media3 同一 cache 目录不能开两个 `SimpleCache`。分站点必须用 key 前缀，**不能分目录**，否则运行期抛异常。
6. **Cookie 域过滤过窄（P1）**：`Network.kt:47,55` 只对单 host 收发，切站点后登录态全丢。要改成"已注册站点的 host 集合"，且 CDN 仍不发送（安全边界不变）。
7. **KVS 登录表单不可通用（P2）**：`Network.kt:164-181` 硬编码 `remember_me_csrf_token`/`PHPSESSID`。多站点建议统一走 WebView 登录 + 共享 `CookieManager`，一次实现覆盖所有站点。
8. **`MainActivity` 已 519 行 God Activity**：加站点会让它继续膨胀。建议只在里面加"站点选择器"一个入口，其余按 Phase 拆出去，不要在这一轮动大手术（那是重写级改动）。

---

## 七、明确不做的事

- 不重写 UI 层（不切 Compose、不切 Fragment）——超出本轮授权范围，收益与风险不成比例。
- 不改签名/发布链路、不改包名、不动 `release.properties`。
- 不引入新依赖（WebView 是系统组件；规则解析用现有 Gson/Jsoup 足够）。
- 不追求"站点规则包在线热更"——先跑通内置多站点，热更是 Phase 5 的事。

---

## 八、待定决策（需要你拍板）

1. **首批站点范围**：只接同平台（KVS）站点，还是同时上 Web 模式覆盖任意站？（建议：先 2 个 KVS + 1 个 Web 验证三模式）
2. **切换入口位置**：顶栏标题下拉，还是底部加第五个「站点」入口？（建议先顶栏，改动最小）
3. **数据是否跨站共享**：历史/收藏默认按站点隔离（推荐），还是提供"全局聚合视图"？（建议先隔离，聚合视图以后加）
4. **是否接受 Phase 1 的迁移风险**：这一步会改本地存储结构，需要真机覆盖升级验证。

---

## 九、验证边界

本文件是只读审计 + 方案，未修改任何源码。基线：`testDebugUnitTest` 33 项全绿；工作区已有未提交的 UI 重构改动（`MainActivity.kt`、`PlayerActivity.kt`、`Views.kt`、`theme.xml`、`docs/ui.md`）作为前置状态。

未验证项：
- 各目标站点的实际 DOM 是否仍为当前 KVS 结构（需按 TARGET_HOST 实测）。
- `RoutedDns` 硬编码 IP 对 rule34video 之外的站点是否可移除/替换。
- Web 模式在 Cloudflare 挑战下的可用性。
