# R34V

支持视频、漫画与写真共 18 个站点的 Kotlin 原生 Android 客户端，包括 Rule34Video、Hanime1、Iwara、MissAV、Jable、rou.video、KBJ、KBJFree、Pornhub、E-Hentai、ExHentai、nhentai、Hitomi、4KHD、Everia、4KUp、GalleryEpic 和 Cosplaytele。界面使用 Jetpack Compose + Material 3，包括浏览、片库、设置、视频控件和条漫阅读器。Media3 播放画面与仅用于 EH/EX 官方认证的 WebView 通过 AndroidView 接入；不依赖 Flutter。迁移说明见 [Compose 重构](docs/dev/compose-migration.md)。

## 功能

- **2.7.1 首页修正**：启动和底部“首页”直接展示当前站点的精选、榜单与最新内容；观看进度、稍后看、订阅更新和个人频道移至“我的”。切站后保持“首页”选中，返回与页面重建保持导航一致。
- **2.7.0 新增**：接入 MissAV、Jable、rou.video、KBJ、KBJFree 和 Pornhub，完善镜像切换、搜索／分页／排序、HLS 下载与离线播放；KBJ 提供浏览器线路入口，KBJFree 仅公开预览。见 [站点适配与验证边界](docs/dev/av-sites-adapters.md)。
- **2.7.0 片库与界面**：个人首页增加媒体类型筛选，片库分区与搜索层级简化；片单内按标题／来源检索，结合加入时间、类型和完成状态筛选，并按标题或加入时间排序。合集支持备注、置顶和从已有作品选择封面，信息随加密备份恢复。见 [片库增强](docs/dev/shelf-features-2026-11-08.md) 与 [2.7.0 发布验收](docs/dev/verification-2.7.0.md)。
- **2.7.0 兼容与性能**：按实际应用窗口适配布局，减少阅读滚动与导航指示器重组，完善画中画过渡、Android 主题图标和系统备份规则。警告分类、修复与保留理由见 [Lint 优化](docs/dev/lint-optimization-2026-11-08.md)。
- **2.6.0 新增**：播放器 A–B 循环；阅读状态栏、阅读亮度与保持亮屏；原图保存／分享、整本或选页 CBZ 导出；保存搜索的更新检查与已读／未读。首次刷新建立基线，各来源失败独立显示，搜索更新随加密备份保存。见 [功能验收](docs/dev/verification-feature-batch-2026-11-07.md)。
- **2.6.0 界面更新**：首页突出真实封面与继续操作，黑色固定底栏、小圆角按钮和齿轮设置统一层级；片库快捷入口适配大字体，搜索框置顶。修复手机片单半列空白、末尾入口被底栏遮挡、已看完仍提示继续，以及本地单图收藏恢复和双页阅读旋转保页问题。见 [原生界面改版](docs/dev/modern-ui-2026-11-08.md)。
- **2.4.0 新增**：个人首页汇总跨来源续看与阅读进度，直达稍后看、订阅更新和常用搜索；搜索方案保存关键词、来源、媒体类型与漫画语言，可重命名、删除并随加密备份恢复。
- 合集可按顺序观看／阅读，明确确认后进入下一项，支持跨媒体与上下文恢复；片库采用封面书架，新增按规则动态更新的智能片单。大屏可并列显示列表与选中详情，播放与阅读保持全屏。
- 支持从浏览器分享完整作品链接到应用；离线准备按真实状态汇总缺少、已排队和已就绪项目，未知容量明确标示。阅读器增加页面缩略图总览；本地 CBZ／ZIP／图片目录可复制导入、自然排序并离线阅读，原文件保留。单本上限 2000 页／1 GiB，导入副本随应用卸载移除。详见 [2.4.0 功能记录](docs/dev/features-2026-11-06.md)。
- **2.3.0 已有**：跨站关键词搜索与来源选择，各站独立分页、重试并保留分站筛选；统一稍后看和自定义合集，支持来源/类型/完成状态筛选、手动排序和批量下载。
- 标签订阅增加更新收件箱和已读/未读，首次成功刷新建立基线，缓存和失败响应不误报更新；刷新最多三个并发请求。
- 漫画/写真阅读支持上下连续、左右翻页和横屏双页，页码书签和模式按来源保存；播放器新增防误触锁定、定时暂停和 Android 8+ 画中画。
- 下载中心支持多选暂停/继续，视频与图集统一遵守“任意网络 / 仅非计费网络”。片单可多选批量加入下载，添加失败逐项显示，已加入的任务不受取消剩余添加影响。
- 设置中的备份与恢复使用用户密码加密文件，支持恢复预览和合并去重；包含收藏、订阅、进度、片单、书签、屏蔽词与设置，登录会话和下载文件仍由原有功能管理。详细说明见 [2.3.0 功能记录](docs/dev/features-2026-11-05.md)。

