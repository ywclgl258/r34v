import 'dart:async';

/// 一次 HTTP 响应。平台无关。
class HttpResult {
  const HttpResult({
    required this.status,
    required this.body,
    required this.url,
    required this.elapsed,
  });

  final int status;
  final String body;
  final String url;
  final Duration elapsed;

  bool get ok => status >= 200 && status < 300;

  /// 直链过期或权限被拒。直链带 `v-acctoken`，过期时站点给 401/403/410。
  bool get isTokenExpired => status == 401 || status == 403 || status == 410;

  /// 被 ddos-guard 拦下来的特征：正常页面是完整 HTML（150KB+），
  /// 挑战页通常很短且带 `__ddg` / 「checking your browser」这类字样。
  bool get looksLikeChallenge {
    if (body.length > 20000) return false;
    final b = body.toLowerCase();
    return b.contains('ddos-guard') ||
        b.contains('__ddg') ||
        b.contains('checking your browser') ||
        b.contains('please enable javascript');
  }

  @override
  String toString() => 'HttpResult($status, ${body.length}B, ${elapsed.inMilliseconds}ms)';
}

/// 网络层异常。
class HttpException implements Exception {
  HttpException(this.message, {this.status, this.url});

  final String message;
  final int? status;
  final String? url;

  @override
  String toString() => 'HttpException: $message${status == null ? "" : " (HTTP $status)"}';
}

/// 站点 HTTP 客户端要满足的行为契约。
///
/// 之所以抽成抽象类：`dart:io` 在浏览器里不存在。
/// - 原生（Android/Windows）：走 `site_client_io.dart`，能完整控制 UA、Range、Cookie；
/// - Web 预览：走 `site_client_web.dart`，改用 `package:web` 的 fetch。
///
/// 两者对外行为一致，上层（解析、UI）不需要知道跑在哪个平台。
abstract class SiteClient {
  /// GET 一个 HTML 页面。
  /// [headers] 用于附加请求头（例如登录后的 Cookie）。
  Future<HttpResult> getHtml(
    String url, {
    String? referer,
    Map<String, String>? headers,
  });

  /// 探测直链是否还能用。只取 1 字节，不下载整个视频。
  Future<bool> isStreamAlive(String url);

  /// 请求日志（最近 50 条），给设置页的诊断区看。
  List<String> get log;

  /// 释放资源。
  void dispose();
}

