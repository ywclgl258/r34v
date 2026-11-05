# Kotlin 原生架构

应用使用 Kotlin 与 Jetpack Compose。所有应用逻辑位于 `app/src/main/kotlin/com/r34v/r34v`，项目根目录就是 Android Gradle 工程；构建不需要 Flutter、Dart、Rust、Node 或 NDK。2.2.0 完成原生 View 到 Compose 的界面迁移，保持原有协议、存储格式和签名继承链。

## 模块和数据流

- `MainActivity`：Compose Scaffold、站点抽屉、首次教程及首页/发现/搜索/片库/设置导航。页面输入、站点和返回栈通过状态与 Bundle 恢复；局部滚动、轮播与展开状态通过 rememberSaveable 管理。
- `ui/ComposeBrowse`：列表请求、LazyVerticalStaggeredGrid 搜索结果与漫画、发现和索引；手机双列，宽屏三列。请求随页面离开取消，分页保留原站游标与语言条件。
- `ui/ComposeCinema`：2.2.1 沉浸式 Hero、透明顶栏配套、轻量底部导航、LazyColumn + LazyRow 海报片架、真实继续观看和片单状态。最新内容在最后追加，避免可选榜单或片架插入导致跳过首页 Hero。
- `PlayerActivity`：Compose 详情、播放控件、清晰度、倍速、手势、横竖屏全屏、进度保存和过期地址刷新。仅 Media3 PlayerView 画面使用 AndroidView，关闭自带控制器，所有控制按钮与时间轴均为 Compose。
- `GalleryActivity` 与 `ui/ComposeReader`：Compose 画廊详情与 LazyColumn 条漫、缩放、全屏、跳页及页内进度。ReaderBuffer 独立管理可见页和后续三页预读，最多两个并发，额外屏外解码预算24MiB；位图释放采用移除引用，避免 RenderThread 使用已 recycle 的位图。
- `SiteAuthActivity`：Compose 登录说明与操作，仅官方 WebView 使用 AndroidView；保留官方域名限制、TLS校验、权限验证与退出时临时 Cookie 清理。
- `ui/ComposeUi`：黑底中性灰阶与三级材质主题、共享小字号/圆角/形变按钮、`GlassSurface`、Coil AsyncImage 封面、菜单、玻璃底部面板和对话框。`R34Application.coverLoader` 按站点缓存 loader，共享有界内存/磁盘缓存并为每次请求取得当前 Network.client。旧 View 组件与 RecyclerView/DrawerLayout 直接依赖已删除。
- `data/HtmlParser`：Jsoup 离线解析站点 HTML，不运行页面脚本。列表、详情、分类和两类排行榜分别解析。
- `data/Network` 和 `data/SystemProxySelector`：OkHttp、分站 Cookie、系统代理与系统 DNS。遵循系统按 URL 作出的代理/PAC 排除决定，未配置代理才直连；不尝试代理列表中的后续直连回退。移除 DoH、固定/手填 IP、IP 池、VPN 分支和应用内独立代理，旧配置在迁移时清除。系统代理变更广播使旧空闲连接失效，新请求采用新设置。页面请求可取消且有限时，视频连接不设整文件请求时限。
- `data/LibraryStore`：原生私有 SharedPreferences，沿用旧 JSON 字段。对旧 Flutter 配置只做一次复制迁移，保留原件供回退。
- `playback/PlaybackStore`：应用级 Media3 DownloadManager、前台下载服务、持久下载索引。完整下载和播放临时缓存使用不同的 SimpleCache。
- `downloads/GalleryDownloadStore` 与 `GalleryDownloadService`：漫画持久任务、按页断点续传、单本队列与前台通知。每页以临时文件加原子替换写入，按站点/作品保存；暂停/删除先取消并等待在途任务，后台时限到达时保存进度并暂停。服务重建或用户回到应用后恢复未完成队列。
- `ui/ComposeDownloads`：统一下载中心，汇总全部站点的 Media3 视频任务、漫画任务和旧版离线视频；按站点和媒体类型筛选。共享展示不改变任务的源站身份、签名地址解析或文件隔离。原有视频索引和旧缓存文件原地沿用。
- `UpdateController`：GitHub Release 检查、Compose 更新/下载进度对话框及通过 FileProvider 调用系统安装器；安装前包名/版本/签名校验保留。

