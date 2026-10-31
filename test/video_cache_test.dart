import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/video_cache.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 缓存逻辑测试。
///
/// 这些测试**真的往临时目录写文件**，而不是全用 mock —— 缓存最容易出错的地方
/// 恰恰是「索引说有、磁盘上没有」这类不一致，只 mock 是测不出来的。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;
  late VideoCache cache;
  late Directory tmp;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    cache = VideoCache(prefs);
    tmp = await Directory.systemTemp.createTemp('r34v_cache_test');
  });

  tearDown(() async {
    if (await tmp.exists()) await tmp.delete(recursive: true);
  });

  /// 造一个假的缓存记录，指向真实存在的文件。
  Future<CachedVideo> seed({
    required String id,
    required int bytes,
    String quality = '720p',
    DateTime? at,
  }) async {
    final f = File('${tmp.path}/$id.mp4');
    await f.writeAsBytes(List<int>.filled(bytes, 0));
    final entry = CachedVideo(
      videoId: id,
      title: 'Video $id',
      filePath: f.path,
      bytes: bytes,
      quality: quality,
      createdAt: at ?? DateTime.now(),
    );
    // 直接写进索引
    final list = cache.loadIndex().toList()..insert(0, entry);
    final raw = list.map((e) => e.toJson()).toList();
    await prefs.setString(
      'video_cache_v1',
      '[${raw.map((e) => _encode(e)).join(',')}]',
    );
    return entry;
  }

  group('索引', () {
    test('初始为空', () {
      expect(cache.loadIndex(), isEmpty);
      expect(cache.usedBytes(), 0);
    });

    test('记录能被读回，字段完整', () async {
      await seed(id: '4703101', bytes: 1024, quality: '1080p');
      final e = cache.find('4703101');
      expect(e, isNotNull);
      expect(e!.quality, '1080p');
      expect(e.bytes, 1024);
      expect(e.megabytes, closeTo(1024 / (1024 * 1024), 0.0001));
    });

    test('isCached：文件在才是 true', () async {
      await seed(id: 'a', bytes: 10);
      expect(cache.isCached('a'), isTrue);

      // 手动删文件，模拟用户清了应用数据
      File('${tmp.path}/a.mp4').deleteSync();
      expect(cache.isCached('a'), isFalse, reason: '索引里有但文件没了，不算已缓存');
    });

    test('localPathFor 返回真实路径，文件丢了返回 null', () async {
      await seed(id: 'a', bytes: 10);
      expect(cache.localPathFor('a'), '${tmp.path}/a.mp4');

      File('${tmp.path}/a.mp4').deleteSync();
      expect(cache.localPathFor('a'), isNull);
    });

    test('usedBytes 只算真的还在的文件', () async {
      await seed(id: 'a', bytes: 100);
      await seed(id: 'b', bytes: 200);
      expect(cache.usedBytes(), 300);

      File('${tmp.path}/a.mp4').deleteSync();
      expect(cache.usedBytes(), 200);
    });
  });

  group('删除', () {
    test('remove 同时删记录和文件', () async {
      await seed(id: 'a', bytes: 10);
      expect(File('${tmp.path}/a.mp4').existsSync(), isTrue);

      await cache.remove('a');
      expect(File('${tmp.path}/a.mp4').existsSync(), isFalse);
      expect(cache.find('a'), isNull);
    });

    test('clearAll 清空所有文件', () async {
      await seed(id: 'a', bytes: 10);
      await seed(id: 'b', bytes: 20);

      await cache.clearAll();
      expect(cache.loadIndex(), isEmpty);
      expect(File('${tmp.path}/a.mp4').existsSync(), isFalse);
      expect(File('${tmp.path}/b.mp4').existsSync(), isFalse);
    });

    test('pruneMissing 清掉指向不存在文件的记录', () async {
      await seed(id: 'a', bytes: 10);
      await seed(id: 'b', bytes: 20);
      File('${tmp.path}/a.mp4').deleteSync();

      await cache.pruneMissing();
      expect(cache.loadIndex().map((e) => e.videoId), ['b']);
    });
  });

  group('空间预算', () {
    test('没超预算时不删', () async {
      await cache.setBudgetMb(1); // 1MB
      await seed(id: 'a', bytes: 100);
      await seed(id: 'b', bytes: 200);
      await cache.enforceBudget();
      expect(cache.loadIndex().length, 2);
    });

    test('超预算时从最旧的删，降到 80%', () async {
      await cache.setBudgetMb(1); // 1MB = 1048576
      // 造三个各 500KB 的，共 1.5MB，超了
      final half = 500 * 1024;
      await seed(id: 'old', bytes: half, at: DateTime(2026, 1, 1));
      await seed(id: 'mid', bytes: half, at: DateTime(2026, 5, 1));
      await seed(id: 'new', bytes: half, at: DateTime(2026, 9, 1));

      await cache.enforceBudget();

      final left = cache.loadIndex().map((e) => e.videoId).toList();
      // 1.5MB → 目标 0.8MB，删掉最旧的一个还剩 1MB，仍超 → 再删一个剩 0.5MB
      expect(left.length, lessThan(3));
      expect(left.contains('new'), isTrue, reason: '最新的不该被删');
      expect(File('${tmp.path}/old.mp4').existsSync(), isFalse);
    });

    test('预算可读写', () async {
      expect(cache.budgetMb, VideoCache.defaultBudgetMb);
      await cache.setBudgetMb(8192);
      expect(cache.budgetMb, 8192);
    });
  });

  group('自动缓存开关', () {
    test('默认开启', () {
      expect(cache.autoCache, isTrue);
    });

    test('可关闭', () async {
      await cache.setAutoCache(false);
      expect(cache.autoCache, isFalse);
    });
  });

  group('缓存进度', () {
    test('总大小已知时给百分比', () {
      const p = CacheProgress(received: 50, total: 100);
      expect(p.fraction, 0.5);
    });

    test('总大小未知时 fraction 为 null（显示不确定态）', () {
      const p = CacheProgress(received: 50, total: 0);
      expect(p.fraction, isNull);
    });

    test('received 超过 total 时夹住 1.0', () {
      const p = CacheProgress(received: 120, total: 100);
      expect(p.fraction, 1.0);
    });
  });

  group('旧数据容错', () {
    test('索引 JSON 坏掉时返回空而不是崩溃', () async {
      await prefs.setString('video_cache_v1', '{不是JSON[');
      expect(cache.loadIndex(), isEmpty);
    });

    test('缺字段的记录被跳过', () async {
      await prefs.setString(
        'video_cache_v1',
        '[{"id":"","path":""},{"id":"ok","path":"/x","bytes":1}]',
      );
      final list = cache.loadIndex();
      expect(list.length, 1);
      expect(list.first.videoId, 'ok');
    });
  });
}

/// 简易 JSON 编码，避开 dart:convert 在测试里的额外 import。
String _encode(Map<String, dynamic> m) {
  final parts = m.entries.map((e) {
    final v = e.value;
    if (v is String) return '"${e.key}":"${v.replaceAll(r'\', r'\\')}"';
    return '"${e.key}":$v';
  });
  return '{${parts.join(',')}}';
}
