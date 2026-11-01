import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/tag_translator.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 标签翻译：解析、缓存、兜底。
///
/// 核心诉求是「**一次性**」：同一个标签只联网翻译一次，结果落到本地，
/// 之后即使没网也能显示。这里用可注入的 Dio 计数，直接验证请求次数。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  /// 假 Dio：用自定义适配器直接返回响应，不碰真实网络。
  ///
  /// 用 adapter 而不是拦截器：拦截器 resolve 在部分 Dio 版本里不会
  /// 真正短路底层请求，测试会挂到超时。
  ({Dio dio, List<String> calls}) fakeDio(String body) {
    final calls = <String>[];
    final dio = Dio(BaseOptions(validateStatus: (_) => true));
    dio.httpClientAdapter = _FakeAdapter(body, calls);
    return (dio: dio, calls: calls);
  }

  group('解析 Google 翻译返回', () {
    test('正常响应', () {
      const body = '[[["巨乳","big breasts",null,null,10]],null,"en",null]';
      expect(TagTranslator.parseGoogleResponse(body), '巨乳');
    });

    test('多段拼接', () {
      const body = '[[["第一","first ",null],["人称","person",null]],null,"en"]';
      expect(TagTranslator.parseGoogleResponse(body), '第一人称');
    });

    test('空串/坏 JSON 返回空，不抛异常', () {
      expect(TagTranslator.parseGoogleResponse(''), '');
      expect(TagTranslator.parseGoogleResponse('   '), '');
      expect(TagTranslator.parseGoogleResponse('not json'), '');
      expect(TagTranslator.parseGoogleResponse('{}'), '');
      expect(TagTranslator.parseGoogleResponse('[]'), '');
      expect(TagTranslator.parseGoogleResponse('[[],null]'), '');
    });
  });

  group('一次性：翻过的不再请求', () {
    test('第一次联网，第二次走缓存（请求数保持 1）', () async {
      final f = fakeDio('[[["巨乳","big breasts",null]]]');
      final t = TagTranslator(prefs: prefs, dio: f.dio);

      final first = await t.translate('big-XXX-breasts');
      expect(first, '巨乳');
      expect(f.calls.length, 1);

      final second = await t.translate('big-XXX-breasts');
      expect(second, '巨乳');
      expect(f.calls.length, 1, reason: '第二次不该再发请求');
    });

    test('新建实例也能直接用磁盘缓存（等价于重启应用）', () async {
      final f = fakeDio('[[["巨乳","x",null]]]');
      final t1 = TagTranslator(prefs: prefs, dio: f.dio);
      await t1.translate('cache-probe-tag');
      expect(f.calls.length, 1);

      // 新实例 = 新的内存缓存，只能靠磁盘缓存命中
      final t2 = TagTranslator(prefs: prefs, dio: f.dio);
      final again = await t2.translate('cache-probe-tag');
      expect(again, '巨乳');
      expect(f.calls.length, 1, reason: '磁盘缓存命中后不该再联网');
    });

    test('词典收录的词根本不联网', () async {
      final f = fakeDio('[[["不该被用到","x",null]]]');
      final t = TagTranslator(prefs: prefs, dio: f.dio);

      final zh = await t.translate('anal');
      expect(zh, '肛交');
      expect(f.calls.length, 0, reason: '词典命中不该发请求');
    });

    test('同标签并发只发一次请求', () async {
      final f = fakeDio('[[["巨乳","x",null]]]');
      final t = TagTranslator(prefs: prefs, dio: f.dio);

      await Future.wait([
        t.translate('concurrent-probe'),
        t.translate('concurrent-probe'),
        t.translate('concurrent-probe'),
      ]);
      expect(f.calls.length, 1, reason: '并发重复请求应合并');
    });
  });

  group('兜底与容错', () {
    test('响应解析不出时返回空（界面显示原文）', () async {
      final f = fakeDio('garbage');
      final t = TagTranslator(prefs: prefs, dio: f.dio);
      expect(await t.translate('unparseable-tag'), '');
    });

    test('网络异常返回空，不抛到界面', () async {
      final dio = Dio();
      dio.interceptors.add(InterceptorsWrapper(
        onRequest: (options, handler) =>
            handler.reject(DioException(
          requestOptions: options,
          type: DioExceptionType.connectionError,
        )),
      ));
      final t = TagTranslator(prefs: prefs, dio: dio);
      expect(await t.translate('offline-tag'), '');
    });

    test('空 slug 返回空', () async {
      final t = TagTranslator(prefs: prefs);
      expect(await t.translate(''), '');
    });

    test('cached() 在没翻过时返回 null', () {
      final t = TagTranslator(prefs: prefs);
      expect(t.cached('never-seen-tag'), isNull);
    });
  });

  group('批量预取', () {
    test('只返回成功的项，重复项不重复请求', () async {
      final f = fakeDio('[[["测试","x",null]]]');
      final t = TagTranslator(prefs: prefs, dio: f.dio);

      final out = await t.translateAll(['batch-a', 'batch-a', 'batch-b']);
      expect(out.keys.toSet(), {'batch-a', 'batch-b'});
      expect(f.calls.length, 2, reason: '两个不同标签各一次，重复的不再请求');
    });

    test('limit 限制上限', () async {
      final f = fakeDio('[[["测试","x",null]]]');
      final t = TagTranslator(prefs: prefs, dio: f.dio);
      await t.translateAll(
        [for (var i = 0; i < 10; i++) 'limit-tag-$i'],
        limit: 3,
      );
      expect(f.calls.length, lessThanOrEqualTo(3));
    });
  });
}
/// 直接返回固定响应的适配器，并记录被请求的 URL。
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.body, this.calls);

  final String body;
  final List<String> calls;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    calls.add(options.uri.toString());
    return ResponseBody.fromString(
      body,
      200,
      headers: {
        Headers.contentTypeHeader: ['application/json; charset=utf-8'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}