播放器与下载共用系统网络路径。站点 Cookie 只发送给站点域名，不发送给 CDN 或 GitHub。TLS 域名和证书验证保持启用；没有自定义 DNS 或 IP 替换。系统 VPN 由 Android 网络栈处理。

## 缓存与生命周期

播放缓存上限 512 MiB，采用 LRU 回收。完整下载在应用私有文件目录持久保存，通过片库手动删除。下载支持暂停、继续、删除、前台通知、网络条件调度和进程重启恢复；失败后最多额外刷新一次源地址。Android 前台服务达到系统时限时暂停任务。

播放器在退出或进入后台时保存进度并释放，在回到界面时重建。切换清晰度保留位置和暂停状态；全屏与自动全屏已应用状态随重建恢复。观看记录按视频所属站点写入，不受后台切站影响，JSON格式不变。完整缓存的离线播放禁止网络回源；旧版缓存文件经应用私有目录检查后仍可播放。列表网络失败时可读取本地快照，并显示离线缓存提示。Coil 封面共享24MiB内存及64MiB磁盘缓存，键包含站点，离开 Composition 时由 AsyncImage 管理请求取消，解码尺寸服从布局约束；漫画 ReaderBuffer 的预读缓存独立。首页可选榜单各最多再等600ms，不要求排行榜成功才显示最新内容。

漫画阅读临时文件仍位于 `files/gallery-cache/<site>`，每站256MiB LRU；主动下载存放在 `files/gallery-downloads/<site>/<作品ID哈希>`，不参与阅读缓存淘汰。任务元数据保存在 `gallery_downloads_v1` SharedPreferences。只有全部页文件与页码目录保存后才写完成标记并将任务置为已下载。离线打开读取下载目录中的详情、页码映射及原始图页，缺失时明确报错，禁止回源；已下载的EX漫画可在无会话时本地阅读，联网加载和未下载的私有内容仍执行原有登录门禁。

旧版多播放器桥接和手写分段传输已退役。当前播放器统一使用 Media3，完整下载使用其原生范围请求和断点恢复，支持同时下载两个任务。旧版的 MPV 选择和自定义 MP4 多线程分段开关不再作为有效设置显示。

## 数据迁移

保持包名 `com.r34v.r34v`，当前本地交付候选为2.2.0 / 34。2.0.0 与1.18.0的签名来源不同，现已加入签名继承，支持覆盖升级并保留数据；详细平台边界见 [签名与升级](signing.md)。开发版使用 `com.r34v.r34v.debug` 并明确显示“开发版”，与发布版隔离。

迁移包括观看历史、视频/角色/作者/标签收藏、设置、登录会话、搜索历史和旧缓存索引。搜索列表兼容旧 Java 列表格式和 JSON 格式，旧浮点倍速转换成原生配置。迁移幂等，重启不会再次覆盖新进度。原生版的持续播放缓存使用新目录，旧版未完成的临时传输文件不会当作完整视频导入。

## 验证

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat assembleRelease
```

JVM 测试覆盖页面样本、分页、懒加载图片、分组边界、播放地址转义、路由、JSON 兼容和版本比较。Android 测试使用合成测试视频，验证实际迁移、原生解码、跳转进度、倍速、播放器释放，以及后台下载暂停/继续和断网读取完整缓存。BrowseUiTest 以隔离的中性网络素材驱动实际界面，覆盖收藏读回/取消、搜索范围与返回状态、片库切换和原生播放器打开，并取得默认与200%字体截图；测试夹具不会进入发布包。真实站点的账户登录和更新安装需要对应账号或新发布版本才能最终验收。

工程仅参考 Han1meViewer 的技术方向，未复制其应用源码。AGP 9 的 Kotlin 接入按 [Android 官方迁移说明](https://developer.android.com/build/migrate-to-built-in-kotlin)配置；下载服务使用 [Media3 官方下载架构](https://developer.android.com/media/media3/exoplayer/downloading-media)。
