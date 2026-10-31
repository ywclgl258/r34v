# evidence · rule34video 站点探测证据

本目录记录 2026-10-31 对 `https://rule34video.com/` 的只读探测结果，供解析层单测与设计决策引用。所有请求均为普通 GET/HEAD，无登录、无写入。

## fixtures（真实抓取的 HTML，解析层单测的固定输入）

文件放在 [test/fixtures/](../test/fixtures/)，不放这里——测试直接读那份，
把同一批 400KB+ 的 HTML 存两份没有意义。下面这张表说明每份文件的来源与用途。

| 文件 | 来源 URL | 用途 |
|---|---|---|
| `latest-p1.html` | `/latest-updates/` | 列表第 1 页（23 卡片），首页瀑布流解析 |
| `latest-p2.html` | `/latest-updates/2/` | 分页解析 + `Page 2 of 14,632` 分页器 |
| `search-zelda-p2.html` | `/search/zelda/2/` | 搜索结果 + 搜索分页 |
| `tag-sex-p2.html` | `/tags/sex/2/` | 标签页分页 |
| `model-derpixon.html` | `/models/derpixon/` | 模型（作者）页 |
| `categories.html` | `/categories/` | 分类索引（含 `no image` 占位分支） |
| `video-4703101.html` | `/video/4703101/moe-love-2-by-doll-house/` | 详情页：flashvars 直链、标签、分类、About、Related |
| `embed-4703101.html` | `/embed/4703101/` | 内嵌播放页（7.7 KB，备用直链来源） |
| `login.html` | `/login/` | 登录表单字段与 CSRF 隐藏项 |
| `rss.xml` | `/rss/` | 最新更新 RSS（可作轻量新片提醒源） |

## 关键探测结论

### 1. 无 JSON API，必须解析 HTML
- `?mode=async&function=get_block&block_id=list_videos_*&from=2` → **200 但 0 字节**（加 `X-Requested-With` 也一样）。
- `/api/json/video/<id>/` → 404；`/video/<id>/?mode=json` → 404。
- `/video/<id>/`（无 slug）→ **404**；必须 `/video/<id>/<slug>/`。

### 2. 直链可流式播放（实测 206）
```
HEAD 720p  → 200, Accept-Ranges: bytes, Content-Length: 473473260, Content-Type: video/mp4
GET Range: bytes=0-1023 → 206, Content-Range: bytes 0-1023/473473260
```
清晰度四档（详情页 `flashvars`）：`video_url` 360p / `video_alt_url` 480p / `video_alt_url2` 720p / `video_alt_url3` 1080p，各带 `?v-acctoken=<base64>`。

另附下载页体积：360p 155 MB / 480p 229 MB / 720p 452 MB / 1080p 796 MB。

拖动预览缩略图模板：`/contents/videos_screenshots/<a>/<id>/timelines/360p/196x109/{time}.jpg`，间隔 140 秒。

### 3. 缩略图格式坑
| 路径 | 实际格式 | 大小 |
|---|---|---|
| `/contents/videos_screenshots/…/336x189/3.jpg` | **WebP**（`RIFF….WEBP VP8`） | 1566 B |
| `/contents/videos_screenshots/…/320x180/3.jpg` | **JPEG**（`FF D8 FF E0 … JFIF`） | 4355 B |
| `/contents/videos_screenshots/…/preview.jpg` | JPEG 1279×719 | 35 KB |

`336x189` 只是被重编码过的小尺寸 WebP；Flutter 按字节解码不受扩展名影响。带外站 `Referer` 仍 200，**无防盗链**。

### 4. 分页与排序（全部实测 200）
- 路径式：`/latest-updates/<n>/`、`/search/<q>/<n>/`、`/tags/<slug>/<n>/`、`/categories/<slug>/<n>/`、`/models/<slug>/<n>/`
- 每页 23–24 条；`latest-updates` 共 14,632 页。
- 排序：`?sort_by=post_date|video_viewed|rating|duration|pseudo_rand`，列表页通用。
- `/top-rated/`、`/most-popular/` 存在且带卡片；`/for-you/`、`/top-model/`、`/top-characters/` 需登录或为 JS 渲染（无卡片）。

### 5. 登录与需登录页
`POST /login/` 字段：`username`、`pass`、`remember_me=1`、`action=login`、`redirect_to=/`、`remember_me_csrf_token=<hex>`。
未登录访问 `/my/favourites/videos/` → 200 但 0 卡片（站点自己返回通用页面，不跳登录）。

### 6. 前置防护
响应头 `Server: ddos-guard`，下发 `__ddg8_` / `__ddg10_` Cookie。当前对 Dart `HttpClient` 与 PowerShell 的纯 HTTP 请求**不拦截**，无 JS 挑战、无 Cloudflare。真实 IP 未做 CDN 溯源（不需要，客户端直连域名即可）。

### 7. 页面结构锚点（解析用）
| 目标 | 选择器 / 文本 |
|---|---|
| 列表容器 | `#custom_list_videos_latest_videos_list_items .item.thumb`，卡片带 `data-video-card-id` |
| 卡片链接 | `a.th[href]`，标题在 `title` 属性与 `.thumb_title` |
| 封面 | `img.thumb[src]`（懒加载时 `src` 是 1px gif，真链在 `data-rd-src`，JPEG 回退在 `data-rd-jpg`） |
| 时长 | `.time` |
| 播放量 / 评分 / 评论数 | `.views.video-views-count` / `.rating` / `.video-comments-count` |
| 悬停预览 | `.wrap_image[data-preview]`（mp4） |
| 详情标题 | `<h1>` |
| 详情时长/播放量 | `.item_info` 三项（发布相对时间 / 播放量 / 时长） |
| 简介 | `.vp-desc` / `.js-vp-desc` |
| 播放器配置 | `script` 内 `flashvars = { … }` |
| 标签 | `a[href^="https://rule34video.com/tags/"]` |
| 分类 | `a[href^="https://rule34video.com/categories/"]` |
| 作者 | `a[href^="https://rule34video.com/members/<数字>/"]` |
| 相关推荐 | `#custom_list_videos_related_videos_items` |

## 复现方式

```powershell
# 抓任一页存成 fixture
$ua = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
Invoke-WebRequest -Uri 'https://rule34video.com/latest-updates/' -UserAgent $ua -OutFile 'test/fixtures/latest-p1.html'
```

Dart 直连验证脚本见 `.spike/fetch.dart`（`dart run .spike/fetch.dart`）。

## 未测项（不要当成已知）

- `v-acctoken` 的确切有效期（未做长时采样）。
- 登录后的 `/my/` 页面结构（无账号，未抓）。
- 站点对高频请求的限速阈值（未压测，首个版本按 1 req/s 保守取值）。
- 移动端 UA 是否返回不同 DOM（实测 mobile UA 页面 213 KB vs desktop 227 KB，**结构差异未逐一比对**）。
