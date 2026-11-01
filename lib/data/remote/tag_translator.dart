import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../local/tag_dictionary.dart';
import 'dio_proxy.dart';

/// 标签自动翻译（联网 + 本地缓存 + 内置词典兜底）。
///
/// 三级策略，按成本和可靠性排序：
/// 1. **内存缓存** —— 同一次会话里重复出现直接命中
/// 2. **本地磁盘缓存** —— 上次翻过的不用再请求（站点标签就那些，翻一次存很久）
/// 3. **内置词典** —— 常用词零延迟、离线可用
/// 4. **联网翻译** —— 前面都没有才发请求
///
/// 网络失败一律退回原文，不猜测、不显示占位符。
///
/// 用的是 Google 的免费翻译端点（`translate_a/single`，gtx 客户端）。
/// 它不需要 key、返回 JSON，是这个用途下最省事的选择。
/// 注意：这是非官方端点，随时可能变；所以词典和缓存都在，网络挂了也能用。
class TagTranslator {
  TagTranslator({required this.prefs, String proxy = '', Dio? dio}) {    _dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
      headers: {
        'User-Agent': Site.userAgent,
        'Accept': 'application/json,text/plain,*/*',
      },
    ));
    applyProxyToDio(_dio, proxy);
    if (dio != null) _dio = dio;
  }

  final SharedPreferences prefs;
  late Dio _dio;

  static const _endpoint = 'https://translate.googleapis.com/translate_a/single';
  static const _cachePrefix = 'tagzh_v1_';

  /// 会话内缓存。
  final Map<String, String> _mem = {};

  /// 正在请求中的，避免同一标签并发发多次。
  final Map<String, Future<String>> _inflight = {};

  /// 上次请求时间，用来做最小间隔。
  DateTime _lastRequest = DateTime.fromMillisecondsSinceEpoch(0);

  String _key(String slug) => '$_cachePrefix$slug';

  /// 已有结果（缓存或词典），没有返回 null。
  String? cached(String slug) {
    final m = _mem[slug];
    if (m != null && m.isNotEmpty) return m;
    final p = prefs.getString(_key(slug));
    if (p != null && p.isNotEmpty) {
      _mem[slug] = p;
      return p;
    }
    return null;
  }

  /// 取译名。返回空字符串表示没有译文（调用方显示原文）。
  Future<String> translate(String slug) {
    if (slug.isEmpty) return Future.value('');

    final hit = cached(slug);
    if (hit != null) return Future.value(hit);

    // 内置词典优先：零延迟、离线可用
    final dict = zhForTag(slug);
    if (dict.isNotEmpty) {
      _mem[slug] = dict;
      return Future.value(dict);
    }

    return _inflight.putIfAbsent(
      slug,
      // 必须块语法：箭头语法会把 remove 的返回值（Future）交给 whenComplete
      // 去 await，而那个 Future 就是当前这个请求本身——自己等自己，死锁。
      () => _fetch(slug).whenComplete(() {
        _inflight.remove(slug);
      }),
    );
  }

  Future<String> _fetch(String slug) async {
    try {
      // 最小间隔 120ms，避免一屏 20 个标签瞬间打出去被限流
      final since = DateTime.now().difference(_lastRequest);
      if (since < const Duration(milliseconds: 120)) {
        await Future<void>.delayed(const Duration(milliseconds: 120) - since);
      }
      _lastRequest = DateTime.now();

      final res = await _dio.get<String>(
        _endpoint,
        queryParameters: {
          'client': 'gtx',
          'sl': 'en',
          'tl': 'zh-CN',
          'dt': 't',
          // 连字符换空格，翻译质量明显更好（big-breasts → big breasts）
          'q': slug.replaceAll('-', ' '),
        },
        options: Options(responseType: ResponseType.plain),
      );

      final zh = parseGoogleResponse(res.data ?? '');
      if (zh.isEmpty) return '';

      await _remember(slug, zh);
      return zh;
    } catch (_) {
      // 网络/超时/被限流：退回原文
      return '';
    }
  }

  Future<void> _remember(String slug, String zh) async {
    _mem[slug] = zh;
    try {
      await prefs.setString(_key(slug), zh);
    } catch (_) {}
  }

  /// 批量预取：给一屏标签用，并发受控。
  ///
  /// 返回只包含成功拿到的项。
  Future<Map<String, String>> translateAll(
    List<String> slugs, {
    int limit = 40,
    int concurrency = 3,
  }) async {
    final out = <String, String>{};
    final todo = <String>[];
    for (final s in slugs) {
      if (s.isEmpty) continue;
      final hit = cached(s);
      if (hit != null) {
        out[s] = hit;
      } else {
        todo.add(s);
      }
      if (todo.length >= limit) break;
    }

    // 简单的工作池
    var index = 0;
    Future<void> worker() async {
      while (true) {
        final i = index++;
        if (i >= todo.length) return;
        final zh = await translate(todo[i]);
        if (zh.isNotEmpty) out[todo[i]] = zh;
      }
    }

    await Future.wait([for (var i = 0; i < concurrency; i++) worker()]);
    return out;
  }

  /// 解析 Google 翻译的返回：`[[["巨乳","big breasts",...]],...]`
  ///
  /// 抽成静态方法是为了能单独测——这段解析最容易因为端点微调而悄悄失效。
  static String parseGoogleResponse(String body) {
    final text = body.trim();
    if (text.isEmpty) return '';
    try {
      final data = jsonDecode(text);
      if (data is! List || data.isEmpty) return '';
      final segments = data[0];
      if (segments is! List) return '';
      final buf = StringBuffer();
      for (final seg in segments) {
        if (seg is! List || seg.isEmpty) continue;
        final piece = seg[0];
        if (piece is String) buf.write(piece);
      }
      return buf.toString().trim();
    } catch (_) {
      return '';
    }
  }
}
