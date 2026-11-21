# 限流防护与请求形态贴近浏览器（2026-11-21）

版本基线：2.7.1 / 48 的工作树。本轮与播放缓冲轮一起进入 2.8.0。

## 问题

用户反馈：使用一会儿之后整个 IP 都会被 rule34video 限流；多个站点存在同类问题。

审计结论（均为主域请求构成，代码证据见各文件）：

| 来源 | 打到哪里 | 改动前的量级 |
|---|---|---|
| 列表封面 | 站点主域（`site::absolute` 解析，如 rule34video.com/contents/…） | 无节奏控制，Coil 并行突发 |
| 页面与 API | 站点主域 / apiHosts | 订阅合并 3 并发（`Network.kt`），保存的搜索 3 一批 |
| 播放媒体 | `/get-file` 先敲主域再 302 到 CDN | 每次起播、seek、引擎重建、下载重开都重走一遍主域 |
| 请求形态 | 全部 | 只有 UA + Referer，缺浏览器必带的 Accept/Sec-Fetch 组合，机器人评分吃亏 |

## 改动

### 1. 429 同址退避 — `data/SiteHtml.kt`

429 时按 `Retry-After`（封顶 15 秒，缺省 1 秒）等待后重试同一地址一次，仍被拒才走镜像链；验证页与连接错误维持原走法。限流存活到 `Repository.feed` 时回退旧快照（`cached = true`），不再整页报错。

### 2. 站点主域请求节奏 — `data/Network.kt:HostGate`

每个站点主机的请求**起点**按固定间隔错开：页面 400ms（≈2.5 请求/秒）、图片 150ms（30 张封面的列表摊开到约 4.5 秒）。只推迟起点、不占住连接。媒体 CDN 与更新服务器不在站点清单里，不受影响。

### 3. 媒体重解析缓存 — `data/Network.kt:MediaRedirectCache`

get-file→CDN 的 302 只解析一次：30 分钟窗口内 seek、引擎重建、下载重开直接敲 CDN。浏览器每次 seek 都重跳 302，这里把主域请求数压到浏览器的几分之一。CDN 侧 403/404/410 立即作废并回站点重解析。302 之后一跳的地址在**网络层拦截器**捕获（应用层拿不到重定向后的真实地址）。

### 4. 浏览器形态请求头 — `data/Network.kt:applyBrowserShape`

按目的地补齐浏览器必带、裸 OkHttp 不带的头（已显式设置的不覆盖，未登记的主机不动）：

| 目的地 | Accept | Sec-Fetch-Dest / Mode / Site |
|---|---|---|
| 页面导航 | text/html,… | document / navigate / same-origin（附 Upgrade-Insecure-Requests） |
| 图片（路径后缀） | image/avif,… | image / no-cors / same-origin 或 cross-site |
| API（apiHosts） | application/json, text/plain, */* | empty / cors / same-site |
| 媒体（mediaClient） | */* | video / no-cors（覆盖基线的 document 形态） |

全部请求补 `Accept-Language: zh-CN,zh;q=0.9,en;q=0.8`。

## 未验证 / 边界

- 对真实站点的限流阈值是未知数：400ms / 150ms 是保守默认值，若实测仍触发限流可再调紧（`PAGE_REQUEST_GAP_MS` / `IMAGE_REQUEST_GAP_MS`）。
- 重解析窗口 30 分钟按 v-acctoken 60 分钟窗口取一半；站点若缩短签发窗口，靠 403 自愈路径兜底。
- 各站点（missav/jable 的 Cloudflare、EH 的图片配额、Iwara 的 API 配额）对浏览器形态头是否买账，需联网实测；本轮刻意未对真实站点压测。
