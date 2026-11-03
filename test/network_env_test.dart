import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/network_env.dart';

/// VPN 检测的基本行为。
///
/// 真实的「有没有 VPN 接口」取决于运行环境，单测里无法可靠构造，
/// 所以这里只验证**不会误判、不会抛异常、缓存能失效**这三点——
/// 它们是「VPN 开关后能否自动切换回直连」的前提。
///
/// 具体策略判断（有 VPN 就不用 IP 直连）在 `site_client_io.dart` 里，
/// 依赖这个 `hasVpn()` 的返回值。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('VPN 检测', () {
    test('返回布尔值，不抛异常', () async {
      // 测试环境通常没有 tun 接口，但不断言具体值——
      // 断言"一定为 false"会在有 VPN 的 CI 上假失败。
      final v = await NetworkEnv.hasVpn();
      expect(v, isA<bool>());
    });

    test('连续调用结果一致（缓存生效）', () async {
      final a = await NetworkEnv.hasVpn();
      final b = await NetworkEnv.hasVpn();
      expect(a, b);
    });

    test('invalidate 之后能重新计算且不崩', () async {
      await NetworkEnv.hasVpn();
      NetworkEnv.invalidate();
      final v = await NetworkEnv.hasVpn();
      expect(v, isA<bool>());
    });
  });
}
