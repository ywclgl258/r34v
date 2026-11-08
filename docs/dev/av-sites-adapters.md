# 番号与录播站适配（MissAV / Jable / rou.video / KBJ / KBJFree / Pornhub）

2026-11-08 新增六个视频站。本文记录每站实际取到的东西、验证到什么程度，以及哪些能力是**刻意不做**的，避免后续把「没验证」当成「已支持」。

## 一句话结论

| 站点 | 列表 | 搜索 | 详情 | 应用内播放 | 说明 |
| --- | --- | --- | --- | --- | --- |
| MissAV | ✅ | ✅ | ✅ | ✅ 真机起播 | 播放清单在 `eval(function(p,a,c,k,e,…))` 打包串里，需解包 |
| Jable | ✅ | ✅ | ✅ | ✅ 真机起播 | `jable.tv` 有 Cloudflare 托管挑战，实际走 `jable.vip` |
| rou.video | ✅ | ✅ | ✅ | ✅ 真机起播 | 清单和分片都是 PNG 外壳，需在数据源层拆壳 |
| KBJ | ✅ | ✅ | ✅ | ❌ 仅外链 | 播放器是第三方 iframe，站点禁止站外嵌入 |
| KBJFree | ✅ | ✅ | ✅ | ⚠️ 仅预览片段 | 完整片源需站内积分解锁 |
| Pornhub | ✅ | ✅ | ✅ | ✅ 真机起播（720p） | 站点按 UA 返回两套列表结构，应用发移动 UA |

表中起播记录来自初次接入时的模拟器验证，并非本次修复重新逐站实播的结论。本次验证范围见文末。

## 各站要点

### MissAV（`missav.ws`）

- 主域名对非浏览器客户端常返回 Cloudflare 托管挑战或 429；`missav.media` 镜像可用，`SiteContext.mirrors` 会在挑战时自动换域名重试。
- 列表卡片 `div.thumbnail.group`；封面在 `data-src`（`src` 是 1×1 占位 base64），时长在 `span.absolute`。
- 播放地址藏在 packer 串里：`eval(function(p,a,c,k,e,d){…}('…',62,…,'w1|w2|…'.split('|')))`。`MissavProtocol.unpack()` 用逐字符扫描还原，再从结果里取 `https://<cdn>/<uuid>/playlist.m3u8`。该清单实测返回 360p/480p/720p/1080p 多档。
- 详情页没有 `h1` 之外的标题来源，女优/类型从 `/actresses/`、`/genres/` 链接取。

### Jable（`jable.vip`）

- `jable.tv` 对任何非浏览器客户端都回 Cloudflare 403，所以 `base` 用 MacCMS 镜像 `jable.vip`，`jable.tv` 留在 `mirrors` 里，分享链接仍能识别。
- 播放配置在 `<script>var player_aaaa={…}</script>`，且**不一定带结尾分号**，所以按花括号配对取值而不是正则截取。
- 作品页正文没有标题，只有 `<title>分类《片名》在线播放… | Jable`；`JableProtocol.title()` 优先取《》里的部分。该页也不提供封面（封面只在列表卡片上），详情不显示封面是正常的。
- 该站播放前会插一段博彩广告，属于站点行为。

### rou.video（`rou.video`）

- 列表是 TanStack Start 的 SSR，卡片 `a[href^='/v/']`。
- 播放地址在 `ev:$R[n]={d:"…",k:N}` 里：base64 解码后每个字节减去 `k`，得到 `{"videoUrl":"/api/hls/<id>"}`。`RouVideoProtocol.decodeBase64()` 用标准库实现，不依赖 `android.util.Base64`（JVM 单测和 API 24 都能跑）。
- **关键点**：`/api/hls/<id>` 302 到 CDN 后，返回的清单和分片都是**合法 PNG**，真实数据在自定义 `roUd` 分块（类型 `0x726f5564`）里，首字节为标志位，`1` 表示其后是 zlib 压缩的 m3u8 或 MPEG-TS。站点自己的播放器靠 shaka 的响应过滤器解包，Media3 直接拿到只会解析失败。
  - `playback/RouPng.kt` 在数据源层按内容识别：PNG 签名 + `roUd` 分块才拆壳，其它响应原样透传，所以同一 CDN 上的封面图不会被误处理。
  - 清单地址没有 `.m3u8` 后缀，`PlaybackStore.item()` 对该站补 `APPLICATION_M3U8`，否则 Media3 会按渐进式解析。
