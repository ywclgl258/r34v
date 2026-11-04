---
project_id: r34v-android
updated: 2026-11-03
revision: 2
status: proposal
scope: read-only audit + site adapter design for hanime1.me
---

# hanime1.me 接入方案（首版含已修正的错误结论）

> **2026-11-04 当前实现**：本文保留早期调研记录，以下固定 IP/Host 直连方案已退役。现统一跟随系统代理，未设置时系统 DNS 直连；使用电脑代理 `127.0.0.1:7897` 已实际通过列表、详情、媒体读取及原生 Media3 播放。当前行为以 [系统网络说明](system-network-auth-masonry.md) 为准。

> **rev 2 说明**：本文件的前一版在 IP 可达性上给出了**错误结论**，原因是本机开着系统代理 `127.0.0.1:7897`，而 `curl --resolve` 在走代理时会被忽略——所有"强制 IP"的测试其实都穿透到了代理，等于什么都没测。本版用 `--noproxy '*'` 重测后，三条结论被推翻，已在下文标注。

## 结论

hanime1.me 是 **Laravel 服务端渲染**站点，和 KVS 完全两套。现有 `HtmlParser` 一行不能复用，必须新写 `HanimeAdapter`。

**但是**：站点在 Cloudflare 后面，且有地区限制。这使它的事实形态更接近"rule34video 那个直连问题"的**加重版**，而不是"一个普通 KVS 站替换成另一个"。真正的难点不在解析，在网络路径。

---

## 一、实测结果（修正后）

### 1.1 网络路径 —— 三条被推翻的结论

| 我上一版说的 | 实测（绕过代理后） | 真相 |
| --- | --- | --- |
| "给 hanime1 开 direct_ip 会触发 403" | 内置 CF IP 直连 → **200** | **反了**。直连是唯一能用的方式 |
| "RoutedDns 那个硬编码 IP 对 hanime1 是错误引导" | 同理 | **反了**。hanime1 恰恰需要它 |
| "Cloudflare 按 UA 拦截" | 仅 UA、无任何其他头 → 200；去掉 UA → 403 | 正确，且是唯一门槛 |

**实测数据（`--noproxy '*'`）**：

```
系统 DNS 解析 hanime1.me      → 31.13.69.245   （Facebook 网段，污染）
  用这个 IP 直连               → 000 失败
vdownload.hembed.com（CDN）    → 系统解析成功，但直连 → 000（地区封锁）
DoH 端点 cloudflare-dns.com    → 直连 000（同样被封）
```

**结论**：直连路径上**系统 DNS 被污染、CDN 被封、DoH 端点不可达**。唯一能通的是「已知 Cloudflare 边缘 IP + TLS SNI 保持域名」这一条路。

这正是参考项目 `HDns.kt` 存在的原因 —— 它内置了 `104.26.0.18` / `104.26.1.18` / `172.67.69.183`。**我实测这三个 IP 直连全部返回 200，仍然有效。**

### 1.2 已验证可用的完整路径

| 场景 | 结果 |
| --- | --- |
| 系统 DNS + 直连 | ✗ `000`（污染） |
| 内置 CF IP + 直连 + 任意 UA | ✓ `200` |
| 内置 CF IP + 直连 + **无 UA** | ✗ `403` |
| 内置 CF IP + 直连 + **Android UA** | ✓ `200` |
| 内置 CF IP + 直连 + 详情页 | ✓ `200` |
| 代理 + 任意 | ✓ `200`（代理是应急手段，不是默认路径） |
| 视频 CDN + 直连 | ✗ `000`（地区封锁） |
| 视频 CDN + 代理 | ✓ `206` |
| 视频 CDN 用站点 CF IP | ✗ `403`（反证 R34V 的 `cdn_direct` 逻辑是对的） |

**Android UA 也返回 200** —— 这条很重要：R34V 现有 `Site.UA` 就是一个 Android Chrome 串，**不用为 hanime1 改 UA**，只要保证 UA 非空。上一版说要换桌面 UA，是多余的。

### 1.3 页面结构（这部分上一版是对的）

首页 229 KB、列表 160 KB、详情 365 KB，全部 200。播放源：

