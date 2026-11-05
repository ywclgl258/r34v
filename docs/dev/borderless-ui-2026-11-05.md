# 2.2.6 / 40 无描边界面调整

用户指定 `ui-ux-pro-max`，要求改善大量线框按钮显旧的问题。范围限定在共享视觉控件及其页面容器；保留黑底、Netflix 红、数据、网络、播放和下载行为，以及此前未提交的功能优化。

技能检索：`dark borderless controls mobile --domain style` 命中 Dark Mode (OLED)、Material 3 Expressive (Mobile)，采用黑底、灰阶填充和轻量状态反馈，不采用不适合现有品牌的紫色或霓虹配色。Compose 精确按钮查询无结果，重试 `accessibility semantics --stack jetpack-compose` 命中原生语义指南；数据集的版本描述不作为升级依据。原生 Button / FilledTonalButton 用法参照 [Android 官方组件文档](https://developer.android.com/develop/ui/compose/components/button)。未安装新包或更改依赖。

## 实施

- 主操作为红色实心按钮，Hero 播放为白色实心按钮，次操作为中性深灰；去掉描边、厚阴影及按压缩放，使用原生 ripple 和明显的焦点填充。
- 筛选胶囊去掉线框，选中态为浅色填充与勾号，保留选择语义。触控区至少 48dp，大字体下允许增高。
- 选择器使用 FilledTonalButton；选择面板移除空心单选圆圈，改为整行选中底与右侧勾号。
- 搜索、登录和编辑弹窗改为填充输入框，正常状态无四边框或底线，错误提示仍明确可见。
- 卡片、悬浮导航、播放浮层、折叠面板与设置行移除装饰描边；Hero 和播放器详情取消重复包裹按钮组的外层容器。
- 设置开关由整行提供原生 Switch 语义；未选中开关使用实心轨道，消除重复触控节点。
- 选择面板直接展开到完整内容高度，跳过会把底部操作留在屏外的半展开状态；选项区在可用高度内滚动，标题与取消操作保持可见；横屏下可滚动选取 2.0 倍速。测试明确滚动到详情控件，并等待收藏提示结束后验证取消收藏。

## 验收

基线：375dp 手机上的 CinemaUiTest / BrowseUiTest 4 项通过，原始界面截图在 `dist/ui-borderless/before/`。上一轮功能基线为 134 项 JVM、Android 100 通过 / 14 实站探针跳过。

本轮完成共享控件和各主要页面的真实原生截图、交互与可用空间检查，最终结果如下。此前功能优化和未提交修改全部保留；没有新增依赖、修改正式包名、换密钥或改签名继承链。

文字对比度按实际实色计算：红底白字 4.79:1、灰色控件白字 14.16:1、焦点填充白字 5.57:1、次文字与 #202020 表面 8.21:1、浅色选中底黑字 18.76:1。白色 Hero 按钮取得焦点时同步切换白字，避免深灰焦点底保留黑字。

## 最终验证

| 配置（Android 16 模拟器） | 用例 | 结果 |
| --- | --- | --- |
| 411dp 手机、正常字体、关闭动画 | Cinema / Browse | 4 通过 |
| 375dp 手机、200% 字体、关闭动画 | Cinema / Browse / Gallery / Responsive | 8 通过 |
| 812dp 横屏、正常字体、关闭动画 | Cinema / Responsive / 播放器 | 4 通过 |
| 1067dp 平板、正常字体、关闭动画 | Cinema / Responsive | 3 通过 |

以上为 19 次通过执行、8 个独立用例，不把重复配置计为独立测试。另在恢复动画后经 adb 运行现有播放器用例 1 次通过。早期失败批次不计入最终通过数；初始 XML 与最终 XML 分开保留。覆盖导航、收藏及取消、搜索/设置、语言筛选、阅读/全屏、播放清晰度/倍速/重建、当前选中项语义与取消操作可见性。上一轮 134 项 JVM 和全量 Android 结果属于功能优化基线，本轮没有重复宣称为新执行结果。

部分关闭动画的自动化截图没有包含面板，未将这些帧作为面板外观验收；另在仪器测试外通过实际原生触控打开设置选择器，在正常字体和 200% 字体下关闭全部动画，确认选中填充、勾号和取消按钮均真实显示。截图在 `dist/ui-borderless/verification/native-no-animation-quality.png` 和 `native-no-animation-200-quality.png`。常规页面、200% 字体、横屏和平板的截图位于 `dist/ui-borderless/` 对应目录；核心交互使用中性测试素材，不进入 Release APK。测试后原始分辨率、密度、字体、旋转和动画设置逐项恢复，并关闭本轮启动的模拟器。

生产源码扫描未发现 `Outlined`、`BorderStroke` 或 `.border(`。Debug Lint 为 0 errors / 70 warnings，Release Lint 为 0 errors / 67 warnings；没有将既有警告描述为全绿。正式构建依次通过 `validateReleaseSigning`、`signUpgradeRelease` 和 `assembleRelease`，使用根目录指定命令及固定仓库外签名配置。Git 空白和范围检查通过；范围检查包含此前功能优化共 40 个文件，不能当成本轮 UI 单独改动数。

## 本地交付

版本 **2.2.6 / versionCode 40**，包名 `com.r34v.r34v`，minSdk 24、targetSdk 36。

| 包类型 | 文件 | SDK 验证范围 | 证书 SHA-256 | 结果 |
| --- | --- | --- | --- | --- |
| 正式标准包 | `dist/R34V-v2.2.6.apk` | API 24–27 | `73543870ea2dd9ead7888b743a3b6b4ccecc82a3e361216a7ded714ca3cdbc4e` | 通过，历史证书 |
| 同一正式标准包 | 同上 | API 28–36 | `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7` | 通过，当前证书与继承链 |
| Android 7/8 上已装 2.0.0 专用兼容包 | `dist/R34V-v2.2.6-compat-2.0.0.apk` | API 24–27 | `75f3270c0914286315881f8f1a3d7b9a0c7e26d9f6a2e86354d91f924907c5e7` | 通过 |

最终 dist 文件使用 SDK 36.0.0 的 `apksigner verify --verbose --print-certs` 核验，并由 `aapt2 dump badging` 核对包名、版本和 SDK 配置。原始输出、Lint XML、最终测试 XML、对比度与机器可读交付摘要在 `dist/ui-borderless/verification/`。开发测试 APK 未作为正式更新交付。旧版 2.2.5 两份 APK 保留。

`dist/R34V-v2.2.6-SHA256SUMS.txt` 只用于文件完整性，不用于判断签名身份：

- `R34V-v2.2.6.apk`：`760b5a0c4af1adcabe9ebd6ee4f9af6f1b782d6c6a67b1e69053e7f04f931711`

- `R34V-v2.2.6-compat-2.0.0.apk`：`2665b92cca7feafc7b9893592ac150ee3729c56b8b3146cf56fc3f7e01a33112`

未验证 Android 7/8 实际运行、真实账号登录或在线更新安装；分段签名已静态核验。本记录完成时尚未提交、推送或发布；用户随后明确授权这些操作。本次发布入口为 [v2.2.6](https://github.com/ywclgl258/r34v/releases/tag/v2.2.6)。
