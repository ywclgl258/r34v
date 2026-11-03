# R34V

rule34video.com 的 Kotlin 原生 Android 客户端。界面、网络、解析、片库、下载和播放器均为原生实现，不依赖 Flutter 或 WebView。

## 功能

- 首页热门轮播、继续观看、最新更新；发现页包含分类、角色作品索引和作者排行。
- 分页视频列表、多种排序、视频/标签/作者搜索及搜索历史。
- Media3 播放器：清晰度、0.25×–3× 倍速、横竖屏全屏、双击快进/后退、滑动调节进度/亮度/音量、长按临时倍速和断点续播。
- 视频、角色、作者、标签收藏；观看历史、内容屏蔽和标签中文词典。
- 边播边缓存、前台后台下载、暂停/继续、完整缓存离线播放；旧版本已完成的离线文件可继续使用。
- 站点账号登录、DoH、VPN 检测、HTTP/SOCKS5 代理和网络诊断。
- GitHub Release 更新检查及系统安装器。

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

发布签名通过本地 `key.properties` 或同名环境变量提供：

```properties
KEYSTORE_PATH=app/r34v-release.jks
KEYSTORE_PASSWORD=your-password
KEY_ALIAS=your-alias
KEY_PASSWORD=your-key-password
```

未配置密钥时发布包不签名；配置原发布密钥后可覆盖安装。密钥和 `key.properties` 已忽略，切勿提交。

## 2.0 迁移

2.0 使用 Kotlin 完全重写，沿用发布包名及签名。覆盖安装会迁移原有历史、收藏、设置、会话和搜索记录。不要先卸载旧版，否则 Android 会删除其私有数据。

播放器统一为 Media3，旧版 MPV 切换与自定义分段下载设置已退役。下载由原生服务和 Media3 持久缓存管理。详细行为、数据流及验证边界见 [架构说明](docs/architecture.md)。

旧 Flutter/Tauri 源码与未提交修改仅存于开发机器的仓库外归档，不作为新工程模块或仓库备份目录分发。

## 技术与许可

Kotlin / 原生 Android View / RecyclerView / OkHttp / Jsoup / Media3。应用源码遵循 [MIT License](LICENSE)。技术方向参考 [Han1meViewer](https://github.com/YenalyLiew/Han1meViewer)，未复制其应用源码。
