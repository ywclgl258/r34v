# Kotlin 原生架构

本轮按用户要求整体切换为 Kotlin。所有应用逻辑位于 `app/src/main/kotlin/com/r34v/r34v`，项目根目录就是 Android Gradle 工程；构建不需要 Flutter、Dart、Rust、Node 或 NDK。

## 模块和数据流

- `MainActivity`：首页、发现、分页列表、索引、搜索、片库、登录和设置。原生 View + RecyclerView，黑底、暖白和珊瑚红，精选封面与横向片架。
- `PlayerActivity`：详情与播放、清晰度、倍速、手势、横竖屏全屏、进度保存和过期地址刷新。
- `data/HtmlParser`：Jsoup 离线解析站点 HTML，不运行页面脚本。列表、详情、分类和两类排行榜分别解析。
- `data/Network` 和 `data/RoutedDns`：OkHttp、站点范围内的 Cookie、Cloudflare/Google DoH、已验证 IP 池、VPN 检测、HTTP/SOCKS5 代理。手填 IP 和旧 IP 池在 DoH 故障时仍保留，只有 TLS 验证成功的连接才能更新持久池。页面请求可取消且有限时；视频连接独立设置，不用整文件请求时限打断长视频。
- `data/LibraryStore`：原生私有 SharedPreferences，沿用旧 JSON 字段。对旧 Flutter 配置只做一次复制迁移，保留原件供回退。
- `playback/PlaybackStore`：应用级 Media3 DownloadManager、前台下载服务、持久下载索引。完整下载和播放临时缓存使用不同的 SimpleCache。
- `UpdateController`：GitHub Release 检查、更新包下载及通过 FileProvider 调用系统安装器。

播放器与下载共用网络配置。站点 Cookie 只发送给站点域名，不发送给 CDN 或 GitHub。TLS 域名和证书验证保持启用；自定义 IP 通过 DNS 层接入。VPN 或代理启用时遵循其域名路由。

## 缓存与生命周期

播放缓存上限 512 MiB，采用 LRU 回收。完整下载在应用私有文件目录持久保存，通过片库手动删除。下载支持暂停、继续、删除、前台通知、网络条件调度和进程重启恢复；失败后最多额外刷新一次源地址。Android 前台服务达到系统时限时暂停任务。

播放器在退出或进入后台时保存进度并释放，在回到界面时重建。切换清晰度保留位置和暂停状态。完整缓存的离线播放禁止网络回源；旧版缓存文件经应用私有目录检查后仍可播放。列表网络失败时可读取本地快照，并显示离线缓存提示。图片缓存为 24 MiB 内存及约 64 MiB 磁盘。

旧版多播放器桥接和手写分段传输已退役。当前播放器统一使用 Media3，完整下载使用其原生范围请求和断点恢复，支持同时下载两个任务。旧版的 MPV 选择和自定义 MP4 多线程分段开关不再作为有效设置显示。

## 数据迁移

保持包名 `com.r34v.r34v`，当前版本为 2.1.0 / 26。2.0.0 与 1.18.0 的签名来源不同，现已加入签名继承，支持覆盖升级并保留数据；详细平台边界见 [签名与升级](signing.md)。开发版使用 `com.r34v.r34v.debug` 并明确显示“开发版”，与发布版隔离。

迁移包括观看历史、视频/角色/作者/标签收藏、设置、登录会话、搜索历史和旧缓存索引。搜索列表兼容旧 Java 列表格式和 JSON 格式，旧浮点倍速转换成原生配置。迁移幂等，重启不会再次覆盖新进度。原生版的持续播放缓存使用新目录，旧版未完成的临时传输文件不会当作完整视频导入。

## 验证

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat assembleRelease
```

JVM 测试覆盖页面样本、分页、懒加载图片、分组边界、播放地址转义、路由、JSON 兼容和版本比较。Android 测试使用合成测试视频，验证实际迁移、原生解码、跳转进度、倍速、播放器释放，以及后台下载暂停/继续和断网读取完整缓存。BrowseUiTest 以隔离的中性网络素材驱动实际界面，覆盖收藏读回/取消、搜索范围与返回状态、片库切换和原生播放器打开，并取得默认与200%字体截图；测试夹具不会进入发布包。真实站点的账户登录和更新安装需要对应账号或新发布版本才能最终验收。

工程仅参考 Han1meViewer 的技术方向，未复制其应用源码。AGP 9 的 Kotlin 接入按 [Android 官方迁移说明](https://developer.android.com/build/migrate-to-built-in-kotlin)配置；下载服务使用 [Media3 官方下载架构](https://developer.android.com/media/media3/exoplayer/downloading-media)。
