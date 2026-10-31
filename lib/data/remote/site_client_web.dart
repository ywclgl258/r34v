import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'site_client_base.dart';

/// Web 预览实现：用浏览器 fetch。
///
/// 存在的唯一理由是**本地预览**——本机不装 Android SDK，
/// 用 `flutter run -d chrome` 看界面。正式的安卓包用的是 io 版。
///
/// 已知限制（预览时正常，别当成 bug）：
/// 1. 浏览器的跨域规则可能拦下站点的直链与图片，表现为视频播不了、封面空着；
///    列表与详情的数据抓取通常没问题（站点没设 CORS 限制时）。
/// 2. 没法自定义 UA —— 浏览器不允许。站点对浏览器 UA 是放行的，所以不影响。
/// 3. 没法发 `Range`，直链探活退化成「能不能拿到 200」。
class SiteClientImpl implements SiteClient {
  SiteClientImpl({
    Duration? timeout,
    this.maxRetries = 3,
    Duration? retryBaseDelay,
  })  : _timeout = timeout ?? const Duration(seconds: 20),
        _retryBaseDelay = retryBaseDelay ?? const Duration(milliseconds: 600);

  final Duration _timeout;
  final int maxRetries;
  final Duration _retryBaseDelay;
  final List<String> _log = [];

  @override
  List<String> get log => List.unmodifiable(_log);

  @override
  void dispose() => _log.clear();

  void _record(String line) {
    final t = DateTime.now();
    String p(int v) => v.toString().padLeft(2, '0');
    _log.add('${p(t.hour)}:${p(t.minute)}:${p(t.second)}  $line');
    if (_log.length > 50) _log.removeAt(0);
  }

  @override
  Future<HttpResult> getHtml(String url, {String? referer}) {
    return _withRetry(() => _fetch(url));
  }

  @override
  Future<bool> isStreamAlive(String url) async {
    try {
      final r = await _fetch(url);
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

  Future<HttpResult> _fetch(String url) async {
    final sw = Stopwatch()..start();
    try {
      final res = await web.window
          .fetch(url.toJS, web.RequestInit(redirect: 'follow'))
          .toDart
          .timeout(_timeout);
      final text = (await res.text().toDart).toDart;
      sw.stop();

      final result = HttpResult(
        status: res.status,
        body: text,
        url: res.url.isEmpty ? url : res.url,
        elapsed: sw.elapsed,
      );
      _record('${result.status} ${result.body.length}B ${result.elapsed.inMilliseconds}ms '
          '${result.url}${_isCrossOriginHint(url) ? "（跨域限制可能生效）" : ""}');
      if (result.looksLikeChallenge) {
        _record('  ⚠ 疑似 ddos-guard 挑战页（${result.body.length}B）');
      }
      return result;
    } on TimeoutException {
      sw.stop();
      _record('TIMEOUT ${sw.elapsed.inMilliseconds}ms $url');
      throw HttpException('请求超时（${_timeout.inSeconds}s）', url: url);
    } catch (e) {
      sw.stop();
      _record('FETCH ERROR ${sw.elapsed.inMilliseconds}ms $url：$e');
      // 浏览器里跨域失败会统一报成 "Failed to fetch"，看不出真实原因，
      // 这里给一句人话，免得预览时一头雾水。
      throw HttpException(
        '请求失败（预览环境下常见原因是跨域限制）：$e',
        url: url,
      );
    }
  }

  /// 只用于日志提示：预览跑在 localhost，访问远端站点必然是跨域。
  bool _isCrossOriginHint(String url) => !url.startsWith('http://localhost');
}

/// 平台工厂：Web 预览实现。
SiteClient createSiteClient() => SiteClientImpl();

