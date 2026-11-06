# Kotlin 原生架构

应用使用 Kotlin 与 Jetpack Compose。所有应用逻辑位于 `app/src/main/kotlin/com/r34v/r34v`，项目根目录就是 Android Gradle 工程；构建不需要 Flutter、Dart、Rust、Node 或 NDK。2.2.0 完成原生 View 到 Compose 的界面迁移，保持原有协议、存储格式和签名继承链。

## 模块和数据流

- `MainActivity`：Compose Scaffold、站点抽屉、首次教程及首页/发现/搜索/片库/设置导航。页面输入、站点和返回栈通过状态与 Bundle 恢复；FeedStates ViewModel 按站点、请求与屏蔽规则保存最多 8 份列表和对应滚动状态，5 分钟后整体过期。进程死亡后重新加载列表并从头布局，完整列表不写入 Bundle；轮播与展开状态仍通过 rememberSaveable 管理。
- `ui/ComposeBrowse`：列表请求、LazyVerticalStaggeredGrid 搜索结果与漫画、发现和索引；手机双列，宽屏三列。请求随页面离开取消，刷新取消旧分页并阻止过期回包；分页保留原站游标与语言条件。过滤后空页不等于结束，连续三页无可显示内容后提供手动继续；重复游标停止自动请求并可刷新恢复。
- `ui/ComposeCinema`：2.2.1 沉浸式 Hero、透明顶栏配套、轻量底部导航、LazyColumn + LazyRow 海报片架、真实继续观看和片单状态。最新内容在最后追加，避免可选榜单或片架插入导致跳过首页 Hero。
- `PlayerActivity`：Compose 详情、播放控件、清晰度、倍速、手势、横竖屏全屏、进度保存和过期地址刷新。仅 Media3 PlayerView 画面使用 AndroidView，关闭自带控制器，所有控制按钮与时间轴均为 Compose。
- `GalleryActivity` 与 `ui/ComposeReader`：Compose 画廊详情与 LazyColumn 条漫、缩放、全屏、跳页及页内进度。ReaderBuffer 独立管理可见页和后续三页预读，最多两个并发，额外屏外解码预算24MiB；位图释放采用移除引用，避免 RenderThread 使用已 recycle 的位图。阅读进度每 750ms 合并写入，离开或进入后台时立即保存；下载目录在打开图集时解析一次，逐页读取不再重复检查整本。
- `SiteAuthActivity`：Compose 登录说明与操作，仅官方 WebView 使用 AndroidView；保留官方域名限制、TLS校验、权限验证与退出时临时 Cookie 清理。
- `ui/ComposeUi`：黑底中性灰阶与三级材质主题、共享字号/固定圆角/无描边填充按钮、`GlassSurface`、Coil AsyncImage 封面、菜单、玻璃底部面板和对话框。`R34Application.coverLoader` 按站点缓存 loader，共享有界内存/磁盘缓存并为每次请求取得当前 Network.client。旧 View 组件与 RecyclerView/DrawerLayout 直接依赖已删除。
- `data/HtmlParser`：Jsoup 离线解析站点 HTML，不运行页面脚本。列表、详情、分类和两类排行榜分别解析。
- `data/Network` 和 `data/SystemProxySelector`：OkHttp、分站 Cookie、系统代理与系统 DNS。遵循系统按 URL 作出的代理/PAC 排除决定，未配置代理才直连；不尝试代理列表中的后续直连回退。移除 DoH、固定/手填 IP、IP 池、VPN 分支和应用内独立代理，旧配置在迁移时清除。系统代理变更广播使旧空闲连接失效，新请求采用新设置。页面请求可取消且有限时，视频连接不设整文件请求时限。
- `data/LibraryStore`：原生私有 SharedPreferences，沿用旧 JSON 字段。对旧 Flutter 配置只做一次复制迁移，保留原件供回退。
- `data/PersonalLibrary`：跨站稍后看/合集、完成状态、页码书签、分站阅读模式与订阅收件箱；以 `site:id` 保留来源身份，不改变原有分站收藏/历史。不可变快照按原始 JSON 缓存，写入仍通过 LibraryStore 的同一 preferences。
- `ui/ComposeCrossSearch`：独立 ViewModel 管理分站搜索、最多三个并发、各站游标/错误及查询替换取消；语言和屏蔽规则变化使请求失效。跨站展示不切换全局站点或共用账号。
- `downloads/BatchDownloads` 与 `DownloadPolicy`：多选作品逐项解析并加入原下载引擎，添加进度和失败分开记录；Media3 和图集共用系统计费条件，等待网络保持队列，手动暂停单独保留。
- `data/LibraryBackup` 与 `ui/ComposeBackup`：类型明确的 schema 1 导出、用户密码认证加密、系统文件选择器、解密预览与一次 preferences 合并提交；会话、本机密钥、下载路径/索引与媒体文件不导出。
- `playback/PlaybackStore`：应用级 Media3 DownloadManager、前台下载服务、持久下载索引。完整下载和播放临时缓存使用不同的 SimpleCache。
- `downloads/GalleryDownloadStore` 与 `GalleryDownloadService`：漫画持久任务、按页断点续传、单本队列与前台通知。每页以临时文件加原子替换写入，按站点/作品保存；暂停/删除先取消并等待在途任务，后台时限到达时保存进度并暂停。服务重建或用户回到应用后恢复未完成队列。
- `ui/ComposeDownloads`：统一下载中心，汇总全部站点的 Media3 视频任务、漫画任务和旧版离线视频；按站点和媒体类型筛选。共享展示不改变任务的源站身份、签名地址解析或文件隔离。原有视频索引和旧缓存文件原地沿用。任务事件与 750ms 进度更新只读取轻量状态，首次进入/恢复、任务状态或索引变化、存储权限变化和手动刷新才扫描磁盘；删除视频先等待 Media3 停写，再清理两处目录，期间同 ID 的重新下载等待清理完成。
- `UpdateController`：GitHub Release 检查、Compose 更新/下载进度对话框及通过 FileProvider 调用系统安装器；安装前包名/版本/签名校验保留。APK 传输不受页面 25 秒总时限限制，保留连接/读取超时及系统代理；取消会关闭网络调用，长度验证后提交文件，进度更新至多每 100ms 一次。