```html
<video id="player" poster="...">
  <source src="https://vdownload.hembed.com/408990-720p.mp4?secure=...,1793743259" type="video/mp4" size="720">
  <source src="https://vdownload.hembed.com/408990-480p.mp4?secure=...,1793743259" type="video/mp4" size="480">
</video>
```

无 Referer 校验，Range 正常 206。

### 1.4 参考项目的选择器已失效（这条仍然成立）

Han1meViewer 的 `Parser.kt` 用 `div[class^=card-mobile-panel]` 抓列表，实测当前站点 **0 次命中**。站点已改版成 `video-item-container` + `horizontal-card`。`home-rows-videos-div`、`home-rows-videos-title` 同样 0 次。

**照抄参考项目的解析器会得到 0 条结果。** 本方案选择器按当前实测 HTML 编写，已验证：

```
id=408990 dur=12:20 title=ネルとおうちデート rating=97% views=1.1萬次
id=408989 dur=03:50 title=ナヒーダ L2dアニメーション rating=99% views=1.4萬次
```

搜索页 61 张卡里 59 张真视频，2 张 `l.erodalabs.com` 广告卡被 `a.video-link[href*=watch]` 排除。

---

## 二、和参考项目的逐项对比

| 维度 | Han1meViewer | R34V 现状 | 本方案 |
| --- | --- | --- | --- |
| HTTP 栈 | Retrofit + kotlinx.serialization | OkHttp + Jsoup | **沿用 OkHttp + Jsoup**（多数请求是 HTML，Retrofit 无收益） |
| DNS | 内置 hosts 表，直接返回固定 IP | DoH + 已验证 IP 池 + 污染回退 | **双轨**：给 SiteContext 加 `pinnedIps`，优先置入 IP 池 |
| UA | 全局拦截器固定 Windows Chrome | 全局拦截器固定 Android Chrome | 按 host 查表，hanime1 用现有 Android UA 即可 |
| Cookie | `cookieMap[url.host]`，按 host 分桶 | 只在 `Site.isSite` 时收发 | **按 host 分桶**，与站点隔离天然一致 |
| 地区限制处理 | 报 `IPBlockedException`，提示别用日本 IP | 无 | **新增**：识别 403 正文，给可操作提示 |
| 模板容错 | 双分支（普通/简化），解析失败返回 null 跳过 | 抛异常终止整页 | **采用其容错思路**，返回 null 跳过 |
| 备用域名 | `hanime1.com` 可切 | 无 | 放 `SiteContext` 字段，暂不启用 |
| 播放源 | `<source>` 优先，退化到 `const source` | KVS flashvars | **双路径**，与参考项目一致 |
| 请求重试 | 无 | 换连接重试一次 | 保留 R34V 的，加"换 IP 重试" |

参考项目值得借鉴的三个点：**DNS hosts 表、Cookie 按 host 分桶、解析容错跳过**。不值得借鉴的：Retrofit 一套（多一层抽象、多两个依赖，收益为零）。

---

## 三、适配器设计

### 3.1 站点上下文（新增网络字段）

```kotlin
data class SiteContext(
    val id: String,
    val name: String,
    val base: String,
    val host: String,
    val alternateHost: String? = null,        // hanime1.com，暂不启用
    val userAgent: String = DEFAULT_UA,
    val referer: String = "$base/",
    val mode: SiteMode = SiteMode.NATIVE,
    val pinnedIps: List<String> = emptyList(),      // ← 新增：已知边缘 IP，防 DNS 污染
    val proxyRequired: Boolean = false,             // ← 新增：媒体是否必须走代理
    val accent: Int = 0xFFE50914.toInt(),
)
```

`pinnedIps` 的实现接进 `RoutedDns`：作为 `stored` 池的第一优先级候选，**不等待 DoH**。

```kotlin
val manual = if (site) settings.customIps... else emptyList()
val pinned = if (site) site.pinnedIps.mapNotNull(::ipv4) else emptyList()   // ← 新增
val stored = if (site) goodSiteIps.mapNotNull(::ipv4) else emptyList()
// 候选顺序：手填 > pinned > 已验证 > DoH > 系统DNS
```

这比参考项目的"完全绕过 DNS"更保守：**DoH 仍然参与**（拿到实时 IP 更好），只是 `pinnedIps` 让它不必等。

### 3.2 列表解析

