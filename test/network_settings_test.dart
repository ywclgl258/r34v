import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/data/remote/site_client_io.dart';
import 'package:r34v/features/player/backends/backends.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    container = ProviderContainer(
      overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
    );
  });

  tearDown(() => container.dispose());

  test('DoH 开关立即重建连接器并应用新值', () async {
    final before = container.read(dohConnectorProvider);
    expect(before.enabled, isTrue);

    await container.read(dohEnabledProvider.notifier).set(false);

    final after = container.read(dohConnectorProvider);
    expect(after.enabled, isFalse);
    expect(identical(before, after), isFalse);
  });

  test('站点直连开关立即重建 API 客户端', () async {
    final before = container.read(rule34ApiProvider);

    await container.read(directIpProvider.notifier).set(false);

    final after = container.read(rule34ApiProvider);
    expect(identical(before, after), isFalse);
    expect((after.client as SiteClientImpl).useDirectIp, isFalse);
  });

  test('播放器工厂尊重 exo，不再因 DoH 暗中切换 mpv', () {
    final factory = container.read(playerBackendFactoryProvider);
    expect(factory('exo'), isA<ExoBackend>());
    expect(factory('mpv'), isA<MpvBackend>());
  });
}
