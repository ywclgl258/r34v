---
handoff_schema: tavernweave/next/v1
project_id: r34v-android
status: implementation-candidate
updated: 2026-11-02
---

# NEXT · R34V · rule34video 安卓客户端

## 当前权威

- 总设计案：`总设计案.md`
- 活动蓝图：`项目实现步骤蓝图.md`
- 执行期持久权威蓝图预算：`0`
- 临时问题支线：仅真实问题触发，最多一层/同时一条，关闭后回归父步骤

## 已确认事实

1. 站点纯 HTML，无 JSON API；列表/详情全部要解析 DOM。
2. 详情页 URL 必须带 slug（`/video/<id>/<slug>/`），裸 id 404。
3. 直链是 mp4 + `v-acctoken`，支持 `Range`（实测 206），可流式播放与拖进度。
4. 清晰度 360/480/720/1080 四档，URL 在详情页 `flashvars` 里。
5. 缩略图 `336x189/*.jpg` 实为 WebP，`320x180/*.jpg` 才是真 JPEG；无防盗链。
6. 分页为路径式，每页 23–24 条；`?sort_by=` 五种排序全页通用。
7. 登录是普通表单 POST，字段与 CSRF 隐藏项已记录；未登录访问 `/my/` 无内容。
8. 站点前置 `ddos-guard`（Cookie 下发），当前不拦纯 HTTP 客户端。
9. APK 由 GitHub Actions 产出；本机当前缺 `JAVA_HOME` 与 Java，本地 APK 构建未验证。
10. 工程已从 Tauri 脚手架迁到 Flutter（3.44.6 / Dart 3.12.2），当前版本 `1.16.0+21`。

## 最近证据

### 第七轮：全面审查后的可靠性修复

- 缓存改为显式流式写入：206 正确续传，服务器忽略 Range 回 200 时覆盖旧 `.part`；取消、短响应和网络错误均保留续传现场，只有完整文件才入库。
- 合并站点与调用方 Cookie，同名会话以调用方登录 Cookie 优先；网络设置保存后立即重建相关客户端。
- 播放器严格尊重 ExoPlayer / libmpv 选择；下载前刷新时效链接；更新下载取消恢复可下载状态；搜索忽略过期响应。
- 屏蔽标签入口接入设置；排行榜图片统一走应用网络层；关闭毛玻璃后底栏不再执行 `BackdropFilter`。
- 移除未使用的图片与图标依赖，修正文档、CI 注释和自动缓存说明。

验证：`flutter analyze` 零问题；`flutter test --reporter expanded` 为 **428 项通过、14 项跳过**（跳过项需显式联网开启）。本机无可用 Android 设备，APK 构建和真机播放仍未在本轮验收。

### 第六轮：本地缓存

驾驶员反馈「看过的、收藏的视频可以快速观看，不用再缓存」。实现为**自动缓存**而非手动下载：

| 项 | 实现 |
|---|---|
| 触发 | 收藏的视频必缓存；看过且时长 ≥60s 也缓存；也可手动点「缓存到本地」 |
| 下载 | dio → `.part` → 校验 `Content-Length` → 改名。不分片（token 有寿命，分片易留脏文件） |
| 播放 | 优先本地文件，其次网络直链；本地播放不受 token 过期影响 |
| 空间 | 默认上限 4GB，可调 1–16GB；超出删最旧，降到 80% |
| 一致性 | `isCached` 每次检查文件真实存在；`pruneMissing` 清死记录 |
| UI | 播放页信息区一行状态（已缓存/进度条/按钮）；片库新增「已缓存」分页；设置页开关+占用+上限 |

**端到端验证过下载链路**（不只是 mock）：
- Range 取 1MB → 206，字节数准确
- 落盘 8MB → 头 12 字节 `00 00 00 20 66 74 79 70 69 73 6F 6D`（有效 MP4 `ftypisom`）
- `moov` 盒位于偏移 36 → 这个文件可以边下边播，不必等整段下完

### 第五轮：四项功能改动（按驾驶员反馈）