```kotlin
fun feed(html: String, request: FeedRequest): FeedPage {
    val doc = Jsoup.parse(html)
    val videos = doc.select("div.video-item-container").mapNotNull { card ->
        val link = card.selectFirst("a.video-link[href*=watch]") ?: return@mapNotNull null
        val url = link.absUrl("href").ifBlank { link.attr("href") }
        val id = Regex("[?&]v=(\\d+)").find(url)?.groupValues?.get(1) ?: return@mapNotNull null
        val stats = card.select(".stats-container .stat-item")
        Video(
            id = id,
            title = card.selectFirst(".title")?.text()?.trim()?.ifBlank { null }
                ?: card.attr("title").trim(),
            url = url,
            thumb = card.selectFirst("img.main-thumb")?.absUrl("src").orEmpty(),
            duration = card.selectFirst(".duration")?.text()?.trim().orEmpty(),
            rating = stats.getOrNull(0)?.text()?.trim().orEmpty(),
            views  = stats.getOrNull(1)?.text()?.trim().orEmpty(),
        )
    }.distinctBy { it.id }
    val total = doc.select("ul.pagination a[href*=page]").mapNotNull {
        Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull() ?: request.page
    return FeedPage(videos, request.page, total, doc.selectFirst("h1, .horizontal-row-title h3")?.text().orEmpty())
}
```

### 3.3 详情解析

```kotlin
fun detail(html: String, fallbackUrl: String = ""): Detail {
    val doc = Jsoup.parse(html)
    val id = Regex("[?&]v=(\\d+)").find(fallbackUrl)?.groupValues?.get(1)
        ?: error("详情页无法识别：缺少视频 ID")

    // 双路径，与参考项目一致
    val streams = doc.select("video#player source[src]").map { src ->
        Stream(src.attr("size").let { if (it.isBlank()) "原画" else "${it}p" },
               src.absUrl("src").ifBlank { src.attr("src") })
    }.ifEmpty {
        Regex("""const source = '(.+?)'""").find(html)?.groupValues?.get(1)
            ?.let { listOf(Stream("原画", it)) }.orEmpty()
    }
    require(streams.isNotEmpty()) { "详情页没有播放地址，可能需要登录或站点已改版" }

    val meta = doc.selectFirst("div.video-details-wrapper") { it.text().contains("觀看次數") }?.text().orEmpty()
    val tags = doc.select("div.single-video-tag > a").map { it.text().trim() }.filter { it.isNotBlank() }
    val artist = doc.getElementById("video-artist-name")?.text()?.trim().orEmpty()
    // ... groups / description / related 同上一版
}
```

### 3.4 路由模板

| 语义 | KVS | hanime1 |
| --- | --- | --- |
| 最新 | `/latest-updates/` | `/search?sort=最新上市` |
| 热门 | `/most-popular/` | `/search?sort=本日排行` |
| 高分 | `/top-rated/` | `/search?sort=讚好比例` |
| 分类 | `/categories/<slug>/` | `/search?genre=裏番` |
| 标签 | `/tags/<slug>/` | `/search?tags[]=巨乳` |
| 搜索 | `/search/<q>/` | `/search?query=<q>` |
| 分页 | `/<n>/` | `&page=<n>` |
| 详情 | `/video/<id>/` | `/watch?v=<id>` |

所以 `FeedRequest.path` 必须从"拼路径"改成"适配器拼 URL"。

可用 `sort`：最新上市 / 最新上傳 / 本日排行 / 本週排行 / 本月排行 / 觀看次數 / 讚好比例 / 時長最長 / 他們在看
可用 `genre`：裏番 / 泡麵番 / Motion Anime / 3DCG / 2.5D / 2D動畫 / AI生成 / MMD / Cosplay / 新番預告

### 3.5 登录

```kotlin
suspend fun login(email: String, pass: String) {
    val token = Jsoup.parse(network.html("$BASE/login"))
        .selectFirst("input[name=_token]")?.attr("value") ?: error("无法读取登录令牌")
    network.post("$BASE/login", FormBody.Builder()
        .add("_token", token).add("email", email).add("password", pass).build())
    check(network.statusOrNull("$BASE/login") == 404) { "邮箱或密码错误" }   // 站点设计如此
}
```

