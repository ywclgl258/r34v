# R34V

支持 Rule34Video、Hanime1、Iwara、E-Hentai、ExHentai、nhentai 和 Hitomi 的 Kotlin 原生 Android 客户端。浏览、片库、下载、播放器和漫画阅读器均为原生实现，不依赖 Flutter；EH/EX 官方网页登录使用独立 WebView，仅用于认证。

## 功能

- 首页精选封面、继续观看和最新更新横向片架；发现页包含分类、角色作品索引和作者排行。
- 分页视频列表、多种排序、视频/标签/作者搜索及搜索历史。
- Media3 播放器：清晰度、0.25×–3× 倍速、横竖屏全屏、双击快进/后退、滑动调节进度/亮度/音量、长按临时倍速和断点续播。
- 视频、角色、作者、标签收藏；观看历史、内容屏蔽和标签中文词典。
- 边播边缓存、前台后台下载、暂停/继续、完整缓存离线播放；旧版本已完成的离线文件可继续使用。
- 站点账号登录与网络诊断。页面、封面、图页、播放、下载和更新统一跟随系统代理；未设置代理时使用系统 DNS 直连，代理失败不回退直连。已移除应用内 DoH、固定/手填 IP、IP 池和单独代理设置。
- GitHub Release 更新检查及系统安装器。
- 顶栏抽屉切换站点；每站历史、收藏、搜索与下载隔离。Iwara 支持公开列表、趋势/热门/点赞/播放榜、视频/标签/作者查询、清晰度播放和离线缓存；账号登录、私密内容、图库与站外嵌入尚未接入。见 [Iwara 接入说明](docs/dev/iwara-adapter.md)。
- E-Hentai / ExHentai 支持画廊列表、分类、热门、搜索、标签与上传者查询；原生漫画阅读采用上下连续滚动的条漫形式，保留跳页、双指/双击缩放、页内阅读位置、画廊收藏和已读图页离线缓存。ExHentai 明确标注需要账号权限，未登录时显示登录门禁，可打开官方论坛登录或导入 Cookie；验证权限后才保存加密会话。真实 ExHentai 账号联网尚未验收。当前不提供整本批量下载。见 [漫画站接入说明](docs/dev/eh-adapter.md)。
- 四个漫画站的首页、搜索列表和画廊片库采用原生瀑布流，封面保留比例，横屏自动增加列数；视频站保留现有列表。网络与登录机制见 [系统网络、官方登录与瀑布流](docs/dev/system-network-auth-masonry.md)。
- 四个漫画站提供“全部显示 / 仅中文 / 仅日文 / 中文＋日文”语言筛选，按站点记忆，并应用于首页、分类、搜索与翻页。切站入口只显示紧凑站名和箭头，抽屉不再重复展示域名。条漫按可见页加载并释放离屏图片。见 [语言筛选与条漫阅读](docs/dev/comic-language-strip.md)。
- nhentai 使用官网公开 v2 API，支持最新/今日热门、分类、关键词/标签/画师查询；Hitomi 支持最新/年度热门、分类、关键词组合与排除词、标签/画师查询，原生解析二进制索引和动态 WebP 图片路由。两站复用漫画阅读器与分站收藏、进度、已读缓存，无需登录；不含账号同步和整本下载。见 [nhentai / Hitomi 接入说明](docs/dev/nhentai-hitomi-adapters.md)。

界面使用纯黑底、白色文字与少量红色状态提示。首页/发现/搜索/片库四个入口固定，片库按观看/收藏/下载分组。搜索支持一键清除、空输入提示并保留范围和关键词；首页轮播支持暂停、手动切换与宽屏图文并排。共享按钮触控区至少48dp，支持大字体换行、键盘焦点和明确的空态入口。播放详情将清晰度、倍速、收藏下载和展开信息分层。见 [界面规范](docs/ui.md)。

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
.\gradlew.bat assembleRelease
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

2.1.1 的标准 APK 在 Android 9 及以上支持从原签名 1.18.0 和迁移版 2.0.0 直接覆盖安装；Android 7/8 原签名版本也使用标准 APK。**仅 Android 7/8 上已装 2.0.0 的用户**使用 `*-compat-2.0.0.apk`，保留该版本签名。无需卸载应用。应用内更新会选择对应的包，并在安装前核验包名、版本和签名。

独立包名的“R34V 开发版”用于开发测试，不是正式版更新包。CI 的发布密钥必须与本机固定指纹一致；密钥缺失时仅产生明确标记的开发版。配置方法见 [签名与升级](docs/signing.md)。

## 2.0 迁移

2.0 使用 Kotlin 完全重写。2.0.0 曾与 1.18.0 使用不同签名，2.0.1 通过签名继承修复覆盖升级，并迁移原有历史、收藏、设置、会话和搜索记录。不要先卸载旧版，否则 Android 会删除其私有数据。

播放器统一为 Media3，旧版 MPV 切换与自定义分段下载设置已退役。下载由原生服务和 Media3 持久缓存管理。详细行为、数据流及验证边界见 [架构说明](docs/architecture.md)。

旧 Flutter/Tauri 源码与未提交修改仅存于开发机器的仓库外归档，不作为新工程模块或仓库备份目录分发。

## 技术与许可

Kotlin / 原生 Android View / RecyclerView / OkHttp / Jsoup / Media3。应用源码遵循 [MIT License](LICENSE)。技术方向参考 [Han1meViewer](https://github.com/YenalyLiew/Han1meViewer)，未复制其应用源码。

Iwara 的 API、封面字段与公开视频源签名参考 [LoveIwara](https://github.com/FoxSensei001/LoveIwara) 的 MIT 实现，使用 Kotlin 原生适配器接入；其许可声明随开发包一并保留于 `assets/licenses/LoveIwara.txt`。

漫画站的协议调研参考 [Ehviewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ)，该参考仓库使用 GPLv3。R34V 根据实际站点 HTML 独立编写解析器和阅读器，未复制或移植其应用源码。
