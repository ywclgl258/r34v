# 2.5.1 本地交付核验

日期：2026-11-06。状态：本文记录发布前本地验收；随后按用户授权提交、推送并创建 Release。包名 `com.r34v.r34v`，版本 `2.5.1` / `44`，名称 `R34V`。

## 本轮范围

在已完成的 `2.5.0 / 43` 首批 16 项功能之上，只追加 Cosplaytele 混合文章播放与 E／Ex 图页恢复，没有重开 B 组加速或第二／三批功能。

### Cosplaytele 文章内视频

- `CossoraProtocol` 仅接受指定 cossora HTTPS 嵌入页及媒体域。原生实现公开 AES-CBC 参数解码，不执行整站脚本、不嵌入网页播放器，不新增依赖。
- 视频使用 `video-<UUID>` 和 `文章地址#video-<UUID>`，与图集作品 ID、图页、收藏及下载身份隔离。`GalleryDetail.videoEntries` 缺省按空列表读取，旧图集缓存仍可打开。
- 图集继续使用原阅读器。文章有实际嵌入时，`GalleryActivity` 显示独立“观看文章内视频”入口，复用 `PlayerActivity` 与 Media3 HLS；单嵌入不虚构播放列表，多嵌入按实际列表选择并只解析当前视频。
- 短效媒体令牌只在打开或刷新视频时取得，不作为永久地址保存。播放器对嵌入页、主清单、子清单及分片使用来源站 Referer，不向媒体 CDN 发送来源站会话 Cookie。

真实样本 `https://cosplaytele.com/lucky-bikini-nebris/`：61 张图片和 **1 个实际 cossora 嵌入**。标题中的“30 videos”没有被当成 30 个可播放条目。

端到端验证从图集详情读取图页，进入视频，返回原图集第 1 页后继续阅读。Media3 `STATE_READY`，**实际输出 28 帧，1080×1920，稳定播放状态成立**；设备截图可见真实画面、1920p、正在播放及 19:12 时长。证据为 `dist/verification-2.5.1/cosplaytele-report.txt` 与 `cosplaytele-video.png`。

### E-Hentai／ExHentai 图页恢复

对照 Ehviewer_CN_SXJ 的 `SpiderQueen` 复核了 `nl=<skipHathKey>`、真实图页 Referer、509 独立处理和最终响应地址检查，没有移植其应用代码。

- 实际故障链为 `SSLHandshakeException: connection closed <- java.io.EOFException: connection closed`，属于连接级中断，不是证书链或主机名安全错误。
- 仅在异常链明确为连接关闭、重置、超时或握手记录中断时，E／Ex 通过页面已有 `nl` 地址单次刷新并新建请求；外层保留既有有界重试。证书、PKIX、主机名及其他安全错误不刷新、不放宽验证。
- E／Ex 图片响应地址与请求地址不同时，拒绝该响应并按已有图页刷新策略处理，不直接接受异常跳转。
- 完成页复用已有文件；取消连接仍由协程取消，完整读取、格式校验和原子提交边界不变。没有关闭 TLS 校验、改用固定站点 IP 或绕过系统代理。

两个当前网络下的真实样本均完整下载并离线读取：

| 样本 | 完成页数 | 保存字节 | 结果 |
| --- | --- | --- | --- |
| `4308593` | 20／20 | 3,478,674 | 完成、完整性检查及离线解码通过 |
| `4308674` | 23／23 | 8,560,346 | 完成、完整性检查及离线解码通过 |

报告为 `dist/live-ehentai-4308593-2.5.1.json` 与 `dist/live-ehentai-4308674-2.5.1.json`。这证明当前系统代理网络下的两个样本，不承诺其他运营商或所有作品都可用。

## 自动化与界面

- JVM：**182 项通过，0 失败、0 跳过**。包含 HLS 参数解码、媒体域限制、视频／图集身份、旧缓存空视频列表、TLS 连接关闭与证书错误分类。
- API 24 / Android 7：原有 31 项核心回归加 E-Hentai 7 项，合计 **38 项通过**。报告 `dist/verification-2.5.1/api24-core.xml`。
- API 36 / Android 16：全部 31 个 Android 测试类分批执行，合计 **144 项通过，0 失败、0 跳过**。覆盖真实 Compose 操作、阅读、下载、取消删除、搜索、播放偏好、画中画、离线和存储。报告 `api36-batch1.xml` 至 `api36-batch5.xml`。
- E-Hentai 可控测试同时覆盖断连刷新恢复、证书失败不刷新且不写图页、完成页不重下、异常跳转刷新、会话隔离与离线缓存。
- Debug Lint：**0 errors / 94 warnings**；Release Lint：**0 errors / 91 warnings**。存量警告没有被称为已清零。
- 设备中断期间的失败组按原类或原组重跑，最终上述各组均通过；不把设备未启动、测试包被卸载或单次环境中断写成应用通过。

## 正式包与签名

通过仓库 Wrapper 和固定签名材料执行：

```bash
R34V_SIGNING_HOME='C:/Users/ywclg/.r34v/signing' ./gradlew.bat :app:validateReleaseSigning :app:assembleRelease
```

`signUpgradeRelease` 正常执行并保留 `app/signing/upgrade.lineage`，没有另建密钥或事后改用其他签名。

| 文件 | 用途 | 文件 SHA-256 |
| --- | --- | --- |
| `dist/R34V-v2.5.1.apk` | 常规标准正式包，3,357,850 字节 | `4621f75582913bddf0ce69f0e35cdc3698afaa6a4b60acc23f46cd993dd11917` |
| `dist/R34V-v2.5.1-compat-2.0.0.apk` | 仅供 Android 7/8 已装迁移版 2.0.0 的用户，3,353,754 字节 | `9944ec385e5ef8b9ac9b4bf7d4da0233f815b32d59170839f5536b4a654255ab` |

`apksigner verify --verbose --print-certs` 按平台分段执行，结果均正常退出：

- 标准包 API 24–27：历史证书 `73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e`。
- 标准包 API 28–36：当前证书 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。
- 兼容包 API 24–27：当前证书 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。

两包 `aapt2 dump badging` 均核对为 `com.r34v.r34v`、版本 44／2.5.1、应用名 R34V。标准包已在 API 36 从已安装的 **43 → 44 覆盖安装并启动成功**。签名输出与包信息保存在 `dist/verification-2.5.1/signatures.txt` 和 `badging-*.txt`。文件 SHA-256 仅用于文件完整性，不当作证书指纹。

## 未验证与后续边界

- ExHentai 缺少授权账号，未做完整联网下载验收。
- 本轮实站追加范围为 Cosplaytele 与两个 E-Hentai 样本；没有重新宣称全部 12 站或用户手机原始网络都已通过。其他站点通过项沿用 2.5.0 报告，不能替代用户手机复验。
- 多嵌入由实际列表和独立 ID 支持；本次真实样本只有一个嵌入，未声称覆盖所有 Cosplaytele 文章结构。
- 旧 2.0.0 兼容包完成静态分段验签；本轮未重复执行原 2.0.0 设备的覆盖安装。
- B 组官方文件、并发与分段实验以及第二／三批账号、社区和本地互通不在本轮范围。
- 随后按用户授权提交、推送并创建 Release。生产包不包含 AndroidTest 适配器、实站探针或中性测试素材。
