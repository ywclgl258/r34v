# 大屏字体与控件密度验证

日期：2026-11-07。范围仅限界面密度、响应式布局及相应测试，不改变搜索、订阅、播放、阅读、下载或备份业务逻辑。正式版本保持 2.5.1 / 44；本轮未提交、推送或发布。

## 实现

- `LocalUiDensity` 在视口宽度至少 840dp 且系统字体不超过 130% 时启用紧凑密度：页面标题 20sp、分组标题 16sp、共享正文 13sp、默认正文 14sp；可见按钮以 40dp 为基准、常规图标 18dp。
- 缩小的是可见控件、字形与留白，不是整个屏幕的缩放。按钮与漫画溢出图标通过 `minimumInteractiveComponentSize` 保留至少 48dp 的实际触控边界。
- 图库按 840／1024／1280dp 使用四／五／六列；跨站搜索和订阅在紧凑密度下使用四列；片库片单双列。漫画宽屏续读改为横向卡，视频／写真续播缩短高度，片库详情和下载卡片收紧内部尺寸。
- 保留黑底、Netflix 珊瑚红、白色主动作、现有 Liquid 导航、真实封面比例及既有动效。手机、短横屏滚动和系统大字体不套用缩小规则。

规范更新见 `docs/ui.md` 与 `docs/architecture.md`。设计事实、前后截图和独立评审原文结论保存在本地 `.oil-ui/large-screen-density/`。

## 真实设备

模拟器 API 36，使用项目现有 `ComposeFixture`、真实页面和 `f.shot`，未以静态设计稿或编译结果代替视觉验收。

| 视口 / 场景 | 结果 |
| --- | --- |
| 1280×720dp、100% 字体：`NextResponsiveUiTest`、`ResponsiveComposeUiTest`、`LiquidMediaUiTest` | 最终 4 项通过；包含片单选择／重建、六列图库、阅读与媒体入口、按钮和溢出图标触控边界 |
| 同上：`CinemaUiTest` | 2 项通过，含首页轮播、片架、搜索和发现导航 |
| 同上：跨站搜索、全部来源、标签订阅入口、订阅收件箱、片单 | 最终 6 项通过 |
| 840×360dp：`NextResponsiveUiTest` | 1 项通过；先上滑露出条目，点击实际条目行，再在详情容器内上滑，确认“打开播放器”真实可见，随后验证重建与筛选 |
| 320×640dp：`BrowseUiTest` 与 `NextResponsiveUiTest` | 2 项通过；手机保持原字号和控件尺寸 |
| 1280×720dp、200% 字体：`NextResponsiveUiTest`、`LiquidMediaUiTest.sourceStagesKeepAllThreeMediaAndNavigationReachable` | 2 项通过；字体正常放大，详情可滚动到主动作 |

此前 `NextResponsiveUiTest` 直接点击视口外条目或依赖嵌套滚动自动定位，截图证明该操作不等同于用户手势。测试已改为实际条目行与详情容器手势，保留主动作真实可见断言；没有删除断言来掩盖问题。

## 构建与静态检查

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过。
- `:app:testDebugUnitTest`：182 项，0 failures、0 errors、0 skipped。
- `:app:lintDebug`：通过，0 errors、96 warnings（含项目已有告警及现有 `Configuration` 尺寸 API 提示），未以忽略错误方式通过。
- `git diff --check` 通过；`release.properties` 未修改。

## 独立评审

独立视觉评审由 `explore` 子 Agent `agent-2` 完成，只读真实前后截图与界面规范，不打分。认可首页、漫画、片库和合集详情的信息密度改进，未发现主操作阻断。唯一必需收尾项是漫画卡片溢出按钮仍偏大；已把可见圆形底缩至 40dp、保留 48dp 触控边界，重新截图与测试后关闭。详见 `.oil-ui/large-screen-density/review.md`。

独立 UX 走查由 `coder` 子 Agent `agent-3` 通过真实开发版入口操作。其自主选择的续读路径被“无法识别画廊地址”阻断，未进入正文或完成翻页，不能宣称该路径走通。已确认首页记录来自先前测试夹具的合成地址，不是有效 E-Hentai 作品，因此不据此判断实站故障；中性媒体的真实页面测试已覆盖阅读、翻页和返回。

独立执行者另在两轮操作中复现“第一次返回同内容错误页、第二次才回首页”。这是现有阅读导航问题，与本轮密度修改无关，未混入本次 UI 改动修复。结果、测试数据分类和主 Agent 复验见 `.oil-ui/large-screen-density/ux.md`，原始步骤与截图见 `.oil-ui/large-screen-density/ux-agent/`。

## 保留边界

- 此前阅读器缓存／进度竞态未修改：`OptimizationIoTest.scrollingCoalescesHistoryWritesAndBackFlushesTheFinalPosition` 曾间歇失败，最后 64 项为 63 通过、1 间歇失败；本轮没有重跑或宣称解决全量设备测试。
- 备份专项在整组执行中出现间歇等待超时：一次等待错误提示，另一次等待正确密码后的恢复预览；单独执行完整备份路径曾通过。错误提示与预览等待的原因尚未定位，不把相关整组结果写成全绿。错误密码的实际可见提示见 `shots/after/backup-wrong-password.png`。
- 一个整组批次被模拟器退出中断，未计入通过结果；最终统计采用上表已完整结束的批次。本轮未重新运行 API 24 设备或正式 Release 构建。
