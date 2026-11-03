# R34V

rule34video.com 的 Kotlin 原生 Android 客户端。界面、网络、解析、片库、下载和播放器均为原生实现，不依赖 Flutter 或 WebView。

## 功能

- 首页精选封面、继续观看和最新更新横向片架；发现页包含分类、角色作品索引和作者排行。
- 分页视频列表、多种排序、视频/标签/作者搜索及搜索历史。
- Media3 播放器：清晰度、0.25×–3× 倍速、横竖屏全屏、双击快进/后退、滑动调节进度/亮度/音量、长按临时倍速和断点续播。
- 视频、角色、作者、标签收藏；观看历史、内容屏蔽和标签中文词典。
- 边播边缓存、前台后台下载、暂停/继续、完整缓存离线播放；旧版本已完成的离线文件可继续使用。
- 站点账号登录、DoH、VPN 检测、HTTP/SOCKS5 代理和网络诊断。
- GitHub Release 更新检查及系统安装器。

2.1 界面使用黑底、暖白文字与少量珊瑚红。首页/发现/搜索/片库四个入口固定，片库按观看/收藏/下载分组。搜索保留范围和关键词，播放详情将清晰度、倍速、收藏下载和展开信息分层。见 [界面规范](docs/ui.md)。

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

2.1.0 的标准 APK 在 Android 9 及以上支持从原签名 1.18.0 和迁移版 2.0.0 直接覆盖安装；Android 7/8 原签名版本也使用标准 APK。**仅 Android 7/8 上已装 2.0.0 的用户**使用 `*-compat-2.0.0.apk`，保留该版本签名。无需卸载应用。应用内更新会选择对应的包，并在安装前核验包名、版本和签名。

独立包名的“R34V 开发版”用于开发测试，不是正式版更新包。CI 的发布密钥必须与本机固定指纹一致；密钥缺失时仅产生明确标记的开发版。配置方法见 [签名与升级](docs/signing.md)。

## 2.0 迁移

2.0 使用 Kotlin 完全重写。2.0.0 曾与 1.18.0 使用不同签名，2.0.1 通过签名继承修复覆盖升级，并迁移原有历史、收藏、设置、会话和搜索记录。不要先卸载旧版，否则 Android 会删除其私有数据。

播放器统一为 Media3，旧版 MPV 切换与自定义分段下载设置已退役。下载由原生服务和 Media3 持久缓存管理。详细行为、数据流及验证边界见 [架构说明](docs/architecture.md)。

旧 Flutter/Tauri 源码与未提交修改仅存于开发机器的仓库外归档，不作为新工程模块或仓库备份目录分发。

## 技术与许可

Kotlin / 原生 Android View / RecyclerView / OkHttp / Jsoup / Media3。应用源码遵循 [MIT License](LICENSE)。技术方向参考 [Han1meViewer](https://github.com/YenalyLiew/Han1meViewer)，未复制其应用源码。
