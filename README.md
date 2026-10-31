# R34V · rule34video 安卓客户端

[rule34video.com](https://rule34video.com/) 的原生安卓第三方客户端。Flutter 写的，不是套壳 WebView。

**本机不需要装 Android SDK。** 所有 APK 由 GitHub Actions 构建，push 上去等几分钟，从 Actions 页面下载。

## 现状

第一个可运行版本，代码已经写完整但没有在真机上跑过（本机没有安卓工具链，这是刻意的选择）。

已通过验证的部分：

| 项 | 状态 |
|---|---|
| 静态分析 | `flutter analyze` 零 issue |
| 单元测试 | 132 项全绿 |
| 解析层对真实页面 | 10 份冻结 HTML 全过 |
| 真实站点联通 | 首页 / 第 2 页 / 详情 / 搜索 / 标签 / 分类 / 直链探活 全过 |
| 安卓构建 | **未验证** —— 等第一次 CI |

## 一键出 APK

```bash
git init
git add -A
git commit -m "init"
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

然后去仓库的 **Actions** 页签，等 `Build APK` 跑完（首次约 10–15 分钟），在运行页面底部 **Artifacts** 下载 `r34v-apk`，解压得到 APK。

没有配置签名密钥时产出的是 **debug 签名包**，可以直接装在手机上自用（需允许「未知来源」）。

### 想让它能覆盖安装（升级不丢数据）

debug 包每次签名可能变，装新版要先卸载。要能直接覆盖装，配一个自己的密钥：

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -alias r34v \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 upload-keystore.jks   # Windows: certutil -encode upload-keystore.jks out.txt
```

把输出填到仓库 **Settings → Secrets and variables → Actions**：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | 上面 base64 的整段 |
| `KEYSTORE_PASSWORD` | 你设的 keystore 密码 |
| `KEY_ALIAS` | `r34v` |
| `KEY_PASSWORD` | key 密码 |

配好之后 CI 自动切到 release 签名，并额外上传混淆符号。

## 站点探测结论（都是实测，不是猜的）

写代码前先把站点摸了一遍，结论直接决定了架构：

| 问题 | 答案 |
|---|---|
| 有 JSON API 吗 | **没有**。`?mode=async&get_block` 返回 0 字节，`/api/json/` 404 → 只能解析 HTML |
| 视频直链能播吗 | 能。`Accept-Ranges: bytes`，`Range: bytes=0-1023` → **206** |
| 清晰度 | 360 / 480 / 720 / 1080 四档，各带 `?v-acctoken=` |
| 详情页 URL | 必须带 slug。`/video/4703101/` → **404** |
| 分页 | 路径式 `/latest-updates/2/`，共 14632 页，每页 23–24 条 |
| 排序 | `?sort_by=post_date\|video_viewed\|rating\|duration\|pseudo_rand` |
| 登录 | 普通表单 POST，7 个字段含 CSRF |
| 有 Cloudflare 吗 | 没有。前置是 `ddos-guard`，但不拦纯 HTTP 客户端 |

完整证据与页面结构锚点在 [`evidence/README.md`](evidence/README.md)，10 份真实 HTML 快照在 [`test/fixtures/`](test/fixtures/)。

### 两个会让人栽跟头的坑（已修，写进测试）

1. **标签不能全页扫 `a[href*="/tags/"]`** —— 会把导航栏、页脚热门标签、相关推荐卡的标签一起吞进来，21 个真标签变 46 个，分类还会多出 10 个无关项。必须按 `div[data-suggest-type="tag|category|model"]` 分组取。
2. **缩略图扩展名在骗人** —— `336x189/xxx.jpg` 实际是 **WebP**，`320x180/xxx.jpg` 才是真 JPEG。按字节解码，别看后缀。

## 项目结构

```
lib/
  core/constants.dart              站点地址、UA、列表请求 → URL 生成
  domain/                          VideoCard / VideoDetail / StreamVariant / FeedLink
  data/
    parsing/listing_parser.dart    列表页 DOM → 卡片（含结构哨兵）
    parsing/detail_parser.dart     详情页 flashvars → 直链表
    parsing/parse_exception.dart   带诊断的解析异常
    remote/site_client.dart        HTTP 入口（条件导入：原生用 dart:io，Web 预览用 fetch）
    remote/site_client_io.dart     原生实现：UA、Range 探活、Cookie、挑战检测、3xx 跟随后取最终 URL
    remote/site_client_web.dart    Web 预览实现：浏览器 fetch（为绕开跨域而生）
    remote/rule34_api.dart         抓 + 解析 → 领域模型
    local/library_store.dart       观看进度、收藏、设置
    local/video_cache.dart         视频缓存：下载、索引、空间预算、清理
  features/
    feed/                          列表 + 卡片（首页/标签/分类/作者共用；3 列自适应）
    search/                        独立搜索页（自动聚焦 + 历史）
    explore/                       搜索入口 + 分类索引 + 榜单
    library/                       继续观看 + 收藏
    detail/                        详情 + 清晰度 + 标签 + 相关推荐
    player/                        播放器（全屏/倍速/清晰度/续播/直链过期恢复）
    settings/                      偏好 + 诊断日志
  app/providers.dart               Riverpod 依赖
  app/router.dart                  路由
test/
  fixtures/                        10 份真实 HTML（解析层回归的固定输入）
  listing_parser_test.dart
  detail_parser_test.dart
  api_test.dart
  library_store_test.dart
  video_detail_test.dart
tool/gen_android_icons.py          从 assets/icon.png 生成各密度图标
tool/preview_proxy.py              本地预览代理（补 CORS，绕开浏览器同源限制）
tool/preview.bat                   一键预览：构建 + 起服务 + 开浏览器
tool/subset_font.py                从中文字体裁出 App 用到的字形子集
evidence/                          站点探测证据与原始快照
legacy-tauri/                      上一版 Tauri 套壳脚手架（仅存档，不参与构建）
```

## 本地能做什么（不用安卓 SDK）

```bash
flutter pub get
flutter analyze                  # 必须零 issue
flutter test                     # 132 项
flutter test --dart-define=R34V_LIVE=true test/api_test.dart   # 回连真实站点
```

跑 UI 冒烟（不需要安卓工具链，但需要 Visual Studio 的 Windows 10 SDK）：

```bash
flutter run -d windows
```

没装 Windows SDK 时也可以 `flutter run -d chrome` 看界面（播放器在 web 上行为不同，只用来验列表和布局）。

## 本地预览（不用安卓 SDK、不用装安卓模拟器）

能看真界面，能拉真数据。走 Flutter 的 Web 构建：

```bash
tool\preview.bat build      # 构建 + 起服务 + 开浏览器
```

之后再预览不用重新构建：

```bash
tool\preview.bat
```

地址 `http://127.0.0.1:8899/`。`tool\preview.bat` 做了三件事：起站点代理 `:8898`、起静态服务器 `:8899`、打开 Chrome。关掉两个黑窗口就停。

### 为什么要那个代理

浏览器有同源策略：页面在 `127.0.0.1:8899`，站点在 `rule34video.com`，直接 fetch 会被拦，表现为**界面能开但一张卡都没有**，控制台报 `TypeError: Failed to fetch`。

`tool/preview_proxy.py` 在本地做转发并补上 CORS 头：

```
浏览器 → 127.0.0.1:8898（补 CORS）→ rule34video.com
```

代码侧靠两个开关配合（都有测试锁住，不会误伤安卓包）：

- `--dart-define=R34V_API_BASE=http://127.0.0.1:8898` 把请求指向代理；
- `Site.rewrite()` 把解析器吐出的站点绝对地址（封面、直链）也改写到代理。
  不传这个 define 时它是恒等操作，安卓包走的就是这条路。

### 预览能验什么、不能验什么

| 能验 | 不能验 |
|---|---|
| 列表加载、分页滚动、下拉刷新 | 播放器行为（web 用 HTML5，安卓用 libmpv） |
| 详情页全部字段、标签、清晰度按钮 | 全屏横屏、系统返回键 |
| 发现页分类索引、搜索入口 | 后台暂停、进度落盘到真机存储 |
| 片库页、空状态文案 | 长按/双击等触屏手势手感 |
| 布局在手机宽度下会不会挤爆 | 真机性能（4K 解码、发热） |

**播放器是唯一必须在真机上的部分。** Web 版点播放会尝试用浏览器播，但直链带 CORS 限制，多半播不出来——这不代表安卓上不行。

### 打不开 / 中文变方块怎么办

**地址打不开**：多半是服务没起。重新跑 `tool\preview.bat`。它会先清掉占用 8898/8899 的旧进程，再等服务器真的就绪才开浏览器。脚本是纯 ASCII，避免 cmd 解析中文出错。

排查顺序：

```bash
python --version                       # python 在 PATH 里吗
netstat -ano | findstr ":8899"         # 端口有没有被占
python tool\preview_proxy.py 8898      # 单独起代理看报错
```

**中文显示成方块**：Flutter Web 渲染中文要靠 `fonts.gstatic.com` 拉兜底字体，而**本机访问 gstatic 不通**（TLS 握手失败，实测）。所以项目自带了一份中文字体子集：

- `assets/fonts/NotoSansSC-Subset.ttf`，约 184KB
- 由 `tool/subset_font.py` 生成：扫描 `lib/` 下所有 Dart 文件收集实际用到的字，**外加全部日文假名**（约 190 个）。假名必须单独加——界面文案是中文，但视频标题来自站点，常带 `フィオニー` 这类日文，只扫源码会漏掉，标题里就显示成方块
- **改了界面文案后要重跑一次**，否则新加的字会显示成方块：

```bash
python tool/subset_font.py
```

脚本需要全量字体作为输入，首次准备一次：

```bash
curl -L -o assets/fonts/NotoSansSC-Regular.woff2 ^
  https://cdn.jsdelivr.net/npm/@fontsource/noto-sans-sc@5.0.0/files/noto-sans-sc-chinese-simplified-400-normal.woff2
```

注：站点标题里的**生僻汉字**（超出常用字表）仍可能不在子集里，会退到系统字体；浏览器上这类字可能是方块。安卓有系统完整字体，不受影响。假名已全覆盖。

### 关于网络

这台机器**必须走系统代理（`HTTP_PROXY`）才能访问站点**，直连会超时（实测禁用代理后全部 `WinError 10060`）。预览代理用的是 `urllib`，会自动继承环境变量里的代理设置，不用额外配。

首次加载有时要等十几秒——是上游本身慢（实测同样请求 0.9 秒到 21 秒都有），不是本地服务的问题。加载完就有缓存了。

### 手动跑（想自己控制时）

```bash
# 终端 1：代理
python tool/preview_proxy.py 8898

# 终端 2：静态服务
python -m http.server 8899 --bind 127.0.0.1 -d build\web

# 构建（改代码后要重跑）
flutter build web --dart-define=R34V_API_BASE=http://127.0.0.1:8898
```

也可以用热重载开发模式，改代码立即生效：

```bash
flutter run -d chrome --dart-define=R34V_API_BASE=http://127.0.0.1:8898
```

## 功能

### 浏览

- **首页**：最新更新瀑布流。手机 3 列（平板 4–6 列自适应），顶部常驻搜索框
- **搜索**：独立搜索页，进入即聚焦。带搜索历史（最近 20 条，可清空）
- **发现**：榜单入口 + 36 个分类索引
- **列表页**：最新 / 高分 / 热门 / 标签 / 分类 / 作者，全部支持五种排序与无限翻页

### 观看

- 四档清晰度（360 / 480 / 720 / 1080），记住上次选择
- 全屏横屏、倍速 0.5–2×、进度拖动
- **音量**：点图标弹滑块；**左侧上下滑动**直接调（手机看视频的肌肉记忆）；长按图标静音；调过的音量会记住
- 续播：每 5 秒落盘，退出再进接着看
- **本地缓存**：看过的和收藏的视频自动存本地，再看不走网络（见下节）
- 直链过期自动回源换链并从当前位置续播

### 收藏（分三类）

| 类型 | 怎么收藏 | 点进去看什么 |
|---|---|---|
| **视频** | 详情页右上角心形 | 播放这个视频 |
| **作者** | 详情页**长按**作者名 | 这个作者的全部作品 |
| **标签** | 详情页**长按**标签 | 这个标签下的所有视频（最热的标签近两千页） |

片库页分四个分页：继续观看 / 视频 / 作者 / 标签。作者与标签用 chip 墙展示，可直接删除。

> 三种类型分开存（`kind + target` 作为唯一键），同名作者和标签互不影响。
> 旧版本的收藏数据会自动迁移成「视频」，不会丢。

### 本地缓存

看过的和收藏的视频会**自动存到本地**，再点开是本地播放，不用重新缓冲。

触发规则：

| 情况 | 是否缓存 |
|---|---|
| 已收藏的视频 | **一定缓存** |
| 看过、且时长 ≥ 60 秒 | 缓存 |
| 看过、但不到 60 秒 | 不缓存（片段太小，不值得占空间） |
| 手动点「缓存到本地」 | 缓存 |
| 自动缓存开关关掉 | 只缓存手动点的 |

管理位置：设置页 → 缓存。片库页有一个「已缓存」分页，能看到占用空间、单独删除、整体清空。

- **空间上限**：默认 4GB，可调 1–16GB。超出后自动删最旧的，降到上限的 80%（留余量，避免每次播放都触发清理）
- **下载方式**：整段下载到 `.part` 临时文件 → 校验字节数 → 改名。不用分片，因为直链 token 有寿命，分片容易拿到半截脏文件
- **播放优先级**：本地文件 → 网络直链。走本地时不受 token 过期影响
- **半截文件**：应用被杀留下的 `.part` 会在下次启动时清掉

> 缓存只在**走网络播放**时触发，已经缓存过的不会重复下载。
> 缓存失败不会打断观看，只在底部提示一句。

## 已经处理好的失败路径

| 失败 | 表现 | 处理 |
|---|---|---|
| 直链 403/410（token 过期） | 播放中断 | 自动回源详情页换链，从当前秒数续播；重试 2 次后给出明确提示 |
| 网络断 / 超时 | 列表空白 | 3 次指数退避重试；仍失败显示重试按钮，不白屏 |
| 站点要人机验证 | 拿到挑战页 | 识别并提示「需要人机验证」，不假装成功 |
| 站点结构改版 | 解析不出来 | 结构哨兵抛带诊断的异常，UI 说「站点结构变了」而不是崩溃或显示空列表 |
| 播放中途切后台 | 进度丢失 | 切后台即落盘并暂停，避免后台跑流量 |
| 缓存下载中断 | 留下半截文件 | 写 .part，校验字节数后才改名；失败删除重来；启动时清理残留 |
| 缓存超出空间上限 | 撑爆存储 | 自动删最旧的，降到上限 80% |
| 缓存索引与磁盘不一致 | 显示已缓存但打不开 | isCached 每次都检查文件是否真的存在；pruneMissing 清理死记录 |

## 没做的（首版明确不含）

登录、在线收藏、下载到本地、评论、评分、上传、投屏、VR/全景、多语言、深色浅色切换（固定深色）。

## 说明

第三方客户端，与 rule34video.com 无任何关联。应用不托管内容，所有视频来自原站。仅读取公开页面，不做登录写入。仅供个人使用，请自行确认符合所在地法律与原站条款。
