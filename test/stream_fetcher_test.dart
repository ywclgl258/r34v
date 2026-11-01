import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/stream_fetcher.dart';

/// 多线程分段下载 + 顺序组装的正确性。
///
/// 关键不变量：`.partial` 文件必须**严格按序**增长（播放器读它），
/// 并行下载的分段无论以什么顺序完成，组出来的文件字节必须和源完全一致。
/// 假服务端：支持 Range，从固定字节数组里切。
class FakeServer {
  FakeServer(this.bytes, {this.failRequest = -1});
  final Uint8List bytes;

  /// 第 N 个分段请求先回 403（测 token 续传）。
  final int failRequest;
  int rangeRequests = 0;
  final ranges = <String>[];

  Dio dio() {
    final d = Dio(BaseOptions(validateStatus: (_) => true));
    d.httpClientAdapter = _Adapter(this);
    return d;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tmpDir;

  setUpAll(() async {
    // path_provider 是平台通道，测试里拦掉，指向临时目录
    tmpDir = await Directory.systemTemp.createTemp('r34v_fetcher_test');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('plugins.flutter.io/path_provider'),
      (call) async {
        if (call.method == 'getApplicationCacheDirectory') {
          return tmpDir.path;
        }
        return null;
      },
    );
  });

  tearDownAll(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('plugins.flutter.io/path_provider'),
      null,
    );
    if (await tmpDir.exists()) await tmpDir.delete(recursive: true);
  });

  Future<Uint8List> runFetch(
    FakeServer server, {
    int chunkSize = 256,
    Future<String> Function()? refreshUrl,
  }) async {
    final fetcher = StreamFetcher(dio: server.dio());
    final events = <String>[];
    final handle = await fetcher.fetch(
      videoId: 'testvid',
      url: 'https://cdn.example.com/v.mp4',
      quality: '720p',
      chunkSizeOverride: chunkSize,
      refreshUrl: refreshUrl,
      onProgress: (received, total) => events.add('p:$received/$total'),
      onReady: (p) => events.add('ready'),
      onDone: (e) => events.add(e == null ? 'done' : 'err:$e'),
    );

    // 等完成
    final file = File(handle.filePath);
    for (var i = 0; i < 200; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
      if (events.any((e) => e == 'done' || e.startsWith('err'))) break;
    }
    expect(events.any((e) => e == 'done'), isTrue,
        reason: '应下载成功，事件: $events');
    expect(handle.complete, isTrue);

    final out = await file.readAsBytes();
    await file.parent.delete(recursive: true).catchError((_) => Directory(''));
    return out;
  }

  Uint8List makeBytes(int n) =>
      Uint8List.fromList(List<int>.generate(n, (i) => i % 251));

  test('分段并行下载后，组装的文件字节与源完全一致', () async {
    final src = makeBytes(1000);
    final server = FakeServer(src);
    final out = await runFetch(server, chunkSize: 100);
    expect(out, orderedEquals(src));
    // 确实走了分段（不止一个 range 请求）
    expect(server.rangeRequests, greaterThan(3));
  });

  test('分段按序追加（range 请求覆盖整个文件且无重叠遗漏）', () async {
    final src = makeBytes(1000);
    final server = FakeServer(src);
    await runFetch(server, chunkSize: 200);

    final spans = server.ranges
        .map((r) => r.split('-').map(int.parse).toList())
        .toList()
      ..sort((a, b) => a[0].compareTo(b[0]));
    // 去掉 403 重试产生的重复段：只验证「有序无洞」——
    // 每段起点 = 前一段终点+1
    var coveredUntil = -1;
    for (final s in spans) {
      if (s[0] <= coveredUntil) continue; // 重复段（重试）跳过
      expect(s[0], coveredUntil + 1, reason: '出现空洞或乱序: $spans');
      coveredUntil = s[1];
    }
    expect(coveredUntil, src.length - 1);
  });

  test('分段下载中 403：调 refreshUrl 换新链续传', () async {
    final src = makeBytes(1000);
    final server = FakeServer(src, failRequest: 2);
    var refreshed = 0;
    final out = await runFetch(server, chunkSize: 100, refreshUrl: () async {
      refreshed++;
      return 'https://cdn.example.com/v2.mp4';
    });
    expect(out, orderedEquals(src));
    expect(refreshed, greaterThanOrEqualTo(1), reason: '403 后必须换新链');
  });

  test('进度回调单调不减，总量正确', () async {
    final src = makeBytes(600);
    final server = FakeServer(src);
    final progresses = <int>[];
    final done = Completer<String?>();
    final fetcher = StreamFetcher(dio: server.dio());
    final handle = await fetcher.fetch(
      videoId: 'vid2',
      url: 'https://x/v.mp4',
      quality: '360p',
      chunkSizeOverride: 100,
      onProgress: (received, total) => progresses.add(received),
      onDone: done.complete,
    );
    // 文件长度先增长，进度回调随后才发出；必须等下载完成再断言和清理。
    expect(await done.future.timeout(const Duration(seconds: 5)), isNull);
    expect(handle.complete, isTrue);
    expect(progresses.last, src.length);
    for (var i = 1; i < progresses.length; i++) {
      expect(progresses[i], greaterThanOrEqualTo(progresses[i - 1]),
          reason: '进度必须单调不减');
    }
    await File(handle.filePath).parent.delete(recursive: true).catchError((_) => Directory(''));
  });

  test('取消：不崩、临时文件全清掉', () async {
    // 文件足够大，50ms 内肯定下不完
    final src = makeBytes(300000);
    final server = FakeServer(src);
    final fetcher = StreamFetcher(dio: server.dio());
    final done = Completer<String?>();
    final handle = await fetcher.fetch(
      videoId: 'vid3',
      url: 'https://x/v.mp4',
      quality: '360p',
      chunkSizeOverride: 1000,
      onDone: (e) => done.complete(e),
    );
    await Future<void>.delayed(const Duration(milliseconds: 50));
    await fetcher.cancel('vid3');
    // 清理是异步的（要等文件句柄关闭），等 onDone
    await done.future.timeout(const Duration(seconds: 5));
    expect(File(handle.filePath).existsSync(), isFalse);
    // 分段临时文件也清掉
    final leftovers = Directory(parentDirOf(handle.filePath))
        .listSync()
        .where((e) => e.path.contains('vid3'))
        .length;
    expect(leftovers, 0);
  });
}

