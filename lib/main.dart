import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/providers.dart';
import 'app/router.dart';
import 'core/player_bootstrap.dart';
import 'data/local/library_store.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // media_kit 必须在 runApp 前初始化（libmpv 的 native 侧准备）
  await PlayerBootstrap.init();

  final prefs = await SharedPreferences.getInstance();
  // 恢复上次保存的播放器代理（libmpv 不认系统代理，必须每次启动告诉它）
  final savedProxy = LibraryStore(prefs).playerProxy;
  if (savedProxy.isNotEmpty) PlayerBootstrap.setProxy(savedProxy);

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
      darkTheme: _darkTheme(),
      theme: _darkTheme(),
      routerConfig: router,
    );
  }

  /// 深色优先：看视频的场景下浅色界面刺眼，而且站点的封面本身偏深色。
  ThemeData _darkTheme() {
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFFD1404A), // 站点主色（红）
      brightness: Brightness.dark,
    );
    // 自带中文字体，不依赖系统字体与网络字体回退。
    // fontFamilyFallback 兜住英文数字，保证图文混排时基线一致。
    const cjk = 'NotoSansSC';
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      fontFamily: cjk,
      fontFamilyFallback: const [cjk],
      scaffoldBackgroundColor: const Color(0xFF121212),
      cardTheme: const CardThemeData(elevation: 0, clipBehavior: Clip.antiAlias),
      appBarTheme: AppBarTheme(
        backgroundColor: const Color(0xFF121212),
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          fontFamily: cjk,
          fontSize: 20,
          fontWeight: FontWeight.w600,
          color: scheme.onSurface,
        ),
      ),
    );
  }
}