| 反馈 | 处理 | 验证 |
|---|---|---|
| 首页没有搜索功能 | 标题栏常驻搜索框；新建独立搜索页（进入即聚焦、搜索历史 20 条可清空）；发现页搜索也接同一页 | 4 项 widget 测试 |
| 观看时没有音量调节 | 播放器底栏加音量按钮（点开滑块）；**左侧上下滑动**直接调；长按静音；音量持久化 | 7 项单测 |
| 手机一排只有 2 个视频 | `maxCrossAxisExtent: 320` → 按屏宽固定列数：手机 3 列、平板 4–5、桌面 6、超窄 2 | 3 项单测锁住 360/390/412/430 都是 3 列 |
| 收藏需要按视频/作者/tag 分开 | 收藏加 `FavoriteKind`（video/model/tag）；详情页长按作者/标签即可收藏；片库分四个分页 | 14 项片库测试 + 6 项分类隔离测试 |

### 本轮抓到的两个真问题

1. **旧收藏数据会被整条丢弃** —— 新结构用 `target`，旧数据用 `id`。`fromJson` 只读新字段，
   升级后老用户的收藏全没了。已加一次性迁移（`t` 缺失时回退读 `id`），并写了测试锁住。
2. **日文假名渲染成方块** —— 字体子集只扫 `lib/` 源码，而 `フィオニー` 这类字来自**站点标题**，
   不在源码里。表现是列表里最显眼的标题变成方块。已把全部假名（约 190 个）强制加入子集，
   体积 134KB → 184KB。

### 本地 Web 预览（已实拍）

本机不装 Android SDK 也能看真界面、拉真数据。构建产物与截图：

```
tool\preview.bat build    →  http://127.0.0.1:8899/
```

截图存于 `evidence/preview/`：`01-home.png`（3 列 + 顶部搜索）、
`02-detail.png`（四档清晰度 + 21 标签）、`03-explore.png`（分类索引）、
`04-library.png`（四分类分页）、`05-search.png`（搜索 zelda 的真实结果）。

预览链路需要两个开关，都有测试保护：
- `--dart-define=R34V_API_BASE=http://127.0.0.1:8898` → 请求指向本地代理；
- `Site.rewrite()` → 把封面/直链的站点绝对地址也改写到代理；不传 define 时恒等，安卓包不受影响。

为此把 `site_client.dart` 拆成条件导入：原生走 `dart:io`，Web 走 `package:web` 的 fetch。
已核验 web 产物里**不含 `dart:io` 任何痕迹**。

### 本机网络与字体的两个硬约束（已解决，后续别再踩）

1. **本机必须走系统代理才能访问站点**。实测禁用代理后直连全部 `WinError 10060` 超时。
   预览代理用 `urllib`，自动继承 `HTTP_PROXY`，无需额外配置。
   上游本身不稳定（同请求 0.9–21s 抖动），首次加载慢属正常。
2. **`fonts.gstatic.com` 在本机不通**（TLS 握手失败）。Flutter Web 渲染中文要靠它拉兜底字体，
   表现为**界面中文全变方块**。已改为自带字体子集 `assets/fonts/NotoSansSC-Subset.ttf`（160KB），
   由 `tool/subset_font.py` 扫描 `lib/` 实际用字裁出。**改了界面文案要重跑该脚本**，否则新字是方块。
   安卓端本来有系统字体，不受此影响，但自带一份能保证中日韩字形一致。

### 自动化（可复现）

```
flutter analyze            → No issues found!
flutter test               → 132 passed, 4 skipped
flutter build web --dart-define=R34V_API_BASE=...  → 成功
```

- `test/listing_parser_test.dart` —— 列表解析 15 项
- `test/detail_parser_test.dart` —— 详情解析 17 项
- `test/api_test.dart` —— 13 项离线 + 4 项真实网络
- `test/library_store_test.dart` —— 17 项
- `test/video_detail_test.dart` —— 10 项
- `test/widget_test.dart` —— 8 项 UI
- `test/preview_mode_test.dart` —— 5 项（锁住「安卓包不被改写到代理」）
- `test/features_test.dart` —— 14 项（信息流列数、首页搜索入口、搜索页、片库四分类）
- `test/volume_test.dart` —— 7 项（音量持久化、静音恢复语义）
- `test/video_cache_test.dart` —— 18 项（索引一致性、真实文件读写、预算清理、进度计算）

