# 2.8.0 播放自适应与限流防护验收

日期：2026-11-21；正式包名 `com.r34v.r34v`，版本 `2.8.0 / 49`。

## 本轮范围

两个工作轮一起进入本版：

1. 播放缓冲与链路自适应（[playback-buffering-2026-11-12](playback-buffering-2026-11-12.md)）：起播按实测带宽选档、卡顿自动降档、缓冲窗口分档、诊断加入媒体测速、流式缓存写入默认关、自动下载延后到首帧之后。
2. 限流防护与浏览器形态（[rate-limit-browser-shape-2026-11-21](rate-limit-browser-shape-2026-11-21.md)）：429 同址退避、主域请求起点节流（页面 400ms / 图片 150ms）、get-file→CDN 解析一次直连（403 自愈）、按目的地补齐浏览器形态头、限流时 feed 回退旧快照。

## 实现与本地验证

- 282 项 JVM 测试通过，0 失败。本轮新增：`HostGateTest` 3 项（槽位错开、双档间隔、并发不共槽）、`BrowserShapeTest` 6 项（页面/图片/CDN 图片/API/显式头不覆盖/未登记主机不动）、`MediaRedirectCacheTest` 3 项（第二次直达 CDN、403 同调用内重解析、无跳转不进缓存），`SiteHtmlTest` 新增 4 项（429 同址重试、无镜像恢复、Retry-After 解析、二次 429 放弃同址）。原“429 直接换镜像”用例按新行为改写。
- Debug / Release Lint 均为 0 errors / 27 warnings，与 2.7.1 基线一致。
- 常规验收按项目规则使用标准设备尺寸和默认系统字体，未执行 320dp 窄屏 / 200% 字体专项。
- 对真实站点的联网压测刻意未执行：验证本身不应消耗站点限流额度；限流防护的实际效果以待真机日常使用观察。限流阈值未知，`PAGE_REQUEST_GAP_MS` / `IMAGE_REQUEST_GAP_MS` 为保守默认值。

## 正式交付

- 使用仓库 Wrapper 执行 `:app:validateReleaseSigning :app:assembleRelease`（`R34V_SIGNING_HOME` 固定目录），既有 `signUpgradeRelease` 成功完成，`app/signing/upgrade.lineage` 未改动。
- 包名与版本经 `aapt2 dump badging` 核对：`com.r34v.r34v`，`2.8.0 / 49`（标准包与兼容包一致）。
- 标准包 API 24–27 验签通过，历史证书 SHA-256：`73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e`。
- 标准包 API 28+、兼容包 API 24–27 验签通过，当前证书 SHA-256：`75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。
- 标准 APK 文件 SHA-256：`9cce37e381f712e187caf834b61f683850293fc573dea7244318620f8719fd02`；兼容 APK 文件 SHA-256：`6b7d5cd69e02bd3c0c711108ddd3b13410e36c4cfc4c0056ff744732dc860beb`。文件摘要不等同证书指纹。
- 兼容包仅用于 Android 7/8 上已安装迁移版 2.0.0 的用户；一般用户选择标准包。
- apksigner 提示的 v2 签名块引用未知 scheme ID 3（v3.1 存在时的正常提示），与 2.7.x 交付一致，不影响验签结论。
