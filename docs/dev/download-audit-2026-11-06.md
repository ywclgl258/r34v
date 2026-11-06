# 12站下载检查与加速方案（2026-11-06）

检查基线为当前 2.4.0 / 42 工作区，Git HEAD `3b0170b4faea1529b73fe5bfa96520e05327e0db` 加既有未提交改动。本轮只新增可选实站检测和报告，未修改下载实现、版本号、签名或正式 APK。

## 结论

12个站点均进入检查范围：10站的选定样本完整下载与离线读取通过，E-Hentai两次中断，ExHentai因缺少登录及账号权限未进入媒体下载。结果是当前时间、网络和样本的证据，不代表每个作品、地区和账号均可下载。

| 站点 | 最终实测结果 | 样本 |
| --- | --- | --- |
| Rule34Video | 完整视频通过，360p，24.78 MB；Range与离线首尾读取通过 | `4714417` |
| Hanime1 | 完整视频通过，480p，2.16 MB；Range与离线首尾读取通过 | `409050` |
| Iwara | 完整视频通过，360p，36.79 MB；Range与离线首尾读取通过 | `dAIgOg9ZIxKb4Y` |
| E-Hentai | 未通过：首轮7/18页、复测5/21页中断；两次均为TLS记录读取错误 | `4308316` |
| ExHentai | 未验证媒体下载：要求登录并具备账号权限 | `无授权样本` |
| nhentai | 3/3 页或张完整保存，0.74 MB，离线读取通过 | `649988` |
| Hitomi | 2/2 页或张完整保存，0.53 MB，离线读取通过 | `4308397` |
| 4KHD | 59/59 页或张完整保存，14.91 MB，离线读取通过 | `hana-song-yanineko` |
| Everia | 80/80 页或张完整保存，13.38 MB，离线读取通过 | `huayang-vol-609-%e5%bf%83%e4%b8%8a%e5%8f%afflora` |
| 4KUp | 26/26 页或张完整保存，5.04 MB，离线读取通过 | `yeon-woo-touch` |
| GalleryEpic | 60/60 页或张完整保存，2.58 MB，离线读取通过 | `10836` |
| Cosplaytele | 99/99 页或张完整保存，15.86 MB，离线读取通过 | `evanescia` |

### nhentai 失败复现

- 同版下载工作单元在直连时完成列表、详情，但下载首图连接 `i1.nhentai.net` 超时，0/3页失败。
- 保持应用下载实现相同，仅把测试模拟器临时切到电脑已有的系统HTTP代理后，同一画廊 `649988` 完整保存3/3页、739178字节，并通过离线完整性和图片解码检查；在12站整组检查中再次通过。
- 这次复现定位在图片CDN的DNS／直连路由阶段，公开API没有失效。单看最后一条IPv6连接错误不能认定只有IPv6有问题；系统代理对照说明该网络下CDN直连不可用。
- 手机需要让图片CDN子域也走正确系统网络路径；仅能打开主站列表不足以证明图片服务器可访问。本轮没有手机现场日志，不能断言昨天的失败与模拟器原因完全相同。
- 模拟器原HTTP代理值为`:0`，测试后恢复为`:0`；Windows代理设置和应用网络策略未修改。

### E-Hentai 未通过

首轮样本18页，保存7页后失败；另一个21页样本复测保存5页后失败。两次错误均包含`DECRYPTION_FAILED_OR_BAD_RECORD_MAC`与`BAD_DECRYPT`，属于HTTPS图片读取中的TLS错误。未降低TLS验证、未用明文HTTP替代、未把部分文件标为完成。当前证据不能区分代理链路、图片节点与设备TLS实现的全部影响，不能报告该站下载顺利。

## 官方或站内下载渠道

