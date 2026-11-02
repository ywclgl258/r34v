import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/stream_resolver.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// CDN 直链**磁盘持久化**的回归测试。
///
/// 背景（实测结论）：CDN 那一层不校验 Referer / UA / Cookie，
/// 只要拿到带 `acctoken` 的 CDN 地址就能取字节，且链接可复用
/// （连取 3 次、含 50MB 偏移的远端区间都是 206）。
/// 所以把它写到磁盘跨启动复用是安全的，能省掉一次 302。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('磁盘持久化', () {
    test('没有 prefs 时退化成纯内存，不崩', () {
      final r = StreamResolver();
      expect(r.cachedFor('https://x/a.mp4'), isNull);
      r.clear();
      expect(r.cachedFor('https://x/a.mp4'), isNull);
    });

    test('清空后磁盘里也清掉', () async {
      final prefs = await SharedPreferences.getInstance();
      final r = StreamResolver(prefs: prefs);
      r.clear();
      final raw = prefs.getString('stream_resolver_v1');
      expect(raw == null || raw == '[]', isTrue);
    });

    test('载入时丢弃已经过期的条目', () async {
      final prefs = await SharedPreferences.getInstance();
      // 手工塞一条「很久以前」的记录，模拟上次启动留下的
      final old = DateTime.now()
          .subtract(const Duration(hours: 5))
          .millisecondsSinceEpoch;
      await prefs.setString(
        'stream_resolver_v1',
        '[{"u":"https://x/old.mp4","c":"https://cdn/old","t":$old}]',
      );

      final r = StreamResolver(prefs: prefs);
      // 过期条目不该被载入缓存
      expect(r.cachedFor('https://x/old.mp4'), isNull);
    });

    test('新条目能被载入', () async {
      final prefs = await SharedPreferences.getInstance();
      final fresh = DateTime.now().millisecondsSinceEpoch;
      await prefs.setString(
        'stream_resolver_v1',
        '[{"u":"https://x/new.mp4","c":"https://cdn/new","t":$fresh}]',
      );

      final r = StreamResolver(prefs: prefs);
      expect(r.cachedFor('https://x/new.mp4'), 'https://cdn/new');
    });

    test('坏数据不致命，退化成空缓存', () async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('stream_resolver_v1', '这不是 JSON');
      final r = StreamResolver(prefs: prefs);
      expect(r.cachedFor('https://x/a.mp4'), isNull);
    });
  });

  group('TTL', () {
    test('有效期是 30 分钟（实测支持的窗口）', () {
      // 太短会频繁回源（回源 = 打详情页，最容易触发限流）
      // 太长则过期后要白等一次失败
      expect(StreamResolver.ttl, const Duration(minutes: 30));
    });
  });

  group('invalidate', () {
    test('作废单个地址不影响其它', () async {
      final prefs = await SharedPreferences.getInstance();
      final r = StreamResolver(prefs: prefs);
      // 没有真实条目时调用也不该抛
      r.invalidate('https://x/none.mp4');
      expect(r.cachedFor('https://x/none.mp4'), isNull);
    });
  });
}
