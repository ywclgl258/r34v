import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/host_router.dart';
import 'package:r34v/data/remote/site_client_io.dart';

/// **联网**验证站点直连 IP 真的能取到页面。
///
/// ```
/// flutter test test/direct_ip_live_test.dart --dart-define=R34V_LIVE=1
/// ```
///
/// 要验证的核心是「换 IP 但保留 Host/SNI」这条路走得通——
/// 只把 URL 的主机名换成 IP 是不够的，必须同时把 Host 头设回原域名，
/// 否则站点会当成未知虚拟主机返回 404/421。
void main() {
  const flag = String.fromEnvironment('R34V_LIVE');
  final live = flag == '1' || flag == 'true';

  test('实测：每个内置 IP 都能取到站点首页', () async {
    if (!live) {
      markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
      return;
    }

    for (final e in kBuiltinEndpoints) {
      final router = HostRouter();
      // 强行把首选设成这个 IP，验证它单独可用
      router.recordSuccess(e.ip);
      expect(router.preferred, e.ip);

      final client = SiteClientImpl(router: router);
      try {
        final r = await client.getHtml('https://rule34video.com/');
        // ignore: avoid_print
        print('${e.ip} -> ${r.status} ${r.body.length}B '
            '${r.elapsed.inMilliseconds}ms');
        expect(r.status, 200, reason: '${e.ip} 应能返回 200');
        expect(r.body.length, greaterThan(50000),
            reason: '${e.ip} 返回的应是真实页面，不是挑战页');
        expect(r.looksLikeChallenge, isFalse,
            reason: '${e.ip} 不应触发 ddos-guard 挑战页');
      } finally {
        client.dispose();
      }
    }
  }, timeout: const Timeout(Duration(seconds: 120)));

  test('实测：首选失败后能自动切到备用', () async {
    if (!live) {
      markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
      return;
    }

    final router = HostRouter();
    // 把一个必定连不上的地址放进候选表最前面，模拟「首选坏了」
    final broken = HostRouter(
      endpoints: const [
        HostEndpoint(ip: '192.0.2.1', label: 'TEST-NET-1', weight: -1),
        ...kBuiltinEndpoints,
      ],
    );
    router.reset();

    final client = SiteClientImpl(router: broken);
    try {
      // 短超时，避免卡在不可达地址上太久
      final r = await client.getHtml('https://rule34video.com/');
      // ignore: avoid_print
      print('兜底后 -> ${r.status} ${r.body.length}B');
      expect(r.status, 200, reason: '首选的 192.0.2.1 不可达，应自动切到备用');
      expect(r.body.length, greaterThan(50000));
    } finally {
      client.dispose();
    }
  }, timeout: const Timeout(Duration(seconds: 180)));
}