| 站点 | 已核实渠道 | 接入边界 |
| --- | --- | --- |
| nhentai | 官方API `POST /api/v2/galleries/{gallery_id}/download?format=zip|cbz|torrent`，返回短效URL | 需User Token或API Key，站方`allow_downloads`功能须启用；格式有不同限流。当前公开逐图路径不要求该下载凭据。 |
| E-Hentai / ExHentai | Archive、Hentai@Home、EHTracker种子 | 受账号、权限、配额、GP及种子可用性影响；本轮没有调用扣GP的归档操作。 |
| Rule34Video | 站内Download弹层给出不同清晰度的MP4直链 | 首轮样本为1080/720/480/360p，实际当前样本的官方360p地址读取通过；地址包含签名／会话相关参数，不永久保存到报告。 |
| Iwara | 公开文件源响应中的`src.download`，另有`src.view` | 本轮General公开视频返回三种质量，官方低清地址在`swan.iwara.tv`／`yuko.iwara.tv`读取成功。原画与高分辨率未逐一完整下载。 |
| Hanime1 | 观看页下载按钮进入`/download?v=...`，官方页按清晰度列出MP4 | 实时下载页样本列1080/720/480p；应用目前未解析独立`downloads`，播放源480p完整下载已通过。 |
| Hitomi | 官方网页逐图请求并在浏览器用JSZip打包ZIP | 官方`download.js`设置1000毫秒节流、顺序下一张和重试；这不是服务器现成整包接口。 |
| GalleryEpic | 官方下载导航页提供PikPak、夸克等网盘 | 站外网盘会话和下载流程独立，不能当本站免登录ZIP API。 |
| Cosplaytele | 官方正文提供Telegram、SoraFolder、Gofile等站外下载入口 | 本轮未点击站外文件、登录或下载其归档。 |
| 4KHD / Everia / 4KUp | 本轮未确认统一的官方整包接口 | 已验证现有逐图下载完整完成；“未确认”不等于不存在站内或付费渠道。 |

主要一手依据：

