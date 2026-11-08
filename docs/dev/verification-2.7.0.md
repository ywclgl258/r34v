# 2.7.0 发布验收

日期：2026-11-08。用户授权提交、推送和上传；正式版本 **2.7.0 / 47**，包名 `com.r34v.r34v`。

## 更新范围

- 接入 MissAV、Jable、rou.video、KBJ、KBJFree、Pornhub，来源总数增至 18。完善镜像回退、搜索链接、分页、系列入口、排序和页面脚本解析。
- HLS 使用原生下载器和按任务隔离的资源缓存，支持中断续传、存储迁移、数据库重建后离线恢复、缺失资源识别及独立删除；保留旧 MP4 下载身份。修复 rou.video 包装流的解码范围、重定向与普通响应流式读取。
- KBJ 的外部播放器与原生媒体流分离，通过浏览器打开原帖；KBJFree 仅公开预览片段。
- 个人首页增加媒体筛选，突出封面与继续操作；片库分区、搜索层级和片单排序后的定位改善。片单增加标题／来源检索、加入时间筛选和显示排序，合集备注、置顶、自选作品封面随加密备份恢复。
- 发布前按用户要求优化 Lint：窗口尺寸跟随应用窗口，减少阅读滚动与导航动画重组，完善画中画、备份、主题图标与组件 API；保留需要确认落盘结果的同步保存。警告含义和保留理由见 [Lint 优化](lint-optimization-2026-11-08.md)。

详细行为与开发阶段原始证据见 [新站适配](av-sites-adapters.md)、[UI 结构调整](ui-restructure-2026-11-08.md) 和 [片库增强](shelf-features-2026-11-08.md)。这些记录中的“未发布”和开发包版本描述对应各自开发阶段，本次正式交付以本文及 Release 为准。

## 验证

- 仓库 Wrapper 的 JVM、双变体 Lint、Debug 与 AndroidTest 构建成功。248 项 JVM，0 failures / errors / skipped。初始 Debug / Release Lint 为 97 / 95 warnings；优化后两者均为 **0 errors / 27 warnings**，未禁用检查。剩余 12 条依赖版本提示、12 条需读取同步保存结果的 KTX 建议、2 条保守空间检测及 1 条本机未跟踪空目录提示。
- 优化阶段最终 API 24 联合回归 14 项全部通过，覆盖下载缓存、普通／PNG 包装流范围读取、KBJ 浏览器入口和应用真实 DownloadService／PlayerActivity 的 HLS 离线恢复。媒体来自中性本地测试夹具，不依赖外站。
- UI 与片库在开发阶段完成 API 24／36、200% 字体、小屏和宽屏专项，准确测试数及失败后的复验分别记录于上述开发文档；本次没有将这些历史专项写成重新执行的全量验收。
- CI 默认回归加入 `ShelfOrganizationUiTest`、合集加密备份用例、`RouDataSourceTest`、`ExternalPlayerUiTest` 和 `HlsPlaybackIntegrationTest`；原有 `DownloadCacheTest` 包含新增 HLS 用例。
- 警告优化后 API 24 联合批次 18 项通过、1 项人工走查夹具跳过；API 36 的 13 项全部通过，含实际画中画进入／返回、窗口适配、图集阅读、会话、备份和 HLS。另完成 API 36 的 1280×800dp 宽屏、API 24 的 200% 字体各 1 项响应式回归。测试后恢复模拟器尺寸和字体。CI 追加响应式、画中画与时间戳链接专项。
- 时间戳新链接组件的 API 36 专项通过，实际点击确认只对时长内的位置跳转，超时长文本和普通文字不触发定位；改动后再次执行 248 项 JVM 与双变体 Lint 通过。

首轮完整 CI 的两个 API 均只失败在合集置顶测试：测试直接读取下一张“稍后看”卡片的节点，但短视口中 LazyColumn 尚未创建该节点。修正为先滚动到各张卡片，再结合列表滚动位置和卡片坐标验证先后顺序，保留备注、封面、置顶与取消断言。本地 360×640dp 原测试通过，更短的 320×480dp 暴露同类不可见节点等待问题；修正后该小屏专项和默认尺寸下的全部 3 项片库专项均通过。此后改动仅涉及测试与本文，正式代码和已经验签上传的 APK 未变化，完整双平台 CI 结果以补充提交的 Actions 为准；保留 [首轮失败记录](https://github.com/ywclgl258/r34v/actions/runs/42478061587)。

## 正式包与发布

仓库 Wrapper 的 `:app:validateReleaseSigning :app:assembleRelease` 成功，明确执行 `signUpgradeRelease`，沿用固定签名材料和既有继承链，未自行重新签名。首个候选标准包在 API 24 从 **2.6.0 / 46 覆盖安装到 2.7.0 / 47** 成功。警告优化后重新构建并验签的最终标准包，在 API 24 覆盖同版本候选包、在 API 36 从 **2.5.2 / 45 覆盖升级到 2.7.0 / 47** 均成功，两台模拟器冷启动 `MainActivity` 均返回 `Status: ok`。下表只记录最终交付 APK。

| 文件 | 类型 | 字节数 | 文件 SHA-256 |
| --- | --- | ---: | --- |
| `R34V-v2.7.0-android.apk` | 标准正式包 | 3460474 | `73ad75b3ba74c8c01df2200223fce69bb6697681c28659557b93896eb638f6c2` |
| `R34V-v2.7.0-compat-2.0.0.apk` | Android 7/8 已装迁移版 2.0.0 专用兼容包 | 3452282 | `d3729284df4c4ab455bb646ae205a5cc71a819fb7007494bbfe7993f00b0541f` |

SDK 36.0.0 `apksigner verify --verbose --print-certs` 分段核验均通过：

- 标准包 API 24–27：历史证书 SHA-256 `73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e`。
- 标准包 API 28+：当前证书 SHA-256 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。
- 兼容包 API 24–27：当前证书 SHA-256 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。

两包经 `aapt dump badging` 核对为 `com.r34v.r34v`、47／2.7.0。文件摘要仅用于完整性，不作为签名证书指纹。发布提供这两份正式 APK 和 `R34V-v2.7.0-SHA256SUMS.txt`，标准包先上传并使用 `-android.apk` 名称，以兼容旧更新器按首个 APK 选择更新的规则。

源码分支 `main`，发布标签 `v2.7.0`；[Release 入口](https://github.com/ywclgl258/r34v/releases/tag/v2.7.0)。上传前本地校验已完成，公开 Release 前核对远端附件摘要并检查对应提交的 CI。远端运行状态以 GitHub Actions 实际结果为准，原始日志和上传回执保存于 Git 忽略的 `dist/verification-2.7.0/`。

## 验证边界

本次未重验所有真实站点页面、各地区网络、账号权限、长期 CDN 稳定性、真机无障碍或所有历史版本的覆盖升级。KBJ 仍需浏览器，KBJFree 仍只有公开预览。正式包只使用既有固定签名材料和 `app/signing/upgrade.lineage`；开发包、签名材料、仓库外旧源码和私有运行证据不上传。
