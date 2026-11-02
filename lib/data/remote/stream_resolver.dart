import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import 'dio_proxy.dart';
import 'doh_connector.dart';

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
  StreamResolver({
    Dio? dio,
    String proxy = '',
    this.prefs,
    DnsOverHttpsConnector? connector,
  }) : _dio = dio ??
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
    // **必须传 connector**。这里请求的是站点的 `/get_file/...`，属于站点
    // 域名；系统 DNS 被污染时会连到死 IP，解析必然失败，于是调用方拿不到
    // CDN 直链、只能把原始 URL 交给播放器——而播放器同样走系统 DNS，
    // 结果就是「Failed to open」。
    applyProxyToDio(_dio, proxy, connector: connector);
    _loadFromDisk();
  }

  final Dio _dio;

  /// 持久化用。为 null 时退化成纯内存缓存（测试环境常见）。
  final SharedPreferences? prefs;

  /// 磁盘缓存的 key 前缀。
  static const String _diskKey = 'stream_resolver_v1';

  /// 磁盘里最多存多少条。CDN 直链在 token 过期后就没用了，
  /// 留太多只是占地方，还让读写变慢。
  static const int _maxDiskEntries = 120;

  /// 缓存：key 是「站点原始地址」，value 是解析出来的 CDN 地址。
  ///
  /// 用原始地址做 key 而不是 videoId，是因为原始地址里已经含 `v-acctoken`，
  /// 换清晰度、换链都会变成不同的 key，天然不会串。
  final Map<String, _Resolved> _cache = {};

  /// CDN 直链的有效期。
  ///
  /// 实测 CDN 的 `acctoken` 带时间戳（`...|1793623019|2840000|...`），
  /// 但**没有可靠的过期字段**。取 30 分钟是基于实测：
  ///
  /// - 同一链接连续取用（含 50MB 偏移的远端区间）稳定 206；
  /// - CDN 层不校验 Referer / UA / Cookie，所以不存在「换了 client 就失效」；
  /// - 太短会频繁回源（回源 = 打详情页，是最容易被限流的动作），
  ///   太长则过期后白等一次失败再重试。
  ///
  /// 过期后 [resolve] 会重新解析，调用方不需要处理。
  static const Duration ttl = Duration(minutes: 30);

  /// 解析出 CDN 直链。失败时返回 null（调用方回退到原始地址）。
  ///
  /// [pageUrl] 是视频详情页地址，用作 Referer——**站点那一层**需要它
  /// （实测外站 Referer 直接 403）。CDN 那一层不校验，但带上无害。
  Future<String?> resolve(String url, {required String pageUrl}) async {
    final hit = _cache[url];
    if (hit != null && !hit.isExpired) return hit.cdnUrl;

    final cdn = await _probe(url, pageUrl: pageUrl);
    if (cdn == null) return null;

    _cache[url] = _Resolved(cdn, DateTime.now());
    _saveToDisk();
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
  void invalidate(String url) {
    if (_cache.remove(url) != null) _saveToDisk();
  }

  void clear() {
    _cache.clear();
    _saveToDisk();
  }

  // ---------- 磁盘持久化 ----------

  void _loadFromDisk() {
    final prefs = this.prefs;
    if (prefs == null) return;
    final raw = prefs.getString(_diskKey);
    if (raw == null || raw.isEmpty) return;
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      for (final e in list) {
        if (e is! Map) continue;
        final url = e['u'] as String? ?? '';
        final cdn = e['c'] as String? ?? '';
        final at = (e['t'] as num?)?.toInt() ?? 0;
        if (url.isEmpty || cdn.isEmpty || at <= 0) continue;
        final resolved = _Resolved(cdn, DateTime.fromMillisecondsSinceEpoch(at));
        // 载入时就丢掉过期的，免得占着位置
        if (resolved.isExpired) continue;
        _cache[url] = resolved;
      }
    } catch (_) {
      // 格式变了就重来，不值得为缓存崩一次
      _cache.clear();
    }
  }

  void _saveToDisk() {
    final prefs = this.prefs;
    if (prefs == null) return;
    try {
      // 先清掉过期的，再按时间倒序只留最近 N 条
      _cache.removeWhere((_, v) => v.isExpired);
      final entries = _cache.entries.toList()
        ..sort((a, b) => b.value.at.compareTo(a.value.at));
      final keep = entries.take(_maxDiskEntries);
      final list = [
        for (final e in keep)
          {
            'u': e.key,
            'c': e.value.cdnUrl,
            't': e.value.at.millisecondsSinceEpoch,
          },
      ];
      unawaited(prefs.setString(_diskKey, jsonEncode(list)));
    } catch (_) {}
  }

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


