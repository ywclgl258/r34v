# VideoShell — 在线视频站第三方客户端（Tauri + GitHub Actions）

把一个在线视频网站包装成安卓 APK 的"套壳"应用。**本地只需要 Node 和 git，不需要安装任何 Android 工具链**——Rust、Android SDK/NDK 全部在 GitHub Actions 云端安装和构建，每次 push 自动产出 APK。

## 项目结构

```
├── src/index.html                  # 占位页（正常情况下不会显示）
├── src-tauri/
│   ├── tauri.conf.json             # ★ 应用配置：站点 URL、应用名、包名都在这里改
│   ├── Cargo.toml                  # Rust 依赖（不用本地编译，仅供参考）
│   ├── capabilities/default.json   # 权限配置
│   ├── icons/icon.png              # 占位图标（可换成你自己的）
│   └── src/                        # Rust 入口（套壳应用基本不需要动）
└── .github/workflows/android.yml   # CI 打包流水线
```

## 你需要改的地方（只有一处必改）

打开 `src-tauri/tauri.conf.json`：

| 字段 | 说明 |
|------|------|
| `app.windows[0].url` | **必改**。把 `https://www.example.com` 换成目标视频站的地址 |
| `productName` | 应用显示名称（装到手机上的名字） |
| `identifier` | 包名（如 `com.xxx.player`）。⚠️ 一旦装上手机就不能再改，改了等于新应用 |
| `app.windows[0].userAgent` | 可选。如果站点给 WebView 显示异常，可加 UA 伪装成 Chrome，如 `Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36` |

换图标：准备一张 ≥1024×1024 的方形 PNG 放到 `src-tauri/icons/icon.png` 覆盖即可，CI 会自动生成全套尺寸。

## 打包流程

1. 在 GitHub 建一个仓库，然后：

   ```bash
   git init && git add -A && git commit -m "init"
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```

2. 打开仓库的 **Actions** 页签，等构建跑完（首次约 15–25 分钟，之后有缓存会快很多）
3. 进入本次运行页面底部 **Artifacts**，下载 `app-debug-apk`，解压得到 APK
4. 手机上安装（需允许"未知来源应用"）

产物是 **arm64-v8a debug 包**，覆盖绝大多数近年的手机。debug 包自带调试签名、可直接安装，适合自用；要给更多人分发见下文"正式签名"。

如需兼容老设备（32 位），在 `android.yml` 中把 targets 改为
`aarch64-linux-android,armv7-linux-androideabi`（两处都要改，构建时间会变长）。

## 首次构建后必须验证的清单（spike 测试）

在真机上装好 APK 后，按这个清单过一遍——它们是 WebView 套壳应用最常见的翻车点：

- [ ] 视频能正常播放（部分站点的播放器在 WebView 里和 Chrome 表现不一致）
- [ ] **全屏按钮能用**（见下方已知风险）
- [ ] 系统返回键能正常回退网页而不是直接退出
- [ ] 登录状态在杀掉应用重开后仍然保持（Cookie/LocalStorage 持久化）
- [ ] 站内跳转不弹出外部浏览器、外链行为符合预期
- [ ] 需要的话：下载功能是否可用

## ⚠️ 已知风险：全屏视频

Android 的 WebView 默认不支持 HTML5 全屏（需要原生层实现 `onShowCustomView` 回调），而 Tauri 的 webview 层 wry 尚未实现，这是当前[未修复的已知问题](https://github.com/tauri-apps/tauri/issues/12063)。表现通常是：视频能播，但全屏按钮点了没反应。

如果首次验证时全屏不可用，两条路（按成本排序）：

1. **JS 桥接模拟全屏**：注入脚本拦截全屏请求，改用 CSS 把播放器铺满 + 调用 Tauri API 隐藏状态栏（注意 [set_fullscreen 在部分版本有卡死问题](https://github.com/tauri-apps/tauri/issues/11254)，需实测）
2. **补 Kotlin 层**：在 `tauri android init` 生成的工程里加自定义 `WebChromeClient` 实现 custom view 全屏（参考 [VideoEnabledWebView](https://github.com/cprcrack/VideoEnabledWebView) 的思路），可以作为 CI 步骤在 init 之后自动注入
3. 如果都不理想，**降级到 Capacitor**：它的 Android WebView 实现对全屏视频、下载、外链的处理最成熟，是同类套壳应用的事实标准。前端配置部分可平移，迁移成本不大

上游相关：[Chromium 对 WebView 全屏 API 的长期限制](https://issues.chromium.org/issues/41296405)、[Tauri #9641](https://github.com/tauri-apps/tauri/issues/9641)

## 正式签名（分发给别人时再做）

debug 包不适合长期分发（签名固定才能覆盖升级）。步骤：

```bash
keytool -genkey -v -keystore release.keystore -alias videoshell -keyalg RSA -keysize 2048 -validity 10000
```

- keystore 文件 base64 后放到仓库 Secrets（如 `KEYSTORE_BASE64`），密码和别名也放 Secrets
- 在 workflow 的 build 步骤前加：解码 keystore、设置 `TAURI_ANDROID_KEYSTORE_PATH` / `TAURI_ANDROID_KEYSTORE_PASSWORD` / `TAURI_ANDROID_KEY_ALIAS` 环境变量
- 去掉 `--debug`，并加 `--apk` 产出签名的 release APK

## 其他说明

- **iOS**：同一套代码可以 `tauri ios build`，但需要 macOS 环境 + 付费 Apple 开发者账号（$99/年），GitHub Actions 的 macOS runner 也只能出模拟器包，真机包必须自己签名
- **构建缓存**：workflow 里有 rust-cache，首次慢是正常的；改了 `Cargo.toml` 后那一次构建会重新缓存
- **合规提醒**：套壳应用只做壳、内容仍来自原站时，自用通常没什么问题；但若公开分发，应用名/图标不要冒用原站品牌，且注意原站服务条款
