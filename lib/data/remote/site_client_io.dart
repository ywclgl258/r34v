import 'dart:async';
import 'dart:convert';
import 'dart:io' as io;

import '../../core/constants.dart';
import 'doh_connector.dart';
import 'host_router.dart';
import 'site_client_base.dart';

/// 原生实现（Android / Windows）：直接用 `dart:io` 的 HttpClient。
///
/// 相比 Web 版，这里能：
/// - 完整设置 UA / Accept / Referer；
/// - 发 `Range: bytes=0-0` 探测直链而不下载；
/// - 保留站点下发的 Cookie（ddos-guard 的 `__ddg8_` / `__ddg10_`，
///   丢了容易被反复挑战）；
/// - 手动跟随 3xx 并保留最终 URL（详情页要靠它取 slug）。
class SiteClientImpl implements SiteClient {
  SiteClientImpl({
    Duration? timeout,
    this.maxRetries = 3,
    Duration? retryBaseDelay,
    HostRouter? router,
    this.connector,
  })  : _timeout = timeout ?? const Duration(seconds: 20),
        _retryBaseDelay = retryBaseDelay ?? const Duration(milliseconds: 600),
        _router = router ?? HostRouter();

  final Duration _timeout;
  final int maxRetries;
  final Duration _retryBaseDelay;

  /// 站点 IP 路由：首选一个 IP，其余兜底。
  ///
  /// 内置 IP 只是**候选**——站点换机房后必然过期，所以试完全部候选
  /// 就回退系统 DNS 正常解析。见 [HostRouter] 的说明。
  final HostRouter _router;

  /// DNS over HTTPS 连接器。
  ///
  /// 装上之后域名在 socket 层就被换成 DoH 查到的 IP，HTTP 层看到的
  /// 仍是原域名（Host / SNI / 证书校验全自动正确）。
  /// 为 null 时退回 [_router] 那套「换 IP + 补 Host 头」的旧逻辑。
  final DnsOverHttpsConnector? connector;

  /// Cookie：host → name → value。
  final Map<String, Map<String, String>> _cookies = {};

  final List<String> _log = [];

  @override
  List<String> get log => List.unmodifiable(_log);

  @override
  void dispose() {
    _cookies.clear();
    _log.clear();
  }

  void _record(String line) {
    final t = DateTime.now();
    String p(int v) => v.toString().padLeft(2, '0');
    _log.add('${p(t.hour)}:${p(t.minute)}:${p(t.second)}  $line');
    if (_log.length > 50) _log.removeAt(0);
  }

  @override
  Future<HttpResult> getHtml(
    String url, {
    String? referer,
    Map<String, String>? headers,
  }) {
    return _withRetry(
      () => _request(
        url,
        method: 'GET',
        referer: referer,
        accept: 'text/html,application/xhtml+xml',
        headers: headers,
      ),
    );
  }

  @override
  Future<bool> isStreamAlive(String url) async {
    try {
      final r = await _request(
        url,
        method: 'GET',
        accept: '*/*',
        headers: const {'range': 'bytes=0-0'},
      );
      return r.status == 200 || r.status == 206;
    } catch (_) {
      return false;
    }
  }

