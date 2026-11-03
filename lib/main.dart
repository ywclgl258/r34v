import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/providers.dart';
import 'app/router.dart';
import 'core/player_bootstrap.dart';
import 'data/local/background_transfer.dart';
import 'data/local/library_store.dart';
import 'features/settings/update_controller.dart';
import 'ui/glass.dart';
import 'ui/net_image.dart';
import 'ui/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // media_kit 必须在 runApp 前初始化（libmpv 的 native 侧准备）
  await PlayerBootstrap.init();

  // 后台传输（缓存/下载）的前台服务通道要在这里建好，
  // 否则第一次 startService 会因为通道未注册而失败。
  BackgroundTransfer.init();

  // 封面磁盘缓存。
  //
  // 内存 LRU 一重启就空了，二进 App 又要重新拉一遍封面。这里挂一个
  // 磁盘层：URL → 文件，最多占 200MB，超出按最后访问时间淘汰。
  // 失败不阻断启动（拿不到目录就一直用内存缓存）。
  try {
    final cacheDir = await getApplicationCacheDirectory();
    ImageByteCache.instance.attachDisk(
      ImageDiskCache(Directory('${cacheDir.path}/covers')),
    );
  } catch (_) {
    // Web 或权限异常时没有磁盘缓存，功能不受影响
  }

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

class R34vApp extends ConsumerStatefulWidget {
  const R34vApp({super.key});

  @override
  ConsumerState<R34vApp> createState() => _R34vAppState();
}

class _R34vAppState extends ConsumerState<R34vApp> {
  @override
  void initState() {
    super.initState();
    // 启动后**延迟**刷新站点直连 IP 池。
    //
    // 「每次打开自动检测新的可用 IP」就在这里做：首屏不等它，用户先看到
    // 内容；探测在后台跑，完成后写入池并落盘，本次及下次启动都用新结果。
    //
    // 只在池子陈旧（超过 6 小时）或从没探测过时才跑，避免每次冷启动都
    // 白耗一轮流量——站点轮询不是分钟级的，没必要那么频繁。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _refreshIpPoolIfNeeded();
    });

    // 启动后**延迟**检查一次更新：不跟首屏抢网络，失败也不打扰。
    //
    // 只在距上次检查超过 12 小时才查，避免频繁冷启动反复打 GitHub
    // （未认证接口有 60 次/小时的限流，共用出口 IP 时很容易撞上）。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final store = ref.read(libraryStoreProvider);
      if (!store.autoCheckUpdate) return;
      final last = store.lastUpdateCheckAt;
      if (last != null &&
          DateTime.now().difference(last) < const Duration(hours: 12)) {
        return;
      }
      unawaited(
        Future<void>.delayed(
          const Duration(seconds: 3),
          () => ref.read(updateControllerProvider.notifier).check(silent: true),
        ),
      );
    });
  }

  /// 后台刷新直连 IP 池。
  Future<void> _refreshIpPoolIfNeeded() async {
    try {
      final pool = ref.read(siteIpPoolProvider);
      if (!pool.isStale) return;

      // 等首屏把第一批请求发出去再探，别抢带宽
      await Future<void>.delayed(const Duration(seconds: 5));
      if (!mounted) return;

      final store = ref.read(libraryStoreProvider);
      // 有代理或 VPN 时不做直连探测：探测本身会绕过用户的分流规则，
      // 结果既不准也没意义。
      if (store.playerProxy.trim().isNotEmpty) return;

      final fresh = await pool.refresh(
        extra: store.customSiteIpList,
      );
      if (!mounted || fresh.isEmpty) return;

      // 池变了要重建 API，否则它还用着旧的候选列表
      ref.invalidate(rule34ApiProvider);
    } catch (_) {
      // 探测失败不影响使用，下次启动会再试
    }
  }

  @override
  Widget build(BuildContext context) {
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
