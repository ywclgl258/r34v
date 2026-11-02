import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/host_router.dart';

/// IP 直连的**联网**验证。
///
/// ```
/// flutter test test/direct_ip_live_test.dart --dart-define=R34V_LIVE=1
/// ```
///
/// ## 这个文件在验什么
///
/// 「用 IP 建 TCP，但 TLS 对域名」。这是唯一能在不改变 URI host 的前提下
/// 指定 IP 的方式——改 URI host 会连 SNI 一起变成 IP，证书必然校验失败。
///
/// ## 为什么允许它偶尔失败
///
/// 实测这个 IP 的**服务端在传正文阶段很不稳定**：TCP 8/8、TLS 6/8，
/// 但拿全正文只有 1/8。这是链路的客观质量，不是代码问题。所以这里只断言
/// 「机制能跑通」，不断言「每次都成功」。
void main() {
  const flag = String.fromEnvironment('R34V_LIVE');
  final live = flag == '1' || flag == 'true';

  const ip = String.fromEnvironment('R34V_TEST_IP',
      defaultValue: '190.115.31.243');

  group('connectViaIp 机制', () {
    test('实测：TCP 连 IP，TLS 对域名，证书能过', () async {
      if (!live) {
        markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
        return;
      }

      // 最多试 4 次：这条链路服务端不稳定，机制正确但单次成功率不高
      Object? lastError;
      for (var i = 0; i < 4; i++) {
        try {
          final tls = await connectViaIp(ip, 443, host: kSiteHost);
          // 证书主体应该是域名，不是 IP——这是整个方案的关键
          final subject = tls.peerCertificate?.subject ?? '';
          // ignore: avoid_print
          print('TLS 证书: $subject, 协议: ${tls.selectedProtocol}');

          tls.write('GET / HTTP/1.1\r\n'
              'Host: $kSiteHost\r\n'
              'User-Agent: Mozilla/5.0 (Linux; Android 14)\r\n'
              'Accept: text/html\r\n'
              'Connection: close\r\n\r\n');
          await tls.flush();

          final chunks = <int>[];
          await for (final d in tls.timeout(const Duration(seconds: 25))) {
            chunks.addAll(d);
            if (chunks.length > 300000) break;
          }
          await tls.close();

          // ignore: avoid_print
          print('第 ${i + 1} 次: 收到 ${chunks.length} 字节');
          if (chunks.length > 50000) {
            expect(subject, contains(kSiteHost),
                reason: '证书必须是对域名的，否则 SNI 没生效');
            return;
          }
        } catch (e) {
          lastError = e;
          // ignore: avoid_print
          print('第 ${i + 1} 次失败: $e');
        }
      }
      fail('4 次都没拿到正文，最后一次错误：$lastError');
    }, timeout: const Timeout(Duration(minutes: 4)));

    test('实测：TCP 层能连上（这条链路的基本条件）', () async {
      if (!live) {
        markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
        return;
      }
      final ok = await probeTcp(ip, timeout: const Duration(seconds: 6));
      // ignore: avoid_print
      print('TCP 探测 $ip: $ok');
      expect(ok, isTrue, reason: 'TCP 都连不上的话后面无从谈起');
    }, timeout: const Timeout(Duration(seconds: 60)));
  });

  group('候选表', () {
    test('内置 IP 只留实测能连的', () {
      final ips = kBuiltinEndpoints.map((e) => e.ip).toList();
      expect(ips, contains('190.115.31.243'));
      // 这两个实测 TCP 0/6 全不通，留着只会白等超时
      expect(ips, isNot(contains('108.160.162.109')));
      expect(ips, isNot(contains('128.121.243.76')));
      // 这个实测 22 秒，慢 7 倍
      expect(ips, isNot(contains('4.78.139.54')));
    });

    test('isSiteHost 只认站点自身域名', () {
      expect(isSiteHost('rule34video.com'), isTrue);
      expect(isSiteHost('www.rule34video.com'), isTrue);
      expect(isSiteHost('boomio-cdn.com'), isFalse);
      expect(isSiteHost('evil.example'), isFalse);
    });
  });
}

