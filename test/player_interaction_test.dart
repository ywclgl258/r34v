import 'dart:async';
import 'package:go_router/go_router.dart';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/app/router.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:r34v/data/parsing/detail_parser.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/data/remote/stream_fetcher.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:r34v/domain/video_detail.dart';
import 'package:r34v/features/player/backends/backends.dart';
import 'package:r34v/features/player/player_page.dart';
import 'package:r34v/ui/net_image.dart';
import 'package:r34v/ui/theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'support/fake_player_backend.dart';

class _Api extends Rule34Api {
  _Api(this.detail);
  final VideoDetail detail;
  int requests = 0;
  bool fail = false;

  @override
  Future<VideoDetail> fetchDetail(String path) async {
    requests++;
    if (fail) throw StateError('test detail failure');
    return detail;
  }

  @override
  Future<FeedPage> fetchFeed(FeedRequest request) async => FeedPage(
    cards: const [],
    pager: const Pager(current: 1, total: 1),
    request: request,
    heading: '',
  );
}

class _FailedFetcher extends StreamFetcher {
  @override
  Future<StreamFetchHandle> fetch({
    required String videoId,
    required String url,
    required String quality,
    Future<String> Function()? refreshUrl,
    int? chunkSizeOverride,
    int? workers,
    void Function(int, int)? onProgress,
    void Function(String)? onReady,
    void Function(String?)? onDone,
  }) async {
    onDone?.call('test stream failure');
    return StreamFetchHandle('unused', videoId);
  }

  @override
  Future<void> cancel(String id) async {}
}

class _SlowConfigure extends FakePlayerBackend {
  final ready = Completer<void>();
  @override
  Future<void> configure() => ready.future;
}

class _NoMetadata extends FakePlayerBackend {
  @override
  Stream<Duration> get duration => const Stream.empty();
}

class _SlowVolume extends FakePlayerBackend {
  final ready = Completer<void>();
  @override
  Future<void> setVolume(double value) async {
    currentVolume = value;
    await ready.future;
  }
}

class _ReadyFetcher extends _FailedFetcher {
  @override
  Future<StreamFetchHandle> fetch({
    required String videoId,
    required String url,
    required String quality,
    Future<String> Function()? refreshUrl,
    int? chunkSizeOverride,
    int? workers,
    void Function(int, int)? onProgress,
    void Function(String)? onReady,
    void Function(String?)? onDone,
  }) async {
    onReady?.call('unused.partial');
    onDone?.call(null);
    return StreamFetchHandle('unused.partial', videoId);
  }
}

class _LazyStreams extends FakePlayerBackend {
  bool configured = false;
  @override
  Future<void> configure() async {
    configured = true;
  }

  @override
  Stream<Duration> get duration =>
      configured ? super.duration : const Stream.empty();
  @override
  Stream<Duration> get position =>
      configured ? super.position : const Stream.empty();
  @override
  Stream<String> get errors => configured ? super.errors : const Stream.empty();
  @override
  Stream<bool> get completed =>
      configured ? super.completed : const Stream.empty();
}