String parentDirOf(String filePath) {
  final i = filePath.lastIndexOf(Platform.pathSeparator);
  return filePath.substring(0, i);
}

class _Adapter implements HttpClientAdapter {
  _Adapter(this.server);
  final dynamic server;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final s = server as FakeServer;
    final range = options.headers['Range'] as String?;
    if (range == null || range == 'bytes=0-0') {
      // 总大小探测（或兼容无 Range 头的探测）：返回 content-range
      return ResponseBody.fromBytes(
        s.bytes.sublist(0, 1),
        206,
        headers: {
          'content-range': ['bytes 0-0/${s.bytes.length}'],
        },
      );
    }
    s.rangeRequests++;
    final m = RegExp(r'bytes=(\d+)-(\d+)').firstMatch(range)!;
    final start = int.parse(m.group(1)!);
    final end = int.parse(m.group(2)!);
    s.ranges.add('$start-$end');

    if (s.failRequest >= 0 && s.rangeRequests == s.failRequest + 1) {
      return ResponseBody.fromBytes(Uint8List(0), 403);
    }
    // 模拟网络耗时，让并行真正交叉（不然快的机器上可能顺序执行）
    await Future<void>.delayed(Duration(milliseconds: (start * 7) % 13));
    return ResponseBody.fromBytes(
      s.bytes.sublist(start, end + 1),
      206,
      headers: {
        'content-range': ['bytes $start-$end/${s.bytes.length}'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
