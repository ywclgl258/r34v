# Jetpack Compose 全面迁移

用户已明确要求使用Jetpack Compose全面重构。基线为6a2017c / 2.1.7，工作区干净；沿用该版本86项JVM、7项相关Android测试和签名安装验证记录。UI层整体迁移获得本轮直接授权，按页面分阶段验证；数据、站点协议、存储格式、签名密钥与继承链不改。

## 范围与验收合同

首页、发现、搜索、列表/索引、片库、设置、站点抽屉及教程、视频详情与播放控件、漫画详情/阅读、官方登录外壳、更新对话框迁移到Compose。仅视频Surface和官方认证WebView保留平台视图互操作；不把原有页面整体包进AndroidView。保留分站数据、语言筛选、连续分页、预读窗口、全屏和进度恢复、播放手势、下载/离线、权限门禁及更新安装校验。

按界面基础/浏览、视频、漫画、登录更新、测试五个边界实施，每阶段先编译再进入设备验证。预期改动限Gradle Compose依赖、UI源码/Activity、对应UI测试、文档与版本；约3500行UI实现预算，测试按行为重写。计入删除旧实现与替换测试的总差异预算为40文件/8000行。业务协议、会话验证及私有签名材料不在重构范围。旧View组件只有确认无生产与测试引用后才删除。出现数据格式变化或签名不匹配时停止相应构建，未通过页面回退至基线实现定位问题，不能通过移除功能交付。

## 采用依据

- [AGP9.0.1说明](https://developer.android.com/build/releases/agp-9-0-0-release-notes)：现有AGP内置Kotlin2.2.10，Compose编译器插件固定同版本，不加入重复的Kotlin Android插件。
- [Compose编译器配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)：启用Compose构建与独立编译器插件；BOM固定2025.08.01，与现有Android工具链协同验证，不扩大到全量依赖升级。
- [平台View互操作](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)：仅用于缺少Compose原生实现的平台播放器/官方WebView。
- ui-ux-pro-max的jetpack-compose查询命中Lifecycle、Accessibility、Semantics：页面状态按生命周期管理，交互提供语义、48dp触控与系统字体适配，采用现有黑底内容优先方向。

A0路由回执：code-quality-workflow；快照2026-08-18；已读取ST-A0、沟通与宿主合同及前端质量规则；无设计候选待选。当前宿主为用户本地Windows/PowerShell；直接在D:/rule34工作，源码和设备验证不以文档检查替代。不新增审批：用户本轮已经明确授权全面迁移。

## 实现结果

| 边界 | Compose实现 | 保留的平台能力 |
| --- | --- | --- |
| 导航、站点及首次教程 | Scaffold、ModalNavigationDrawer、DropdownMenu、BackHandler | Activity结果与系统返回 |
| 首页、搜索、索引、片库、设置 | LazyVerticalStaggeredGrid、HorizontalPager、OutlinedTextField、Material3控件 | 既有Repository、分站SharedPreferences、下载服务 |
| 视频详情与控制 | Compose按钮、菜单、时间轴、错误/加载状态、可展开标签和评论 | AndroidView中的Media3 PlayerView，仅画面，useController=false |
| 漫画详情和条漫 | LazyColumn、pointerInput缩放/滚动/单击、Compose进度与跳页 | GalleryRepository与原有磁盘缓存 |
| 官方登录 | Compose标题、说明、状态和操作 | 受限官方WebView，FLAG_SECURE、TLS和域名限制保留 |
| 更新 | Compose对话框与下载进度 | 原有版本/包名/签名核验、FileProvider和系统安装器 |

旧CinemaLoadingView、ComicStripView、ZoomPageView、VideoCarousel、FlowLayout、GalleryLanguageBar、SiteSwitcher、SiteSwitchTutorial及View构建工具已删除，生产源码仅有上述两处AndroidView。移除RecyclerView与DrawerLayout直接依赖，保留黑底、白色主按钮/选中筛选、红色开关和进度。

状态随Compose页面离开取消相应请求。首次教程沿用2.1.7的一次性全局标记；页面、搜索、返回栈、播放全屏和阅读页内偏移保留恢复能力。条漫由ReaderBuffer管理可见页及后续三页预读：最多两个并发，跳页取消旧范围，额外屏外解码预算24MiB，预读不推进历史，图页失败可原位重试。双击与双指均经过真实输入验证，已修复放大状态下触摸监听吞掉第二次双击复位的问题。

正式包检查还发现首次数据回来后动态插入轮播会被Lazy列表的滚动锚点跳过。现先展示加载/错误状态，完整第一页准备好后才创建Lazy列表，使轮播从首屏开始；增加首屏轮播可见高度断言。修复后12项相关UI回归及375dp/200%字体下首屏、教程两项复测通过。

唯一的存储接口调整是让观看历史读取可传所属站点，写入按Watch.site保存；旧JSON格式、迁移与站点键不变。用于避免播放页生命周期回调在后台切站后把观看进度写入另一站，并增加Android回归覆盖。所有会话导入权限校验与缓存/下载协议沿用原实现。

## 验证及边界

按Android测试XML核对：Android16常规整批共48项，34项通过、14项需显式开启的实站探针跳过；另新增响应式用例在1067dp平板及812dp横屏通过，合计35项独立Android用例分批通过，重复运行不增加数量。Gradle控制台将跳过项重复计入完成数，不能使用其“Finished62”作为通过数量。375dp / 系统200%字体下，浏览/设置、播放清晰度倍速/全屏、语言筛选、预读缩放/离线恢复、局部失败与EX门禁及首次教程六项流程通过。双指缩放、双击复位、跳页、关闭菜单增加阅读空间、重建离线恢复均有真实输入与中性媒体证据。

测试使用独立线程池与连接池的中性素材夹具，覆盖实际Repository/磁盘缓存/Media3解码/下载服务；外站协议、会话和网络原有测试保留。截图和分批XML在仓库忽略的 `dist/compose-2.2.0/`。UI测试不再通过递归遍历TextView/Button查找控件，改用Compose语义；播放器引擎状态与下载结果仍独立断言。

最终86项JVM测试通过，Debug/Release Lint均无错误（47/48项警告），仓库Gradle签名构建成功。标准与Android7/8已装2.0.0专用兼容APK的固定证书和继承链经独立apksigner验证；aapt确认两包均为com.r34v.r34v / 2.2.0 / 34。最终压缩签名标准包实际覆盖安装成功，首屏轮播可见、已有用户不重复教程、按UI树触摸真实站点行并等待选择生效、结束进程再启动保留站点均通过。详细结果见 [HANDOVER.md](HANDOVER.md) 与本地 `dist/compose-2.2.0/VERIFICATION.md`。

Android7/8运行、真实EX账号与各实站线上播放本轮未重跑，不能以中性夹具替代这些验收；站点协议层未做改写。本轮仅本地交付，不提交、推送或发布。

收尾范围卡：可见问题为首页轮播被初始滚动锚点跳过；授权依据为本轮全面Compose迁移；修复仅限ComposeBrowse与BrowseUiTest；退出条件为普通/200%字体首屏可见以及最终签名包复核。其他设计想法不扩大本轮验收。
