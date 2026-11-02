import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/providers.dart';
import 'app/router.dart';
import 'core/player_bootstrap.dart';
import 'data/local/background_transfer.dart';
import 'data/local/library_store.dart';
import 'ui/glass.dart';
import 'ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // media_kit 必须在 runApp 前初始化（libmpv 的 native 侧准备）
  await PlayerBootstrap.init();

  // 后台传输（缓存/下载）的前台服务通道要在这里建好，
  // 否则第一次 startService 会因为通道未注册而失败。
  BackgroundTransfer.init();

  final prefs = await SharedPreferences.getInstance();
  // 恢复上次保存的播放器代理（libmpv 不认系统代理，必须每次启动告诉它）
  final savedProxy = LibraryStore(prefs).playerProxy;
  if (savedProxy.isNotEmpty) PlayerBootstrap.setProxy(savedProxy);
  // 玻璃模糊是纯 UI 偏好，不进 Riverpod，启动时直接喂给全局开关。
  glassBlurEnabled.value = LibraryStore(prefs).glassBlur;

  runApp(
    ProviderScope(
      overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
      child: const R34vApp(),
    ),
  );
}

class R34vApp extends ConsumerWidget {
  const R34vApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    return MaterialApp.router(
      title: 'R34V',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: buildAppTheme(),
      theme: buildAppTheme(),
      routerConfig: router,
      // 环境光铺在所有页面下面。Scaffold 背景是透明的，
      // 于是列表滚动、玻璃栏、半透明浮层都能透出同一层光。
      builder: (context, child) =>
          AmbientBackdrop(child: child ?? const SizedBox.shrink()),
    );
  }
}
