import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:r34v/data/remote/host_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 站点 IP 路由：一个首选、其余兜底。
///
/// 实测背景：`rule34video.com` 的 DNS 结果随 resolver 变化——
/// 本地 `/` 8.8.8.8 `/` 1.1.1.1 给出三个不同 IP，且三个都可用
/// （都是 ddos-guard 前置、都不返回挑战页）。
/// 所以内置这三条，按实测耗时排序，全失败再退回系统 DNS。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('内置候选表', () {
    test('三个 IP 都保留，一个都不丢', () {
      expect(kBuiltinEndpoints.length, 3);
      final ips = kBuiltinEndpoints.map((e) => e.ip).toList();
      expect(ips, contains('108.160.162.109'));
      expect(ips, contains('128.121.243.76'));
      expect(ips, contains('4.78.139.54'));
    });

    test('首选是 weight 最小的那个（实测最快）', () {
      final r = HostRouter();
      // 不写死 IP 字面量：排序规则是 weight，实测最快那条 weight=0。
      // 写死会让「以后调整候选表」变成改测试，容易漏。
      final fastest =
          kBuiltinEndpoints.reduce((a, b) => a.weight <= b.weight ? a : b);
      expect(r.preferred, fastest.ip);
    });
  });

  group('兜底顺序', () {
    test('首选之后依次给出其余候选', () {
      final r = HostRouter();
      final first = r.preferred!;
      final second = r.nextAfter(first);
      final third = second == null ? null : r.nextAfter(second);
      final fourth = third == null ? null : r.nextAfter(third);

      expect(first, isNotNull);
      expect(second, isNotNull, reason: '第二个候选必须存在');
      expect(third, isNotNull, reason: '第三个候选必须存在');
      expect(fourth, isNull, reason: '试完就回退系统 DNS');
    });

    test('三个 IP 各不相同，且都在内置表里', () {
      final r = HostRouter();
      final seen = <String>[];
      String? cur = r.preferred;
      while (cur != null) {
        seen.add(cur);
        cur = r.nextAfter(cur);
      }
      expect(seen.length, 3);
      expect(seen.toSet().length, 3, reason: '不能重复试同一个 IP');
      for (final ip in seen) {
        expect(kBuiltinEndpoints.map((e) => e.ip), contains(ip));
      }
    });
  });

  group('失败降级', () {
    test('首选失败两次后自动换人', () {
      final r = HostRouter();
      final first = r.preferred!;
      r.recordFailure(first);
      expect(r.preferred, first, reason: '失败一次还给它机会');

      r.recordFailure(first);
      expect(r.preferred, isNot(first), reason: '连续失败两次就该换');
    });

    test('连续失败两次后不再作为首选，但仍在候选表里（没被删除）', () {
      final r = HostRouter();
      final first = r.preferred!;
      r.recordFailure(first);
      r.recordFailure(first);

      // 首选已经换人
      expect(r.preferred, isNot(first));

      // 但候选表本身没变——IP 都留着，只是暂时不选它。
      // 之所以不删：用户那边也许它是通的，网络恢复后该能重新用上。
      expect(r.endpoints.map((e) => e.ip), contains(first));

      // 清掉失败计数后能重新回到首选
      r.reset();
      expect(r.preferred, first);
    });

    test('成功会把它提为首选', () {
      final r = HostRouter();
      r.recordSuccess('4.78.139.54');
      expect(r.preferred, '4.78.139.54');
    });
  });

  group('开关', () {
    test('默认开启', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      expect(LibraryStore(prefs).directIp, isTrue);
    });

    test('关掉后 preferred 为 null（走系统 DNS）', () {
      final r = HostRouter(enabled: false);
      expect(r.preferred, isNull);
      expect(r.nextAfter('108.160.162.109'), isNull);
    });

    test('持久化正常', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final store = LibraryStore(prefs);
      await store.setDirectIp(false);
      expect(store.directIp, isFalse);
      expect(prefs.getBool('settings_v1_direct_ip'), isFalse);
    });
  });

  group('withIp', () {
    test('只换主机名，路径与查询串原样保留', () {
      final u = Uri.parse('https://rule34video.com/video/1/a/?x=1&y=2');
      final r = HostRouter.withIp(u, '108.160.162.109');
      expect(r.host, '108.160.162.109');
      expect(r.path, '/video/1/a/');
      expect(r.query, 'x=1&y=2');
      expect(r.scheme, 'https');
    });
  });

  test('尝试上限 = 候选数 + 1（最后一次是系统 DNS）', () {
    final r = HostRouter();
    expect(r.maxAttempts, kBuiltinEndpoints.length + 1);
  });
}
