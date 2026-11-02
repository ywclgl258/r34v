import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/video_cache.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tmp;
  late HttpServer server;
  late VideoCache cache;
  HttpOverrides? previousOverrides;

  setUp(() async {
    // flutter_test 默认把所有 HTTP 请求替换成 400；本组使用本机回环服务器。
    previousOverrides = HttpOverrides.current;
    HttpOverrides.global = null;
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    tmp = await Directory.systemTemp.createTemp('r34v_resume_test');
    cache = VideoCache(prefs, cacheDirectory: tmp);
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  });

  tearDown(() async {
    await server.close(force: true);
    HttpOverrides.global = previousOverrides;
    if (await tmp.exists()) await tmp.delete(recursive: true);
  });

  String url() => 'http://${server.address.host}:${server.port}/video.mp4';

  test('206 续传按完整文件长度校验并保留原数据', () async {
    final prefix = List<int>.generate(70 * 1024, (i) => i % 251);
    final suffix = List<int>.generate(1024, (i) => (i + 17) % 251);
    final part = File('${tmp.path}/resume_720p.mp4.part');
    await part.writeAsBytes(prefix);

    server.listen((req) async {
      expect(
        req.headers.value(HttpHeaders.rangeHeader),
        'bytes=${prefix.length}-',
      );
      req.response.statusCode = HttpStatus.partialContent;
      req.response.headers.set(
        HttpHeaders.contentRangeHeader,
        'bytes ${prefix.length}-${prefix.length + suffix.length - 1}/${prefix.length + suffix.length}',
      );
      req.response.contentLength = suffix.length;
      req.response.add(suffix);
      await req.response.close();
    });

    final entry = await cache.cache(
      videoId: 'resume',
      title: 'resume',
      url: url(),
      quality: '720p',
    );

    expect(entry.bytes, prefix.length + suffix.length);
    expect(await File(entry.filePath).readAsBytes(), [...prefix, ...suffix]);
    expect(await part.exists(), isFalse);
    expect(cache.isCached('resume'), isTrue);
  });

  test('短响应不写正式索引并保留 part 供下次恢复', () async {
    server.listen((req) async {
      req.response.contentLength = 100;
      final socket = await req.response.detachSocket();
      socket.add(List<int>.filled(10, 7));
      await socket.flush();
      await socket.close();
    });

    await expectLater(
      cache.cache(
        videoId: 'short',
        title: 'short',
        url: url(),
        quality: '720p',
      ),
      throwsException,
    );

    final part = File('${tmp.path}/short_720p.mp4.part');
    expect(await part.exists(), isTrue);
    expect(await part.length(), 10);
    expect(cache.find('short'), isNull);
  });

  test('服务器忽略 Range 返回 200 时覆盖旧 part', () async {
    final part = File('${tmp.path}/ignored_720p.mp4.part');
    final stale = List<int>.filled(70 * 1024, 9);
    await part.writeAsBytes(stale);
    final complete = [1, 2, 3, 4, 5];

    server.listen((req) async {
      expect(
        req.headers.value(HttpHeaders.rangeHeader),
        'bytes=${stale.length}-',
      );
      req.response.statusCode = HttpStatus.ok;
      req.response.contentLength = complete.length;
      req.response.add(complete);
      await req.response.close();
    });

    final entry = await cache.cache(
      videoId: 'ignored',
      title: 'ignored',
      url: url(),
      quality: '720p',
    );

    expect(entry.bytes, complete.length);
    expect(await File(entry.filePath).readAsBytes(), complete);
  });

  test('用户取消后保留已接收的 part', () async {
    final token = CancelToken();
    server.listen((req) async {
      req.response.contentLength = 200 * 1024;
      req.response.add(List<int>.filled(70 * 1024, 3));
      await req.response.flush();
      Timer(const Duration(milliseconds: 50), token.cancel);
      await Future<void>.delayed(const Duration(milliseconds: 200));
      try {
        await req.response.close();
      } catch (_) {}
    });

    await expectLater(
      cache.cache(
        videoId: 'cancel',
        title: 'cancel',
        url: url(),
        quality: '720p',
        cancelToken: token,
      ),
      throwsException,
    );

    expect(await File('${tmp.path}/cancel_720p.mp4.part').exists(), isTrue);
    expect(cache.find('cancel'), isNull);
  });
}