播放器与下载共用系统网络路径。站点 Cookie 只发送给站点域名，不发送给 CDN 或 GitHub。TLS 域名和证书验证保持启用；没有自定义 DNS 或 IP 替换。系统 VPN 由 Android 网络栈处理。

## 缓存与生命周期

播放缓存上限 512 MiB，采用 LRU 回收。完整下载在应用私有文件目录持久保存，通过片库手动删除。下载支持暂停、继续、删除、前台通知、网络条件调度和进程重启恢复；失败后最多额外刷新一次源地址。Android 前台服务达到系统时限时暂停任务。

播放器在普通后台暂停，并在真正停止可见时保存进度和释放，返回时重建；2.3.0 画中画可见期间保留同一引擎继续播放，进入/退出小窗不重建 Activity。切换清晰度保留位置和暂停状态；全屏与自动全屏已应用状态随重建恢复。观看记录按视频所属站点写入，不受后台切站影响，JSON格式不变。完整缓存的离线播放禁止网络回源；旧版缓存文件经应用私有目录检查后仍可播放。列表网络失败时可读取本地快照，并显示离线缓存提示。Coil 封面共享24MiB内存及64MiB磁盘缓存，键包含站点，离开 Composition 时由 AsyncImage 管理请求取消，解码尺寸服从布局约束；漫画 ReaderBuffer 的预读缓存独立。首页可选榜单各最多再等600ms，不要求排行榜成功才显示最新内容。

漫画阅读临时文件仍位于 `files/gallery-cache/<site>`，每站256MiB LRU；主动下载由 StorageRoot 选择应用内 `files/gallery-downloads/<site>/<作品ID哈希>` 或公共目录 `R34V/comics/<site>/<作品ID哈希>`，不参与阅读缓存淘汰。任务元数据保存在 `gallery_downloads_v1` SharedPreferences。只有全部页文件与页码目录保存后才写完成标记并将任务置为已下载。离线打开读取下载目录中的详情、页码映射及原始图页，缺失时明确报错，禁止回源；已下载的EX漫画可在无会话时本地阅读，联网加载和未下载的私有内容仍执行原有登录门禁。

旧版多播放器桥接和手写分段传输已退役。当前播放器统一使用 Media3，完整下载使用其原生范围请求和断点恢复，支持同时下载两个任务。旧版的 MPV 选择和自定义 MP4 多线程分段开关不再作为有效设置显示。

## 数据迁移

保持包名 `com.r34v.r34v`，当前发布版本为2.5.1 / 44。2.0.0 与1.18.0的签名来源不同，现已加入签名继承，支持覆盖升级并保留数据；详细平台边界见 [签名与升级](signing.md)。开发版使用 `com.r34v.r34v.debug` 并明确显示“开发版”，与发布版隔离。

迁移包括观看历史、视频/角色/作者/标签收藏、设置、登录会话、搜索历史和旧缓存索引。搜索列表兼容旧 Java 列表格式和 JSON 格式，旧浮点倍速转换成原生配置。迁移幂等，重启不会再次覆盖新进度。原生版的持续播放缓存使用新目录，旧版未完成的临时传输文件不会当作完整视频导入。

