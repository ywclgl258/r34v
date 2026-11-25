# 结档说明（2026-11-25）

R34V 于 2.12.1 结档，项目不再接受功能更新与缺陷修复。仓库保留全部历史、签名继承链和可复现构建配置，后续如需维护，从本快照继续。

## 结档版本

| 项 | 值 |
| --- | --- |
| 版本 | 2.12.1 / versionCode 57 |
| 包名 | `com.r34v.r34v`（开发版 `com.r34v.r34v.debug`） |
| 结档提交 | `a7fdc5f` fix(android): preserve playback speed and reader geometry |
| 上一正式版本 | 2.12.0 / 56（[v2.12.0 发布入口](https://github.com/ywclgl258/r34v/releases/tag/v2.12.0)） |

2.12.1 相对 2.12.0 的改动只有两项：站点默认倍速与本次手动倍速并存时，播放器重建后仍保留本次选择；漫画按实际图页尺寸限制缩放拖动，并在阅读模式切换和界面重建时复用图页宽高比。

## 结档产物

`dist/v2.12.1/`（受 Git 忽略，属于本地交付目录）：

| 文件 | 说明 |
| --- | --- |
| `R34V-v2.12.1-android.apk` | 正式标准包。API 24–27 用历史证书，API 28+ 用当前证书 |
| `R34V-v2.12.1-compat-2.0.0.apk` | 仅 Android 7/8 上已装 2.0.0 的用户使用，API 24–27 用当前证书 |
| `standard-api24-27.txt`、`standard-api28-plus.txt`、`compat-api24-27.txt` | `apksigner verify --print-certs` 原始输出 |
| `*.badging.txt` | `aapt2 dump badging` 包名与版本记录 |
| `SHA256SUMS.txt` | 文件完整性校验和（不等于证书指纹） |

文件 SHA-256：

```
ebfcc27a39dd50ebc800b608b9cf836a879543c38c2d087fb24e2663ca5fbeb8 *R34V-v2.12.1-android.apk
fa0ff11808c415ef5dbfe559b3ef498f61aa9fb999b07bbabdaf151418266be0 *R34V-v2.12.1-compat-2.0.0.apk
```

证书 SHA-256（核验结果，与 `release.properties` 一致）：

- 当前证书 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7` — 标准包 API 28+、兼容包 API 24–27
- 历史证书 `73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e` — 标准包 API 24–27

## 结档时的构建与验证

- `:app:validateReleaseSigning :app:assembleRelease` 通过，3m 8s，`signUpgradeRelease` 与 `verifyReleaseApks` 随 `assembleRelease` 执行；日志实际输出三行签名指纹核验，与上表一致。
- 本次未运行 JVM 单测、Lint 与模拟器回归；它们的最近一次完整结果属于 2.12.0 验证轮次。结档构建只重新执行了打包、签名与验签链路。
- 2.12.1 未上传 GitHub Release，也未推送远端；本地 tag `v2.12.1` 指向结档提交。

## 归档位置

- `D:\rule34-archives\` — 仓库外本地归档，不纳入 Git：
  - `r34v-before-kotlin-20261103-125559.zip`、`legacy-worktree-20261103/`：Kotlin 重写前的 Flutter/Tauri 源码与未提交修改
  - `raven-design-20261124/`：Raven 图标设计过程稿与生成脚本，从本仓库 `design/`、`tools/design/` 移出，含六组候选、圆角变体和 `raven_icon*.py`
- 签名材料不在归档内，仍由 `R34V_SIGNING_HOME` 指向 `C:\Users\ywclg\.r34v\signing`。修订老版本或重新签名必须复用该目录，禁止新建密钥。

## 后续

新项目为 Raven（`D:\Raven`，包名 `com.raven.app`），与本仓库无代码依赖：Raven 是 17 模块 Clean Architecture 独立工程，不继承 R34V 的包名、签名或数据。R34V 的站点解析器、下载队列和阅读器实现可作为阅读材料参考，不作为 Raven 的模块来源。

本仓库冻结后只接受两类改动：签名/构建链失效的修复（例如 CI 或 SDK 变更导致无法再产出可覆盖安装的包），以及安全或隐私问题的修复。
