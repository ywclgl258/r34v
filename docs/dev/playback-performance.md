# MP4 与 HLS 播放耗时评估

2026-11-22，基线 `d8d42d9`，Android 16（API 36）x86_64 标准手机模拟器，1080 × 2400、420 dpi、默认字体。使用 2.10.0 开发版和 Media3 1.9.2。

## 测量口径

- 同一份约 36 秒的中性素材，640 × 360、H.264 + AAC。MP4 启用 faststart，HLS 直接重封装原编码轨，目标分片长度 2 秒。两种封装逐帧解码后，861 帧图像哈希及顺序一致。
- 同一个本地 HTTP 服务分别提供两种媒体和格式相同的详情页。每次响应延迟 120 ms 发送响应头，响应体按每 80 ms 16 KiB 发送（约 1.64 Mbps，每连接）。使用应用实际的 KVS 详情解析、在线播放器和缓存。未走外部站点、DNS、代理和 CDN，未注入丢包或速率抖动。
- 每轮只提供 `360p` 单档，1× 播放，关闭自动下载。两种媒体使用相同缓冲计划。分别将应用带宽估值设为未知和已知（1.60 Mbps），每次使用独立作品 ID 和缓存键，以 HTTP 日志核对是否触发起播测速。先对两种格式各预热一次，随后交替正序和逆序执行测试矩阵。
- 首次出画从请求打开播放器到 Media3 首帧渲染时间。详情阶段包含 Activity 启动及详情 HTTP 完成，之后分别计测速请求和媒体请求。详情完成到首个媒体请求也包含解析、主线程调度和引擎准备。[Media3 首帧回调](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener#onRenderedFirstFrame(androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,java.lang.Object,long))
- 回拖从 22.5 秒跳回已播放过的 1.2 秒，计到目标附近的首帧渲染。先从 2.7 秒跳到缓冲区之外的 22 秒，以避免整段都驻留在播放队列中。同步记录回拖至重新出画期间的 HTTP 请求范围和实际触碰的磁盘缓存段。命中按有缓存段读取的轮次计数。HTTP 请求可通过 Range 和分片名区分目标位置重读与后续补缓冲。[Media3 缓存监听](https://developer.android.com/reference/androidx/media3/datasource/cache/Cache.Listener#onSpanTouched(androidx.media3.datasource.cache.Cache,androidx.media3.datasource.cache.CacheSpan,androidx.media3.datasource.cache.CacheSpan))

## 结果

每个条件 5 次，共 30 次有效样本。耗时为中位数（最小–最大），单位毫秒。默认启用 MP4 会话回拖缓存，关闭全局流式缓存。

| 格式与条件 | 首次出画 | 回拖恢复 | 回拖命中轮次 | 回拖到出画的新 HTTP 请求 |
| --- | ---: | ---: | ---: | --- |
| MP4 · 网速未知，默认缓存 | 6150（6093–6336） | 394（375–420） | 5/5 | 每轮 1 次，从已缓存范围之后开始 |
| HLS · 网速未知，默认缓存 | 1241（1138–1327） | 637（624–654） | 0/5 | 每轮重读 `segment00.ts` |
| MP4 · 网速已知，默认缓存 | 1087（1018–1187） | 395（372–457） | 5/5 | 每轮 1 次，从已缓存范围之后开始 |
| HLS · 网速已知，默认缓存 | 1105（1078–1280） | 657（646–680） | 0/5 | 每轮重读 `segment00.ts` |
| MP4 · 网速已知，关闭会话缓存 | 1011（929–1145） | 633（627–657） | 0/5 | 每轮从第一个视频关键帧字节 `29156` 开始请求 |
| HLS · 网速已知，开启流式缓存 | 1168（1004–1280） | 404（381–452） | 5/5 | 4 轮 0 次，1 轮补读 `segment03.ts` 剩余范围 |

- **首帧主要耗在 MP4 起播前测速。** 网速未知时，1 MiB 范围请求的中位数是 5268 ms，它在正式媒体请求之前完成。HLS 同一条测速路径只读 671 B 的播放列表，中位数 128 ms。网速已知时，两种格式首帧均约 1.1 秒。默认缓存四组的详情完成中位数为 181–199 ms，正式媒体请求至首帧为 643–844 ms。已知网速的默认 HLS 组从播放列表请求到首个分片请求为 161 ms。[起播选档](../../app/src/main/kotlin/com/r34v/r34v/PlayerActivity.kt) · [测速代码](../../app/src/main/kotlin/com/r34v/r34v/playback/MediaProbe.kt)
- **回拖差异来自目标数据能否复用。** 默认 MP4 从缓存读取文件开头约 0.69–0.74 MB，后续请求从缓存边界开始。关闭会话缓存后需要从目标的前置关键帧重读，恢复中位数增加 238 ms。默认 HLS 每次都重读目标分片，开启现有流式缓存后 5 次均命中目标分片，恢复中位数减少 253 ms。30 次跳转均沿用同一个播放引擎。[播放缓存配置](../../app/src/main/kotlin/com/r34v/r34v/playback/PlaybackStore.kt)

这一组数据适用于单档、固定速率的短点播样本。弱网再缓冲见下文，真实 CDN、更长素材、HLS 多变体及网络抖动仍待复测。

## 优化顺序

1. 单档直接起播已实现，原片源和 1080p HLS 的弱网复测见下文。多档选档后续可区分真实传输采样和网络类型的默认估值，HLS 的实测速率可取自媒体分片。预跑在尚无媒体传输时观察到 2.40 Mbps 的初始估值，Media3 的网络类型回调可携带默认估值，当前 `LinkSpeed` 接受所有正值回调。[带宽估算源码](https://github.com/androidx/media/blob/1.9.2/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/upstream/DefaultBandwidthMeter.java#L389-L431) · [应用带宽估算](../../app/src/main/kotlin/com/r34v/r34v/playback/LinkSpeed.kt)
2. 点播 HLS 支持有容量上限的会话回拖缓存，使用独立会话和完整分片 URI 隔离资源。下文记录开关对照和全局流式缓存的结果。[会话缓存](../../app/src/main/kotlin/com/r34v/r34v/playback/HlsSeekSession.kt)

## 单档直接起播复测

以 `7abc44f` 为基线，使用同一台 Android 16 模拟器和原来生成的 MP4、HLS 文件。起播前将应用带宽估值置为未知，沿用默认缓存。两种封装各预热一次，每种格式、链路和代码版本各测 3 次，交替顺序。

- 原链路沿用每 80 ms 16 KiB（约 1.64 Mbps），记录首次出画和同步测速请求数。
- 弱网从起播起限制为每 200 ms 16 KiB（约 0.655 Mbps），两种限速下每次响应头都延迟 120 ms。记录首次出画，以及从首次进入播放到视频位置 16 秒之间的再缓冲次数、总时长和最长单次时长。再缓冲从进入 `STATE_BUFFERING` 计到回到 `STATE_READY`。初次出画前的缓冲计入首帧耗时。
- 同一用例检查实际打开的媒体地址、整个观测期的引擎实例和播放错误。另用 `360p`、`720p` 两档验证偏好 `360p` 的选档，未知网速仍发出一次测速，已知网速直接选档。

下表为中位数（最小–最大），每组 3 次，共 24 次单档样本。耗时单位为毫秒。

| 格式与版本 | 原链路首次出画 | 弱网首次出画 | 弱网再缓冲次数 | 弱网再缓冲总时长 | 弱网最长一次 |
| --- | ---: | ---: | ---: | ---: | ---: |
| MP4 · 改动前 | 6220（6107–6371） | 13796（13733–13816） | 2（2–2） | 2019（1985–2050） | 1023（993–1029） |
| MP4 · 直接起播 | 1104（1010–1161） | 1058（975–1092） | 2（2–3） | 2022（1846–2779） | 998（993–1018） |
| HLS · 改动前 | 1324（1246–1394） | 1421（1259–1432） | 4（4–5） | 3990（3611–4072） | 1102（1007–1118） |
| HLS · 直接起播 | 1090（1071–1182） | 1334（1261–1336） | 4（4–5） | 3650（3408–3909） | 1067（1029–1121） |

改动前每次单档起播会发出一次同步测速，改动后 12 次单档起播均为 0 次。弱网 MP4 跳过了中位数 12.83 秒的测速，首帧少等 12.74 秒。HLS 原测速只传播放列表，首帧收益较小。弱网再缓冲次数的中位数保持在 MP4 两次、HLS 四次。新版 MP4 有一轮达到 3 次和 2779 ms；这组数据来自单个短片、单台模拟器和固定限速。[单档起播分支](../../app/src/main/kotlin/com/r34v/r34v/PlayerActivity.kt)

## HLS 会话回拖缓存复测

以 `1be07fb` 的单档直接起播为基线，继续使用原始 36 秒测试片和 Android 16 模拟器。限速约 1.64 Mbps，每请求延迟 120 ms 发出响应头。保持同一播放配置，比较缓存关闭、32 MiB 会话缓存、现有 512 MiB 全局流式缓存三组。每组预热一次后测 5 次，正序和逆序交替。每轮使用新作品 ID，应用带宽估值置为未知。

会话缓存写入已结束播放列表的媒体数据。Media3 按请求类型分别处理媒体、播放列表和密钥。完整 URI（含查询参数）与字节范围都参与缓存身份判定。已下载的片段及已有的流式缓存优先使用。开启全局流式缓存时使用原有缓存路径。会话关闭后等待正在使用的缓存数据源关闭，再移除本会话的缓存。已提交的 HLS 数据由独立的 32 MiB LRU 限制，每个正在写入的缓存文件最多 2 MiB。播放器重启后可清理前一进程残留的会话数据。[Media3 请求类型](https://github.com/androidx/media/blob/1.9.2/libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/HlsDataSourceFactory.java) · [Media3 LRU](https://github.com/androidx/media/blob/1.9.2/libraries/datasource/src/main/java/androidx/media3/datasource/cache/LeastRecentlyUsedCacheEvictor.java)

沿用同一个回拖路径（2.7 秒跳到 22 秒，播放到 22.5 秒后跳回 1.2 秒），回拖后继续播到 5 秒再退出。缓存命中按读取 `segment00.ts` 的轮次统计，同时记录该分片的 HTTP 重读。写入开销以退出前提交的缓存文件字节数和次数计，包含正常取消加载时提交的部分分片。该计数反映缓存数据写入量，文件系统及 SQLite 元数据、设备物理写入和 `fsync` 延迟另行测量。

15 次有效样本。耗时和写入量为中位数（最小–最大）。

| HLS 配置 | 首次出画 ms | 回拖恢复 ms | 目标分片命中轮次 | 重读目标分片轮次 | 提交字节数 | 提交次数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 缓存关闭 | 1140（1072–1269） | 662（629–664） | 0/5 | 5/5 | 0 | 0 |
| 32 MiB 会话缓存 | 1090（1063–1345） | 410（398–476） | 5/5 | 0/5 | 1,803,836（1,762,876–1,814,200） | 11（10–11） |
| 512 MiB 全局流式缓存 | 1144（1017–1263） | 439（398–516） | 5/5 | 0/5 | 1,763,547（1,573,855–1,763,547） | 11（9–11） |

会话缓存使回拖中位数缩短 252 ms（38%），5 轮均在退出后清除会话分片。三组首帧范围重叠。会话缓存与全局流式缓存的短片写入量同量级，分别为 1.72 MiB 和 1.68 MiB。会话缓存组首帧前尚未提交完整缓存段，全局组每轮均已提交 671 B 的播放列表；两组首帧前都可能已写入尚未提交的媒体数据。这组数字适用于该点播样本及模拟限速，长期播放的写入量取决于实际媒体码率和读取范围。

Android 16 另通过容量淘汰、字节范围和 URI 隔离、并发会话关闭、直播列表绕过、离线 HLS 播放及损坏分片重新下载回归。[缓存边界用例](../../app/src/androidTest/kotlin/com/r34v/r34v/HlsSeekSessionTest.kt) · [离线 HLS 回归](../../app/src/androidTest/kotlin/com/r34v/r34v/HlsPlaybackIntegrationTest.kt)

## 1080p 与真实 CDN 复测

基线 `58b59ce`。在同一台 Android 16 模拟器上固定使用 Mux 公共测试流 Big Buck Bunny 的 1920 × 1080 HLS 点播档，主清单标记 6.22 Mbps。每轮都从同一公开 HTTPS URL 读取点播清单和原始分片，播放时核对解码尺寸。详情页由本地夹具生成，仍走应用的 KVS 解析器。测试关闭 HTTP 响应缓存、全局流式缓存和自动下载。[Mux 测试流目录](https://test-streams.mux.dev/) · [1080p 变体所在主清单](https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8)

该档共有 64 个分片，前两个各 10 秒，分别为 5,312,128 B 和 13,184,440 B，合计约 7.40 Mbps。实际响应经 HTTP/2 由香港 CDN 节点提供。日志分别记录 CDN 响应的 `X-Cache` 和应用会话缓存读取。

- 原链路使用设备连接到 CDN 的实际传输。弱网组在相同 CDN 连接上将媒体响应体交付速率限制到每请求 4.00 Mbps，保留 TLS、请求延迟和 CDN 响应。限速施加在播放器读取侧，未注入丢包。
- 缓存开与关使用同一片源及播放步骤。两组先预热原链路，再交替执行原链路和弱网各 3 轮。每轮进入新作品会话，首帧计到 Media3 实际渲染。再缓冲计实际播放到 16 秒期间的次数和总时间。
- 从 16 秒跳到缓冲区外的 72 秒，播到 72.5 秒后回拖至 1.2 秒，计到目标附近首帧。记录回拖前缓存里首分片的字节数、实际读取的缓存段、回拖到出画的同分片 HTTP 请求，以及回拖前淘汰次数。回拖后播到 5 秒退出，写入量为当轮缓存文件累计提交字节及次数，含退出时提交的部分文件。

12 次有效样本，每组 3 次。耗时和写入量为中位数（最小–最大）。

| 链路与会话缓存 | 首次出画 ms | 再缓冲次数 / 总时长 ms | 回拖恢复 ms | 目标分片命中 / HTTP 重读 | 累计写入 B / 提交次数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 原链路 · 关闭 | 1464（1251–1490） | 0 / 0 | 2812（2392–2846） | 0/3 · 3/3 | 0 / 0 |
| 原链路 · 开启 | 3049（1322–3234） | 0（0–1） / 0（0–11502） | 2692（2559–3173） | 0/3 · 3/3 | 79,310,929（63,467,080–79,683,290） / 44（35–44） |
| 4.00 Mbps · 关闭 | 1155（1105–3098） | 6 / 12639（12528–12708） | 1208（1022–1328） | 0/3 · 3/3 | 0 / 0 |
| 4.00 Mbps · 开启 | 1311（1290–1330） | 6 / 12490（12331–12581） | 1083（1023–1232） | 3/3 · 0/3 | 19,771,008（19,600,552–19,893,888） / 12 |

- **原链路达到容量压力。** 三轮开启缓存时，回拖前均淘汰了首分片，已提交留存约 29.6 MiB，累计写入中位数为 75.64 MiB。回拖耗时范围重叠。开启组的首帧中位数高 1.59 秒，并有一轮发生 11.50 秒再缓冲。这个样本量只能记录差异，首帧和再缓冲仍需分段计时确认原因。
- **限速时保住了回拖目标。** 开启组回拖前留存约 16.2 MiB，三轮都读到完整的 5,312,128 B 首分片，累计写入中位数为 18.86 MiB。两组回拖时间范围重叠，连续播放再缓冲均为 6 次。

下一轮用同一片源对照限制预读写入、给已播数据保留额度两种做法。沿用 32 MiB 容量，比较首帧、弱网再缓冲、目标分片复用和累计写入。首帧按详情、播放列表、首分片获取和出画分段记录。本轮数据来自公共样片、单台模拟器和一个 CDN 边缘节点。

## 复测

在仓库根目录用 PowerShell 执行，需 PATH 中有 `ffmpeg`、`adb`，并连接专用测试设备。设置 serial 后执行。启动时读取 `playback_probe` 目录内的本地文件，默认测试套件会跳过此项。[设备测量用例](../../app/src/androidTest/kotlin/com/r34v/r34v/PlaybackPerformanceProbe.kt)

```powershell
$serial = 'emulator-5554'
$media = New-Item -ItemType Directory -Path (Join-Path $env:TEMP "r34v-playback-$([guid]::NewGuid().ToString('N'))")
$mediaPath = $media.FullName
ffmpeg -v error -stream_loop -1 -i app/src/androidTest/assets/test-pattern.mp4 -t 36 `
  -vf scale=640:360 -c:v libx264 -preset fast -b:v 650k -maxrate 800k -bufsize 1300k `
  -g 48 -keyint_min 48 -sc_threshold 0 -c:a aac -b:a 96k -movflags +faststart "$mediaPath/source.mp4"
ffmpeg -v error -i "$mediaPath/source.mp4" -map 0:v:0 -map 0:a:0 -c copy -f hls `
  -hls_time 2 -hls_list_size 0 -hls_playlist_type vod -hls_segment_filename "$mediaPath/segment%02d.ts" "$mediaPath/index.m3u8"

.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s $serial install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$appId = 'com.r34v.r34v.debug'
$staging = "/data/local/tmp/$($media.Name)"
$destination = "/data/user/0/$appId/files/playback_probe"
adb -s $serial push "$mediaPath/." $staging
adb -s $serial shell "run-as $appId mkdir -p $destination"
Get-ChildItem -LiteralPath $mediaPath -File | ForEach-Object {
  $fileName = $_.Name
  adb -s $serial shell "cat $staging/$fileName | run-as $appId tee $destination/$fileName > /dev/null"
  $size = adb -s $serial shell "run-as $appId stat -c %s $destination/$fileName"
  if ([long]$size -ne $_.Length) { throw "Incomplete fixture $fileName" }
}
adb -s $serial shell am instrument -w -r -e class com.r34v.r34v.PlaybackPerformanceProbe `
  -e playbackPerf true -e playbackPerfRepeats 5 com.r34v.r34v.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s $serial logcat -d -s R34VPlaybackPerf:I
```

沿用同一套媒体和安装步骤，运行首帧及弱网对照用例。将应用包换成 `7abc44f` 的开发包时，在 `am instrument` 参数中加入 `-e playbackPerfExpectSingleProbe true`，用同一个测试包测改动前的数据。

```powershell
adb -s $serial shell am instrument -w -r -e class com.r34v.r34v.PlaybackPerformanceProbe `
  -e playbackPerf true -e playbackPerfScenario startup -e playbackPerfRepeats 3 `
  com.r34v.r34v.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s $serial logcat -d -s R34VSingleStart:I
```

HLS 缓存开关对照沿用同一片源和安装步骤，日志包含每轮首帧、回拖、目标分片命中和写入量。

```powershell
adb -s $serial shell am instrument -w -r -e class com.r34v.r34v.PlaybackPerformanceProbe `
  -e playbackPerf true -e playbackPerfScenario hls-cache -e playbackPerfRepeats 5 `
  com.r34v.r34v.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s $serial logcat -d -s R34VHlsSeek:I
```

真实 CDN 复测使用同样的开发包及测试包，媒体由设备连接公共测试 CDN 获取。

```powershell
adb -s $serial shell am instrument -w -r -e class com.r34v.r34v.PlaybackPerformanceProbe `
  -e playbackPerf true -e playbackPerfScenario cdn-high -e playbackPerfRepeats 3 `
  com.r34v.r34v.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s $serial logcat -d -s R34VCdn:I R34VCdnPerf:I
```