- 首页展示当前站点的精选封面和最新更新片架；发现页包含分类、角色作品索引和作者排行；“我的”汇总跨站续看与订阅内容。
- 分页视频列表、多种有效排序、视频/标签/作者搜索及搜索历史。列表与滚动位置在页面往返和界面重建时一起保留，刷新会取消旧分页；语言过滤后的空页继续读取，连续空页提供手动加载。
- Media3 播放器：清晰度、0.25×–3× 倍速、横竖屏全屏、双击快进/后退、滑动调节进度/亮度/音量、长按临时倍速和断点续播。
- 视频、角色、作者、标签收藏；观看历史、内容屏蔽和标签中文词典。
- 视频按清晰度下载，四个漫画站支持整本下载；统一下载中心汇总各站视频、漫画与旧版本离线文件，支持类型/站点筛选、暂停/继续、失败重试、删除、离线播放和离线阅读。漫画按页续传，已下载整本独立持久保存，不随阅读临时数据清理。
- 站点账号登录与网络诊断。页面、封面、图页、播放、下载和更新统一跟随系统代理；未设置代理时使用系统 DNS 直连，代理失败不回退直连。已移除应用内 DoH、固定/手填 IP、IP 池和单独代理设置。
- GitHub Release 更新检查及系统安装器。慢速 APK 传输保留读取超时，不再因整文件超过 25 秒而失败；安装前验证长度、包名、版本与签名。
- 顶栏抽屉切换站点；每站历史、收藏、搜索与下载隔离。Iwara 支持公开列表、趋势/热门/点赞/播放榜、视频/标签/作者查询、清晰度播放和离线缓存；账号登录、私密内容、图库与站外嵌入尚未接入。见 [Iwara 接入说明](docs/dev/iwara-adapter.md)。
- 番号与录播站（MissAV、Jable、rou.video、KBJ、KBJFree、Pornhub）支持列表、搜索、详情与播放。其中 MissAV/Jable/rou.video/Pornhub 在应用内直接起播；KBJ 的播放器是第三方 iframe，只列线路地址供浏览器打开；KBJFree 只公开预览片段，完整片源需站内积分解锁。rou.video 的清单与分片是 PNG 外壳，在数据源层拆壳。见 [番号与录播站适配](docs/dev/av-sites-adapters.md)。
- 全新安装首次进入首页时，高亮站点切换入口，提供“打开站点列表”和“知道了”；提示仅显示一次，关闭、返回、重启或切站后均不重复。已有用户升级不弹出。见 [首次切站教程](docs/dev/site-switch-tutorial.md)。
- E-Hentai / ExHentai 支持画廊列表、分类、热门、搜索、标签与上传者查询；原生漫画阅读采用上下连续滚动的条漫形式，保留跳页、双指/双击缩放、页内阅读位置、画廊收藏和已读图页离线保存。漫画详情可下载整本并在统一下载中心离线打开。ExHentai 在线访问仍需要账号权限，未登录时显示登录门禁，可打开官方论坛登录或导入 Cookie；验证权限后才保存加密会话。真实 ExHentai 账号联网尚未验收。见 [漫画站接入说明](docs/dev/eh-adapter.md) 与 [统一下载](docs/dev/unified-downloads.md)。
- 四个漫画站的首页、搜索列表和画廊片库采用原生瀑布流，封面保留比例，横屏自动增加列数；视频站保留现有列表。网络与登录机制见 [系统网络、官方登录与瀑布流](docs/dev/system-network-auth-masonry.md)。
- 四个漫画站提供“全部 / 中文 / 日文 / 中＋日”单排语言筛选，按站点记忆，并应用于首页、分类、搜索与翻页；下滑收起、上滑显示。切站入口只显示紧凑站名和箭头，抽屉不再重复展示域名。条漫支持单击隐藏/显示菜单和沉浸全屏，并连续预读后续三页，限制并发和图片内存。见 [语言筛选与条漫阅读](docs/dev/comic-language-strip.md) 与 [阅读控件与连续预读](docs/dev/reader-controls-prefetch.md)。
- nhentai 使用官网公开 v2 API，支持最新/今日热门、分类、关键词/标签/画师查询；Hitomi 支持最新/年度热门、分类、关键词组合与排除词、标签/画师查询，原生解析二进制索引和动态 WebP 图片路由。两站复用漫画阅读器、整本下载与分站收藏/进度，无需登录；不含账号同步。见 [nhentai / Hitomi 接入说明](docs/dev/nhentai-hitomi-adapters.md)。

界面使用纯黑底、白色主按钮与红色强调色。视频、漫画和写真采用各自的封面与续看布局，个人首页汇总跨来源进度；片库集中管理稍后看、合集、智能片单与本地导入。底部导航为稳定黑色实底，暗色模糊仅用于覆盖媒体的控制层，旧系统及“减少透明效果”设置使用实色降级。封面使用 Coil AsyncImage 并保留分站请求/缓存隔离。搜索保留范围和关键词；轮播支持暂停与手动切换。共享按钮触控区至少48dp，支持大字体换行、键盘焦点和明确的空态入口。见 [界面规范](docs/ui.md) 与 [2.6.0 改版记录](docs/dev/modern-ui-2026-11-08.md)。