Cookie 名是 `hanime1_session`（不是 `PHPSESSID`）。**Cookie 必须按 host 分桶**——数据隔离的前提。

---

## 四、三个仍需处理的问题

### 4.1 播放链接有时效（P1）

URL 里 `?secure=...,1793743259` 逗号后是 Unix 时间戳（≈2026-11-04）。

**约束**：对 hanime1，收藏/历史/下载**只能存 `watch?v=ID`**，绝不存直链。`PlayerActivity` 已有的过期重取逻辑刚好覆盖；`PlaybackStore` 的失败重刷也能复用。

### 4.2 媒体必须走代理（P1，新发现）

实测：视频 CDN 直连 `000`，走代理 `206`。而站点本身直连可用。

这意味着 hanime1 的**页面和媒体路径可能不一致**：页面能用内置 IP 直连，视频必须走代理。对 `PlaybackStore` 来说，`DataSource.Factory` 是应用级单例，不能简单按站点切换代理。

方案：`SiteContext.proxyRequired` 标记媒体强制走代理；`PlaybackStore` 在构造 `DataSource.Factory` 时按 `dataSpec.uri.host` 选择是否套代理。**这一条需要先在用户真实网络复测**——本机 CDN 直连失败可能是本机网络策略，不一定是普遍情况。

### 4.3 模板会变（P1）

参考项目的教训：它 2025 还在维护 `card-mobile-panel`，现在全废。对策：

1. 解析器返回 `null` 而非抛异常，广告卡/异形卡静默跳过（学参考项目的容错思路）。
2. 每站点维护样本 HTML 进 `app/src/test/resources/`，改版先跑样本测试。
3. `Section`/`Sort` 用数据不用枚举。

---

## 五、实施步骤

| 步 | 内容 | 改动 | 依赖 | 数据隔离 |
| --- | --- | --- | --- | --- |
| 1 | 保存三份真实 HTML 进 `test/resources/hanime/` | 新增 3 | 无 | — |
| 2 | `HanimeAdapter.feed/detail` 纯解析 + JVM 单测 | 新增 2 | 1 | — |
| 3 | `SiteContext` / `SiteRegistry` 落地 | 改 2 | 无 | — |
| 4 | **`LibraryStore` 站点命名空间 + 一次性迁移** | 改 1 | 3 | ✅ 核心 |
| 5 | **`SessionCookies` 按 host 分桶** | 改 1 | 3 | ✅ Cookie |
| 6 | **`PlaybackStore` 缓存 key 加站点前缀** | 改 1 | 3 | ✅ 下载 |
| 7 | `RoutedDns` 接 `pinnedIps` + 按 host 的 IP 池 | 改 1 | 3 | ✅ 网络 |
| 8 | `Repository` 委托到适配器 | 改 1 | 2, 4 | — |
| 9 | 站点切换 UI（顶栏标题下拉） | 改 1 | 8 | — |
| 10 | 模拟器实测全链路 | 验收 | 9 | ✅ 验证 |

步 1–2 完全离线可做，不碰现有代码。步 4–6 是数据隔离，必须**一起做完**再测，中间状态会串数据。

---

## 六、明确不做

- 不抄 Retrofit + kotlinx.serialization。多数请求是 HTML，Jsoup 直接处理，加两个依赖换零收益。
- 不接评论、播放列表、订阅、点赞（要 `_token` + 登录态 + 多组 POST，属于站点特色功能）。
- 不做 `hanime1.com` 备用域名切换，先跑稳主域名；但字段留好。
- 不碰 `hanimeone.me/comics`（图集，属 Phase 4 异构内容）。

---

## 七、验证边界

**已实测**：代理污染导致的首轮误判已修正；内置 CF IP 直连 200；无 UA 则 403；Android UA 可行；CDN 直连 000 / 代理 206；CDN 用站点 IP 403；页面结构、播放源、Range、登录表单、列表与详情选择器。

**未验证**：
- 内置 CF IP 的有效期（Cloudflare 边缘 IP 会轮换，参考项目的表也可能随时失效）。
- 视频 CDN 的地区策略是否与用户所在网络一致（本机失败≠普遍失败）。
- 登录成功后的完整 Cookie 集合（需真实账号）。
- `related-tabcontent` 内部是否稳定复用 `video-item-container`。
