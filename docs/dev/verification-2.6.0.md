# 2.6.0 发布验收

日期：2026-11-08。用户授权提交、推送和上传；正式版本 **2.6.0 / 46**，包名 `com.r34v.r34v`。

## 更新范围

- 界面现代化：封面与继续操作优先，黑色固定底栏、小圆角控件、齿轮设置、片库快捷入口、搜索框置顶。修复手机片单／单图收藏的半列空白、大字体操作被底栏遮挡，以及已完成作品仍提示继续。
- 播放 A–B 区间循环；阅读时间／电量／页码／进度状态栏、亮度和保持亮屏。
- 原图保存和分享、整本或选页 CBZ 导出；保存搜索更新检查、逐来源错误和已读／未读，状态随加密备份保存。
- 本地单图收藏备份使用稳定书籍 ID 与页码，恢复后从当前本地库解析缩略图；双页阅读旋转与重建保持当前逻辑页。

具体行为、限制和开发期证据分别见 [三组新功能](verification-feature-batch-2026-11-07.md) 与 [界面改版](modern-ui-2026-11-08.md)。

## 本地验证

- 仓库 Wrapper 的 `:app:validateReleaseSigning :app:assembleRelease` 成功，明确执行 `signUpgradeRelease`，保留 `app/signing/upgrade.lineage`。沿用仓库外既有固定签名材料，未生成密钥或构建后另行签名。
- `:app:testDebugUnitTest :app:lintDebug :app:lintRelease` 成功：192 项 JVM，0 failures / errors / skipped；Debug Lint 0 errors / 96 warnings，Release Lint 0 errors / 94 warnings。
- 开发期三组功能与界面专项已在 API 24／36 验证，包含 375dp／200% 字体、1280×800dp 宽屏、循环重建、原字节导出与恢复、保存搜索状态和收藏重启找回。早期模拟器退出、空截图和测试时钟问题保留原始记录，不将中断批次整体算作通过。
- 本轮正式标准 APK 在 API 24 从 **2.5.2 / 45 覆盖升级到 2.6.0 / 46** 成功，冷启动 `MainActivity` 返回 `Status: ok`；原生首页 → 片库 → 搜索 → 首页的可见标题检查通过，已检查实际截图。
- CI 默认回归增加 `FeatureBatchUiTest`、`ModernUiTest`、`GalleryExportTest` 和 `SavedSearchStorageTest`。人工走查夹具只有显式 `modernUiReview=1` 才启动，常规 CI 跳过该人工入口。

首轮完整 CI 暴露两处测试同步假设：Media3 数据库完成状态早于主线程写入资料库 sidecar；保存搜索列表的新结果在较小视口中尚未创建可见行。回归用例改为等待索引结果，并在数据更新后滚动列表再断言可见；保留原有内容与已读断言，未修改正式 APK 代码。本地 API 36 的 `VideoRemovalTest`、`FeatureBatchUiTest` 共 7 项补验通过；修正后的完整双平台 CI 结果见 Actions。

本轮日志、JVM／Lint、包身份、签名、升级和冷启动证据保存在本地 `dist/verification-2.6.0/`。远端 CI 与上传后的校验另在该目录记录，以实际运行状态为准。

## 正式包身份

| 文件 | 类型 | 字节数 | 文件 SHA-256 |
| --- | --- | ---: | --- |
| `R34V-v2.6.0-android.apk` | 标准正式包 | 3407114 | `69b01221db51182ebf640c87e85a90f31336d526449795291e9520afb11d82af` |
| `R34V-v2.6.0-compat-2.0.0.apk` | Android 7/8 已装迁移版 2.0.0 专用兼容包 | 3403018 | `e2248c6710bb643415b72fde92b17d9ea6e95c734ad81bdeedcc4a2b33ecfb4e` |

SDK `apksigner verify --verbose --print-certs` 分段核验通过：

- 标准包 API 24–27：历史证书 SHA-256 `73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e`。
- 标准包 API 28–36：当前证书 SHA-256 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。
- 兼容包 API 24–27：当前证书 SHA-256 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。

两包均经 `aapt dump badging` 核对为 `com.r34v.r34v`、46／2.6.0、名称 R34V。文件 SHA-256 仅用于完整性，不作为签名证书身份。发布同时提供 `R34V-v2.6.0-SHA256SUMS.txt`，标准包使用 `-android.apk` 名称，在 GitHub 返回的附件列表中排在兼容包之前，兼容旧版按首个 APK 选择更新的逻辑；兼容包保留 `-compat-2.0.0` 标记。

## 发布入口与边界

- 源码分支 `main`，发布标签 `v2.6.0`；[Release 入口](https://github.com/ywclgl258/r34v/releases/tag/v2.6.0)。上传前完成本地校验，远端资产摘要与下载后的哈希均需对应上表，CI 通过后公开 Release。
- 本轮未重新验证所有真实站点、账号联网、真实第三方分享接收与全部历史版本升级。旧 2.0.0 兼容包完成静态分段验签，未重复旧 2.0.0 的运行时升级。
- 公开附件仅为两份正式 APK 与校验和；开发包、私有运行证据、签名材料及仓库外旧源码不上传。
