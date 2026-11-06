---
project_id: r34v-android
updated: 2026-11-03
status: implemented
---

# Iwara 原生适配器

站点 `iwara` / Iwara / `https://www.iwara.tv`，为第三个内置站点。继续使用 Kotlin、OkHttp、Gson 和 Media3，复用现有站点片库及缓存前缀。

## 来源与协议核对

参考用户指定的 [LoveIwara](https://github.com/FoxSensei001/LoveIwara)，核对快照 `0e91d955c0c804fe7088a6a3a8baa9c6a83f1ab2`：

- [video_service.dart](https://github.com/FoxSensei001/LoveIwara/blob/0e91d955c0c804fe7088a6a3a8baa9c6a83f1ab2/lib/app/services/video_service.dart)：列表、详情、相关与片源请求。
- [video.model.dart](https://github.com/FoxSensei001/LoveIwara/blob/0e91d955c0c804fe7088a6a3a8baa9c6a83f1ab2/lib/app/models/video.model.dart)：视频、封面和作者字段。
- [x_version_calculator_utils.dart](https://github.com/FoxSensei001/LoveIwara/blob/0e91d955c0c804fe7088a6a3a8baa9c6a83f1ab2/lib/utils/x_version_calculator_utils.dart)、[x_version_salt_service.dart](https://github.com/FoxSensei001/LoveIwara/blob/0e91d955c0c804fe7088a6a3a8baa9c6a83f1ab2/lib/app/services/x_version_salt_service.dart)：公开前端签名协议与盐值刷新。

协议已用当前 Iwara API 与官网 `/main.838884031a28ff0e346d.js` 交叉核对。未运行远端脚本或引入 Flutter。MIT 许可原文保存于 `app/src/main/assets/licenses/LoveIwara.txt`。

## 实现边界

| 功能 | 实现 |
| --- | --- |
| 列表/分页 | `/videos`，最新、趋势、热门、点赞、播放量；应用 1 基页码转换为 API 0 基页码 |
| 视频搜索 | `/search?type=videos&query=...`；`type=video` 会令当前服务端返回 500 |
| 标签 | `/videos?tags=<原始标签>`，保留下划线，不套用 KVS slug 规则 |
| 作者 | UUID 直接传 `user`；用户名先取 `/profile/<username>` 获取 UUID |
| 详情 | `/video/<字母数字ID>`；简介、作者、标签、时长、点赞、播放量 |
| 相关推荐/评论 | 并行读取，各有 1.2 秒可选请求预算，失败不阻断主片源 |
| 视频源 | `fileUrl` + `X-Version`；排除 preview 与非 HTTPS 源，Source 采用文件真实高度参与清晰度选择 |
| 下载/片库 | Media3 持久缓存与后台服务；key 保持 `iwara:<videoId>:<quality>`，按站点筛选 |
| 图库 | `/images` 列表、`/image/<id>` 详情、作者图库与图集阅读；图片使用 `#page=N` 保持文件身份，混合视频文件不进入图片解码路径 |
| 账号/私密内容 | 未接入，设置页明确显示公开浏览；无账号凭据或 token 的存储/发送 |
| 站外嵌入/AI 站 | 未接入，站外嵌入给出明确提示 |

`/videos` 的 `count` 在实测中会随页码增长（例如 limit=3，page=0 返回 count=4、page=1 返回 count=7），继续按每页返回的 count 更新可翻页上限。不能把第一页 count 缓存为全站总数。

## 网络与数据隔离

- 官网 host 为 `iwara.tv` / `www.iwara.tv`；API 为 `apiq.iwara.tv`，兼容请求头识别 `api.iwara.tv`。
- API 别名仅参与请求头归属，不注册进官网 Cookie/DNS host 集合，保留独立的按 host DNS 缓存，防止把官网已验证 IP 用到 API 域名。
- Iwara 媒体域及其子域使用 Iwara 的 Referer、Origin、x-site 和原生 UA；媒体域识别不授予 Cookie 或 Authorization。带有相似后缀的外部域名不匹配。
- `fileUrl` 只接受 HTTPS 的 `files.iwara.tv` / `filesq.iwara.tv` 文件端点，签名按 `<fileId>_<expires>_<公开盐值>` 计算 SHA-1。
- 片源缺失 Source 时，最多等官网盐值刷新 5 秒并重签一次；重签成功拿到 Source 才保存为内存中的已验证盐值。刷新失败沿用首次可播放结果，取消仍向上传递。
- Repository 请求开始时固定适配器；详情按 URL 的所属站点路由。MainActivity 的作者/标签入口也委托适配器解析链接。
- R34Application 在初始化播放器前恢复持久化站点；切站时清空当前搜索输入与范围。

## 验证记录

新增 10 项 JVM 协议测试与 3 项 Android 适配器测试，覆盖路由、页码、保留下划线、封面、原画分辨率、HTTPS/preview 筛选、签名向量、动态盐值、失败降级、请求头及 DNS/Cookie 边界。全量 JVM 当前 60 项通过。

第三站界面测试在默认及 200% 字体下通过：选择站点、发现、排序、搜索、真实 Media3 中性素材播放、收藏、标签跳转、片库隔离、后台下载、离线播放（零新增媒体请求）。通过抽屉选择 Iwara 后 force-stop，再启动的 UI 树确认活动站点恢复。原 9 项 Android 回归、3 项新适配器测试、该 UI 测试和实时播放探针共 14 个独立测试分批通过。

Android 16 实时公开内容验证已通过（限定 `rating=general`，未登录）：5 张卡片约 652 ms，详情返回 1080p 原画/540p/360p，12 条相关视频；封面 HTTP 206；Media3 ready，实际输出 7 帧。该次单样本只证明链路可用，不代表其他网络、清晰度或连续播放性能。

证据位于忽略目录 `dist/iwara-qa/`，包含测试 XML、中性界面截图和 `iwara-live.txt`。Android 7/8 未在本轮运行。
