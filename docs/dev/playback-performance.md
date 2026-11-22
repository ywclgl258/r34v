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

这一组数据适用于单档、固定速率的短点播样本。真实 CDN 及更长素材、HLS 多变体、网络抖动和持续播放再缓冲另行复测。

## 优化顺序

1. 单档直接尝试起播，把 1 MiB 同步测速移出这条路径。用同一矩阵确认实际播放档位一致、测速请求消失、首帧缩短，再补高码率单档弱网回归。多档继续按带宽选档，同时区分真实传输采样和网络类型的默认估值，HLS 的实测速率取自媒体分片。预跑在尚无媒体传输时观察到 2.40 Mbps 的初始估值，Media3 的网络类型回调可携带默认估值，当前 `LinkSpeed` 接受所有正值回调。[带宽估算源码](https://github.com/androidx/media/blob/1.9.2/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/upstream/DefaultBandwidthMeter.java#L389-L431) · [应用带宽估算](../../app/src/main/kotlin/com/r34v/r34v/playback/LinkSpeed.kt)
2. 为点播 HLS 验证有容量上限的单次会话回拖缓存，按作品和媒体资源 URI 隔离分片，保持全局流式缓存的现有默认设置。用默认 HLS 作为基线，复测目标分片重读、首帧和回拖耗时，并检查持续播放中的缓存写入开销。缓冲窗口沿用当前配置，便于比较。

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