- 实测 `hidx16.xyz` / `qutp83.xyz` 等分片域名会轮换，且部分节点（如 `v2.hidx16.xyz`）单连接极慢；播放器换档重试即可，不是解析问题。

### KBJ（`kbj.im`）

- Laravel + Tailwind，列表 `#main a[href*=/posts/]`，标题在同卡片的 `h2`。
- 封面走 `i1.wp.com/kbj.im/images/…` 的 WordPress 图片代理。实测该代理在移动网络下一分钟都传不完一张图，而原站直连同一张图只要 0.1 秒级，所以 `KbjProtocol.direct()` 把代理前缀剥成原站地址。
- **播放器全部是第三方 iframe**（初次接入抽样帖子的 `data-type` 全是 `iframe`，0 条 hls）：站外播放器需要在原站嵌入。详情通过独立 `externalPlayers` 提供「在浏览器打开 KBJ」，仅将真正的 HLS 直链放进 `streams`；只有 iframe 时不启动原生播放、不显示下载按钮，也不声明全站下载能力。
- 帖子地址**不能**映射成列表条件：早期版本把 `/posts/<id>` 当搜索词，分享链接进来会打开一个标题是哈希值的列表页。

### KBJFree（`kbjfree.com`）

- Next.js，列表数据在 RSC 的 `self.__next_f.push([1,"…"])` 里，需要先反转义再按 `"videos":[{…}]` 取字段。
- 观看页要站内积分才能解锁完整片源；页面公开的只有一段静音预览 mp4（LD-JSON 的 `contentUrl`）。适配器把这条流标成「预览」，并在简介里写明完整片源需站内解锁。
- 详情页的结构化数据用 ISO 8601 时长（`PT1H31M0S`），列表用 `01:31:00`；`KbjFreeProtocol.isoDuration()` 统一成可读格式，避免副标题出现 ISO 串。

### Pornhub（`www.pornhub.com`）

- **站点按 UA 返回两套列表**：桌面 UA 是 `li.pcVideoListItem`，移动 UA（应用默认发送的）是 `li[data-video-vkey]` + `.title a` + `.duration .time`。两套都解析，先试桌面再退移动。早期只认桌面结构，真机首页直接报「未返回可识别的列表」。
- 播放源在 `flashvars_<id>.mediaDefinitions`，HLS 条目带 height（1080/720/480/240），按高度降序给出清晰度。
- 卡片上的 `data-webm` 只是鼠标悬停预览动效，不能混进播放列表。

## 能力声明

- 六个站都提供列表、搜索和详情；`Capability` 中的对应枚举为 `FEED / SEARCH`，没有 `DETAIL` 枚举。
- MissAV/Jable/rou.video/Pornhub 的清单由 Media3 原生 HLS 下载器下载，包括清单、分片与密钥。KBJ 的 iframe 走浏览器；KBJFree 仅提供公开预览，不承诺完整片源。
- 六个站都没有已验证的高级筛选（年份/品牌/时长等），`SearchSupport.filterFields()` 对它们返回空集，界面只保留关键词搜索。这是刻意不承诺，不是遗漏。

## 测试

`app/src/test/kotlin/com/r34v/r34v/AvSitesParserTest.kt` 用 `app/src/test/resources/avsites/` 下的离线页面片段断言「结构可识别 + 字段正确 + 地址拼接正确」，共 32 条用例。`RouPngTest` 用真实抓下来的 PNG 载荷验证拆壳。单测不访问外网；需要联网的验证见 `docs/dev/verification-2.6.0.md` 的 live 用例约定。

## 2026-11-08 审查后修复

授权范围：用户「全优化」指向本次审查的全部 10 项问题。主流程为 `code-quality-workflow`；A0 路由回执为 `code-quality-workflow / ST-A0`，库快照 `2026-08-18`，无额外候选或未解决路由。基线：238 项 JVM 单测通过；原工作区已有 UI、片库及站点接入改动，已在仓库外保存改动前快照。

改动门控：网络、站点路由与 KBJ 入口采用 `Local Fix / Local Fix Only`；下载采用分阶段局部修复，依次处理数据源范围语义、HLS 缓存分组、播放器恢复。总增量预算为 30 个文件、1,000 行文本及 4 个中性视频分片，覆盖解析器、网络、播放缓存、PlayerActivity、相关测试和本文。不得触及原有 UI 重构、依赖版本、签名材料、正式包名和既有 MP4 缓存身份；回退仅撤回本轮增量。范围或验证根因改变时重新审查，不重置用户改动。