## 验证

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
$env:R34V_SIGNING_HOME = 'C:\Users\ywclg\.r34v\signing'
.\gradlew.bat :app:validateReleaseSigning :app:assembleRelease
```

JVM 测试覆盖页面样本、分页、懒加载图片、分组边界、播放地址转义、路由、JSON 兼容和版本比较。Android 测试使用合成测试视频，验证实际迁移、原生解码、跳转进度、倍速、播放器释放，以及后台下载暂停/继续和断网读取完整缓存。BrowseUiTest 以隔离的中性网络素材驱动实际界面，覆盖收藏读回/取消、搜索范围与返回状态、片库切换和原生播放器打开，并取得默认与200%字体截图；测试夹具不会进入发布包。真实站点的账户登录和更新安装需要对应账号或新发布版本才能最终验收。

工程仅参考 Han1meViewer 的技术方向，未复制其应用源码。AGP 9 的 Kotlin 接入按 [Android 官方迁移说明](https://developer.android.com/build/migrate-to-built-in-kotlin)配置；下载服务使用 [Media3 官方下载架构](https://developer.android.com/media/media3/exoplayer/downloading-media)。

## 2.4 新模块与控制材质

- `data/LocalLibrary`：有界 ZIP/CBZ 与 SAF 目录导入、私有副本、自然排序、去重和本地图页；逻辑身份不加入网络站点列表。
- `data/PersonalCatalog`、`PlaybackQueue`、`IncomingLink`：跨来源片库快照与智能规则、持久顺序会话、小体积媒体路由 Intent 和受支持分享链接识别。
- `ui/ComposePersonalHome`、`ComposeLibraryHub`、`ComposeCollections`：个人继续入口、固定／智能片单、离线准备和宽屏列表详情。`ComposeThumbnails` 与 `ComposeLocalImport` 复用既有阅读器及系统选择器。
- `ui/ComposeLiquid`：Backdrop 1.0.0 封装，控制与内容为兄弟图层；API 31 模糊、33 折射、旧版及减少透明效果使用实底并停止图层记录。`ComposeMediaStage` 根据视频／漫画／写真选择展示比例和动作词。正文、内容卡片和下载任务不使用采样材质；Media3 SurfaceView 不作为 Compose 背景采样。

## 2.5 首批功能与恢复模型

- `data/NextFeatures`、`SearchSupport`：高级筛选、包含／排除标签、播放与阅读偏好、时间书签、单图收藏和发现频道共用 `PersonalStore` 与既有备份。`FeedRequest` 增加内容类型和条件身份；Iwara 图库以 `site + gallery + id` 区分旧视频，缓存键不改写。
- `data/adapter/IwaraAdapter`：视频继续使用 `/videos`，图库使用 `/images` 与 `/image/<id>`。图库页地址以 `#page=N` 保持页文件身份唯一；混合文件中的视频不进入图片解码路径。
- `downloads/DownloadRecovery`：下载错误分类、有限退避、`Retry-After` 秒数／HTTP 日期解析、长期限流等待和存储／TLS 边界。`PlaybackStore` 以删除代次和刷新任务取消阻止过期回包重建任务。
- `GalleryDownloadStore`：持久优先级、分组、所选页、真实完成页集合、失败阶段和重试时间。图集提交前执行完整性审计，部分保存不写整本完成标记；Media3 视频继续由原引擎调度，不伪造优先级接口。
- `GalleryRepository`：图页校验、缺页／坏页审计、部分选择清单和原下载目录恢复。部分保存只开放有效页；修复只重取损坏或缺失页。
- `ui/ComposeAdvancedSearch`、`TimestampText`、`ComposeDownloads`：组合搜索、时间戳跳转、任务详情与恢复入口复用现有黑底 Liquid UI；没有另建导航或视觉系统。

## 2.5.1 混合文章与图页恢复

- `data/adapter/CossoraProtocol`：仅识别 Cosplaytele 文章里的指定 cossora HTTPS 嵌入页，以 `video-<UUID>` 和 `文章地址#video-<UUID>` 保持视频与图集身份独立。嵌入页的公开 AES-CBC 参数在原生代码中解码，不运行整站脚本；只接受 cossora 域下的 HLS 与封面，短效令牌不写入图集目录。
- `PhotoArchiveAdapter` 的 `gallery()` 保持图页读取，并在 `GalleryDetail.videoEntries` 携带可选视频列表；`detail()` 仅在实际打开或刷新视频时解析嵌入页。`GalleryActivity` 复用 `PlayerActivity` 和 Media3 HLS，图页缓存、阅读位置、收藏与下载身份不混用。
- `DownloadRecovery` 区分 TLS 连接中断与证书／主机名安全错误。E／Ex 仅在异常链明确为连接关闭、重置或超时等传输故障时，通过页面已有 `nl` 地址单次刷新；证书与安全错误不重试。响应地址不同也先拒绝，再按页面刷新策略处理。所有路径沿用系统代理、HTTPS 证书校验与既有完成页／取消／落盘事务。
