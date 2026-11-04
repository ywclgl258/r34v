# 系统网络、官方登录与漫画瀑布流

2026-11-04，根据用户要求完成三项变更：统一系统网络路径、EX 登录门禁及官方网页登录、漫画列表瀑布流。当前开发包及测试数量以 [HANDOVER.md](HANDOVER.md) 最新段为准。

## 网络路径

`Network` 的页面、API、图片、播放、后台下载、登录验证和更新请求统一使用 `Dns.SYSTEM` 和 `SystemProxySelector`。选择系统针对当前 URL 返回的首选路径，遵循系统/PAC 的排除决定。未配置代理才直连；选择了代理后不会继续尝试列表中的 DIRECT 回退。选择器读取失败作为 I/O 错误显示。

删除 `RoutedDns`、DoH 依赖、固定/手填 IP、IP 池、VPN 检测分支、CDN 独立解析及应用内代理输入。旧配置仅按明确键名移除，不修改历史、收藏、会话或已下载文件。VPN 由 Android 网络栈处理。系统代理变更广播使旧空闲连接失效，后续请求重新使用系统设置；切站不再重建网络客户端。

设置页只说明当前系统路径并保留站点访问诊断。诊断通过适配器读取列表，不再对各站的首页 URL 一律按 HTML 请求，也不把返回的网页/JSON 正文直接展示给用户。系统广播语义参考 [Android Proxy](https://developer.android.com/reference/android/net/Proxy#PROXY_CHANGE_ACTION)。

Media3 播放固定使用 `Video.site`，后台下载根据 `DataSpec.key` 的站点前缀固定 UA/Referer。请求显式标记所属站点，即使媒体主机不在站点域名下，或用户随后切站，也保留正确的公共请求头。账号 Cookie 仍只发送到所属站点主机，媒体 CDN 不携带站点账号 Cookie。

用户提供的 `127.0.0.1:7897` 仅用于本轮测试。Windows 直接通过该 HTTP 代理访问，模拟器用临时 `adb reverse` 和系统代理连接电脑端口；地址没有写入应用默认配置。实测结束后撤销转发和模拟器代理。手机上的 `127.0.0.1` 指向手机自身，用户应在手机系统或代理客户端中设置适合手机的网络路径。

## EX 官方登录

参考 [Ehviewer_CN_SXJ 的 WebViewSignInScene](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/blob/BiLi_PC_Gamer/app/src/main/java/com/hippo/ehviewer/ui/scene/sign/WebViewSignInScene.kt) 和 `EhUrl.URL_SIGN_IN` 核对认证协议。没有复制其应用源码。登录地址是 [E-Hentai 官方论坛](https://forums.e-hentai.org/index.php?act=Login)。

抽屉明确标注 EX 需要登录及账号权限。无本地会话时，EX 首页、搜索、发现、片库及画廊入口均被登录门禁拦住，提示未登录可能白屏。点击“官方网页登录”进入独立认证 Activity，原生浏览和阅读界面保持不变。

认证 Activity 加载官方表单，允许登录所需 JavaScript/DOM storage；不注入 JS bridge，不读取或保存密码。仅允许官方 HTTPS 主机的顶层导航，禁用文件/内容访问和混合内容，证书错误停止连接。窗口开启 FLAG_SECURE。论坛产生身份 Cookie 后，临时同步到用户所选的 EH 或 EX，再让原生适配器验证该站返回的画廊列表；成功才保存 AndroidKeyStore 加密会话并回到原生首页，失败不覆盖旧会话。仅登录成功并不代表拥有 EX 权限。

系统 WebView 的认证 Cookie 属于临时浏览会话，流程开始和结束均清除，退出时清除 WebView 历史与缓存；原生持久会话另用 AES-GCM 保存。此流程依赖系统 WebView。官方页面拒绝请求、浏览器验证未完成或 WebView 不可用时，提供浏览器入口及 Cookie 导入备用路径。Cookie 导入接受大小写请求头前缀。

`GalleryLoginRequiredException` 区分会话失效与配额/网络错误。EX 返回未授权页面、403 或登录重定向循环时回到登录门禁；明确权限拒绝不会触发私有详情的缓存降级。未联网但本地仍持有已验证会话的已读页面可继续离线重看。

本轮使用合成 Cookie 验证了“无权限不保存 → 授权成功才保存 → 清理临时网页登录 Cookie”的设备流程；没有真实 EX 账号，没有宣称真实账号登录与授权访问已完成。官方论坛的桌面探测曾返回 403，人工浏览器验证仍由原站决定。

## 漫画瀑布流

EH、EX、nhentai、Hitomi 的首页、搜索结果和画廊片库使用原生 `StaggeredGridLayoutManager`。默认手机双列，宽屏增加列数；标题、提示、分页入口等跨列。保留视频站的原有网格/横向片架。

nhentai / Hitomi 使用 API 中的缩略图/图片宽高，EH 读取缩略图属性或 CSS 尺寸；尺寸缺失时以图片实际比例修正。保留完整封面，限制极端长宽比，避免单张异常图占满长屏。未知比例先保留占位，继续使用现有缩略图采样、并发上限和缓存。分页支持瀑布流可见位置，不再强转 GridLayoutManager。

本轮 UI 检查应用了 `ui-ux-pro-max` 的移动端图片尺寸/缓存建议，沿用原黑底主题，没有另建配色或组件系统。中性设备素材覆盖不同封面比例、滚动分页、横竖屏、200% 字体、收藏和阅读链路；认证页不保存真实凭据截图。
