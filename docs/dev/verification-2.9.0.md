# 2.9.0 功能批次与播放兼容验收

日期：2026-11-22；正式包名 `com.r34v.r34v`，版本 `2.9.0 / 50`。

## 本轮范围

六个功能与三项基础改进（合并自 2026-11-21/22 五个工作轮，路线与过程记录见 [roadmap-2026-11-21](roadmap-2026-11-21.md)）：

1. **F41 重复文件检查**：整本内容指纹比对（逐页 SHA-256，页数+字节预筛），设置 → 本地数据入口；候选只报告，删除走下载中心/本地库安全路径，绝不删单页。
2. **F02 收口**：主搜索框标签候选 chips（与高级搜索共用词典，点候选按当前范围提交）。
3. **F37 本地视频导入**：复制进应用内（上限 8 GiB、首帧缩略图、内容摘要去重）、片库“本地内容”展示、PlayerActivity 本地分支播放/续看、移除同步清登记。
4. **F34 原图优先（EH/EX）**：解析 fullimg.php 入口与体积文案，按站点偏好统一作用于阅读/下载/导出，已缓存与已下载文件不追溯变化。
5. **F25 Iwara 账号（v1）**：登录（令牌 SessionVault 密文落盘 + 内存缓存）、API Bearer 头（媒体 CDN 不带）、关注动态 /subscriptions/videos、401 友好提示。
6. **限流可观测 + API 429 退避**：RateLimitLog 记录 429 退避/重试失败/CDN 令牌重解析，网络诊断与下载中心“检查网络”附带报告；`text()/json()` 同址退避一次。
7. **会话级 seek 缓存**：在线渐进流回退 seek 命中本地字节（64MB LRU、仅当前视频、换视频自动清理），设置默认开并入备份白名单。
8. **Android 7 兼容修复（重要）**：媒体 CDN 用 Let's Encrypt 证书，Android 7.0 出厂信任库无 ISRG Root X1 → 兼容包用户中安全 Provider 未更新的设备完全无法播放。打包官方 ISRG 根（res/raw，指纹 96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6）+ network_security_config（system + ISRG，不信任用户证书，明文仍禁）；debug 变体配置同步合并。

## 实现与本地验证

- 304 项 JVM 通过，0 失败（本轮新增 22 项：限流退避与事件 8、HostGate 3、标签匹配 6、重复扫描 5、EH 原图解析 1、Iwara 登录/订阅 2；既有“429 直接换镜像”用例按新行为改写）。
- Debug / Release Lint 均 0 errors / 28 warnings（与 2.8.0 基线一致量级；+1 为 UsableSpace 空间检查提示，与既有导入代码同款）。
- 模拟器 r34v_api24（headless）验收：F02 候选 chips、F41 空态、**F37 全链路**（注入测试视频 → catalog → 路由 → `file://` 播放至完毕 → 进度记录）、F25 设置行与发现入口（详见 [路线文档设备验收](roadmap-2026-11-21.md)）。验收后已清理注入数据。
- **Android 7 播放修复经同模拟器实测**：修复前同一视频报 `Trust anchor not found`，修复后 360p 真实媒体正常播放。
- 对真实站点的联网压测未做（验证不消耗站点限流额度）；Iwara 登录/关注动态与 EH 原图 fullimg 抓取需真实账号联调，边界已记录。

## 正式交付

- 使用仓库 Wrapper 执行 `:app:validateReleaseSigning :app:assembleRelease`（`R34V_SIGNING_HOME` 固定目录），既有 `signUpgradeRelease` 完成，`app/signing/upgrade.lineage` 未改动。
- 包名与版本经 `aapt2 dump badging` 核对：`com.r34v.r34v`，`2.9.0 / 50`（标准包与兼容包一致）。
- 标准包 API 24–27 验签通过，历史证书 SHA-256：`73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e`。
- 标准包 API 28+、兼容包 API 24–27 验签通过，当前证书 SHA-256：`75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。
- 标准 APK 文件 SHA-256：`3212121b94d4f5f14517b4ebd68310542912a594a4ff1f1478ee9a2fc91958b3`；兼容 APK 文件 SHA-256：`14fed304a9f434264c9ad6feab71fbbe748a6b62077ad4313e7c41e3a42304cd`。文件摘要不等同证书指纹。
- 兼容包仅用于 Android 7/8 上已安装迁移版 2.0.0 的用户；一般用户选择标准包。
- Android 7/8 真机未在本轮运行；API 24 模拟器已覆盖本版播放兼容路径的实测。