void main() {
  StreamFetcher? auditFetcher;
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('github.com/aaassseee/screen_brightness');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late SharedPreferences prefs;
  late FakePlayerBackend backend;
  late _Api api;
  late double brightness;
  late int resets;
  late bool brightnessSupported;

  setUp(() async {
    SharedPreferences.setMockInitialValues({
      'settings_v1_translate_tags': false,
      'settings_v1_speed': 1.5,
    });
    prefs = await SharedPreferences.getInstance();
    backend = FakePlayerBackend();
    auditFetcher = null;
    api = _Api(
      const DetailParser().parse(
        File('test/fixtures/video-4cat.html').readAsStringSync(),
      )!,
    );
    brightness = 0.5;
    resets = 0;
    brightnessSupported = true;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (!brightnessSupported) throw MissingPluginException();
      switch (call.method) {
        case 'getApplicationScreenBrightness':
          return brightness;
        case 'setApplicationScreenBrightness':
          brightness = (call.arguments as Map)['brightness'] as double;
          return null;
        case 'resetApplicationScreenBrightness':
          resets++;
          brightness = 0.5;
          return null;
      }
      throw StateError('Unexpected brightness method: ${call.method}');
    });
    NetImage.imageOverride = (_) => const AssetImage('assets/icon.png');
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    NetImage.imageOverride = null;
  });

  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    // **本文件验的是手势与生命周期，不是下载路径。**
    //
    // 生产默认走多线程分段下载（见 LibraryStore.useChunkedStreaming），
    // 但这些用例注入的 fetcher 是 `_FailedFetcher`（它故意立刻报错），
    // 以前只有在"直连失败"之后才会走到它，所以无关紧要；改成多线程优先后，
    // 它一上来就把页面打进错误态，Slider 之类的控件根本不会出现。
    //
    // 所以这里显式关掉多线程，让这些用例回到"直连成功"的前提。
    // 下载路径本身由 stream_fetcher_test 覆盖。
    await prefs.setBool('settings_v1_chunked_stream', false);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sharedPrefsProvider.overrideWithValue(prefs),
          rule34ApiProvider.overrideWithValue(api),
          playerBackendFactoryProvider.overrideWithValue((_) => backend),
          streamFetcherProvider.overrideWithValue(
            auditFetcher ?? _FailedFetcher(),
          ),
        ],
        child: MaterialApp(
          theme: buildAppTheme(),
          home: PlayerPage(videoId: api.detail.id, slug: 'test'),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    // Dart 的空流取消可能通过真实事件循环完成，先让取消链收尾。
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Rect video(WidgetTester tester) => tester.getRect(
    find.byWidgetPredicate(
      (widget) =>
          widget is GestureDetector && widget.onHorizontalDragUpdate != null,
    ),
  );
  Offset side(WidgetTester tester, double fraction) {
    final rect = video(tester);
    return Offset(rect.left + rect.width * fraction, rect.top + 35);
  }

  Future<void> vertical(WidgetTester tester, double fraction, double dy) async {
    final gesture = await tester.startGesture(side(tester, fraction));
    for (var i = 0; i < 8; i++) {
      await gesture.moveBy(Offset(0, dy / 8));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await gesture.up();
    await tester.pump();
  }

  Future<void> horizontal(WidgetTester tester) async {
    final gesture = await tester.startGesture(side(tester, 0.3));
    await gesture.moveBy(const Offset(25, 0));
    await tester.pump();
    await gesture.moveBy(const Offset(175, 0));
    await tester.pump();
    expect(backend.rate, greaterThan(1.5));
    await gesture.up();
    await tester.pump();
    expect(backend.rate, 1.5);
  }

  testWidgets('控制栏渐变空白处能单击显隐，按钮仍能控制播放', (tester) async {
    await pump(tester);
    expect(find.byType(Slider), findsOneWidget);
    await tester.tapAt(video(tester).center);
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.byType(Slider), findsNothing);
    await tester.tapAt(video(tester).center);
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.byType(Slider), findsOneWidget);
    await tester.tap(find.byIcon(Icons.pause_rounded));
    expect(backend.isPlaying, isFalse);
  });

  testWidgets('双击左右两侧实际后退和快进十秒', (tester) async {
    await pump(tester);
    backend.currentPosition = const Duration(seconds: 30);
    for (final fraction in [0.25, 0.75]) {
      await tester.tapAt(side(tester, fraction));
      await tester.pump(const Duration(milliseconds: 70));
      await tester.tapAt(side(tester, fraction));
      await tester.pump(const Duration(milliseconds: 350));
      expect(backend.currentPosition.inSeconds, fraction < 0.5 ? 20 : 30);
    }
  });

  testWidgets('左侧上下滑调整亮度，不改音量，离开恢复亮度', (tester) async {
    await pump(tester);
    await vertical(tester, 0.25, 90);
    expect(brightness, lessThan(0.5));
    expect(find.textContaining('亮度 '), findsOneWidget);
    expect(backend.currentVolume, 100);
    await vertical(tester, 0.25, -90);
    expect(brightness, closeTo(0.5, 0.001));
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(resets, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('右侧上下滑调整音量，不改亮度，提示自动收起', (tester) async {
    await pump(tester);
    await vertical(tester, 0.75, 90);
    expect(backend.currentVolume, lessThan(100));
    expect(LibraryStore(prefs).volume, backend.currentVolume);
    expect(brightness, 0.5);
    expect(find.textContaining('音量 '), findsOneWidget);
    await vertical(tester, 0.75, -90);
    expect(backend.currentVolume, closeTo(100, 0.001));
    await tester.pump(const Duration(milliseconds: 800));
    expect(find.textContaining('音量 '), findsNothing);
  });

  testWidgets('不支持亮度的平台仍能使用音量手势', (tester) async {
    brightnessSupported = false;
    await pump(tester);
    await vertical(tester, 0.25, 90);
    expect(find.text('当前平台不支持调节亮度'), findsOneWidget);
    await vertical(tester, 0.75, 90);
    expect(backend.currentVolume, lessThan(100));
    expect(tester.takeException(), isNull);
  });

  testWidgets('亮度与音量都有上下限，亮度最低保留5%', (tester) async {
    await pump(tester);
    await vertical(tester, 0.25, 1000);
    expect(brightness, 0.05);
    await vertical(tester, 0.25, -1000);
    expect(brightness, 1);
    await vertical(tester, 0.75, 1000);
    expect(backend.currentVolume, 0);
    expect(LibraryStore(prefs).volume, 10); // 下次进入不会意外保持静音
    await vertical(tester, 0.75, -1000);
    expect(backend.currentVolume, 100);
  });

  testWidgets('全屏中仍能左右分区调节，跨过中线不会切换功能', (tester) async {
    await pump(tester);
    await tester.tap(find.byTooltip('全屏'));
    await tester.pumpAndSettle();
    final gesture = await tester.startGesture(side(tester, 0.25));
    await gesture.moveBy(const Offset(0, 25));
    await tester.pump();
    await gesture.moveBy(const Offset(180, 120));
    await tester.pump();
    await gesture.up();
    await tester.pump();
    expect(brightness, lessThan(0.5));
    expect(backend.currentVolume, 100);
    await vertical(tester, 0.75, 100);
    expect(backend.currentVolume, lessThan(100));
    await tester.pump(const Duration(milliseconds: 800));
  });

  testWidgets('离开页面取消横滑提示的延时', (tester) async {
    await pump(tester);
    await horizontal(tester);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 100));
    expect(tester.takeException(), isNull);
    expect(backend.disposed, isTrue);
  });

  testWidgets('连续横滑恢复原倍速，期间不覆盖手动选定的倍速', (tester) async {
    await pump(tester);
    await horizontal(tester);
    await horizontal(tester); // 上次提示还没消失就再次滑动
    await tester.tap(find.byTooltip('倍速'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('1.25x'));
    await tester.pumpAndSettle();
    expect(backend.rate, 1.25);
    expect(LibraryStore(prefs).playbackSpeed, 1.25);
  });

  testWidgets('播放失败保留详情，换清晰度按钮能点击并恢复画面', (tester) async {
    backend.failOpen = true;
    await pump(tester);
    expect(find.textContaining('打不开视频流'), findsOneWidget);
    expect(find.text('清晰度'), findsOneWidget);
    backend.failOpen = false;
    final before = backend.opened.length;
    await tester.tap(find.text('换清晰度'));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump();
    expect(backend.opened.length, before + 1);
    expect(find.textContaining('打不开视频流'), findsNothing);
    expect(backend.isPlaying, isTrue);
  });

  testWidgets('播放错误里的重试按钮能点击，失败的换档有错误兜底', (tester) async {
    backend.failOpen = true;
    await pump(tester);
    final requests = api.requests;
    await tester.tap(find.text('重试'));
    await tester.pump(const Duration(milliseconds: 500));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pump();
    expect(api.requests, requests + 1);
    await tester.tap(find.text('换清晰度'));
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.textContaining('切换清晰度失败'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('详情请求失败显示整页错误而非空白播放器', (tester) async {
    api.fail = true;
    await pump(tester);
    expect(find.textContaining('test detail failure'), findsOneWidget);
    expect(find.byType(AspectRatio), findsNothing);
  });

  testWidgets('继续观看跳到记录位置并恢复播放', (tester) async {
    await LibraryStore(prefs).saveWatch(
      WatchEntry(
        videoId: api.detail.id,
        title: api.detail.title,
        thumb: '',
        path: api.detail.path,
        positionSeconds: 30,
        durationSeconds: 600,
        streamLabel: '',
        updatedAt: DateTime.now(),
      ),
    );
    await pump(tester);
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump();
    backend.currentPosition = const Duration(seconds: 90);
    backend.isPlaying = false;
    final button = find.textContaining('继续观看 ');
    await tester.ensureVisible(button);
    await tester.tap(button);
    await tester.pump();
    expect(backend.currentPosition.inSeconds, 30);
    expect(backend.isPlaying, isTrue);
  });

  testWidgets('打开合并页后播放不新增路由，返回一次回到列表', (tester) async {
    // 走直连路径（理由同 pump：本组不验下载器，且它注入的 fetcher 不存在）。
    await prefs.setBool('settings_v1_chunked_stream', false);
    final container = ProviderContainer(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        rule34ApiProvider.overrideWithValue(api),
        playerBackendFactoryProvider.overrideWithValue((_) => backend),
      ],
    );
    final router = container.read(routerProvider);
    addTearDown(router.dispose);
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(routerConfig: router, theme: buildAppTheme()),
      ),
    );
    await tester.pump();
    final navigation = router.push('/video/${api.detail.id}/test');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump();
    expect(find.byType(PlayerPage), findsOneWidget);
    expect(backend.isPlaying, isTrue);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await navigation;
    expect(router.routeInformationProvider.value.uri.path, '/feed/latest');
    expect(find.byType(PlayerPage), findsNothing);
    expect(backend.disposed, isTrue);
  });

  Future<GoRouter> routePump(
    WidgetTester tester,
    List<FakePlayerBackend> players, {
    FakePlayerBackend Function()? createPlayer,
  }) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    // 走直连路径（理由同 pump）。
    await prefs.setBool('settings_v1_chunked_stream', false);
    final container = ProviderContainer(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        rule34ApiProvider.overrideWithValue(api),
        playerBackendFactoryProvider.overrideWithValue((_) {
          final value = createPlayer?.call() ?? FakePlayerBackend();
          players.add(value);
          return value;
        }),
      ],
    );
    addTearDown(container.dispose);
    final router = container.read(routerProvider);
    addTearDown(router.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router),
      ),
    );
    await tester.pump();
    router.push('/video/${api.detail.id}/test');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pump();
    return router;
  }

  testWidgets('覆盖播放页应暂停播放并恢复亮度', (tester) async {
    final players = <FakePlayerBackend>[];
    final router = await routePump(tester, players);
    await vertical(tester, 0.25, 80);
    router.push('/list/tag/pov');
    await tester.pumpAndSettle();
    expect(players.first.isPlaying, isFalse, reason: '进入标签页后原视频不应继续播放');
    expect(resets, 1);
  });

  testWidgets('打开相关推荐不应两路视频同时播放', (tester) async {
    final players = <FakePlayerBackend>[];
    final router = await routePump(tester, players);
    router.push('/video/999999/related');
    await tester.pumpAndSettle();
    expect(players.length, 2);
    expect(players.where((p) => p.isPlaying).length, 1);
  });

  testWidgets('退出时仍在配置的播放器不能稍后启动', (tester) async {
    final slow = _SlowConfigure();
    backend = slow;
    await pump(tester);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    slow.ready.complete();
    await tester.pump();
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pump();
    expect(slow.opened, isEmpty);
  });

  testWidgets('从1.5倍速向右滑动不应减速', (tester) async {
    await pump(tester);
    final gesture = await tester.startGesture(side(tester, 0.3));
    await gesture.moveBy(const Offset(25, 0));
    await tester.pump(const Duration(milliseconds: 60));
    final rate = backend.rate;
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 700));
    expect(rate, greaterThanOrEqualTo(1.5));
  });

  testWidgets('第二次触摸改成竖滑不应触发双击快进', (tester) async {
    await pump(tester);
    backend.currentPosition = const Duration(seconds: 30);
    await tester.tapAt(side(tester, 0.75));
    await tester.pump(const Duration(milliseconds: 70));
    final gesture = await tester.startGesture(side(tester, 0.75));
    await gesture.moveBy(const Offset(0, 80));
    await tester.pump(const Duration(milliseconds: 60));
    await gesture.up();
    await tester.pump(const Duration(milliseconds: 800));
    expect(backend.currentPosition.inSeconds, 30);
  });

  testWidgets('边下边播解码失败必须显示错误', (tester) async {
    auditFetcher = _ReadyFetcher();
    backend.failOpen = true;
    await pump(tester);
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pump();
    expect(backend.opened.length, 2);
    expect(find.textContaining('打不开'), findsWidgets);
  });

  testWidgets('音量指令未完成时退出不能访问已销毁的ref', (tester) async {
    final slow = _SlowVolume();
    backend = slow;
    await pump(tester);
    final gesture = await tester.startGesture(side(tester, 0.75));
    await gesture.moveBy(const Offset(0, 60));
    await tester.pump(const Duration(milliseconds: 60));
    await gesture.up();
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    slow.ready.complete();
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('普通播放模式旋转到横屏不能溢出', (tester) async {
    await pump(tester);
    tester.view.physicalSize = const Size(2532, 1170);
    await tester.pump();
    expect(tester.takeException(), isNull);
  });
  testWidgets('延迟创建状态流的后端直连成功不应退回下载', (tester) async {
    final lazy = _LazyStreams();
    backend = lazy;
    await pump(tester);
    expect(lazy.isPlaying, isTrue);
    await tester.pump(const Duration(seconds: 21));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pump();
    expect(find.textContaining('打不开视频流'), findsNothing);
  });

  testWidgets('返回播放页恢复播放与亮度，手动暂停的状态则保留', (tester) async {
    final players = <FakePlayerBackend>[];
    final router = await routePump(tester, players);
    await vertical(tester, 0.25, 80);
    final previousBrightness = brightness;
    router.push('/list/tag/pov');
    await tester.pumpAndSettle();
    expect(brightness, 0.5);
    expect(players.first.isPlaying, isFalse);
    router.pop();
    await tester.pumpAndSettle();
    expect(players.first.isPlaying, isTrue);
    expect(brightness, previousBrightness);
    await players.first.pause();
    router.push('/list/tag/pov');
    await tester.pumpAndSettle();
    router.pop();
    await tester.pumpAndSettle();
    expect(players.first.isPlaying, isFalse);
  });

  testWidgets('从第二个播放页返回，销毁旧页不会覆盖当前页亮度', (tester) async {
    final players = <FakePlayerBackend>[];
    final router = await routePump(tester, players);
    await vertical(tester, 0.25, 80);
    final firstBrightness = brightness;
    router.push('/video/999999/related');
    await tester.pumpAndSettle();
    await vertical(tester, 0.25, 40);
    router.pop();
    await tester.pumpAndSettle();
    expect(players.last.disposed, isTrue);
    expect(players.first.isPlaying, isTrue);
    expect(brightness, firstBrightness);
  });

  testWidgets('配置期间切页不启动，返回后只启动一次', (tester) async {
    final players = <FakePlayerBackend>[];
    final slow = _SlowConfigure();
    final router = await routePump(tester, players, createPlayer: () => slow);
    router.push('/list/tag/pov');
    await tester.pumpAndSettle();
    slow.ready.complete();
    await tester.pump();
    expect(slow.opened, isEmpty);
    router.pop();
    await tester.pumpAndSettle();
    expect(slow.opened, hasLength(1));
    expect(slow.isPlaying, isTrue);
  });

  testWidgets('等待起播元数据时退出会取消探测超时', (tester) async {
    backend = _NoMetadata();
    await pump(tester);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 100));
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    expect(backend.disposed, isTrue);
    expect(tester.takeException(), isNull);
  });
}

