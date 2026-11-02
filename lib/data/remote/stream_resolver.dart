import 'dart:async';

import 'package:dio/dio.dart';

import '../../core/constants.dart';
import 'dio_proxy.dart';

/// 把站点的 `/get_file/...` 地址解析成 **CDN 直链**。
///
/// ## 为什么必须做这件事
///
/// 站点不直接吐视频字节，而是先回一个 302：
///
/// ```
/// GET https://rule34video.com/get_file/58/<hash>/4706545/4706545_1080p.mp4/?v-acctoken=...
///   → 302 Location: https://eu-cdn10-prem.boomio-cdn.com/remote_control.php?file=...&acctoken=...
/// ```
///
/// 播放器（ExoPlayer / libmpv）拿到的是**第一段**地址。它每发一个 Range
/// 请求——首次加载、每次 seek、每次重连——都要重新经历这条 302 链路。
///
/// 实测代价（同一线路、同一文件、20MB）：
///
/// | 请求方式 | 耗时 | 吞吐 | TTFB |
/// |---|---|---|---|
/// | 经 rule34video.com（每次 302） | 23.6s | 887 KB/s | 3.04s |
/// | CDN 直链 | 6.0s | 3.48 MB/s | 0.92s |
///
/// **差 4 倍。** 这就是「浏览器缓冲很快、App 很卡」的全部原因：浏览器处理
/// 一次 302 之后就一直在用 CDN 直链，而播放器每次都要重走一遍。
///
/// ## 实测过的三个事实
///
/// 1. CDN 链接**可复用**：连续请求 3 次都是 206，远端区间
///    `bytes=100000000-` 也能取。所以解析一次就能喂给整个播放会话。
/// 2. CDN 上的 `acctoken` **带时间戳**（`...|1793623019|2840000|rule34video.com|...`），
///    所以不能永久缓存——这里按会话缓存，并把 TTL 设得保守些。
/// 3. 站点层有 **ddos-guard**（`Server: ddos-guard`）会下发 `__ddg*` cookie，
///    302 请求需要带上这些 cookie 才能继续。
///
/// ## 反盗链
///
/// 实测 Referer 判定：
///
/// | Referer | 结果 |
/// |---|---|
/// | 外站 `https://evil.example/` | **403** |
/// | 站点根 `https://rule34video.com/` | 302 正常 |
/// | 视频页 URL | 302 正常 |
///
/// 所以 Referer 必须带，而且要用**真实视频页 URL**——这才是浏览器的行为。
class StreamResolver {
  StreamResolver({Dio? dio, String proxy = ''})
      : _dio = dio ??
            Dio(BaseOptions(
              connectTimeout: const Duration(seconds: 15),
              receiveTimeout: const Duration(seconds: 20),
              // 只要响应头，不要 body
              headers: {
                'User-Agent': Site.userAgent,
                'Accept': '*/*',
                'Accept-Encoding': 'identity',
              },
              // 302 是我们要的结果，不能自动跟——跟了就看不到 Location 了
              followRedirects: false,
              validateStatus: (s) => s != null && s < 500,
            )) {
    applyProxyToDio(_dio, proxy);
  }

  final Dio _dio;

  /// 会话内缓存：key 是「站点原始地址」，value 是解析出来的 CDN 地址。
  ///
  /// 用原始地址做 key 而不是 videoId，是因为原始地址里已经含 `v-acctoken`，
  /// 换清晰度、换链都会变成不同的 key，天然不会串。
  final Map<String, _Resolved> _cache = {};

  /// CDN 直链的有效期。
  ///
  /// 实测 CDN 的 `acctoken` 带时间戳，保守取 10 分钟：一段视频的连续观看
  /// 基本都在这个窗口内，到期后重新解析一次即可。
  static const Duration ttl = Duration(minutes: 10);

  /// 解析出 CDN 直链。失败时返回 null（调用方回退到原始地址）。
  ///
  /// [pageUrl] 是视频详情页地址，用作 Referer——与浏览器一致。
  Future<String?> resolve(String url, {required String pageUrl}) async {
    final hit = _cache[url];
    if (hit != null && !hit.isExpired) return hit.cdnUrl;

    final cdn = await _probe(url, pageUrl: pageUrl);
    if (cdn == null) return null;

    _cache[url] = _Resolved(cdn, DateTime.now());
    return cdn;
  }

  /// 同步取已缓存的直链（没有就返回 null）。
  ///
  /// 给 seek / 重试用：这些路径上不该再等一次网络。
  String? cachedFor(String url) {
    final hit = _cache[url];
    if (hit == null || hit.isExpired) return null;
    return hit.cdnUrl;
  }

  /// 作废某个地址的缓存（CDN 返回 403 时调用）。
  void invalidate(String url) => _cache.remove(url);

  void clear() => _cache.clear();

  Future<String?> _probe(String url, {required String pageUrl}) async {
    try {
      // Range 只要 1 字节：把这次请求的流量压到最小。
      // 站点对 `bytes=0-0` 回 206 或 302 都能接受，我们只关心 Location。
      final res = await _dio.get<void>(
        url,
        options: Options(
          headers: {
            'Referer': pageUrl,
            'Range': 'bytes=0-0',
          },
        ),
      );

      final location = res.headers.value('location');
      if (location != null && location.isNotEmpty) {
        // 已经是绝对地址就直接用，相对地址补全。
        return location.startsWith('http')
            ? location
            : Uri.parse(url).resolve(location).toString();
      }

      // 没有 Location：说明这个地址本身就是最终地址（某些 CDN 或
      // 服务端不支持跳转时会直接回 200/206）。原样返回，等于不优化。
      if (res.statusCode == 200 || res.statusCode == 206) return null;

      // 403/401/410 等：交给调用方处理（可能需要换链）。
      return null;
    } catch (_) {
      return null;
    }
  }

  /// 把视频详情页路径转成完整 URL，用作 Referer。
  static String pageUrlFor(String videoId, String slug) =>
      '${Site.base}/video/$videoId/$slug/';
}

class _Resolved {
  _Resolved(this.cdnUrl, this.at);

  final String cdnUrl;
  final DateTime at;

  bool get isExpired =>
      DateTime.now().difference(at) > StreamResolver.ttl;
}