| 修复 | 最终行为 |
| --- | --- |
| 镜像失败回退 | 403、429、5xx、连接异常及 200 验证页均可尝试已登记镜像；404 和取消不重试；镜像结果仍经过登录检查。 |
| HLS 下载 | 明确声明 MIME，不设置不被 Media3 接受的自定义 HLS 下载键；每个任务用独立的清单、分片和密钥缓存键，避免跨站串用。 |
| HLS 恢复和删除 | 缓存中的完成记录与实际字节一同持久化；记录包含清单地址和已完成资源范围，缺分片时不标记完成；迁移保留重定向信息，删除只清理当前任务。旧 MP4 路径保持兼容。 |
| KBJ 外部播放 | 外部入口与媒体流分离，浏览器按钮打开原帖；不将 HTML 网页下载成视频。 |
| rou.video 范围读取 | 解包后只应用一次偏移；限制返回长度、保留重定向 URI、零长度读取返回 0。普通响应仅预读 8 字节，PNG 包装才完整缓冲，并限制解压大小。 |
| KBJFree 分页 | 从实际分页链接读取尾页；线上 `/videos?sort=recent&page=1/2` 返回不同条目，页面提供末页链接。 |
| Jable 搜索链接 | 分离关键词、排序后缀和页码，链接转换前后保持一致。 |
| Pornhub 演员分页 | 第二页使用 `?page=2`。 |
| rou.video 系列 | `/s/<id>` 可以从索引和详情进入，并保留分页；标签只解码一次。 |
| 排序 | Pornhub 最新列表和 KBJFree 最新列表正确传递排序；未验证的 MissAV 搜索/标签/分类、Jable 分类等隐藏无效排序选项。 |
| MissAV 解包 | 支持 62 进制和大写 token，避免展开后的词典文本再被替换。 |

验证使用中性测试视频，不依赖成人媒体内容。`androidTest/assets/hls/` 由现有 `test-pattern.mp4` 经 FFmpeg 转为 4 秒、每秒一片的 H.264 HLS（`-c:v libx264 -preset ultrafast -g 12 -keyint_min 12 -sc_threshold 0 -an -f hls -hls_time 1 -hls_list_size 0`）。

- JVM：248 项通过，新增镜像状态/取消、搜索链接往返、分页、排序和 base62 回归。
- Android 7.0 / API 24：`DownloadCacheTest`、`RouDataSourceTest`、`ExternalPlayerUiTest` 共 13 项通过，包含普通及 PNG 包装 HLS 中断续传、迁移、数据库重建后离线解码/跳转、缺分片识别、删除隔离和旧 MP4 回归。
- `HlsPlaybackIntegrationTest` 通过应用实际 DownloadService 完成 PNG 包装 HLS 下载，并分别通过下载记录和恢复缓存两个入口离线解码。测试响应由中性本地夹具提供，临时解除模拟器的公网验证条件，结束后恢复；不改变产品下载网络策略。
- 最终联合回归：248 项 JVM 单测、14 项 API 24 模拟器测试全部通过，无失败或跳过。测试命令为 `gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.r34v.r34v.DownloadCacheTest,com.r34v.r34v.RouDataSourceTest,com.r34v.r34v.ExternalPlayerUiTest,com.r34v.r34v.HlsPlaybackIntegrationTest`。
- 本轮不等于重新确认六站所有线上页面、长期 CDN 稳定性或正式发布验收；KBJ 完整媒体依然在浏览器播放，KBJFree 依然只有公开预览。

最终范围核对：对照开工快照，本轮涉及 27 个文件，文本增量低于 1,000 行，4 个中性测试分片合计 353,064 字节，均属于上述修复或验证路径。默认全工作区检查仍提示超过单阶段 5 文件/200 行阈值：它包含本轮开始前的 UI、片库和全部新增站点文件，不能视作本轮增量；二进制夹具也不能用文本行数计量。本轮按批量修复预算和快照另行核对，未修改签名、正式版本、依赖或无关 UI 源码；`git diff --check` 通过。

开发验证包：`com.r34v.r34v.debug`，名称「R34V 开发版」，`2.6.0-debug / versionCode 46`。SDK `apksigner verify --print-certs` 已通过，开发证书 SHA-256 为 `89b1cb482d3cb14be38af672f7db9f794bf4e1c582731111ff2a741bfcb5174c`。该证书来自本机既有开发密钥，不代表跨电脑统一签名。本轮未构建或交付新的正式 APK，未改正式版本号、未推送或发布。
