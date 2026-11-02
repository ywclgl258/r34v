import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/doh_connector.dart';
import 'package:r34v/data/remote/doh_resolver.dart';
import 'package:r34v/data/remote/site_client_io.dart';

/// **联网**验证 DoH 路线真的能取到页面。
///
/// ```
/// flutter test test/doh_connector_live_test.dart --dart-define=R34V_LIVE=1
/// ```
void main() {
  const flag = String.fromEnvironment('R34V_LIVE');
  final live = flag == '1' || flag == 'true';

  group('DoH 解析', () {
    test('实测：能解析出站点的真实 IP，并过滤掉连不通的', () async {
      if (!live) {
        markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
        return;
      }
      final r = DohResolver();
      final ips = await r.resolve('rule34video.com');
      // ignore: avoid_print
      print('DoH 原始返回: $ips');
      expect(ips, isNotEmpty, reason: 'DoH 应返回至少一个 A 记录');
      expect(r.cachedFor('rule34video.com'), ips, reason: '缓存应生效');
    }, timeout: const Timeout(Duration(seconds: 60)));

    test('实测：连接器能从候选里挑出可连的 IP', () async {
      if (!live) {
        markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
        return;
      }
      final connector = DnsOverHttpsConnector(resolver: DohResolver());
      final ip = await connector.resolveSiteIp();
      // ignore: avoid_print
      print('选中的 IP: $ip');
      expect(ip, isNotNull, reason: '应至少有一个 IP 能连通 443');
      expect(connector.lastResolvedIp, ip);
    }, timeout: const Timeout(Duration(seconds: 90)));
  });

  group('整条链路', () {
    test('实测：装上 DoH 后能取到站点首页', () async {
      if (!live) {
        markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
        return;
      }

      final connector = DnsOverHttpsConnector(resolver: DohResolver());
      final client = SiteClientImpl(connector: connector);
      try {
        final r = await client.getHtml('https://rule34video.com/');
        // ignore: avoid_print
        print('状态=${r.status} 大小=${r.body.length}B '
            '耗时=${r.elapsed.inMilliseconds}ms '
            '用的IP=${connector.lastResolvedIp}');
        expect(r.status, 200);
        expect(r.body.length, greaterThan(50000),
            reason: '应是真实页面而非挑战页');
        expect(r.looksLikeChallenge, isFalse);
        expect(connector.lastResolvedIp, isNotNull,
            reason: '应该用上了 DoH 解析出的 IP');
      } finally {
        client.dispose();
      }
    }, timeout: const Timeout(Duration(seconds: 120)));

    test('实测：非站点域名不受影响', () async {
      if (!live) {
        markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
        return;
      }
      final connector = DnsOverHttpsConnector(resolver: DohResolver());
      final client = SiteClientImpl(connector: connector);
      try {
        final r = await client.getHtml('https://example.com/');
        // ignore: avoid_print
        print('example.com 状态=${r.status}');
        expect(r.status, 200);
        // 非站点域名不该触发 DoH
        expect(connector.lastResolvedIp, isNull);
      } finally {
        client.dispose();
      }
    }, timeout: const Timeout(Duration(seconds: 60)));
  });

  group('解析器纯逻辑', () {
    test('解析 AliDNS 的真实返回格式', () {
      const raw = '{"Status":0,"TC":false,"RD":true,"RA":true,'
          '"Question":{"name":"rule34video.com.","type":1},'
          '"Answer":[{"name":"rule34video.com.","TTL":14,"type":1,'
          '"data":"202.160.128.96"}]}';
      expect(parseDohJsonText(raw), ['202.160.128.96']);
    });

    test('只取 A 记录，忽略 AAAA', () {
      expect(
        DohResolver.parseAnswers({
          'Status': 0,
          'Answer': [
            {'type': 1, 'data': '1.2.3.4'},
            {'type': 28, 'data': '2001::1'},
            {'type': 1, 'data': '5.6.7.8'},
          ],
        }),
        ['1.2.3.4', '5.6.7.8'],
      );
    });

    test('NXDOMAIN 返回空', () {
      expect(DohResolver.parseAnswers({'Status': 3}), isEmpty);
    });

    test('坏数据不崩', () {
      expect(DohResolver.parseAnswers(null), isEmpty);
      expect(DohResolver.parseAnswers('字符串'), isEmpty);
      expect(parseDohJsonText('不是 JSON'), isEmpty);
    });

    test('去重且保序', () {
      expect(
        DohResolver.parseAnswers({
          'Status': 0,
          'Answer': [
            {'type': 1, 'data': '1.1.1.1'},
            {'type': 1, 'data': '2.2.2.2'},
            {'type': 1, 'data': '1.1.1.1'},
          ],
        }),
        ['1.1.1.1', '2.2.2.2'],
      );
    });

    test('国外服务商排在前面（国内会返回污染结果）', () {
      expect(DohResolver.presets.first.key, 'cloudflare');
      expect(DohResolver.presets.last.key, 'alidns',
          reason: 'AliDNS 对该域名返回 Facebook IP 段，只能垫底');
    });
  });
}