  Future<HttpResult> _withRetry(Future<HttpResult> Function() action) async {
    Object? lastError;
    for (var attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        final r = await action();
        // 5xx / 429 值得重试；4xx 是确定性错误，重试没用
        if (r.status >= 500 || r.status == 429) {
          lastError = HttpException('服务端错误', status: r.status, url: r.url);
          if (attempt == maxRetries) return r;
        } else {
          return r;
        }
      } catch (e) {
        lastError = e;
        if (attempt == maxRetries) break;
      }
      final delay = _retryBaseDelay * (1 << attempt);
      _record('retry ${attempt + 1}/$maxRetries 等 ${delay.inMilliseconds}ms：$lastError');
      await Future<void>.delayed(delay);
    }
    if (lastError is HttpException) throw lastError;
    throw HttpException('请求失败：$lastError');
  }

  Future<HttpResult> _request(
    String url, {
    required String method,
    String? referer,
    String accept = 'text/html',
    Map<String, String>? headers,
  }) async {
    final target = Uri.parse(url);

    // 站点自己的域名：先问 DoH 要一个当下可连的 IP。
    //
    // 走的是「换 IP + 补 Host 头」这条路——为什么不在 socket 层拦截，
    // 见 [DnsOverHttpsConnector] 的注释（`connectionFactory` 那条路
    // 因为 HttpClient 不再做 TLS、且 `ConnectionTask` 是 final 而走不通）。
    //
    // DoH 拿不到可用 IP 时返回 null，下面的候选循环会退到硬编码列表
    // 和系统 DNS。
    String? dohIp;
    if (connector != null && _isSiteHost(target.host)) {
      dohIp = await connector!.resolveSiteIp();
    }

    final apHost = dohIp ?? (_router.enabled ? _router.preferred : null);
    final candidates = <String?>[
      // 首选 IP；为 null 时这一项不参与，直接落到下面的系统 DNS
      ?apHost,
      // 同一站点域名的请求才值得换 IP；其它域（图片 CDN 等）直接走 DNS
      if (apHost != null && _isSiteHost(target.host)) ..._restAfter(apHost),
      null, // null = 系统 DNS
    ];

    Object? lastError;
    for (final ip in candidates) {
      try {
        final r = await _requestVia(url, ip,
            method: method, referer: referer, accept: accept, headers: headers);
        if (ip != null) _router.recordSuccess(ip);
        return r;
      } catch (e) {
        lastError = e;
        if (ip != null) _router.recordFailure(ip);
        _record('IP $ip 失败，换下一个：$e');
      }
    }
    final e = lastError;
    if (e is HttpException) throw e;
    throw HttpException('请求失败：$e');
  }

  /// 依次列出 [ip] 之后还能试的候选。
  List<String> _restAfter(String ip) {
    final out = <String>[];
    var cur = _router.nextAfter(ip);
    final guard = _router.endpoints.length + 1;
    var steps = 0;
    while (cur != null && steps < guard) {
      out.add(cur);
      cur = _router.nextAfter(cur);
      steps++;
    }
    return out;
  }

  /// 只有站点自己的域名才换 IP。图片/视频走各自 CDN，域名不同。
  bool _isSiteHost(String host) {
    final siteHost = Uri.parse(Site.base).host;
    return host == siteHost;
  }

  /// 真正发请求。[ipOverride] 为 null 时走系统 DNS。
  ///
  /// 换 IP 时**必须同时把 Host 头设回原域名**，否则站点会当成未知虚拟主机
  /// （因为 HTTPS 的 SNI 与 Host 都要匹配）。
  Future<HttpResult> _requestVia(
    String url,
    String? ipOverride, {
    required String method,
    String? referer,
    String accept = 'text/html',
    Map<String, String>? headers,
  }) async {
    final sw = Stopwatch()..start();
    final raw = io.HttpClient();
    // 换 IP 时 URI 的 host 会变成 IP，所以必须把 Host 头补回原域名，
    // 否则站点会当成未知虚拟主机（404/421）。
    final manualIp = ipOverride;
    var current = Uri.parse(url);
    var redirects = 0;

    try {
      while (true) {
        // 换 IP 只作用于**请求的主机名**，重定向后的新主机名不再替换
        // （重定向多半指向 CDN，用 IP 打过去只会失败）。
        final useIp = manualIp != null && current.host == Uri.parse(url).host;
        final target = useIp ? HostRouter.withIp(current, manualIp) : current;

        final req = await raw.openUrl(method, target).timeout(_timeout);
        req.followRedirects = false;
        req.headers.set(io.HttpHeaders.userAgentHeader, Site.userAgent);
        req.headers.set(io.HttpHeaders.acceptHeader, accept);
        req.headers.set('accept-language', 'en-US,en;q=0.9');
        if (useIp) {
          // Host 头保持原域名；HttpClient 会据此设置 SNI
          req.headers.set(io.HttpHeaders.hostHeader, current.host);
        }
        if (referer != null) req.headers.set(io.HttpHeaders.refererHeader, referer);
        headers?.forEach((k, v) => req.headers.set(k, v));
        final cookie = _cookieHeader(current.host);
        if (cookie.isNotEmpty) req.headers.set(io.HttpHeaders.cookieHeader, cookie);

        final res = await req.close().timeout(_timeout);
        _absorbCookies(res, current.host);

        // 手动跟随 3xx，保留最终 URL
        if (res.isRedirect && redirects < 5) {
          final loc = res.headers.value(io.HttpHeaders.locationHeader);
          if (loc != null && loc.isNotEmpty) {
            current = current.resolve(loc);
            redirects++;
            await res.drain<void>();
            continue;
          }
        }

        final bytes = await res
            .fold<List<int>>(<int>[], (acc, chunk) => acc..addAll(chunk))
            .timeout(_timeout);
        sw.stop();

        final body = _decode(bytes, res.headers.contentType);
        final result = HttpResult(
          status: res.statusCode,
          body: body,
          url: current.toString(),
          elapsed: sw.elapsed,
        );
        _record('${result.status} ${result.body.length}B '
            '${result.elapsed.inMilliseconds}ms '
            '${ipOverride ?? 'DNS'} $current');
        if (result.looksLikeChallenge) {
          _record('  ⚠ 疑似 ddos-guard 挑战页（${result.body.length}B）');
        }
        return result;
      }
    } on TimeoutException {
      sw.stop();
      _record('TIMEOUT ${sw.elapsed.inMilliseconds}ms $url');
      throw HttpException('请求超时（${_timeout.inSeconds}s）', url: url);
    } on io.SocketException catch (e) {
      sw.stop();
      _record('SOCKET ${sw.elapsed.inMilliseconds}ms $url：${e.message}');
      throw HttpException('网络不可用：${e.message}', url: url);
    } finally {
      raw.close(force: true);
    }
  }

  String _cookieHeader(String host) {
    final jar = _cookies[host];
    if (jar == null || jar.isEmpty) return '';
    return jar.entries.map((e) => '${e.key}=${e.value}').join('; ');
  }

  void _absorbCookies(io.HttpClientResponse res, String host) {
    final raw = res.headers[io.HttpHeaders.setCookieHeader];
    if (raw == null || raw.isEmpty) return;
    final jar = _cookies.putIfAbsent(host, () => <String, String>{});
    for (final line in raw) {
      final first = line.split(';').first;
      final eq = first.indexOf('=');
      if (eq <= 0) continue;
      final name = first.substring(0, eq).trim();
      final value = first.substring(eq + 1).trim();
      if (name.isNotEmpty) jar[name] = value;
    }
  }

  /// 站点实际编码是 UTF-8，但响应头偶尔缺 charset 或写错。
  /// 一律优先按 UTF-8 严格解码，失败才退回宽松模式——否则中日文标题会变乱码。
  String _decode(List<int> bytes, io.ContentType? ct) {
    final strict = _utf8OrNull(bytes);
    if (strict != null) return strict;
    return const Utf8Decoder(allowMalformed: true).convert(bytes);
  }

  String? _utf8OrNull(List<int> bytes) {
    try {
      return const Utf8Decoder(allowMalformed: false).convert(bytes);
    } catch (_) {
      return null;
    }
  }
}

/// 平台工厂：原生实现。
SiteClient createSiteClient() => SiteClientImpl();





