---
project_id: r34v-android
updated: 2026-11-04
status: site-categories-and-photo-archives
scope: 站点分类显示 + 5 个写真站接入
---

# 站点分类与写真站接入（2026-11-04）

## 一、这一轮做了什么

用户提出两件事：站点列表要按内容类型分组显示，并新增写真站。经确认后，写真站共五个：
4KHD、Everia、4KUp、GalleryEpic、Cosplaytele。**18comic 未接入**（用户明确撤回，见第四节）。

站点从 7 个变为 12 个，按三类分组显示：

| 分类 | 站点 | 数量 |
| --- | --- | --- |
| 视频 | Rule34Video、Hanime1、Iwara | 3 |
| 漫画 | E-Hentai、ExHentai、nhentai、Hitomi | 4 |
| 写真 | 4KHD、Everia、4KUp、GalleryEpic、Cosplaytele | 5 |

## 二、分类为什么单独一个类型

`MediaKind`（VIDEO / GALLERY）回答的是"用什么界面打开"，而不是"这是什么内容"。写真集和漫画都走
`GalleryActivity` 逐页阅读，但把写真叫成"漫画"是错的，把漫画叫成"写真"也是错的。

所以新增 `SiteCategory`（VIDEO / COMIC / PHOTO），每个站点声明一个，`SiteRegistry.ofCategory()` 按组分流。
`SiteCategory` 自带 `itemNoun`（视频 / 漫画 / 写真集）和 `verb`（观看 / 阅读 / 浏览），UI 一律从这两个字段取词：

- 首页标题：`最新${itemNoun}` → 写真站显示"最新写真集"
- 详情按钮：`开始${verb}`、`收藏${itemNoun}`、`下载全部图片`
- 片库分页：`${verb} / 收藏 / 下载`，记录名"还没有浏览记录"
- 搜索页：`寻找想看的${itemNoun}`

`SiteAdapter.itemNoun` 从 `site.category.itemNoun` 取值，适配器仍可覆盖。

## 三、五个写真站的技术差异（都已实测）

| 站点 | 形态 | 图集长度 | 图片源 | 关键点 |
| --- | --- | --- | --- | --- |
| 4KHD | WordPress + Jetpack CDN | 4 个内容分页，20 图/页，共 79 | `i0.wp.com/pic.4khd.com` | 文章 ID 是 slug，数字段是年份桶 |
| Everia | WordPress | `/slug/2/` 分页，15 图/页 | `miya.fujibox.top` | 日期式路径 `/YYYY/MM/DD/slug/` |
| 4KUp | WordPress (GeneratePress) | 单页内联，26 图 | `blogger.googleusercontent.com` | `src` 是 data: 占位，真实地址在 `<a href>` |
| GalleryEpic | Next.js (Turbopack) | 单页 | `static.galleryepic.xyz` | 只渲染 12 张预览，完整 id 列表在服务端 payload 里 |
| Cosplaytele | WordPress (Flatsome) | 单页内联，99 图 | 站内 `/wp-content/uploads/` | `#gallery-1` 之后是推荐区，不能算进本集 |

### 共用的分页映射

写真集对阅读器暴露成**一条扁平的页索引**，每个页地址自带 `#index-page-offset`：

```
https://everia.club/2026/11/04/slug/2/#13-2-0
                                    │  │ └ offset（该内容分页内的第几张，0 起）
                                    │  └ 内容分页号（站点认识的路径后缀）
                                    └ 整本第几张（1 起）
```

好处是**不需要记住"每页几张图"**。进程重启后直接读 fragment 就能定位，不依赖任何内存状态；
某个内容分页比首页短也不会错位（`galleryImage` 会往后翻着找，最多 4 页）。

4KHD 实测：第 25 张正确落在第 2 个内容分页的 offset 4（20+5=25），屏幕显示 `25 / 79`。

### 写真集与漫画共用阅读器

`GalleryActivity` + `ComicReader` + `GalleryRepository`（含整本下载）全部复用，只把文案参数化。
下载目录仍是 `R34V/comics/<站点>/`，写真集按站点独立存放，不与其他站混。

## 五、联调中发现并修掉的三个真实缺陷

这三个都是"单测全绿、真机上却打不开"的问题，记录在这里以免以后重犯。

### 1. IPv6 优先导致整站打不开（`data/Ipv4FirstDns.kt`）

`everia.club` 同时有 A 和 AAAA 记录。OkHttp 的 `connectTimeout` 是**整段建连预算**，不是每个候选地址各给一份；
先试一个不可达的 IPv6 地址就吃光 5 秒，IPv4 明明可达也报"连接超时"，很容易被误判成站点挂了。

修法是只调整地址顺序：新加 `Ipv4FirstDns`，包一层系统 DNS，把 IPv4 地址排到前面。不写死任何 IP、
不改超时、不影响 TLS 校验，纯 IPv6 网络下没有 IPv4 可排，顺序原样保留。

### 2. 图片源域名轮换，不能锁死主机（Everia）

Everia 的图片在站外 CDN 上，而且**按帖子换域名**：同一个站不同文章可能分别在 `fujibox.top`、
`karubox.top` 上。原来按 `fujibox.top` 过滤，遇到换域名的帖子就整帖读不到图，详情页报
"这条内容没有可读取的图片"。

改为不锁主机名：正文 `figure` 里的图片就是图集本身，图片直取不需要 Referer（实测带不带 Referer 都是 200）。
站点侧也不再登记固定媒体域，图片请求由代码显式携带站点身份，不依赖主机名匹配。

### 3. 封面取了惰性加载的占位符（4KUp）