应用不托管内容，与原站无关联。仅供个人使用。

## 构建

需要 JDK 17 和 Android SDK 36，最低支持 Android 7.0（API 24）。用 Android Studio 打开此目录即可；也可以运行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/`，包名为 `com.r34v.r34v.debug`，与发布版并存。模拟器验证：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

实时联网探针只在显式指定对应 `Live*Probe` 类或传入 `-Pandroid.testInstrumentationRunnerArguments.live=true` 时运行；常规回归不依赖外站或电脑代理。`LiveSystemNetworkProbe` 使用 `live=true` 启用。系统代理探针需先在设备系统配置测试代理，例如本轮通过临时 adb 端口转发连接电脑代理；测试结束后撤销配置。

构建发布 APK：

```powershell
$env:R34V_SIGNING_HOME = 'C:\Users\ywclg\.r34v\signing'
.\gradlew.bat :app:validateReleaseSigning :app:assembleRelease
```

所有工具共用仓库外的签名目录。默认位置为 `%USERPROFILE%\.r34v\signing`（Linux/macOS：`~/.r34v/signing`），也可用 `R34V_SIGNING_HOME` 指向同一份目录。Android Studio 和命令行都通过本仓库 Gradle 读取其 `signing.properties`，项目内不再保存密钥或密码。

```properties
KEYSTORE_PATH=release.jks
KEYSTORE_PASSWORD=your-password
KEY_ALIAS=your-alias
KEY_PASSWORD=your-key-password
LEGACY_KEYSTORE_PATH=historical.jks
LEGACY_KEYSTORE_PASSWORD=historical-store-password
LEGACY_KEY_ALIAS=historical-alias
LEGACY_KEY_PASSWORD=historical-key-password
```

发布构建会核对 `release.properties` 中固定的证书指纹，缺少密钥或指纹不符就失败，禁止新建替代密钥或回退 debug 签名。`app/signing/upgrade.lineage` 是公开证书的签名继承证明，不含私钥。

正式标准 APK 在 Android 9 及以上支持从原签名 1.18.0 和迁移版 2.0.0 直接覆盖安装；Android 7/8 原签名版本也使用标准 APK。**仅 Android 7/8 上已装 2.0.0 的用户**使用 `*-compat-2.0.0.apk`，保留该版本签名。无需卸载应用。应用内更新会选择对应的包，并在安装前核验包名、版本和签名。

独立包名的“R34V 开发版”用于开发测试，不是正式版更新包。CI 的发布密钥必须与本机固定指纹一致；密钥缺失时仅产生明确标记的开发版。配置方法见 [签名与升级](docs/signing.md)。

CI 配置包含 API 24 / 36 模拟器回归，覆盖分页、播放、下载、存储与阅读；本轮优化和实际验证边界见 [2.2.5 优化记录](docs/dev/optimization-2026-11-05.md)。 2.2.6 统一无描边填充控件，并完善大字体、横屏选择面板和原生交互语义，见 [UI/UX 验证记录](docs/dev/borderless-ui-2026-11-05.md)。

## 2.0 迁移

2.0 使用 Kotlin 完全重写。2.0.0 曾与 1.18.0 使用不同签名，2.0.1 通过签名继承修复覆盖升级，并迁移原有历史、收藏、设置、会话和搜索记录。不要先卸载旧版，否则 Android 会删除其私有数据。

播放器统一为 Media3，旧版 MPV 切换与自定义分段下载设置已退役。下载由原生服务和 Media3 持久缓存管理。详细行为、数据流及验证边界见 [架构说明](docs/architecture.md)。

旧 Flutter/Tauri 源码与未提交修改仅存于开发机器的仓库外归档，不作为新工程模块或仓库备份目录分发。

## 技术与许可

Kotlin / Jetpack Compose / Material 3 / OkHttp / Jsoup / Media3。应用源码遵循 [MIT License](LICENSE)。技术方向参考 [Han1meViewer](https://github.com/YenalyLiew/Han1meViewer)，未复制其应用源码。

Iwara 的 API、封面字段与公开视频源签名参考 [LoveIwara](https://github.com/FoxSensei001/LoveIwara) 的 MIT 实现，使用 Kotlin 原生适配器接入；其许可声明随开发包一并保留于 `assets/licenses/LoveIwara.txt`。

2.4.0 的导航与阅读控制材质使用 [AndroidLiquidGlass / Backdrop 1.0.0](https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.0)，Apache 2.0 许可保留于 `assets/licenses/AndroidLiquidGlass.txt`。三类媒体的布局与无障碍原则参考 [apple-design-skill](https://github.com/dickwu/apple-design-skill)，继续采用 Android 原生导航、48dp 触控区域与系统字体缩放；API 24–30 和“减少透明效果”使用深色实底控件。详见 [UI 改版记录](docs/dev/liquid-ui-2026-11-06.md)。

漫画站的协议调研参考 [Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)，该参考仓库使用 GPLv3。R34V 根据实际站点 HTML 独立编写解析器和阅读器，未复制或移植其应用源码。
