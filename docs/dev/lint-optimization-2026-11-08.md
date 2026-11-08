# 发布前 Lint 优化

用户在授权提交、推送与上传后要求检查并优化警告。本阶段在同一 2.7.0 / 47 发布候选中处理，之前已验签但未上传的 APK 需要重新构建，不能沿用其文件摘要。

## 警告含义与处理

初始 Debug 为 0 errors / 97 warnings，Release 为 0 errors / 95 warnings。警告数量不等于崩溃数量，其中既有实际适配和性能建议，也有代码风格与依赖更新通知。

| 类别 | 初始 Debug 数量 | 处理 |
| --- | ---: | --- |
| 窗口尺寸读取 | 30 | 统一从 `LocalWindowInfo.containerSize` 按当前密度换算，沿用原有断点和布局，响应应用窗口变化；子布局继续使用实际约束。 |
| Kotlin 扩展 API | 41 | 普通异步设置改用 KTX，URL 和缩略图缩放采用对应扩展；需要读取 `commit()` 成败结果的 12 处保留。 |
| 依赖版本 | 12 | 保留已验证的固定依赖组合。这里只是存在新版本的提示；没有据此认定有漏洞或把跨版本升级混入本次局部修复，也没有隐藏检查。 |
| Compose 参数与重组 | 6 | Modifier 调整为首个可选参数并修正调用；滚动索引在 `snapshotFlow` 中收集，导航指示器使用布局阶段偏移。 |
| Android 配置与分支 | 4 | 补齐文件访问设置页面的包可见性声明、显式备份排除规则、画中画过渡矩形和手动进入策略，以及播放器 IDLE 分支。 |
| 磁盘空间 | 2 | 保留保守的物理可用空间检测，避免把尚未回收的缓存算成当前可写空间。Lint 推荐的可分配空间统计需要配套空间回收流程，不能仅替换数值。 |
| 本机旧目录 | 1 | `drawable-v21` 是空的未跟踪目录；清理操作被自动审批以策略原因阻止，因此保留。它没有资源进入 APK，也不会被 Git 提交。 |
| Release 资源提示 | Debug 未报告 | 开发预览专用图标移至 `src/debug/res`；自适应启动图标增加 Android 主题单色图层。 |

保留同步提交的原因：下载索引、迁移标记、阅读进度、会话、片单进度和存储位置均使用布尔返回值确认落盘是否成功。KTX `edit(commit = true)` 返回 Unit，不能替换这些校验；直接改成 `apply()` 还会丢失同步失败反馈。备份失败后的回滚保持同步执行。

本阶段不新增 `lint.disable`、`lint.xml` 忽略项或 `SuppressLint`。变更边界为现有警告涉及的窗口／Compose、Android 配置、KTX 调用与图标资源；固定依赖、正式包名、证书和升级链保持不变。

窗口 API 参考 [Android WindowInfo](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/WindowInfo)；画中画过渡区域参考 [Android PiP](https://developer.android.com/develop/ui/compose/system/pip-enter)。系统备份规则按 [Android 12 备份行为](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore) 显式区分云备份与设备迁移，应用仍使用用户主动发起的加密导出。

## 验证记录

最终双变体 Lint 为 **0 errors / 27 warnings**，Debug 减少 70 条，Release 减少 68 条。还修正了独立于 Lint 计数的 Kotlin 编译提示：状态保存覆写参数名称与父类一致，时间戳从废弃 `ClickableText` 迁移到 `Text` / `LinkAnnotation`，保留只对时长范围内的时间戳跳转；开发预览改用 `enableEdgeToEdge` 设置系统栏，并明确资源注解的作用目标。

源码改动后 JVM、双变体 Lint、Debug 与 AndroidTest 构建通过；首次编译发现的调用参数与可空 URI 问题已修正，原始失败日志保留。API 24 联合回归通过，人工走查夹具跳过，未记为通过用例。API 36 的 13 项联合专项、宽屏与 API 24 大字体专项通过。时间戳组件专项实际点击有效时间戳、超时长时间戳和普通文字，确认只有有效位置触发跳转；该专项通过并加入 CI。完整结果见 [2.7.0 发布验收](verification-2.7.0.md)。

本机日志保存在 Git 忽略的 `.workflow-lite/warnings-2026-11-08/`，变更前源码快照保存于仓库外临时目录。