- [nhentai官方OpenAPI](https://nhentai.net/api/v2/openapi.json)。公开详情接口匿名限20次/分钟；ZIP/CBZ下载URL申请另有每IP和每用户限制。当前应用已支持本地CBZ/ZIP导入，可承接用户从官方渠道取得的文件。
- [EHWiki下载说明](https://ehwiki.org/wiki/Downloading)、[归档说明](https://ehwiki.org/wiki/Archives)。归档与普通逐图、原图计费不是同一个流程。
- [Hitomi官方脚本](https://ltn.gold-usergeneratedcontent.net/download.js)。仅检查公开脚本，不执行远端JavaScript。
- [Hanime1官方下载页样本](https://hanime1.me/download?v=409039)。网页可读证据与原生媒体任务结果分别记录。
- [GalleryEpic官方下载导航样本](https://galleryepic.com/en/download/cosplay/10795)、[Cosplaytele官方正文样本](https://cosplaytele.com/evanescia/)。
- Rule34Video站内详情下载弹层的只读HTML检查，以及本轮原生`Detail.downloads`返回与字节读取；Iwara另由本轮原生公开API／官方文件字段补证。只读研究者受403限制的环境不被扩大为Iwara不可用结论。

## 多线程与加速

### 当前实现

- 视频：`PlaybackStore`配置2个下载任务并行、3线程执行池。它不等于每个MP4已经分成多个片段并行。
- 图集：`GalleryDownloadStore`一次一本、每次一页。已完成图页可复用；单个图页没有字节级断点续传。
- 当前图片先完整读入内存再写文件，单页上限24 MiB，并有字节数组复制；直接增加多页并发会放大内存占用。
- [Android Media3官方说明](https://developer.android.com/media/media3/exoplayer/downloading-media)明确区分任务并行和可拆分下载的执行器并行。

### 可实施顺序

1. **先修恢复路径**：将503/502等可恢复HTTP错误与401/403/404、配额错误区分，有限次数退避；429遵守Retry-After或暂停提示。图片失败后允许一次站点支持的刷新／备用节点，已完成页继续复用。保持TLS校验和权限边界。
2. **优先采用现成官方文件**：Rule34Video和Iwara已解析`Detail.downloads`，可统一同质量优先官方直链、缺失回退播放源；切换来源前核对文件大小／ETag等身份，避免把旧缓存拼到不同文件。nhentai官方CBZ需要新增凭据与短效URL生命周期，不能拿ZIP当单页图片塞入当前解析器。
3. **写真／nhentai尝试2页并发**：先改流式临时文件和格式校验，再设置每站上限、完成页统计、取消等待与完整性提交。本轮未测量并发收益，不承诺固定倍数。
4. **单视频尝试2–4分段**：三个视频站样本均返回正确206与Content-Range，具备验证分段的基础。实施时必须检查Range响应、总大小、ETag／Last-Modified及If-Range；不支持Range时回到单流，并保证暂停、删除、恢复不会复活任务或拼坏文件。这需要Downloader／缓存策略，单调大线程池不能完成。
5. **Hitomi和E/Ex先保守**：Hitomi先遵循官方约1秒节奏与顺序请求；E/Ex先处理错误恢复、配额与官方归档，不用高并发掩盖当前传输失败。

## 代码检查的可靠性缺口

以下为独立静态检查发现，生产代码本轮未改，不能与实站下载结果混淆。

| 优先级 | 问题与证据 | 最小验证门 |
| --- | --- | --- |
| P1 | `PlaybackStore.kt:106`失败后异步刷新详情并无条件重加任务，`remove:193`未取消／作废刷新，存在删除后重新出现的可达竞态 | 阻塞详情刷新→删除到任务／缓存消失→释放响应→断言任务和索引不重建；保留用户主动删除后再下载。 |
| P2 | `GalleryDownloadStore.retryIo`仅捕获IOException，但`GalleryRepository.imageBytes`的HTTP错误由`check`抛出IllegalStateException，503等不会进入当前重试 | 503→200、持续503、429等待、509配额、404分别验证请求次数和状态。 |
| P2 | 下载路径没有采用`GalleryImage.reloadUrl`；nhentai固定按页号选CDN，手动重试不保证备用服务器；旧book.json优先复用 | 初始图源坏→刷新源成功、取消期间刷新、继续时不重下完成页。 |

门禁结论：现有模块保留，不重写。可靠性问题进入`Local Fix / Local Fix Only`候选；并发与官方归档接入仍需独立实现和验收。本轮检查没有把加速或故障修复默认为已完成。

## 证据、覆盖与限制

- 设备为API36模拟器；样本真实联网，非ComposeFixture模拟适配器。图集使用现有实际适配器、下载工作单元、索引和完整性模型；视频使用现有生产HTTP工厂与Media3 DownloadManager。
- 图集329页／张完整落盘；3个视频共63728389字节完整缓存；完成后做离线读取。媒体不展示或导出，隔离目录、数据库及测试偏好已清理。
- 本轮验证下载执行与保存；没有逐站额外重测前台通知、系统杀进程和后台时限的所有组合。ExHentai账户、官方付费归档及nhentai登录下载接口未运行验证。
- JUnit报告绿色仅代表检测流程执行完毕，逐站通过／失败以JSON行结果为准，不能称12站全通过。
- 独立审查者：`/root/download_contract_review`，按下载仓库、执行器、适配器和取消边界切片只读检查；原生实测与最终结论由主Agent执行和核对。
- 本地证据位于`dist/download-audit-2026-11-06`：直连NH、系统代理对照、12站首轮、视频与EH复测、最终合并JSON、公开API快照、模拟器日志。检测入口为`LiveDownloadAudit`，默认不运行；显式选择该类后可用`probeSites`限制站点。
- Debug编译、检测流程和Debug Lint通过。正式版本仍为2.4.0 /42，本轮无签名、推送或发布动作。