4KUp 的 `img` 的 `src` 是 `data:image/gif;base64,...` 占位符，真实地址在 `<a href>` 上。
原来的 `cover()` 直接取正文第一个 `img` 的 `src`，详情页因此是一块空白封面（图片列表本身是对的）。

改为封面复用图集提取的同一套规则（`cover()` = 第一张图），并在测试里断言封面等于第一张图。

## 六、明确没做的

- **18comic.vip 未接入**。用户在本轮中途明确要求放弃（`不要 https://18comic.vip/ 了`）。实测该站
  对自动化访问返回 Cloudflare 托管质询（HTTP 403 + `Just a moment...`），绕过它需要持续维护的人机验证，
  不适合放进原生适配器。若以后要接，`SiteCategory.COMIC` 的分组、阅读器、整本下载都已就绪，只差一个适配器。
- 没有做"站点规则热更"，五个站的选择器写死在 `PhotoProfiles.kt`，站点改版需跟版。
- 没有接写真站的登录。五个站都是公开浏览，无需账号。
- 没有接写真站的打包下载链接（TeraBox / Mediafire / Gofile 等）。这些是站外的文件分享站，
  在应用内下载整本图集已经覆盖同样的需求。

## 七、验证状态

- JVM 单测 **121 项全绿**（原 86 + 4KHD 解析 11 + 写真引擎 15 + IPv4 优先 2 + 分类口径调整）。
- Debug Lint **0 errors / 62 warnings**。
- Android 实机（Android 16 模拟器）分批通过 5 项：
  - `SiteSwitcherUiTest.drawerTouchNavigationAndSiteSpecificLibrary` — 抽屉分组与触控、分站片库
  - `SiteSessionTest.sessionsSurviveReloadAndLogoutOnlyClearsTheSelectedSite` — 12 站会话重建与单站退出
  - `GalleryUiTest` 三项 — 漫画站阅读/语言筛选/离线恢复未受文案改动影响
- 五个写真站逐站真机联网验证，首页 / 详情 / 阅读器全部点开过：

| 站点 | 首页 | 详情 | 阅读器 |
| --- | --- | --- | --- |
| 4KHD | ✅ 最新写真集 | ✅ 79 张 / 4 分页 | ✅ 跳第 25 张跨页正确（`25 / 79`） |
| Everia | ✅ | ✅ 75 张 / 5 分页 | ✅ 第 16 张跨页正确（`16 / 75`） |
| 4KUp | ✅ | ✅ 26 张（封面修复后可见） | ✅ `1 / 26` |
| GalleryEpic | ✅ | ✅ 62 张 + 模特链接 | ✅ `1 / 62` |
| Cosplaytele | ✅ | ✅ 99 张 | ✅ `1 / 99` |

- 整本下载：Cosplaytele 一本 99 张完整下载到 `离线浏览` 状态，开飞行模式后仍能打开并翻页。
- 收藏与片库：GalleryEpic 一本点「收藏写真集」后按钮变「已收藏写真集」，片库「收藏 → 写真集」页显示
  该本封面与「62 张」；片库分页显示为「浏览 / 收藏 / 下载」，收藏分类为「写真集 / 模特 / 标签」，
  均按写真站的用词参数化。
- 写真站搜索：Cosplaytele 搜 `machi` 返回 8 条真实结果，页头显示"machi"，范围只有「关键词」一项
  （写真站没有标签/作者索引，所以不再显示那两个无效选项）。
- 截图见 `shots/photos/`（本地，不入 Git）。

### 联调环境说明（不是产品缺陷）

本机对 `everia.club` 的 DNS 被污染：系统解析返回 Facebook 网段（`31.13.106.4`），AAAA 返回 Teredo
网段（`2001::/32`），不同公共解析器还给出三个互不相关的 A 记录。本机浏览器能开是因为走了系统代理
（`127.0.0.1:7897`）。所以 Everia 的真机验证是把模拟器系统代理指到宿主机代理（`10.0.2.2:7897`）后做的，
与应用里的网络实现无关；应用本身仍按项目既定策略只跟随系统代理。


尚未验证：4KHD 的整本下载全量（Cosplaytele 已跑通，4KHD 只验证了阅读与跨页）、Android 7/8 运行、
系统 200% 字体与横屏下的写真站界面、写真集在下载中心的"图集"筛选口径的独立复测。

## 八、回滚

本轮改动集中在这些文件，删掉即回到"7 站 + 无分类"状态：

- 新增 `data/adapter/FourKhdAdapter.kt`、`PhotoArchiveAdapter.kt`、`PhotoProfiles.kt`、`data/Ipv4FirstDns.kt`
- 新增 `app/src/test/.../FourKhdParserTest.kt`、`PhotoArchiveTest.kt`、`Ipv4FirstDnsTest.kt`，
  及 `src/test/resources/{4khd,photos}/`
- 改动 `data/SiteContext.kt`（`SiteCategory` + 5 个站点）、`data/Network.kt`（适配器注册、IPv4 优先 DNS）、
  `data/adapter/SiteAdapter.kt`（`itemNoun` / `supportsLanguage`）、
  `MainActivity.kt`（抽屉分组、文案参数化）、`GalleryActivity.kt`、`ui/ComposeBrowse.kt`、
  `ui/ComposeDownloads.kt`、`ui/ComposeReader.kt`、`data/GalleryRepository.kt`、
  `downloads/GalleryDownloadStore.kt`、`downloads/GalleryDownloadService.kt`

站点数据按 `site.id` 命名空间隔离，新增站点不迁移、不改旧站数据，回滚不会丢用户记录。
