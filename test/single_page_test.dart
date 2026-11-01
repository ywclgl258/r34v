import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/app/router.dart';
import 'package:r34v/features/player/player_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 详情与播放**不能再被拆成两页**。
///
/// 曾经是 `/video/:id/:slug`（详情）→ 点播放 → `/play/:id/:slug`（播放），
/// 于是从列表点进来要按两次返回，两页的信息还几乎重复。
/// 现在两条路径都指向同一个 [PlayerPage]：点播放按钮就地起播，
/// 返回一次直接回列表。
///
/// 这条测试盯的是**路由层**，因为拆分就发生在那里。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  ProviderContainer makeContainer() {
    final c = ProviderContainer(
      overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
    );
    addTearDown(c.dispose);
    return c;
  }

  test('两条路径都注册了（保留 /video 让旧深链不失效）', () {
    final router = makeContainer().read(routerProvider);
    final paths = router.configuration.routes
        .whereType<GoRoute>()
        .map((r) => r.path)
        .toList();

    expect(paths, contains('/video/:id/:slug'));
    expect(paths, contains('/play/:id/:slug'));
  });

  Future<void> pumpRoute(WidgetTester tester, String location) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.reset);

    final container = makeContainer();
    final router = container.read(routerProvider);
    router.go(location);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pump();
  }

  testWidgets('/video 进去是 PlayerPage，不是详情页', (tester) async {
    await pumpRoute(tester, '/video/4703101/x');
    expect(find.byType(PlayerPage), findsOneWidget,
        reason: '/video 应该渲染 PlayerPage（详情与播放已合并）');
  });

  testWidgets('/play 进去也是 PlayerPage（同一个类型，不是副本）', (tester) async {
    await pumpRoute(tester, '/play/4703101/x');
    expect(find.byType(PlayerPage), findsOneWidget);
  });
}
