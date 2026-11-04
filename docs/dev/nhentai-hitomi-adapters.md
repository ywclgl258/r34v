# nhentai / Hitomi 原生接入

2026-11-03 增加 `nhentai`（`https://nhentai.net`）和 `hitomi`（`https://hitomi.la`），抽屉共七站。复用 `MediaKind.GALLERY`、`GalleryActivity`、`GalleryRepository` 与分站片库，不引入 WebView、远程 JS 执行或新依赖。

2026-11-04：两站画廊列表改为保留封面比例的原生瀑布流，并与其他站统一跟随系统代理；通过电脑代理实际验证列表、详情和首图。两站浏览/阅读仍不用 WebView；EH/EX 的独立官方认证例外见 [本轮说明](system-network-auth-masonry.md)。

## nhentai

当前官网已停用旧 `/api/galleries/all` 和 `/api/gallery/<id>`。本实现依据 [官方 v2 文档](https://nhentai.net/api/v2/docs) 及 [OpenAPI](https://nhentai.net/api/v2/openapi.json)，调研快照版本 `2.0.0+f4fdd1d`，调用以下公开 GET 接口：

- `/api/v2/galleries?page=`：最新列表；`/galleries/popular`：今日热门，返回数组，没有分页。
- `/api/v2/search?query=&sort=&page=`：关键词、标签、语言、分类与画师查询，保留引号、排除词和原站过滤语法。
- `/api/v2/galleries/<id>`：标题、备用标题、图页、分类标签、画师和收藏数。
- `/api/v2/cdn`：当前图片/缩略图服务器。只接受 HTTPS、`i[数字].nhentai.net` / `t[数字].nhentai.net`，不把固定旧 CDN 当回退地址。

图页直接使用 v2 提供的 `pages[].path` 和 `number`，支持 WebP/JPG/PNG/GIF 的原站地址。API 中偶发的重复同扩展名（如 `thumb.webp.webp`）收敛为一次。校验路径属于对应 `media_id`，拒绝站外地址或错序页码。`/g/<id>/<page>/` 是阅读与缓存的稳定页址，不需要再解析前台防护页。

列表/详情/搜索公开访问无需 API Key；本次不接入账号认证、服务端收藏同步、评论写入。官网声明匿名列表 15 次/分钟、搜索 10 次/分钟、详情 20 次/分钟，具体以服务器返回为准。适配器缓存 32 份详情，图页翻页不重复请求详情；CDN 配置缓存 30 分钟，手动重试图页刷新配置/元数据。429 显示频率限制及可用的 Retry-After，不自动连续重试。

## Hitomi

依据官网当前加载的 [common.js](https://ltn.gold-usergeneratedcontent.net/common.js)、[gg.js](https://ltn.gold-usergeneratedcontent.net/gg.js)、[search.js](https://ltn.gold-usergeneratedcontent.net/search.js)、[galleryblock.js](https://ltn.gold-usergeneratedcontent.net/galleryblock.js) 和 [reader.js](https://ltn.gold-usergeneratedcontent.net/reader.js) 核对协议，独立实现 Kotlin 解析。调研响应仅存 Git 忽略目录 `.spike/nh-hitomi/`，不随 APK 分发。

- 首页、年度热门和单一标签/语言/画师/分类索引使用 `.nozomi` 的 HTTP Range，每次只读取 12 个大端 32 位编号；分类热门使用原站对应排行索引，避免下载整个大分类。核对 Content-Range 的起点/长度/总量，按实际总量分页。明确 404/416 的索引边界，不把 403 或限流当作空结果。
- 从 `ltn.gold-usergeneratedcontent.net/galleries/<id>.js` 读取 `galleryinfo` JSON。只解析数据，不执行脚本。最多三个元数据请求并行，缓存 32 份，索引中已删除的条目单独跳过。
- 标签、画师、语言、分类等映射至原站对应 `.nozomi`；普通关键词按 SHA-256 前四字节查 `galleriesindex` 的 B 树，再按数据指针获取编号。支持多个正向词取交集及排除词；热门排序按年度排行保序筛选。
- 关键词最多六个、至少一个正向词，命名空间内空格可使用下划线；单词结果超过 50 万条时提示缩小查询，单次二进制响应上限 16 MiB，树遍历最多 16 层并检测循环。只缓存不超过五万条的查询结果，最多八组、三分钟过期。排行筛选逐个读取整数，不为整份排行榜构建装箱列表。
- 解析当前 `gg.b` 前缀、`gg.m` 的分片数据，用图片散列末三位计算路径和 WebP 节点；缩略图使用当前分片的 `webpbigtn`。不执行 `gg.js`；格式不可识别时明确失败。路由缓存十分钟，重试图页重新读取，兼容 Android 7+ 的 WebP 解码，不依赖 AVIF。
- 阅读页址为 `/reader/<id>.html#<page>`，缓存保留 fragment 来区分页码；实际图片 Referer 去除 fragment。详情地址稳定保存 `/galleries/<id>.html`，同时识别官网带标题的漫画地址。

Hitomi 本次接入漫画/CG 的图页阅读，没有增加该站视频播放器、账号功能或站外跳转内容。

## 共用界面和数据

两站提供原生画廊列表、搜索、分类/热门、详情、画师/标签跳转、画廊/条目收藏、阅读记录、翻页/跳页/缩放和已读图页缓存。画师搜索/收藏使用“画师”，EH/EX 继续使用“上传者”。同一个 gid 在四个漫画站分别存储，缓存仍为 `files/gallery-cache/<siteId>`；图片域不发送站点账号 Cookie，EH 的 `nw` 偏好 Cookie 只用于 EH/EX。

沿用每站阅读临时缓存256MiB、单页24MiB和约600万像素阅读解码上限。临时缓存仅涵盖打开且尚未逐出的页面，可从阅读记录离线重看。2.2.2起两站均支持整本下载、后台按页续传与统一下载中心离线打开；整本文件保存在独立目录，不受阅读LRU或临时清理影响。尚无原图归档抓取。详见 [统一下载](unified-downloads.md)。

## 验证

两站真实公开列表、详情和单张 HTTPS 显示图已成功读取与解码；Hitomi 的真实 B 树关键词查询也已通过。仅一次样本，不代表其他地区/运营商的性能或长期可用性。实际原生 UI 测试使用原创城市速写素材，覆盖新站切换、阅读收藏、页码跳转、离线重看/重建、画师搜索和分站数据隔离。最终测试数量、截图和开发包哈希见 [HANDOVER.md](HANDOVER.md) 最新段和本地 `dist/nh-hitomi-qa/VERIFICATION.md`。

本轮只交付开发包，正式版本、包名和发布签名不变；Android 7/8 没有本轮运行证据，真实 ExHentai 账号授权仍未验收。