### 真实站点联通（已跑通两次）

```
flutter test --dart-define=R34V_LIVE=true test/api_test.dart   → 17 passed
```

### 这一轮抓到的 bug

前一轮三个（`FeedRequest` 缺 `==`、转圈占位动画、CI keystore 路径）之外，本轮新增：

4. **Web 产物里含 `dart:io`** —— 编译能过，浏览器里一请求就崩。已用条件导入拆开。
5. **预览时封面全破图** —— 解析器吐的是站点绝对地址，浏览器直连被同源策略拦。已加 `Site.rewrite`。
6. **预览代理 90 秒才返回** —— 上游 chunked 无 `Content-Length`，代理又保持长连接。已加 `Accept-Encoding: identity` + `Connection: close` 定界。
7. **发现页分类名撑爆布局** —— 36 个分类里 15 个超 20 字符。已改成固定列宽 + 两行截断。
8. **预览页中文全变方块** —— 本机访问 fonts.gstatic.com 不通，Flutter Web 拉不到兜底字体。已改为自带 160KB 字体子集。
9. **preview.bat 中文注释导致 cmd 解析报错** —— 报 `is not recognized`、for 循环里中文直接崩。已改纯 ASCII + 端口自清理 + 就绪探测。

### 待验证（本机做不了，只能真机）

| 项 | 谁来做 |
|---|---|
| `flutter build apk` 能否成功 | 第一次 push 后的 CI |
| APK 能否装上并启动 | 你 |
| 播放器（media_kit/libmpv）真机行为 | 你（**唯一完全没验过的部分**） |
| 全屏横屏、返回键、后台暂停 | 你 |
| 直链过期自动换链 | 后续观察（token TTL 未知） |

## 开放风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 首次 CI 可能失败 | 拿不到 APK | workflow 里已加「失败排查提示」，覆盖 media_kit 下载 / 签名不全 / AGP 冲突三类 |
| `media_kit_libs_android_video` 构建时从 GitHub Releases 下 4 个 .jar（约 20MB）并校验 MD5 | CI 网络抖动会失败 | 已验证两个 arm 包可下载；失败重跑即可，提示已写进 workflow |
| `v-acctoken` TTL 未知 | 长时间暂停后可能 403 | 已实现「回源换链 + 续播」，重试 2 次后给明确提示 |
| 站点 DOM 随时改版 | 解析静默失效 | 结构哨兵 + 10 份冻结 fixture 回归 |
| 未在真机验证过播放器 | 可能有黑屏/无音 | 第一次试用就是验收；已知回退路径是切软解或换 `video_player` |
| 16KB page size（Android 15+ 要求） | 老版 libmpv 可能不满足 | 未验证；若目标机报错，需要升 `media_kit_libs_android_video` |
| 参考项目 AGPL / 使用限制 | 不能复制代码 | 只借结构与依赖选型，全部自写 |
| 内容为成人向 | 上架商店无望 | 定为自用 sideload，应用名图标不冒用原站 |

## 下一道门

**完成 APK 构建与真机验收。** 本机需先配置 JDK 17 和 `JAVA_HOME`，或由 GitHub Actions 构建；当前 `adb devices -l` 没有可用设备。

APK 装上后，重点走「登录会员内容 → 播放与换档 → 中断缓存后续传 → 下载前换链 → 搜索快速连续提交 → 取消更新下载」，确认本轮修复在真实网络与播放器上闭环。

## 项目画板状态来源（可选）

本文件仍是唯一当前续接入口。需要进度画板时，按已安装 TavernWeave 的 `orchestrate-project-blueprint/references/progress-board.md`，在本节下方补入完整的 `tw-progress-state` 标记块，并与总设计案中的 `tw-progress-scope` 定义逐项核对。

## 一句续接

读取总设计案、活动蓝图和本文件，先完成 APK 构建，再在真机上验登录、播放、缓存续传与下载闭环。
