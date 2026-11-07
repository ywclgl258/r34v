# 2.5.2 本地交付核验

日期：2026-11-07。状态：本地实现、构建及设备验证完成；随后按用户授权提交、推送并创建 Release。正式包名 `com.r34v.r34v`，版本 `2.5.2` / `45`。

## 范围

- 多站搜索在未保存来源选择时默认全部支持搜索的来源，保留已有自定义子集并提供“全选”。片库与订阅更新页恢复明确的“标签订阅”入口，原分站订阅、排序、管理和收件箱不变。
- 宽屏使用紧凑密度：页面标题 20sp、分组 16sp、共享正文 13sp、可见按钮约 40dp、常规图标 18dp，实际触控边界至少 48dp。手机与系统大字体不套用缩小规则。
- 图库按宽度使用四至六列，跨站搜索与订阅结果四列，片库片单双列；Hero、续读卡、导航、下载和详情留白同步收紧。保留黑底、Netflix 红、白色主动作与 Liquid 材质。
- `GalleryActivity` 在图集已加载、首图尚未解码时也保存当前页码与偏移，取得真实图页地址后继续更新；不为加载失败的记录制造进度。
- 测试同步修正：等待系统返回完成、通过窗口令牌收起键盘、在对应懒加载容器内露出并点击实际按钮。自动幻灯片验证离开首页，而非依赖恰好停在第 2 页。

## 自动化与真实设备

- JVM：**182 项通过，0 failures、0 errors、0 skipped**。
- API 24：全量批次记录 169 个测试条目，20 个平台／实站条件跳过，5 个操作路径失败已逐项定位和修正。失败测试类最终执行 26 项，其中 2 个平台条件跳过，其余全部通过。保留原始失败记录，以最终通过结果覆盖对应路径，不宣称最初的单次批次全绿。
- API 36：全部非实站测试共 150 项。首轮 149 项通过，只有“首图未解码时退出”因读取早于实际保存失败；补充实际进度等待并保留生产兜底后，`OptimizationIoTest` 3 项全部通过，全部 150 个路径均有最终通过结果。
- 画中画在硬件加速模拟器中验证进入、播放保持、返回及同一播放引擎复用。软件渲染环境在画中画处退出，改用硬件加速后通过，未修改播放器逻辑或跳过该项。
- 备份导出、错误密码拒绝、正确密码预览与合并恢复均通过。曾出现的测试进程崩溃经系统日志确认为测试期间重装 APK 触发 `installPackageLI`，后续不在设备测试期间重装应用。
- 1280dp、320dp、840×360dp 与系统 200% 字体均有真实页面截图和相应操作验证。独立视觉评审提出的漫画溢出按钮问题已修正；独立 UX 的无效果廊记录及重复错误页另列后续导航优化，未据此宣称真实站点全部可用。

原始报告保存在 `dist/verification-2.5.2/`，过程日志与截图位于本地 `.oil-ui/release-2.5.2/` 和 `.oil-ui/large-screen-density/`。早期中断或失败批次不冒充通过；最终通过项以对应类或补跑记录为准。

## 构建与 Lint

- `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:lintRelease` 均通过。
- Debug Lint：**0 errors / 96 warnings**；Release Lint：**0 errors / 93 warnings**。包含项目已有告警和 `Configuration` 尺寸 API 提示，未把警告数写成清零。
- `validateReleaseSigning` 通过，随后 `assembleRelease` 自动执行既有 `signUpgradeRelease`，保留 `app/signing/upgrade.lineage`。没有创建新密钥、改变包名或事后改用其他签名。

## 正式 APK 与签名

| 文件 | 用途 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| `dist/R34V-v2.5.2.apk` | 常规标准正式包 | 3,357,850 bytes | `385ce6741ed461d0207ded3827c0d2d90cd9c9cb519dd38b0ab63d64722aec94` |
| `dist/R34V-v2.5.2-compat-2.0.0.apk` | 仅供 Android 7/8 已装迁移版 2.0.0 的用户 | 3,353,754 bytes | `f2fdb57843fc1ca6620def9cdda2e8c2fa0002c6cb9aad1b6a1d68972da3fd92` |

`apksigner verify --verbose --print-certs` 按平台分段验证均正常退出：

- 标准包 API 24–27：历史证书 `73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e`。
- 标准包 API 28–36：当前证书 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。
- 兼容包 API 24–27：当前证书 `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7`。

两包 `aapt2 dump badging` 均核对为 `com.r34v.r34v`、45／2.5.2、应用名 R34V。API 36 上从已安装的 **2.5.1／44 覆盖升级到 2.5.2／45**，冷启动 `MainActivity` 返回 `Status: ok`。证据为 `signatures.txt`、`badging-*.txt`、`upgrade-start.txt` 和 `upgrade-package.txt`。文件 SHA-256 只用于文件完整性，不当作证书指纹。

## 边界

- ExHentai 缺少授权账号，未做新的完整联网验收。没有重新宣称全部站点或所有网络均通过；此前 Cosplaytele／E-Hentai 的实站证据不替代本轮 UI 回归。
- 旧 2.0.0 兼容包完成静态分段验签，本轮未重复原 2.0.0 设备的运行时覆盖安装。
- 无效果廊记录的重复错误页仍属于后续导航优化范围；正常阅读、未解码时退出与进度保存按上述实际路径验证。
- 生产包不包含 AndroidTest 适配器、中性测试素材或实站探针。

## 远端提交、CI 与 Release

- 正式源码提交：`c4d1bf345efcdef087ad831451a680834e87f0b2`；标签：`v2.5.2`。
- [CI 运行 42347678285](https://github.com/ywclgl258/r34v/actions/runs/42347678285)：verify、API 24、API 36 全部通过，两个设备各 54 项测试，日志保存在 `dist/verification-2.5.2/ci-*.log`。
- CI 日志明确提示缺少固定签名材料，工作流因此跳过签名构建及签名产物上传，没有生成替代密钥。正式包由本机既有固定证书构建，按平台分段验签后上传。
- [R34V v2.5.2 Release](https://github.com/ywclgl258/r34v/releases/tag/v2.5.2) 已创建且 `isDraft=false`。标准包、Android 7/8 兼容包及 `R34V-v2.5.2-SHA256SUMS.txt` 均已上传。
- 远端 API 资产名称、大小及 SHA-256 与本机一致；重新下载两个 APK 后，`sha256sum -c` 均返回 OK。远端记录保存在 `dist/verification-2.5.2/remote/`。
