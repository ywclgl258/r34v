---
project_id: r34v-android
updated: 2026-11-04
status: reader-controls-prefetch-release-2.1.6
scope: 漫画全屏菜单、单排语言栏、连续预读、视频选择器排版
---

# 漫画全屏菜单、连续预读与控件修复（最新，2026-11-04）

2.1.6 / 32：漫画可单击画面隐藏/恢复上下菜单，顶栏增加明确的全屏按钮，隐藏菜单时同时隐藏系统栏；返回键先恢复菜单，重建保留全屏和页内位置。阅读时连续预读后面三页，最多两个并发，额外屏外解码图像不超过24MiB，跳页取消旧范围，预读不推进历史。

语言栏改为48dp单排短标签，下滑收起、上滑/回顶部显示；大字体可横滑，不折行。视频清晰度/倍速按钮修复背景覆盖内边距导致的贴边排版，统一文字、箭头和触控高度；选择行为继续正常。

86项JVM通过，相关Android UI与播放回归分批验证，Debug/Release Lint无错误；签名包由仓库Gradle构建。交付 `dist/R34V-v2.1.6-android.apk` 和 `dist/R34V-v2.1.6-compat-2.0.0.apk`。用户随后明确授权提交、推送与上传；发布入口为 [v2.1.6](https://github.com/ywclgl258/r34v/releases/tag/v2.1.6)。说明见 [reader-controls-prefetch.md](reader-controls-prefetch.md)，截图见本地 `dist/reader-ui-2.1.6/`。以下为前轮记录。

---

# 漫画语言分类、紧凑切站与条漫阅读（2026-11-04）

四个漫画站新增全部/中文/日文/中日合并筛选，按站点保存，应用到首页、分类、搜索和翻页。顶栏改为小号站名与箭头，抽屉删除重复域名。阅读器改为按宽度连续滚动，保留缩放、跳页和离线已读缓存，增加页内位置恢复；仅附着页加载，离屏取消请求并释放图片。实现和来源见 [comic-language-strip.md](comic-language-strip.md)。

86项JVM单测通过；10项独立Android适配器/UI测试分批通过，含中日合并分页、缓存隔离、真实触摸滚动、缩放复位、页内偏移重建、局部错误恢复、离线跳页和切站。Debug/Release Lint均无错误。版本递增为2.1.5 / 31；`assembleRelease` 已验证两份固定证书及继承链。标准包 `dist/R34V-v2.1.5.apk`，仅Android 7/8已装2.0.0使用 `dist/R34V-v2.1.5-compat-2.0.0.apk`。另独立核验标准包API24–27/28–36与兼容包API24–27的签名指纹。截图和SHA256见 `dist/release-2.1.5/`。用户随后明确授权提交、推送与上传；发布入口为 [v2.1.5](https://github.com/ywclgl258/r34v/releases/tag/v2.1.5)。

发布工作流显式安装 `platform-tools`、`platforms;android-36` 和 `build-tools;36.0.0`，替换 setup-android 默认的旧 `tools` 包，修复上次远端SDK初始化失败。运行截图目录 `shots/` 留在本地；源码、测试、文档和公开签名继承证明入库，APK与SHA256作为Release附件。

以下为前轮记录。

---

# UI/UX 打磨（2026-11-04）

已按用户指定的 `ui-ux-pro-max` 完成原生界面优化：黑底灰阶与强调文字、48dp共享触控区、键盘焦点与按钮语义、顶栏省略和选中导航、搜索清除/校验、片库空态入口、可暂停且适配宽屏的轮播。另修复官方登录的延迟回调覆盖权限说明。实现、截图与验收边界见 [本轮记录](uiux-2026-11-04.md)。

最终83项JVM单测、5项Android UI整批通过，Debug构建与Lint通过（0 errors / 63 warnings）；375dp / 200%字体 / 关闭动画、1067dp平板及横屏另行通过。开发包为 `dist/R34V-dev-uiux.apk`，名称“R34V 开发版”，正式版本仍为2.1.4 / 30。保留全部前轮工作；未提交、推送或发布。

---

# 系统网络、EX 官方登录与漫画瀑布流（2026-11-04）

已继续完成用户要求的网络清理、ExHentai 强制登录及四个漫画站的瀑布流。实现与参考来源见 [system-network-auth-masonry.md](system-network-auth-masonry.md)。现有七站和原生阅读、播放、分站片库继续保留。

- 删除 Host/IP 直连、固定/手填 IP、IP 池、DoH、VPN 分支及应用内代理输入。页面、API、封面、图页、播放、后台下载、更新与原生会话验证统一采用系统 DNS / 系统代理选择器；遵循系统的排除规则，选择代理后失败不会回退直连。旧设置按键名退役，系统代理变化会清理旧空闲连接。
- 实际通过电脑 `127.0.0.1:7897` 代理复测六个公开站的列表、详情及首个媒体/图页，均成功；Hanime 另通过真正的 Media3 播放探针，480p ready 并输出 5 帧。播放/下载显式绑定所属站点，第三方媒体主机及切站后继续使用正确 UA/Referer，账号 Cookie 不发往媒体 CDN。
- EX 抽屉标注“需登录及账号权限”；无会话时首页、搜索、发现、片库和画廊入口显示原生登录门禁，解释白屏原因。新增独立官方论坛登录 Activity，仅认证使用系统 WebView；论坛身份 Cookie 同步到目标站后，原生适配器验证访问权限，成功才加密保存。保留浏览器入口与手动 Cookie 导入。退出认证清理临时 WebView Cookie。明确权限拒绝时不能降级到私有详情缓存。
- EH、EX、nhentai、Hitomi 的画廊列表与片库使用原生瀑布流，按封面比例显示完整封面；缺少尺寸时读取实际图像比例。标题/提示/分页跨列，手机双列、宽屏增加列数，滚动加载支持瀑布流位置。视频列表保持现有布局。

验证：全量 JVM **83 项通过**；Debug Lint **0 errors / 63 warnings**；Debug / AndroidTest 构建成功。旧 DNS/IP 功能的 13 项测试随功能删除，新增系统路由、官方认证与封面比例用例，因此与前轮 84 项不能直接按数量比较。Android 16 分批通过 **32 项独立测试**：24 项本地协议/会话/完整 UI/播放/下载回归、7 项系统代理探针（六站实际联网与 EX 无会话门禁）、1 项真实 Hanime 原生播放；重复运行不增加数量。

默认与系统 200% 字体下检查了不同封面比例、漫画阅读/收藏、EX 官方登录门禁与设置；最终大字体 UI 三项全部通过，另验证横屏三列与真实 Range 分页。截图使用原创中性素材。最终 APK 已实际安装并通过抽屉选择 EX，重启后恢复 EX 且显示登录门禁。报告、实时摘要和截图见 `dist/system-proxy-qa/VERIFICATION.md`。测试结束撤销了模拟器代理/端口转发，字体恢复 1.0；没有更改 Windows 代理设置。

交付：`dist/R34V-dev-system-proxy.apk`，2.1.4-debug / 30，`com.r34v.r34v.debug`，名称“R34V 开发版”。SHA-256：`2084859de97094e8554a77595ce03e6da3ebfc74aad2cf9b460756254963d8db`。

边界：没有真实 EX 账号，官方账号的线上登录及授权浏览仍待验证；官方论坛桌面请求曾返回 403，是否要求浏览器验证由原站决定。合成会话设备测试覆盖无权限不保存、验证成功才保存及退出清理。Android 7/8 本轮未运行，不新增整本漫画下载或账号同步。正式 `release.properties` 仍为 2.1.4 / 30，正式包名/签名路径未改；未提交、推送或发布。当前工作区包含前轮多站点和本轮改动，应整体保留。

---

# 前轮 nhentai / Hitomi 接入结果（2026-11-03）

已按用户要求增加 `https://nhentai.net/` 和 `https://hitomi.la/`。现在共七站，全部保持 Kotlin 原生。协议来源、实现和限制见 [nhentai-hitomi-adapters.md](nhentai-hitomi-adapters.md)。

本轮实现：

- 新增 `NhentaiAdapter` / `NhentaiProtocol`，依据官网当前 OpenAPI 使用公开 v2 API；旧 API 已退役，未继续使用旧 `/api/gallery` 路径。支持最新、今日热门、分类、关键词/标签/画师搜索、详情与图页读取；动态取得并校验 CDN，不需要登录或 API Key。
- 新增 `HitomiAdapter` / `HitomiProtocol`，Range 读取 `.nozomi` 分页，SHA-256 / B 树查词、标签索引、交并/排除词、年度排行保序；校验分段和数据长度，限制响应、查询规模和递归深度。
- 从 Hitomi 当前 `galleryinfo` JSON 读取元数据；解析 `gg` 的图片分片数据，原生计算 WebP 路由，未执行远程 JS。支持可空元数据数组；每次最多三个详情请求并行，元数据与查询缓存有界。
- 复用画廊详情、原生阅读/缩放/跳页、收藏、进度、已读缓存和清理入口；四个漫画站各有独立目录与片库。Hitomi fragment 页码保留为缓存身份，图片 Referer 去掉 fragment。
- 新站搜索/收藏显示“画师”，EH/EX 继续使用“上传者”；修正画师收藏的查询目标避免重复命名空间。EH 的 `nw` 偏好 Cookie 只发往 EH/EX，所有图片域仍不带账号 Cookie。
- 服务器拒绝、限流、已删除和不支持的索引格式分别给出错误；手动重试重新验证图源路由，元数据请求使用 no-cache，避免仅刷新内存而沿用过期 HTTP 路由。

验证结果：

- 全量 JVM 84 项通过；Debug Lint 0 errors / 52 warnings；Debug / AndroidTest 构建通过，无新依赖。
- Android 16 共 24 个独立测试分批成功：`regression-final.xml` 22 项（原站浏览/播放/后台下载/网络/Iwara、EH/EX、七站会话、新适配器和两组漫画 UI）加两个真实新站探针。最后 `font-200-final.xml` 对两组漫画 UI 和新站适配器共五项复跑通过，字体恢复 1.0。
- 最终补齐单一分类/语言/画师索引及其热门排序的 Range 分页，避免大分类整份读入；`facet-final.xml` 对三个新适配器测试和新 UI 共四项复跑通过，全量 JVM/Lint/Debug 再构建通过。该修正不增加独立测试数量。
- 新 UI 覆盖抽屉七站切换、NH 详情/收藏/读页、离线重看与 Activity 重建、Hitomi 读页/跳页/画师搜索/收藏，以及两站同 gid 的数据隔离。默认/200% 中性截图在 `dist/nh-hitomi-qa/ui-final/` 和 `ui-font-200/`。
- 最终 APK 实际安装后，使用真实抽屉选择 Hitomi，等待选择完成再 force-stop/重启，UI 树确认顶栏恢复 Hitomi。见 `dist/nh-hitomi-qa/restart.txt`；清空旧测试崩溃记录后的安装启动无新崩溃。
- 实时 nhentai v2：25 张卡片约 1066 ms，详情 40 页、22 条元数据链接；一张 HTTPS 显示图 223494 字节，按 4 倍采样解码 320×445。
- 实时 Hitomi：12 张卡片约 3289 ms，详情 200 页；实际 B 树关键词搜索返回 12 张卡片；一张 HTTPS WebP 153464 字节，按 4 倍采样解码 361×512。仅单次样本，不作其他网络或长期可用性的承诺。

早期测试失败已保留报告：一项产品问题是 Hitomi 可空元数据数组，已修正并补单测；其余是离线测试未先保存磁盘详情、切站清理网络客户端使新 UI 夹具失效，以及共享测试客户端的 dispatcher 被关闭。改用独立夹具网络、切站请求门闩与完整可见的抽屉行定位后，所有旧站回归和新流程通过；200% 的门闩超时改为可恢复 I/O 异常并修正滚动定位，未跳过断言。

交付：`dist/R34V-dev-nh-hitomi.apk`，2.1.4-debug / 30，`com.r34v.r34v.debug`，名称“R34V 开发版”。SHA-256：`96ca52d320bb203de02a5b5978a657e73c830444e95ae4b540f0d38702aa1a53`。报告、原生截图、实时摘要与 JVM/Lint 副本在 `dist/nh-hitomi-qa/`。

边界：本轮仅公开浏览与本地收藏/阅读，不接入新站账号同步、Hitomi 视频或整本下载。Android 7/8 本轮未运行；真实 ExHentai 账号权限访问仍待有效会话验证。正式 `release.properties` 仍为 2.1.4 / 30，正式包名/签名未改，未提交、推送或发布。现有工作区包含前轮多站点及本轮修改，应整体保留。

---

# 前轮 E-Hentai / ExHentai 接入结果（2026-11-03）

按用户指定的 [Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) 调研漫画站协议。用户明确选择同时接入 ExHentai（需要账号权限）。现在抽屉包含 Rule34Video / Hanime1 / Iwara / E-Hentai / ExHentai，保持 Kotlin 原生，正式包名和签名路径不变。详细实现与来源见 [eh-adapter.md](eh-adapter.md)。

本轮实现：

- 新增 `EhAdapter` / `EhProtocol`，支持 EH/EX 最新、热门、分类、画廊/标签/上传者查询；跟随原站游标翻页，保留原站搜索语法。
- 引入 `MediaKind.GALLERY`、画廊/图片页/阅读进度模型及 `GalleryActivity`；列表使用竖向封面，画廊独立于视频播放路由。
- 原生阅读支持翻页、滑动、跳页、进度条、双击/双指缩放、拖动；成功解码后保存进度，失败不推进记录。详情支持备用标题、元数据、收藏、标签和上传者跳转。
- 每站独立阅读记录、收藏和已读图页缓存；保存已读页址，重启后可离线跳转至首批预览之外的缓存页。只读取打开的显示图，单站图片 LRU 上限 256 MiB；当前没有整本下载或原图/归档抓取。
- 设置中导入本站 Cookie，先请求验证再提交，失败保留旧会话；AndroidKeyStore AES-GCM 加密持久化，无明文回退。ExHentai 未导入时给出设置入口，仍由原站判定账号权限。退出只清本站会话。
- 图片请求显式绑定所属站点与图片页 Referer，EH/EX 即使共用 H@H 域也不串头，图片域不携带账号 Cookie。
- 参考客户端为 GPLv3，本轮未复制、移植其应用源码；按实际 HTML 独立编写协议解析和原生阅读器，测试使用原创中性页面/图像。

验证：

- 全量 JVM 70 项通过；Debug Lint 0 errors / 52 warnings；Debug 和 AndroidTest 构建通过。未新增依赖。
- Android 16 共 19 个独立测试分批通过：13 项原站/播放器/下载/Iwara/五站会话回归，4 项 EH/EX 适配器/加密/缓存测试，1 项完整漫画 UI，1 项真实 EH 探针。漫画 UI 另以系统 200% 字体复跑通过，检查详情、片库和阅读器截图后字体已恢复 1.0。
- 完整 UI 覆盖五站切换、画廊收藏、真实双击缩放、跨预览分组跳页、进度、离线重看和离线跳页、未缓存页失败后恢复、EX 权限入口/会话导入/阅读，以及双站同 gid 的记录/收藏/图片请求隔离。
- EH 实时非成人分类列表返回 25 张卡片，约 1681 ms；详情有 719 页 / 首批 20 个预览链接，单张 HTTPS 显示图 784×980 成功解码。仅单样本，不代表其他网络性能。
- 安装最终开发包，通过真实抽屉选择 ExHentai，force-stop 后重新启动，UI 树确认恢复 ExHentai 并显示导入会话入口，无应用崩溃记录。见 `dist/eh-qa/restart.txt`。
- **没有真实 ExHentai 会话，账号授权后的线上访问未验收**；EX 会话验证/导入与阅读链路使用隔离 HTTP 素材验证。Android 7/8 本轮未运行；此前各视频站的其他边界继续保留。

测试驱动修正：阅读记录卡片包含语言/评分，断言改为包含页码；JUnit 实时探针显式返回 Unit；UI 测试的独立网络夹具改用应用同一个 CookieJar，以符合生产对象关系。早期失败报告保留，最终报告通过，未跳过产品行为。离线跳页缺口已补持久页址并新增针对断言。

交付：`dist/R34V-dev-eh.apk`，2.1.4-debug / 30，`com.r34v.r34v.debug`，名称“R34V 开发版”。SHA-256：`dd8545eeaf91755b6a479713d4ae66a16e83a642f45605b34d251f43c649762b`。本轮仅开发包，未更改 `release.properties`、正式包名或签名配置，未提交、推送或发布。

证据：`dist/eh-qa/VERIFICATION.md`、最终测试 XML、`ui-final/` 与 `ui-font-200/` 中性截图、`eh-live.txt`。当前工作区包含前轮多站点与本轮漫画功能的未提交修改，应整体保留。后续正式更新必须递增 versionCode 并按既有签名继承/兼容包规则构建；本开发包不能用于覆盖正式版。

---

# 前轮 Iwara 接入结果（2026-11-03）

已按用户指定的 LoveIwara 参考实现接入 `https://www.iwara.tv`，站点 id 为 `iwara`。抽屉现在包含 Rule34Video / Hanime1 / Iwara。详细协议、来源快照及边界见 [iwara-adapter.md](iwara-adapter.md)。

本轮实现：

- 新增 `IwaraAdapter` / `IwaraProtocol` / `IwaraSignature`，用现有 OkHttp + Gson 请求 JSON API，保留 Kotlin 原生与 Media3。
- 接入最新、趋势、热门、点赞、播放量排序，视频搜索、标签筛选与作者查询；支持字母数字视频 ID、独立封面、原画清晰度、简介、相关推荐及评论。
- 支持公开视频签名源，缺 Source 时有限时官网盐值刷新，验证成功才采用候选值，失败保留首次可播放结果。
- API/CDN 按 Iwara 设置公开请求头；API 别名保留独立 DNS host 缓存，媒体仍不携带站点 Cookie/Authorization。
- 首页请求、排序文案与选项、搜索输入规则、作者/标签链接交给适配器；请求开始时固定适配器，详情按所属 URL 路由。
- 修复应用新进程未恢复 `SiteRegistry.current` 的问题。切站清空搜索输入与范围，片库和下载继续使用站点命名空间。
- 按 LoveIwara 的 MIT 许可保留声明到 `app/src/main/assets/licenses/LoveIwara.txt`；未引入 Flutter/WebView。

验证完成：

- 全量 JVM 60 项通过；Debug Lint 0 errors / 44 warnings；Debug、AndroidTest 构建通过。
- Android 16 上 14 个独立测试分批验证通过：原 9 项回归、3 项 Iwara 适配器测试、1 项第三站完整 UI 流程、1 项实时 Iwara 播放探针。Iwara UI 另以 200% 字体复跑通过。
- 第三站 UI 覆盖发现/排序/搜索、Media3 中性素材播放、收藏、标签跳转、后台下载、离线播放零新增媒体请求、切回原站后的数据隔离。
- 实时探针仅取 `rating=general` 的公开视频：列表 5 张卡片约 652 ms；详情返回 1080p 原画/540p/360p 和 12 条相关；封面 HTTP 206；Media3 ready 并实际输出 7 帧。该次单样本不作为连续播放或其他网络的性能承诺。
- 安装开发版，通过真实抽屉选择 Iwara，force-stop 后重新启动，UI 树确认顶栏恢复 Iwara。200% 测试后字体已恢复 1.0。

测试过程中修正了两个测试驱动问题：等待抽屉完全展开后再点行；优先命中当前对话框中的同名清晰度，并预授予开发包测试用通知权限。产品播放/下载断言保留，未放宽到跳过失败。

交付：`dist/R34V-dev-iwara.apk`，2.1.4-debug / 30，`com.r34v.r34v.debug`，名称“R34V 开发版”。SHA-256：`bb806f0e7d29e1a6e4e38890bf380860378157f1a0403b03b3956feb508a09a3`。本轮未更改正式版本号、签名配置或正式包名，未提交、推送或发布。

证据：`dist/iwara-qa/` 的测试 XML、默认/200% 中性截图、`iwara-live.txt`、`restart.txt`。研究阶段的官网/API 原始响应在忽略目录 `.spike/iwara/`，不作为发布素材。

当前边界：Iwara 账号登录、私密/授权内容、站外嵌入、图库和 `iwara.ai` 未接入；设置页明确显示公开浏览。Android 7/8 本轮未运行。此前 hanime1 实时账号/深分页边界仍保留。

---

# 前轮抽屉修复结果（2026-11-03）

此前阻塞已修复，主页面现在由 `SiteSwitcher : DrawerLayout` 承载，不再向 `android.R.id.content` 叠加透明全屏 View。原面板内容、黑底主题、站点隔离与缓存 key 前缀保留。

本轮完成：

- 顶栏点击打开抽屉；点遮罩、选站点、返回键关闭；页面重建后仍可使用。边缘拖动交给 DrawerLayout，手势导航下先长按边缘使其露出，再拖动。
- 抽屉按窗口宽度测量，独立处理系统栏/输入法 inset，使用真实可见状态判断返回键是否先关抽屉。
- hanime1 的榜单和类型声明实际 `FeedRequest`，分别进入 `sort`/`genre` 列表；不支持的空索引入口不再显示。恢复到不支持的旧索引时给出返回本站浏览的入口。
- `MainActivity.index()` 统一委托当前适配器。补齐 KVS `work:<slug>` 路由，修复作品子页误进作者排行。
- Cookie 恢复键从错误的 `$site.id:cookies` 改为 `${site.id}:cookies`。新增在磁盘保存后重建 Store/Jar 的验证，覆盖双站恢复、单站退出和 CDN 不发送 Cookie。
- 修复已有 Android 测试的多站点适配：`LiveDirectProbe` 显式传入解析站点，`BrowseUiTest` 使用真实命名空间并还原活动站点，`NativeSmokeTest` 将本地播放素材登记到站点缓存键。

当前验证：50 项 JVM 测试通过；Debug Lint 为 0 errors / 44 warnings；Debug 与 AndroidTest APK 已构建。Android 16 模拟器共 9 项测试完成验证（分批执行）：

- 新增 `SiteSwitcherUiTest`：真实触摸、遮罩/返回、重复导航、双站切换、榜单/类型请求、片库隔离、活动站点持久化、边缘长按拖动。
- 新增 `SiteSessionTest`：重建会话后恢复、单站退出和 CDN 隔离。
- 原有 `BrowseUiTest` 1 项、`NativeSmokeTest` 4 项、`NetworkRecoveryTest` 2 项：浏览搜索收藏、原生播放解码/跳转/倍速/全屏/释放、后台下载暂停/继续/离线播放、旧数据迁移、网络失败恢复及代理不旁路。
- `SiteSwitcherUiTest` 另在系统 200% 字体下通过，已检查两种字号的抽屉与发现页关键截图；测试后字体恢复 1.0。

原回归首次有 1 项失败：本地播放测试仍写旧 `video_cache_v1`，素材登记不到新命名空间；修正测试配置后单项复跑通过。报告分别保留于 `regression-before-fixture-fix.xml` 和 `playback-after-fixture-fix.xml`。

本轮证据位于 `dist/multi-site-qa/`，包含 `default/` 和 `font-200/` 截图、测试 XML 和 `VERIFICATION.md`，使用隔离的中性页面素材。下面的 `shots/`、真实站点播放与网络数据属于**接手时的前轮证据**，本轮未重新作实时联网验收。

交付 `dist/R34V-dev-multisite.apk`：2.1.4-debug / 30，`com.r34v.r34v.debug`，应用名“R34V 开发版”。SHA-256：`09ba36a37b7779a88c0e3ec2e2e9f4b9da1798ed214338bb728a7c73fe6defe8`。

发布边界：沿用 `release.properties` 的 2.1.4 / 30；本轮仅构建开发版，未更改正式版本或签名配置，未推送、未发布、未创建提交。工作区包含前轮多站点未提交修改，应整体保留。

下一步边界：真实账号登录、Android 7/8 运行、hanime1 深分页/相关内容、不同移动模板和实际 CDN 网络仍待对应环境验收。排序对话框仍是通用 `Sort.entries`，尚未按适配器 `sorts` 完整收口；新站点/Web 模式不在此次抽屉修复范围。

---

# 前轮交接记录（接手时快照）

以下为修复前记录；“未完成/卡住”的状态以本轮续接结果为准。

## 一句话现状

**数据层、网络层、解析层全部完成并验证通过；只剩 UI 的站点切换器没做出来。**

`SiteSwitcher` 面板能构建、能渲染、动画数值全对，但**从上方工具栏点击后不再出现**（早期版本曾正常打开，见 `shots/02-switcher-open.png`）。卡在最后这一环。

---

## 一、已经做完并验证过的部分

### 1.1 构建与测试

```
.\gradlew.bat testDebugUnitTest   → 50 tests, 0 failures
.\gradlew.bat assembleDebug       → BUILD SUCCESSFUL
.\gradlew.bat lintDebug           → 通过
```

新增测试覆盖：`SiteIsolationTest`（8 项）、`HanimeParserTest`（7 项）、`RoutedDnsTest` 扩展到 12 项。

### 1.2 hanime1.me 已真实跑通

这是**功能成功的关键证据**（`shots/06-hanime1-home.png`、`shots/10-hanime-detail.png`）：

- 首页列表真实加载（日文标题、缩略图、时长、评分）
- 详情页真实播放：时间轴 `00:02 / 12:20`，`720p 正在播放`
- 简介、16 个标签、作者、相关推荐全部解析出来
- 观看记录写入成功

**切换功能本身是好的**，只是入口按钮失效。当时通过点击站点名（`shots/02`）成功切换过多次。

### 1.3 数据隔离已验证

`shots/11-library-hanime.png` vs `shots/13-library-r34v.png`：

- hanime1 片库只有 hanime1 的记录
- 切回 Rule34Video 后只有 r34v 自己的记录（「夜色之下·城市漫游」）
- **两个站点的数据没有互相污染**

持久化也确认过（`run-as ... cat shared_prefs/r34v_native.xml`）：

```xml
<string name="active_site">hanime1</string>
<boolean name="site_namespace_v1" value="true" />
<set name="hanime1:cookies">...</set>
<set name="r34v:cookies">...</set>
```

---

## 二、已完成的技术改动清单

| 文件 | 改动 | 状态 |
| --- | --- | --- |
| `data/SiteContext.kt` | **新增**。`SiteContext` + `SiteRegistry`，含 `pinnedIps` | ✅ |
| `data/adapter/SiteAdapter.kt` | **新增**。适配器接口 + `Capability`/`Section` | ✅ |
| `data/adapter/KvsAdapter.kt` | **新增**。包装现有 KVS 解析，行为不变 | ✅ |
| `data/adapter/HanimeAdapter.kt` | **新增**。hanime1 解析器，选择器实测验证 | ✅ |
| `data/Models.kt` | `Site` 改为委托 `SiteRegistry.current`；模型加 `site` 字段；`Favorite.key` 加站点前缀 | ✅ |
| `data/HtmlParser.kt` | 全部函数改为显式接收 `SiteContext`，不再读全局 | ✅ |
| `data/Network.kt` | Cookie 按 host 分桶；拦截器按站点查 UA/Referer；`AdapterRegistry` + `Repository` 委托 | ✅ |
| `data/RoutedDns.kt` | 按站点隔离 IP 池；接入 `pinnedIps` | ✅ |
| `data/LibraryStore.kt` | 站点命名空间 + 一次性迁移；`switchSite()`；`logout(id)` 只清当前站点 | ✅ |
| `playback/PlaybackStore.kt` | 缓存 key 加站点前缀；`downloadsFor(siteId)`；媒体 Referer 按 host 路由 | ✅ |
| `ui/SiteSwitcher.kt` | **新增但有问题**（见下） | ❌ |
| `MainActivity.kt` | 标题改为站点名 + “切换”提示；`switchSite()`；`explore()` 按站点能力渲染 | 部分 ✅ |

### 关键设计决策（别推翻）

1. **`PlaybackStore.key(siteId, videoId, quality)`** —— Media3 按这个字符串索引缓存和下载库。两站同 ID 会互相覆盖，**这行不能去掉站点前缀**。
2. **Cookie 按 `url.host` 分桶** —— 顺带满足了数据隔离，也让 CDN 天然不带 Cookie。
3. **存绝对 URL** —— 收藏/历史里的 `path` 若以 `http` 开头就直接用，否则才按记录自己的 `site` 拼。
4. **`pinnedIps`** —— hanime1 在部分网络下系统 DNS 被污染（实测解析到 `31.13.69.245`，Facebook 网段）。内置 Cloudflare 边缘 IP `104.26.0.18` / `104.26.1.18` / `172.67.69.183` 直连可用。

---

## 三、卡住的问题：站点切换器不显示

### 3.1 现象

点击左上角 `Rule34Video › 切换`，面板不出现。日志显示 `switcher.open()` 没有被调用（没有 `apply f=1.0`）。

但**更早的版本能打开**（`shots/02-switcher-open.png` 是证据），说明 `SiteSwitcher` 本身渲染没问题。

### 3.2 已经排除的原因

按时间顺序，这些都已验证并修掉：

| 尝试 | 结果 |
| --- | --- |
| `decorView.addView` + `LayoutParams(-1,-1)` | 视图树里查不到节点 |
| `android.R.id.content` + `MATCH_PARENT` | 同上 |
| `View.GONE` 初值 | uiautomator 不显示 GONE 视图，改用 `alpha=0` |
| `LinearLayout` 当 panel | 宽度被压成 0，改 `FrameLayout` + 显式宽度 |
| `buildPanel()` 里 `root` 被加两次 | 已修 |
| `rows` 重复声明三次 | 已修（`Cannot add a null child view` 崩溃的根因） |
| `bringToFront()` in `navigate()` | 会让 `alpha=0` 的 overlay 挡住整页，已移除 |

### 3.3 当前最可疑的点

**`navigate()` 每次重建 `toolbar`，而 `toolbar` 的点击 lambda 捕获的是 `this@MainActivity.openSiteSwitcher()`。** 这个调用链本身是对的：

```kotlin
setOnClickListener { openSiteSwitcher() }          // MainActivity:161
private fun openSiteSwitcher() { if (::switcher.isInitialized) switcher.open() }   // :65
```

`uiautomator` 里能查到 `content-desc="切换站点，当前 Rule34Video"` 且 `clickable="true"`，说明控件在线。

**怀疑方向**：`switcher` 在 `onCreate` 里被加进 `android.R.id.content`，但 `setContentView(root)` 之后 `ContentFrameLayout` 又加了一层子视图，导致 switcher 虽然 `isAttachedToWindow=true`（旧日志确认过），但**父容器尺寸是 0**。

### 3.4 排查建议（给接手的人）

最直接的办法：**别再用 overlay，改成真正的 `DrawerLayout`**。

```kotlin
// 依赖：androidx.drawerlayout:drawerlayout:1.2.0（一个很小的库）
val drawer = DrawerLayout(this)
val content = column()      // 现有 root 全部内容
val panel = buildPanel()    // 现有 buildPanel() 直接复用
drawer.addView(content, DrawerLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
drawer.addView(panel, DrawerLayout.LayoutParams(dp(320), MATCH_PARENT).apply { gravity = Gravity.START })
// 打开：drawer.openDrawer(panel)
// 关闭：drawer.closeDrawer(panel)
// 边缘滑动：DrawerLayout 自带，无需自己写手势
```

`DrawerLayout` 自带边缘滑动、scrim、返回键处理，能一次性解决这三件事——之前手写手势踩的所有坑（系统返回手势区冲突、`ViewFlipper` 抢事件、`ACTION_CANCEL` 误判）全部消失。

**`SiteSwitcher.kt` 里的 `buildPanel()` / `siteRow()` / `renderRows()` 可以直接搬走复用**，只把 `FrameLayout` 外壳换成 `DrawerLayout` 的一侧。

---

## 四、另一个待修问题

`MainActivity.kt` 的 `explore()` 已改为按适配器 `sections` 渲染，但 **`index()` 仍写死 KVS 的分段**（`works` / `artists` / `work:`）。hanime1 没有这些索引，点进去是空列表。

`HanimeAdapter.index()` 目前直接返回 `emptyList()`，等于静默失败。建议改成：

```kotlin
// HanimeAdapter
override suspend fun index(section: String): List<Link> = emptyList()
override fun sectionUrl(key: String): String? = null
```

并在 `explore()` 里过滤掉 `sectionUrl == null && feed == null` 的入口。

---

## 五、未验证 / 已知边界

1. **移动 UA 是否触发 hanime1 不同模板** —— 未测。当前沿用 R34V 现有的 Android Chrome UA，实测返回 200 且页面结构正常。
2. **登录** —— `HanimeAdapter.login()` 实现了 Laravel CSRF 流程，但**没有真实账号，从未跑通**。KVS 登录同理。
3. **视频 CDN 直连失败** —— 本机所有 CDN 直连返回 `000`、走代理 `206`。这可能是本机网络策略，**不代表用户网络**。`SiteContext.proxyRequired` 字段留了但未接入。
4. **分页深度** —— `?page=N` 只验证到第 2 页。
5. **相关推荐** —— `related-tabcontent` 的解析写了但未单独验证。

---

## 六、给接手者的一句话

**数据层是真做完的，别重写。** 50 项单测绿、两个站点真实跑通、隔离验证过——这些是硬证据。

**只差一个抽屉。** 用 `DrawerLayout` 换掉手写 overlay，半小时的事。之前之所以卡这么久，是因为在跟 Android 的手势分发、`ViewFlipper`、系统返回手势区硬碰，而 `DrawerLayout` 早就把这些处理好了。

`SiteSwitcher.kt` 里的面板内容代码（`buildPanel`、`siteRow`、`renderRows`）可直接保留，只换外壳。
