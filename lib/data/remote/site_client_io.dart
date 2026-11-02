import 'dart:async';
import 'dart:convert';
import 'dart:io' as io;

import 'package:shared_preferences/shared_preferences.dart';

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
    this.useDirectIp = true,
    this.connector,
    this._proxy,
    this._prefs,
  })  : _timeout = timeout ?? const Duration(seconds: 20),
        _retryBaseDelay = retryBaseDelay ?? const Duration(milliseconds: 600) {
    _loadCookies();
  }

  final Duration _timeout;
  final int maxRetries;
  final Duration _retryBaseDelay;

  /// 是否允许用 DoH 解析出的 IP 直连站点。
  ///
  /// 关掉后只用代理/系统 DNS。
  final bool useDirectIp;

  /// 用户配置的代理（形如 `127.0.0.1:7897`）。非空时优先走代理。
  ///
  /// 实测：代理 10/10 成功、0.3–1.5 秒；直连只有 1/8 能拿到完整正文。
  /// 所以有代理就先用代理，别为了"直连"把好好的链路丢掉。
  final String? _proxy;

  /// DoH 解析器。用来拿当下的真实 IP。
  final DnsOverHttpsConnector? connector;

  /// Cookie：host → name → cookie。
  ///
  /// 存的是带属性的 [DomainCookie]，不再是裸字符串——只有这样才能判断过期。
  final Map<String, Map<String, DomainCookie>> _cookies = {};

  /// 持久化用。为 null 时退化成纯内存（测试环境常见）。
  final SharedPreferences? _prefs;

  static const String _kCookies = 'site_cookies_v1';

  final List<String> _log = [];

  @override
  List<String> get log => List.unmodifiable(_log);

  @override
  void dispose() {
    _cookies.clear();
    _log.clear();
  }

  // ---------- Cookie 持久化 ----------

  /// 冷启动时读回上次的 cookie。
  ///
  /// 为什么要持久化：ddos-guard 的 `__ddg1_` 有效期是**一年**。
  /// 以前 cookie 只在内存里，退出 App 就全丢，下次冷启动又得从零开始被
  /// 挑战一次。持久化之后，正常使用几乎不会再撞挑战页。
  void _loadCookies() {
    final prefs = _prefs;
    if (prefs == null) return;
    final raw = prefs.getString(_kCookies);
    if (raw == null || raw.isEmpty) return;
    try {
      final data = jsonDecode(raw) as Map<String, dynamic>;
      final now = DateTime.now();
      for (final entry in data.entries) {
        final host = entry.key;
        final list = entry.value;
        if (list is! List) continue;
        final jar = <String, DomainCookie>{};
        for (final item in list) {
          if (item is! Map) continue;
          final c = DomainCookie.fromJson(Map<String, dynamic>.from(item));
          if (c == null) continue;
          // 载入时就丢掉过期的，免得带着死 cookie 出门
          if (c.isExpired(now)) continue;
          jar[c.name] = c;
        }
        if (jar.isNotEmpty) _cookies[host] = jar;
      }
    } catch (_) {
      // 格式变了就重来，不值得为 cookie 崩一次
      _cookies.clear();
    }
  }

  void _saveCookies() {
    final prefs = _prefs;
    if (prefs == null) return;
    try {
      final now = DateTime.now();
      final data = <String, dynamic>{};
      _cookies.forEach((host, jar) {
        jar.removeWhere((_, c) => c.isExpired(now));
        if (jar.isEmpty) return;
        data[host] = jar.values.map((c) => c.toJson()).toList();
      });
      unawaited(prefs.setString(_kCookies, jsonEncode(data)));
    } catch (_) {}
  }

  void _record(String line) {    final t = DateTime.now();
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
    final onSite = _isSiteHost(target.host);

    // 站点自身的域名，按「当前实际可用路径」排序候选：
    //
    // 1. 用户配了代理 → 代理优先。实测代理 10/10 成功、0.3–1.5s；
    //    而去掉代理直连只有 1/8 能拿到正文（能连上、能握手，但传正文
    //    阶段大量 No stream event）。
    // 2. 没配代理 → 直连优先，用 DoH 解析出的 IP（走 TCP→IP、TLS→域名）。
    // 3. 都失败 → 换 DoH 的其它 IP，最后退回系统 DNS。
    //
    // 注意这里**不再把 URL 的 host 换成 IP**。那样做 TLS 必然失败：
    // host 是 IP 时 SNI 也是 IP，而证书是 /CN=rule34video.com。
    // 正确做法见 [_openViaIp]：URI 保持域名，只在 socket 层指定 IP。
    final steps = <_Attempt>[];

    if (onSite) {
      final hasProxy = (_proxy ?? '').trim().isNotEmpty;
      if (hasProxy) {
        steps.add(const _Attempt.proxy());
      }
      if (useDirectIp) {
        String? ip;
        if (connector != null) {
          ip = await connector!.resolveSiteIp();
        }
        final list = <String>[
          ?ip,
          ...kBuiltinEndpoints.map((e) => e.ip).where((e) => e != ip),
        ];
        for (final one in list) {
          steps.add(_Attempt.ip(one));
        }
      }
      if (!hasProxy) steps.add(const _Attempt.direct());
    } else {
      // 非站点域名（CDN、DoH 端点等）一律走系统 DNS，不替换 IP。
      steps.add(const _Attempt.direct());
    }

    Object? lastError;
    var triedIp = false;
    for (var i = 0; i < steps.length; i++) {
      final step = steps[i];
      if (step.ip != null) triedIp = true;
      try {
        final r = await _requestVia(
          url,
          step,
          method: method,
          referer: referer,
          accept: accept,
          headers: headers,
        );
        if (step.ip != null) {
          _record('IP ${step.ip} 可用');
        }
        return r;
      } catch (e) {
        lastError = e;
        if (step.ip != null) {
          _record('IP ${step.ip} 失败：${_short(e)}');
        } else {
          _record('${step.label} 失败：${_short(e)}');
        }

        // **自动升级**：如果「站点直连 IP」这个开关是关的，而系统 DNS
        // 已经失败，就仍然试一次 DoH 解析出的 IP。
        //
        // 为什么必须这样：某些网络下系统 DNS 返回的是污染 IP（实测
        // 202.160.128.40 的 TCP 是 0/8，完全不通），这时「关掉直连 IP」
        // 等于把唯一能用的路也关掉，用户看到的只是反复 20 秒超时。
        // 开关的本意是「别用硬编码 IP」，不是「宁可连不上也不用 IP」。
        if (!triedIp && onSite && connector != null && i == steps.length - 1) {
          triedIp = true;
          _record('系统 DNS 不通，自动改用 DoH 解析的 IP 再试一次');
          try {
            final ip = await connector!.resolveSiteIp();
            if (ip != null) {
              final r = await _requestVia(
                url,
                _Attempt.ip(ip),
                method: method,
                referer: referer,
                accept: accept,
                headers: headers,
              );
              _record('IP $ip 可用（自动升级）');
              return r;
            }
          } catch (e2) {
            _record('自动升级也失败：${_short(e2)}');
          }
        }
      }
    }
    final e = lastError;
    if (e is HttpException) throw e;
    throw HttpException('请求失败：$e');
  }

  static String _short(Object e) {
    final s = '$e';
    return s.length > 80 ? '${s.substring(0, 80)}…' : s;
  }

  /// 只有站点自己的域名才走 IP 直连。图片/视频走各自 CDN，域名不同。
  bool _isSiteHost(String host) {
    final siteHost = Uri.parse(Site.base).host;
    return host == siteHost;
  }

  /// 真正发请求。
  ///
  /// **关键：`current` 始终保留原始域名。** 指定 IP 时只改底层 socket
  /// 的连接目标，URI 一个字都不动——所以 Host 头、SNI、证书校验、
  /// Cookie 域匹配全部自动正确，不需要任何手工补丁。
  Future<HttpResult> _requestVia(
    String url,
    _Attempt attempt, {
    required String method,
    String? referer,
    String accept = 'text/html',
    Map<String, String>? headers,
  }) async {
    final sw = Stopwatch()..start();
    final raw = io.HttpClient();
    // SDK 在 http_impl.dart 里有一句 assert(connectionTimeout != null)：
    // socket 超时且该值为 null 时会直接断言失败（看起来像神秘崩溃）。
    // 用了自定义 connectionFactory 就必须显式给值。
    raw.connectionTimeout = _timeout;

    if (attempt.kind == _AttemptKind.proxy) {
      final p = (_proxy ?? '').trim();
      if (p.isNotEmpty) {
        raw.findProxy = (_) => 'PROXY $p';
      }
    } else if (attempt.kind == _AttemptKind.ip) {
      raw.connectionFactory = (uri, proxyHost, proxyPort) async {
        // 走代理时不接管；HTTPS 代理要的是 CONNECT 域名。
        if (proxyHost != null) {
          final s = await io.Socket.connect(proxyHost, proxyPort ?? 443,
              timeout: _timeout);
          return io.ConnectionTask.fromSocket<io.Socket>(
              Future.value(s), () {});
        }
        final secure = await connectViaIp(
          attempt.ip!,
          uri.port == 0 ? 443 : uri.port,
          host: uri.host,
          connectTimeout: _timeout,
        );
        return io.ConnectionTask.fromSocket<io.SecureSocket>(
            Future.value(secure), () {});
      };
    } else {
      // 系统 DNS：不接管，但要显式声明直连，否则 HttpClient 会自动套用
      // 系统代理，那就不是"直连"了。
      raw.findProxy = (_) => 'DIRECT';
    }

    var current = Uri.parse(url);
    var redirects = 0;

    try {
      while (true) {
        final req = await raw.openUrl(method, current).timeout(_timeout);
        req.followRedirects = false;
        req.headers.set(io.HttpHeaders.userAgentHeader, Site.userAgent);
        req.headers.set(io.HttpHeaders.acceptHeader, accept);
        req.headers.set('accept-language', 'en-US,en;q=0.9');
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
            '${attempt.ip ?? attempt.label} $current');
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
    } on io.HandshakeException catch (e) {
      sw.stop();
      _record('TLS ${sw.elapsed.inMilliseconds}ms $url：${e.message}');
      throw HttpException('TLS 握手失败：${e.message}', url: url);
    } finally {
      raw.close(force: true);
    }
  }

  /// 组装 `Cookie` 请求头。
  ///
  /// **会跳过已过期的 cookie**，并顺手清掉它们。以前不看过期时间，
  /// 所有 cookie 永久留在内存里，等于把早就失效的值一直往外发。
  String _cookieHeader(String host) {
    final jar = _cookies[host];
    if (jar == null || jar.isEmpty) return '';
    final now = DateTime.now();
    jar.removeWhere((_, c) => c.isExpired(now));
    if (jar.isEmpty) return '';
    return jar.entries.map((e) => '${e.key}=${e.value}').join('; ');
  }

  /// 解析并保存 `Set-Cookie`。
  ///
  /// ## 为什么不能只取第一段
  ///
  /// 以前是 `line.split(';').first`，只留 `name=value`，把 **Path、
  /// Domain、Expires、HttpOnly、Secure 全部丢掉**。后果：
  ///
  /// - 不知道过期时间 → 失效的 cookie 一直被发出去
  /// - 本站的 ddos-guard cookie 里 `__ddg1_` 是一年、`__ddg8_` 是一小时，
  ///   混在一起当永久值处理，行为完全错乱
  ///
  /// 现在按 RFC 6265 把常见属性都解析出来，并据此判断是否过期。
  void _absorbCookies(io.HttpClientResponse res, String host) {
    final raw = res.headers[io.HttpHeaders.setCookieHeader];
    if (raw == null || raw.isEmpty) return;
    final jar = _cookies.putIfAbsent(host, () => <String, DomainCookie>{});

    for (final line in raw) {
      final parsed = DomainCookie.parse(line);
      if (parsed == null) continue;

      // Domain 属性：统一按当前请求主机归档。本站是同域站点，
      // 不做跨子域的精细匹配——那需要完整的 RFC 6265 域匹配算法，
      // 而这里只有一个域名，过度设计没有收益。
      final key = parsed.name;
      if (parsed.isExpired(DateTime.now())) {
        jar.remove(key);
      } else {
        jar[key] = parsed;
      }
      _saveCookies();
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

/// 一次请求尝试的方式。
enum _AttemptKind {
  /// 走系统 DNS（或系统代理）。
  direct,

  /// 显式走配置的代理。
  proxy,

  /// 指定 IP 建连，但 TLS 仍对域名。
  ip,
}

class _Attempt {
  const _Attempt.direct()
      : kind = _AttemptKind.direct,
        ip = null;

  const _Attempt.proxy()
      : kind = _AttemptKind.proxy,
        ip = null;

  const _Attempt.ip(String this.ip) : kind = _AttemptKind.ip;

  final _AttemptKind kind;
  final String? ip;

  String get label => switch (kind) {
        _AttemptKind.direct => '系统DNS',
        _AttemptKind.proxy => '代理',
        _AttemptKind.ip => 'IP',
      };
}

/// 平台工厂：原生实现。
SiteClient createSiteClient() => SiteClientImpl();

/// 一条 Cookie，按 RFC 6265 解析出会用到的属性。
///
/// 抽成独立类是为了能被单测覆盖——Cookie 解析是最容易悄悄出错的地方，
/// 而它的错误表现（"偶发要重新验证"）极难在真机上定位。
class DomainCookie {
  DomainCookie({
    required this.name,
    required this.value,
    this.expiresAt,
    this.path,
    this.domain,
    this.secure = false,
    this.httpOnly = false,
  });

  final String name;
  final String value;

  /// 过期时刻。null 表示**会话 cookie**（关掉浏览器/进程就没了）。
  final DateTime? expiresAt;

  final String? path;
  final String? domain;
  final bool secure;
  final bool httpOnly;

  /// 是否已过期。
  ///
  /// 会话 cookie（[expiresAt] 为 null）**不算过期**——它在本次进程内一直有效。
  /// 只有显式带了 `Expires` / `Max-Age` 且时间已过的才算。
  bool isExpired(DateTime now) {
    final at = expiresAt;
    if (at == null) return false;
    return !at.isAfter(now);
  }

  /// 序列化（用于持久化到 prefs）。
  Map<String, dynamic> toJson() => {
        'n': name,
        'v': value,
        if (expiresAt != null) 'e': expiresAt!.millisecondsSinceEpoch,
        if (path != null) 'p': path,
        if (domain != null) 'd': domain,
        if (secure) 's': 1,
        if (httpOnly) 'h': 1,
      };

  static DomainCookie? fromJson(Map<String, dynamic> m) {
    final n = m['n'] as String?;
    final v = m['v'] as String?;
    if (n == null || n.isEmpty || v == null) return null;
    final e = (m['e'] as num?)?.toInt();
    return DomainCookie(
      name: n,
      value: v,
      expiresAt: e == null ? null : DateTime.fromMillisecondsSinceEpoch(e),
      path: m['p'] as String?,
      domain: m['d'] as String?,
      secure: m['s'] == 1,
      httpOnly: m['h'] == 1,
    );
  }

  /// 解析一行 `Set-Cookie`。格式非法时返回 null。
  ///
  /// 支持：`<name>=<value>; Path=/; Domain=...; Expires=<HTTP-date>;`
  /// `Max-Age=<n>; Secure; HttpOnly`
  ///
  /// `Expires` 用 **RFC 1123 格式**（`Mon, 02 Nov 2026 00:36:19 GMT`），
  /// 这是站点实际用的格式；Dart 的 `HttpDate.parse` 正好能读。
  /// `Max-Age` 优先级高于 `Expires`（RFC 6265 §5.3）。
  static DomainCookie? parse(String line) {
    final parts = line.split(';');
    if (parts.isEmpty) return null;

    final first = parts.first;
    final eq = first.indexOf('=');
    if (eq <= 0) return null;
    final name = first.substring(0, eq).trim();
    final value = first.substring(eq + 1).trim();
    if (name.isEmpty) return null;

    DateTime? expiresAt;
    int? maxAge;
    String? path;
    String? domain;
    var secure = false;
    var httpOnly = false;

    for (var i = 1; i < parts.length; i++) {
      final seg = parts[i].trim();
      if (seg.isEmpty) continue;
      final eq2 = seg.indexOf('=');
      final key = (eq2 < 0 ? seg : seg.substring(0, eq2)).trim().toLowerCase();
      final val = eq2 < 0 ? '' : seg.substring(eq2 + 1).trim();

      switch (key) {
        case 'expires':
          // 解析失败就当没写（不退化成"立即过期"——那会误删有效 cookie）
          final parsed = _tryParseHttpDate(val);
          if (parsed != null) expiresAt = parsed;
        case 'max-age':
          maxAge = int.tryParse(val);
        case 'path':
          path = val;
        case 'domain':
          domain = val;
        case 'secure':
          secure = true;
        case 'httponly':
          httpOnly = true;
      }
    }

    // Max-Age 优先：<=0 表示立刻失效
    if (maxAge != null) {
      expiresAt = maxAge <= 0
          ? DateTime.fromMillisecondsSinceEpoch(0)
          : DateTime.now().add(Duration(seconds: maxAge));
    }

    return DomainCookie(
      name: name,
      value: value,
      expiresAt: expiresAt,
      path: path,
      domain: domain,
      secure: secure,
      httpOnly: httpOnly,
    );
  }

  /// 解析 HTTP 日期。ddos-guard 的格式是
  /// `Mon, 02 Nov 2026 00:36:19 GMT`。
  static DateTime? _tryParseHttpDate(String? raw) {
    final s = raw?.trim() ?? '';
    if (s.isEmpty) return null;
    try {
      return io.HttpDate.parse(s);
    } catch (_) {
      return null;
    }
  }
}














