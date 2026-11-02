import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/host_router.dart';

/// 直连 IP 相关的基础约定。
///
/// 这里只放**不依赖网络**的判断。联网验证在
/// `direct_ip_live_test.dart`。
void main() {
  group('候选表', () {
    test('只保留实测能连通的 IP', () {
      final ips = kBuiltinEndpoints.map((e) => e.ip).toList();
      expect(ips, contains('190.115.31.243'),
          reason: '这是实测唯一能连通的');

      // 下面这些留着只会让每次请求先白等一个超时：
      // - 108.160.162.109 / 128.121.243.76 实测 TCP 0/6，完全不通
      // - 4.78.139.54 实测首页 22 秒，比其它慢 7 倍
      expect(ips, isNot(contains('108.160.162.109')));
      expect(ips, isNot(contains('128.121.243.76')));
      expect(ips, isNot(contains('4.78.139.54')));
    });

    test('没有重复 IP', () {
      final ips = kBuiltinEndpoints.map((e) => e.ip).toList();
      expect(ips.toSet().length, ips.length);
    });

    test('都是合法 IPv4', () {
      for (final e in kBuiltinEndpoints) {
        final parts = e.ip.split('.');
        expect(parts.length, 4, reason: '${e.ip} 不是 IPv4');
        for (final p in parts) {
          final n = int.tryParse(p);
          expect(n, isNotNull);
          expect(n, inInclusiveRange(0, 255));
        }
      }
    });
  });

  group('isSiteHost', () {
    test('认站点自身与 www', () {
      expect(isSiteHost('rule34video.com'), isTrue);
      expect(isSiteHost('www.rule34video.com'), isTrue);
    });

    test('不认 CDN 和其它域名', () {
      // CDN 有正常 DNS，替换成站点 IP 会连错服务器
      expect(isSiteHost('boomio-cdn.com'), isFalse);
      expect(isSiteHost('cdn.boomio-cdn.com'), isFalse);
      expect(isSiteHost('rule34video.com.evil.example'), isFalse);
      expect(isSiteHost(''), isFalse);
    });
  });
}
