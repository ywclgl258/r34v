import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/gestures.dart' show DragStartBehavior;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:screen_brightness/screen_brightness.dart';

import 'backends/backends.dart';
import 'swipe_rate.dart';
import 'playback_guards.dart';
import 'package:dio/dio.dart' show CancelToken;

import '../../app/providers.dart';
import '../../core/constants.dart';
import '../../data/local/background_transfer.dart';
import '../../data/local/library_store.dart';
import '../../data/local/video_cache.dart';
import '../../ui/components.dart';
import '../../ui/tokens.dart';
import '../common/video_sections.dart';
import '../feed/video_card_tile.dart';
import '../feed/video_grid.dart';
import '../../data/remote/stream_fetcher.dart';
import '../../data/parsing/parse_exception.dart';
import '../../data/remote/site_client.dart';
import '../../domain/video_detail.dart';

/// 播放页。
///
/// 三件必须做对的事：
/// 1. **直链会过期**：`v-acctoken` 有寿命，播放中途 403/410 要回源换链并从当前
///    秒数续播（站点支持 Range，实测 206，所以重开也能立刻跳到位置）。
/// 2. **进度要落盘**：每 5 秒写一次，杀进程后能从「继续观看」接着看。
/// 3. **全屏横屏**：Android 上用系统 UI 沉浸 + 强制横屏；退出时复原。
class PlayerPage extends ConsumerStatefulWidget {
  const PlayerPage({super.key, required this.videoId, required this.slug});

  final String videoId;
  final String slug;

  @override
  ConsumerState<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends ConsumerState<PlayerPage>
    with WidgetsBindingObserver, RouteAware {
  /// 播放引擎（ExoPlayer 默认 / libmpv 兜底），进页面时按设置定型。
  /// 设置里切换引擎对下一个播放生效。
  late final PlayerBackend _backend = ref.read(playerBackendFactoryProvider)(
    ref.read(libraryStoreProvider).playerEngine,
  );
  late final LibraryStore _store = ref.read(libraryStoreProvider);
  late final RouteObserver<PageRoute<dynamic>> _routeObserver =
      ref.read(routeObserverProvider);
  PageRoute<dynamic>? _route;
  bool _routeVisible = true;
  bool _appActive = true;
  bool _resumeOnReturn = false;
  bool _pendingPlayback = true;
  int _playbackRevision = 0;
  VoidCallback? _cancelProbe;
  final _subscriptions = <StreamSubscription<dynamic>>[];
  bool _listening = false;
  bool get _canPlay => mounted && _routeVisible && _appActive;
  bool _isCurrent(int revision) => _canPlay && revision == _playbackRevision;
  int _doubleTapSeconds = 10;

  VideoDetail? _detail;
  StreamVariant? _current;
  String? _error;
  bool _loading = true;
  bool _fullscreen = false;
  bool _restoringStream = false;

  Timer? _saveTimer;
  Timer? _hideTimer;
  bool _controlsVisible = true;
  double _speed = 1.0;

  /// 音量：0 表示静音，null 表示从未调过。
  double? _volume;
  bool _muted = false;
  double _volumeBeforeMute = 100;
  final _gestureKey = GlobalKey();
  double get _gestureWidth => _gestureKey.currentContext?.size?.width ?? 1;

  double? _brightness;
  bool _brightnessChanged = false;
  bool _brightnessApplied = false;
  bool _verticalAdjustsBrightness = false;
  String? _verticalFeedback;
  Timer? _verticalFeedbackTimer;

  /// 横向拖动的累计位移（本次手势）。
  double _rateDragDx = 0;

  /// 本次手势是不是在调速。只有判定成立才动播放器，
  /// 避免误触把好好的 1.0x 改掉。
  bool _rateDragging = false;

  /// 调速提示是否可见。
  bool _swipeRateVisible = false;
  double _swipeRate = 1.0;
  double _rateBeforeDrag = 1.0;

  /// 上次回源换链的时间，用来做节流（见 `_recoverStream`）。
  DateTime? _lastRecoverAt;

  /// 已经真正下发给播放器的倍速。
  ///
  /// 滑动是逐帧回调，但档位只有 8 档，同一档内不该重复下发：
  /// 每次下发都要跨 platform channel 打到 ExoPlayer，逐帧发会把主线程
  /// 和播放器都拖住，播放看起来就像卡住了。
  late final RateApplyGate _rateGate = RateApplyGate(initial: _speed);

  /// 松手后收起临时倍速提示。
  Timer? _rateRestoreTimer;

  /// 本次播放是不是用的本地缓存文件。
  bool _playingFromCache = false;

  /// 进页面时读到的上次进度，用来显示「继续观看」入口。
  WatchEntry? _savedProgress;

  /// 正在试探直连是否可用（期间不触发自动换链）。
  bool _probingDirect = false;

  /// 边下边播的缓冲状态。
  bool _buffering = false;
  int _fetchReceived = 0;
  int _fetchTotal = 0;
  StreamFetchHandle? _currentFetch;

  /// 正在做后台缓存。
  bool _caching = false;
  CacheProgress? _cacheProgress;
  String _cacheReason = '';
  CancelToken? _cacheCancel;

  /// 记录「本次播放已经因为 token 过期换过几次链」。防止坏链反复重试打死站点。
  int _refreshCount = 0;
  static const int _maxRefresh = 2;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // 注意顺序：_speed / 音量下发必须在 _backend 可用之后，且**绝不能抛异常**。
    //
    // 这里曾经是个真坑：ExoBackend 在 `open` 之前调用 setRate 会同步抛
    // StateError（`=> _require.xxx()` 的 expression body 是同步抛，不是
    // 返回失败的 Future），initState 一崩，播放页连 build 都跑不到，
    // release 下就只剩一块 Flutter 错误框——看起来正是「灰屏」。
    // 现在后端把未 open 时的控制调用都做成 no-op 并记下意图，open 后补上；
    // 这里再包一层兜底，保证任何引擎实现出错都不至于让整页打不开。
    try {
      _speed = _store.playbackSpeed;
      // 记下来，避免横滑时第一次就把同一个档位重复下发一遍。
      _rateBeforeDrag = _speed;
      _rateGate.reset(applied: _speed);
      unawaited(_backend.setRate(_speed).catchError((Object _) {}));
      final savedVolume = _store.volume;
      if (savedVolume != null) {
        _volume = savedVolume < 10 ? 10 : savedVolume;
        unawaited(_backend.setVolume(_volume!).catchError((Object _) {}));
      }
    } catch (_) {
      // 引擎未就绪不该阻断页面；起播时还会再下发一次。
    }
    _readBrightness();
    _load();
    _saveTimer = Timer.periodic(const Duration(seconds: 5), (_) => _persistProgress());
    if (ref.read(libraryStoreProvider).autoFullscreen) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _enterFullscreen());
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route is PageRoute<dynamic> && route != _route) {
      _routeObserver.unsubscribe(this);
      _route = route;
      _routeObserver.subscribe(this, route);
    }
  }

  void _invalidatePlayback() {
    _playbackRevision++;
    _cancelProbe?.call();
    _cancelProbe = null;
    _probingDirect = false;
  }

  void _resetBrightness() {
    if (!_brightnessApplied) return;
    _brightnessApplied = false;
    unawaited(ScreenBrightness.instance
        .resetApplicationScreenBrightness()
        .catchError((Object _) {}));
  }

  void _suspendPlayback({required bool resumeOnReturn}) {
    _resumeOnReturn = resumeOnReturn &&
        (_backend.isPlaying || _pendingPlayback || _loading);
    _invalidatePlayback();
    _persistProgress();
    unawaited(_backend.pause().catchError((Object _) {}));
    _resetBrightness();
    if (_rateDragging) _onRateDragEnd();
  }

  @override
  void didPushNext() {
    _routeVisible = false;
    _suspendPlayback(resumeOnReturn: true);
  }

  @override
  void didPop() {
    _routeVisible = false;
    _suspendPlayback(resumeOnReturn: false);
  }

  @override
  void didPopNext() {
    _routeVisible = true;
    // 先让被弹出页面恢复亮度，再应用当前页自己的亮度。
    scheduleMicrotask(_restoreVisiblePage);
  }

  void _restoreVisiblePage() {
    if (!_canPlay || (_route != null && !_route!.isCurrent)) return;
    if (_brightnessChanged && _brightness != null) {
      _brightnessApplied = true;
      unawaited(ScreenBrightness.instance
          .setApplicationScreenBrightness(_brightness!)
          .catchError((Object _) {}));
    }
    if (!_resumeOnReturn) return;
    _resumeOnReturn = false;
    if (_pendingPlayback && _detail != null && _current != null) {
      unawaited(_startPlayback(_detail!, _current!));
    } else if (!_pendingPlayback) {
      unawaited(_backend.resume().catchError((Object _) {}));
    }
  }

  @override
  void dispose() {
    _routeVisible = false;
    _invalidatePlayback();
    _routeObserver.unsubscribe(this);
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    WidgetsBinding.instance.removeObserver(this);
    _saveTimer?.cancel();
    _hideTimer?.cancel();
    // 调速提示的回弹延时。不取消的话，离开页面后它还会触发一次
    // setState（mounted 已为 false 所以不会崩），但测试框架会报
    // "A Timer is still pending"——而且这是个真实泄漏，应当清掉。
    _rateRestoreTimer?.cancel();
    _verticalFeedbackTimer?.cancel();
    _resetBrightness();

    // 先记下位置：dispose 之后 player 的状态就不可靠了
    final posNow = _backend.currentPosition.inSeconds;
    final durNow = _backend.currentDuration.inSeconds;

    // **先停掉声音，再处理其它事。**
    //
    // 之前顺序是「存进度 → dispose(player)」，而存进度里若抛异常
    // （prefs 写失败、player 状态已失效等），后面的 _backend.dispose()
    // 就永远执行不到——表现就是退出页面后视频还在后台放，有声音。
    // 所以这里每一步都单独兜住，任何一步失败都不影响停止播放。
    try {
      unawaited(_backend.pause());
    } catch (_) {}
    try {
      unawaited(_backend.dispose());
    } catch (_) {}

    // 再存进度
    try {
      _persistProgressAt(posNow, durNow);
    } catch (_) {}

    // 传输任务：**解绑而不是取消**。
    //
    // 之前这里直接 `_fetcher.cancel(...)`，退出页面 = 缓存/下载立刻中断。
    // 现在改成 detach：进度回调不再 setState，但下载继续在后台跑，
    // 由前台服务保活。用户显式取消才真的停（走 TransferRegistry.cancel）。
    final fetching = _currentFetch;
    if (fetching != null) {
      try {
        TransferRegistry.instance.find(fetching.videoId)?.detach();
      } catch (_) {}
    }

    if (_fullscreen) {
      SystemChrome.setPreferredOrientations(DeviceOrientation.values);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
    super.dispose();
  }

  String get _path => '/video/${widget.videoId}/${widget.slug}/';

  /// dispose 用的存进度版本：位置和时长由调用方传入，
  /// 不再读已经要销毁的 player。
  void _persistProgressAt(int pos, int dur) {
    final d = _detail;
    if (d == null) return;
    if (pos <= 0) return;
    final total = dur > 0 ? dur : d.durationSeconds;
    _store.saveWatch(WatchEntry(
          videoId: d.id,
          title: d.title,
          thumb: d.poster,
          path: d.path,
          positionSeconds: pos,
          durationSeconds: total,
          streamLabel: _current?.label ?? '',
          updatedAt: DateTime.now(),
        ));
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 切后台就存进度并暂停，避免后台继续消耗流量
    if (state == AppLifecycleState.paused || state == AppLifecycleState.inactive) {
      _appActive = false;
      _suspendPlayback(resumeOnReturn: false);
    } else if (state == AppLifecycleState.resumed) {
      _appActive = true;
      _restoreVisiblePage();
    }
  }

  void _listen() {
    if (_listening) return;
    _listening = true;
    _subscriptions.add(_backend.errors.listen((e) async {
      if (!_canPlay) return;
      // 直连探测期间由 _openRemoteDirect 自己判断成败，
      // 这里若也抢着回源换链，会两路同时开打。
      if (_probingDirect) return;
      if (_refreshCount >= _maxRefresh) {
        setState(() => _error = '播放失败：$e\n\n已经自动重试过 $_maxRefresh 次，请重试或换清晰度。');
        return;
      }
      // 只有 401/403/410 才是 token 过期；其它错误（网络抖动、
      // TLS 失败）重开同一条链就好，不必回源——回源反而可能拿到
      // 一样的新链还白说一句「链接过期」误导用户。
      if (_looksLikeTokenExpiry(e)) {
        await _recoverStream(reason: '链接过期（$e）');
      } else {
        await _retrySameStream(reason: e);
      }
    }));

    // 播完之后把进度写成「已完成」
    _subscriptions.add(_backend.completed.listen((done) {
      if (mounted && done) _persistProgress(forceFinished: true);
    }));
  }

  /// 错误信息里是否真的表达了「HTTP 401/403/410」。
  ///
  /// 实现在 [looksLikeTokenExpiry]（可单独测试）。要点：**不能简单
  /// `contains('403')`**，否则 `position 403000` 会被误判成过期，
  /// 进而回源抓详情页——那是「线路被限流」的来源。
  static bool _looksLikeTokenExpiry(String err) => looksLikeTokenExpiry(err);

  /// 不重回源，直接用当前链重开一次（从当前位置续）。
  /// 网络抖动类错误多半这样就好了。
  Future<void> _retrySameStream({required String reason}) async {
    final d = _detail;
    final s = _current;
    if (d == null || s == null || !_canPlay) return;
    final revision = ++_playbackRevision;
    _pendingPlayback = true;
    _refreshCount++;
    try {
      final pos = _backend.currentPosition;
      final wasPlaying = _backend.isPlaying;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('播放中断，正在重试…（$reason）'),
            duration: const Duration(seconds: 2),
          ),
        );
      }
      await _openAndSeek(s.url, pos.inSeconds, revision: revision, play: wasPlaying);
    } catch (e) {
      if (!_isCurrent(revision)) return;
      // 重试还失败，按 token 过期处理
      await _recoverStream(reason: '重试失败（$e）');
    }
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
      _refreshCount = 0;
    });
    try {
      final api = ref.read(rule34ApiProvider);
      final d = await api.fetchDetail(_path);
      if (!mounted) return;

      // 优先用上次这个视频用过的清晰度，其次用全局偏好，最后用详情页推荐
      final saved = ref.read(libraryStoreProvider).findWatch(d.id);
      final globalPref = ref.read(libraryStoreProvider).preferredQuality;
      final label = (saved?.streamLabel.isNotEmpty ?? false) ? saved!.streamLabel : globalPref;
      final stream = d.streams.firstWhere(
        (s) => s.label == label,
        orElse: () => d.preferredStream ?? d.streams.first,
      );

      // 屏蔽拦截：详情层有真实 tag，命中就不播
      final blocked = ref.read(blockedTagsProvider);
      final hit = d.tags.where((t) => blocked.contains(t.toLowerCase())).toList();
      if (hit.isNotEmpty) {
        setState(() {
          _error = '已按屏蔽设置拦截（命中标签：${hit.join('、')}）\n\n'
              '可在「设置 → 屏蔽标签」里管理';
          _loading = false;
          _detail = null;
        });
        return;
      }

      setState(() {
        _detail = d;
        _current = stream;
        _savedProgress = saved;
        _loading = false;
      });
      await _startPlayback(d, stream);
    } on HttpException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '打不开：${e.message}';
        _loading = false;
      });
    } on ParseException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '解析失败：${e.message}';
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '打不开：$e';
        _loading = false;
      });
    }
  }

  Future<void> _startPlayback(VideoDetail d, StreamVariant s) async {
    final revision = ++_playbackRevision;
    _pendingPlayback = true;
    if (!_isCurrent(revision)) return;
    try {
      // 从上次看到的位置接着播
      final saved = ref.read(libraryStoreProvider).findWatch(d.id);
      final resumeAt = (saved != null && saved.isResumable) ? saved.positionSeconds : 0;

      final cache = ref.read(videoCacheProvider);
      // 优先播本地完整缓存：省流量、不受 token 过期影响、拖动也准
      final local = cache.localPathFor(d.id);
      if (local != null) {
        await _openAndSeek(local, resumeAt, revision: revision);
        if (!_isCurrent(revision)) return;
        _playingFromCache = true;
        if (mounted) setState(() {});
        return;
      }

      // **优先多线程分段下载，而不是直连。**
      //
      // 这是「缓冲比网页慢」和「线路被限流」两个问题的共同根因。
      //
      // 直连时 ExoPlayer 对单个 mp4 只开**一条** HTTP 连接，而站点对
      // 单连接有限速——这就是速度天花板，几倍于网页端（网页用 hls/多段
      // 请求，天然多连接）。之前的顺序是「先直连，失败才退多线程」，
      // 于是多线程这条路几乎从未被启用过。
      //
      // 反过来：提前走多线程也解决限流。反复直连探测失败会触发
      // `_recoverStream` 回源重新抓详情页，一次播放能打好几次详情页，
      // 那才是被判定成异常流量的原因。
      //
      // 代价：读正在写入的 `.partial` 文件时，seek 到未下载区间会被
      // 当成文件末尾。缓解办法是设置里可切回直连（见播放设置），
      // 且分段下载本身边下边播，起播只需 512KB。
      final useFetch = ref.read(libraryStoreProvider).useChunkedStreaming;
      if (useFetch) {
        await _openViaFetch(d, s, resumeAt, revision);
        return;
      }

      // 设置里关掉多线程时走直连（也可作为多线程出问题时的退路）。
      final ok = await _openRemoteDirect(s.url, resumeAt, revision);
      if (!_isCurrent(revision)) return;
      if (ok) return;

      // 直连失败再退回多线程保底。
      await _openViaFetch(d, s, resumeAt, revision);
    } catch (e) {
      if (!_isCurrent(revision)) return;
      setState(() {
        _buffering = false;
        _error = '打不开视频流：$e';
      });
    }
  }

  /// 用作 Referer 的**视频详情页地址**。
  ///
  /// 站点有反盗链白名单，实测：
  /// - 外站 Referer → **403 Forbidden**
  /// - 站点根 `/` → 放行（但不是浏览器的行为）
  /// - 视频页 URL → 放行（**这才是浏览器发的东西**）
  ///
  /// 所以这里拼真实页面地址，而不是用站点根。
  String get _pageReferer {
    final id = widget.videoId;
    final slug = widget.slug;
    return slug.isEmpty
        ? '${Site.base}/video/$id/'
        : '${Site.base}/video/$id/$slug/';
  }

  /// 把站点地址解析成 CDN 直链；解析不出来就用原地址。
  ///
  /// 解析失败不该阻断播放——原地址虽然慢（每次 Range 都要走 302），
  /// 但能播。所以这里永不抛异常。
  Future<String> _resolvePlaybackUrl(String url) async {
    if (!url.startsWith('http')) return url;
    try {
      final resolver = ref.read(streamResolverProvider);
      // 先看会话缓存：seek 重试路径上不该再等一次网络。
      final cached = resolver.cachedFor(url);
      if (cached != null) return cached;
      final cdn = await resolver.resolve(url, pageUrl: _pageReferer);
      return cdn ?? url;
    } catch (_) {
      return url;
    }
  }

  /// 直接打开远端地址，返回是否成功起播。
  ///
  /// media_kit 的 `open` 不会因为网络失败而抛异常，错误是从 `stream.error`
  /// 出来的，所以这里同时监听 duration（成功）和 error（失败），加超时兜底。
  ///
  /// **打开前先把站点地址解析成 CDN 直链。** 站点对 `/get_file/...` 回 302
  /// 跳 CDN，播放器每发一个 Range 请求（首播 / 每次 seek / 每次重连）都要
  /// 重走一遍这条链路。实测同一文件 20MB：
  ///
  /// | 请求方式 | 耗时 | 吞吐 |
  /// |---|---|---|
  /// | 每次 302 | 23.6s | 887 KB/s |
  /// | CDN 直链 | 6.0s | 3.48 MB/s |
  ///
  /// 差 4 倍，这就是「浏览器很快、App 很卡」的原因。解析一次、整个会话复用。
  Future<bool> _openRemoteDirect(String url, int resumeAt, int revision) async {
    await _backend.configure();
    if (!_isCurrent(revision)) return false;
    _listen();
    final done = Completer<bool>();
    StreamSubscription<String>? errSub;
    StreamSubscription<Duration>? durSub;
    StreamSubscription<Duration>? posSub;

    void finish(bool v) {
      if (!done.isCompleted) done.complete(v);
    }
    void cancel() => finish(false);
    _cancelProbe = cancel;

    // 探测期间抑制 _listen 里的自动换链，避免直连失败时又触发一轮回源
    _probingDirect = true;
    errSub = _backend.errors.listen((e) {
      finish(false);
    });
    // 有任一时长或播放位置推进，就说明连通了
    durSub = _backend.duration.listen((dur) {
      if (dur.inMilliseconds > 0) finish(true);
    });
    posSub = _backend.position.listen((pos) {
      if (pos.inMilliseconds > 0) finish(true);
    });

    try {
      final target = await _resolvePlaybackUrl(url);
      if (!_isCurrent(revision)) return false;
      await _backend.open(target, play: true, referer: _pageReferer);
      if (!_isCurrent(revision)) return false;
      // 站点实测首字节要 5–11 秒，给足余量；太短会把能直连的误判成失败，
      // 一退回边下边播拖动就又坏了。
      final ok = await done.future.timeout(
        const Duration(seconds: 20),
        onTimeout: () => false,
      );
      if (!ok || !_isCurrent(revision)) return false;

      if (resumeAt > 0) {
        await Future<void>.delayed(const Duration(milliseconds: 350));
        if (!_isCurrent(revision)) return false;
        await _backend.seek(Duration(seconds: resumeAt));
      }
      await _backend.setRate(_speed);
      if (!_isCurrent(revision)) return false;
      if (_volume != null) await _backend.setVolume(_muted ? 0 : _volume!);
      if (_isCurrent(revision)) {
        setState(() {
          _buffering = false;
          _error = null;
          _playingFromCache = false;
          _pendingPlayback = false;
        });
      }
      return true;
    } catch (_) {
      return false;
    } finally {
      if (identical(_cancelProbe, cancel)) {
        _cancelProbe = null;
        _probingDirect = false;
      }
      await errSub.cancel();
      await durSub.cancel();
      await posSub.cancel();
    }
  }

  /// 通过「边下边播」打开远端流。
  Future<void> _openViaFetch(VideoDetail d, StreamVariant s, int resumeAt, int revision) async {
    if (!_isCurrent(revision)) return;
    final fetch = ref.read(streamFetcherProvider);
    setState(() {
      _buffering = true;
      _cacheProgress = null;
    });

    var opened = false;
    var starting = false;

    final handle = await fetch.fetch(
      videoId: d.id,
      url: s.url,
      quality: s.label,
      // 并行连接数：设置里可调（1–8）。站点对单连接限速，
      // 多连接并行是「缓冲比网页慢」的主要解法。
      workers: ref.read(libraryStoreProvider).streamWorkers,
      // 分段下载中途 token 过期：回源换新链续传（换的是同清晰度的链）
      refreshUrl: () async {
        final api = ref.read(rule34ApiProvider);
        final fresh = await api.fetchDetail(_path);
        if (!_isCurrent(revision)) return s.url;
        setState(() => _detail = fresh);
        final label = _current?.label ?? s.label;
        return fresh.streams
                .firstWhere(
                  (x) => x.label == label,
                  orElse: () =>
                      fresh.preferredStream ?? fresh.streams.first,
                )
                .url;
      },
      onProgress: (received, total) {
        if (!_isCurrent(revision)) return;
        setState(() {
          _fetchReceived = received;
          _fetchTotal = total;
        });
      },
      onReady: (filePath) {
        // 头部数据够了，可以起播
        if (starting || opened || !_isCurrent(revision)) return;
        starting = true;
        unawaited(_openAndSeek(filePath, resumeAt, revision: revision).then((_) {
          if (_isCurrent(revision)) opened = true;
        }).catchError((Object e) {
          if (!_isCurrent(revision)) return;
          setState(() {
            _buffering = false;
            _error = '打不开视频流：$e';
          });
        }));
      },
      onDone: (err) {
        if (!_isCurrent(revision)) return;
        final fatal = err != null && !opened && !starting;
        if (fatal) {
          // 还没起播就失败，才是真失败
          setState(() {
            _buffering = false;
            _error = '打不开视频流：$err';
          });
        } else {
          setState(() {
            _buffering = false;
            _fetchReceived = 0;
            _fetchTotal = 0;
          });
        }
      },
    );

    if (!_isCurrent(revision)) {
      await fetch.cancel(d.id);
      return;
    }
    _currentFetch = handle;

    // 收藏的视频顺手转成正式缓存，下次就不用再抓了
    if (ref.read(videoCacheProvider).autoCache) {
      _promoteToCache(d, s, handle.filePath);
    }
  }

  /// 把边下边播抓到的文件升格为正式缓存记录。
  Future<void> _promoteToCache(
    VideoDetail d,
    StreamVariant s,
    String partialPath,
  ) async {
    try {
      final cache = ref.read(videoCacheProvider);
      if (cache.isCached(d.id)) return;
      await cache.adoptFile(
        videoId: d.id,
        title: d.title,
        sourcePath: partialPath,
        quality: s.label,
        thumb: d.poster,
        durationSeconds: d.durationSeconds,
      );
      if (!mounted) return;
      ref.invalidate(cacheIndexProvider);
    } catch (_) {
      // 升格失败不影响当前播放
    }
  }

  Future<void> _openAndSeek(String source, int resumeAt, {
    required int revision, bool play = true,
  }) async {
    if (!_isCurrent(revision)) return;
    await _backend.configure();
    if (!_isCurrent(revision)) return;
    _listen();
    // 本地文件（缓存）没有 302 可解析，只有远端地址才值得解析。
    // _resolvePlaybackUrl 内部对非 http 地址直接原样返回。
    final target = await _resolvePlaybackUrl(source);
    if (!_isCurrent(revision)) return;
    await _backend.open(target, play: play, referer: _pageReferer);
    if (!_isCurrent(revision)) return;
    if (resumeAt > 0) {
      // media_kit 在 open 后立刻 seek 有时会被忽略，等一小会儿再跳
      await Future<void>.delayed(const Duration(milliseconds: 350));
      if (!_isCurrent(revision)) return;
      await _backend.seek(Duration(seconds: resumeAt));
    }
    await _backend.setRate(_speed);
    if (!_isCurrent(revision)) return;
    if (_volume != null) await _backend.setVolume(_muted ? 0 : _volume!);
    if (_isCurrent(revision)) {
      setState(() {
        _buffering = false;
        _error = null;
        _pendingPlayback = false;
      });
    }
  }

  Future<void> _startCache(VideoDetail d, StreamVariant s, {String reason = ''}) async {
    if (_caching) return;
    // Web 预览下浏览器会拦跨域下载，这里先说清楚，别让人以为缓存坏了
    if (kIsWeb) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('网页预览不能缓存（浏览器跨域限制），安装 APK 后才可用'),
          duration: Duration(seconds: 3),
        ),
      );
      return;
    }
    _caching = true;
    _cacheCancel = CancelToken();
    setState(() {
      _cacheProgress = const CacheProgress(received: 0, total: 0);
      _cacheReason = reason;
    });

    final cache = ref.read(videoCacheProvider);
    final label = '缓存：${d.title}';
    // 缓存走 CDN 直链：站点对 /get_file/ 回 302，Dio 每次重定向都要重付
    // 一次开销。实测直链比走 302 快 4 倍。
    final cacheUrl = await _resolvePlaybackUrl(s.url);
    if (!mounted) return;
    // 注册到全局任务表 + 拉起前台服务：**退出页面后继续下**。
    final session = TransferSession(
      id: d.id,
      label: label,
      cancel: () async {
        _cacheCancel?.cancel();
      },
    );
    TransferRegistry.instance.add(session);
    unawaited(BackgroundTransfer.begin(label));

    var lastNotify = DateTime.fromMillisecondsSinceEpoch(0);
    cache
        .cache(
      videoId: d.id,
      title: d.title,
      url: cacheUrl,
      quality: s.label,
      thumb: d.poster,
      pageUrl: _pageReferer,
      durationSeconds: d.durationSeconds,
      cancelToken: _cacheCancel,
      onProgress: (prog) {
        // 通知更新要节流：跨进程调用，每帧发会卡。
        final now = DateTime.now();
        if (now.difference(lastNotify) >= const Duration(seconds: 1)) {
          lastNotify = now;
          final pct = prog.total > 0
              ? (prog.received * 100 ~/ prog.total)
              : 0;
          unawaited(BackgroundTransfer.update(
            label,
            '已下载 ${_fmtMb(prog.received)}'
            '${prog.total > 0 ? ' / ${_fmtMb(prog.total)}（$pct%）' : ''}',
          ));
        }
        // 页面已解绑（用户退出了）就不再 setState，但下载照跑。
        if (!mounted || session.detached) return;
        setState(() => _cacheProgress = prog);
      },
    )
        .then((entry) {
      unawaited(TransferRegistry.instance.finish(d.id));
      if (!mounted || session.detached) return;
      setState(() {
        _caching = false;
        _cacheProgress = null;
      });
      ref.invalidate(cacheIndexProvider);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('已缓存 ${s.label}（${entry.megabytes.toStringAsFixed(0)} MB），下次秒开'),
          duration: const Duration(seconds: 2),
        ),
      );
    })
        .catchError((Object e) {
      unawaited(TransferRegistry.instance.finish(d.id));
      if (!mounted || session.detached) return;
      setState(() {
        _caching = false;
        _cacheProgress = null;
      });
      // 缓存失败不该打断观看，只提示一句
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('缓存没成功：$e'),
          duration: const Duration(seconds: 3),
        ),
      );
    });
  }

  static String _fmtMb(int bytes) =>
      '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';

  /// 直链过期时的恢复：回源详情页拿新链，从当前位置续播。
  Future<void> _recoverStream({required String reason}) async {
    if (_restoringStream || !_canPlay) return;
    // 回源节流：两次回源之间至少隔 3 秒。
    //
    // 回源 = 重新抓一次详情页，是站点最容易被判定成异常流量的动作。
    // 播放器偶尔会连续抛一串错误（解码重试、链路抖动），没有节流的话
    // 就会连着打好几个详情页——这正是「线路被限流」的来源之一。
    // 被节流时静默跳过：上层 `_listen` 还有重试计数兜底。
    final now = DateTime.now();
    if (_lastRecoverAt != null &&
        now.difference(_lastRecoverAt!) < const Duration(seconds: 3)) {
      return;
    }
    _lastRecoverAt = now;
    final revision = ++_playbackRevision;
    _pendingPlayback = true;
    _restoringStream = true;
    _refreshCount++;
    try {
      final pos = _backend.currentPosition;
      final wasPlaying = _backend.isPlaying;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('链接过期，正在换新链接…（$reason）'),
            duration: const Duration(seconds: 2),
          ),
        );
      }

      final api = ref.read(rule34ApiProvider);
      final fresh = await api.refreshStreams(_detail!);
      if (!_isCurrent(revision)) return;

      final label = _current?.label ?? '';
      final stream = fresh.streams.firstWhere(
        (s) => s.label == label,
        orElse: () => fresh.preferredStream ?? fresh.streams.first,
      );

      setState(() {
        _detail = fresh;
        _current = stream;
      });

      await _openAndSeek(stream.url, pos.inSeconds, revision: revision, play: wasPlaying);
    } catch (e) {
      if (!_isCurrent(revision)) return;
      setState(() => _error = '换链接失败：$e\n这个视频现在打不开，稍后再试。');
    } finally {
      _restoringStream = false;
    }
  }

  Future<void> _switchQuality(StreamVariant s) async {
    final d = _detail;
    if (!_canPlay || d == null || (s.label == _current?.label && _error == null)) return;
    final revision = ++_playbackRevision;
    _pendingPlayback = true;
    final pos = _backend.currentPosition;
    setState(() {
      _current = s;
      _error = null;
      _refreshCount = 0;
    });
    try {
      await ref.read(libraryStoreProvider).setPreferredQuality(s.label);
      if (!_isCurrent(revision)) return;
      await _openAndSeek(s.url, pos.inSeconds, revision: revision);
    } catch (e) {
      if (_isCurrent(revision)) setState(() => _error = '切换清晰度失败：$e');
    }
  }

  Future<void> _readBrightness() async {
    try {
      final value = await ScreenBrightness.instance.application;
      if (mounted) _brightness = value.clamp(0.05, 1.0);
    } catch (_) {
      // Web 预览或平台不支持时，不阻断播放与其它手势。
    }
  }

  void _onVerticalDragStart(DragStartDetails details) {
    _verticalAdjustsBrightness = details.localPosition.dx < _gestureWidth / 2;
    _verticalFeedbackTimer?.cancel();
    _hideTimer?.cancel();
  }

  Future<void> _onVerticalDragUpdate(DragUpdateDetails details) async {
    final delta = -details.delta.dy;
    if (delta == 0) return;
    if (_verticalAdjustsBrightness) {
      final current = _brightness;
      if (current == null) {
        setState(() => _verticalFeedback = '当前平台不支持调节亮度');
        return;
      }
      final value = (current + delta * 0.004).clamp(0.05, 1.0);
      _brightness = value;
      _brightnessChanged = true;
      _brightnessApplied = true;
      final revision = _playbackRevision;
      try {
        await ScreenBrightness.instance.setApplicationScreenBrightness(value);
        if (_isCurrent(revision)) {
          setState(() => _verticalFeedback = '亮度 ${(value * 100).round()}%');
        }
      } catch (_) {
        if (mounted) {
          setState(() => _verticalFeedback = '当前平台不支持调节亮度');
        }
      }
    } else {
      final current = _muted ? 0.0 : (_volume ?? _backend.currentVolume);
      final value = (current + delta * 0.4).clamp(0.0, 100.0);
      setState(() => _verticalFeedback = '音量 ${value.round()}%');
      await _setVolume(value);
    }
  }

  void _onVerticalDragEnd() {
    _verticalFeedbackTimer?.cancel();
    _verticalFeedbackTimer = Timer(const Duration(milliseconds: 700), () {
      if (mounted) setState(() => _verticalFeedback = null);
    });
  }

  /// 设置音量（0–100）。
  ///
  /// 持久化有个下限 10：拖到 0 只代表「这次想安静」，不该让以后每次
  /// 打开播放页都无声。静音状态本身也不落盘。
  Future<void> _setVolume(double v) async {
    final store = _store;
    final clamped = v.clamp(0.0, 100.0);
    setState(() {
      _volume = clamped;
      _muted = clamped <= 0;
      if (clamped > 0) _volumeBeforeMute = clamped;
    });
    await _backend.setVolume(clamped);
    await store.setVolume(LibraryStore.persistableVolume(clamped));
  }

  /// 静音/取消静音。静音记的是「静音前那一刻的音量」，能原样恢复。
  Future<void> _toggleMute() async {
    if (_muted) {
      final restore = _volumeBeforeMute <= 0 ? 100.0 : _volumeBeforeMute;
      setState(() => _muted = false);
      await _setVolume(restore);
    } else {
      final cur = _backend.currentVolume;
      setState(() {
        _volumeBeforeMute = cur > 0 ? cur : 100;
        _muted = true;
        _volume = 0;
      });
      await _backend.setVolume(0);
      // 静音不落盘——落盘下限见 _setVolume
    }
  }

  /// 点音量图标弹一个小滑块。
  ///
  /// 手机上没有滚轮，直接拖盘最省事。做成气泡而不是常驻滑块，
  /// 是因为播放器底栏已经挤了播放键、时间、清晰度、倍速、全屏五个控件。
  void _showVolumeSheet() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.black87,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setSheet) {
            final current = _muted ? 0.0 : (_volume ?? _backend.currentVolume);
            return SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        const Text('音量', style: TextStyle(color: Colors.white)),
                        const Spacer(),
                        Text(
                          '${current.round()}%',
                          style: const TextStyle(color: Colors.white70),
                        ),
                        const SizedBox(width: 8),
                        IconButton(
                          icon: Icon(
                            current <= 0 ? Icons.volume_off : Icons.volume_up,
                            color: Colors.white,
                          ),
                          onPressed: () async {
                            await _toggleMute();
                            if (context.mounted) setSheet(() {});
                          },
                        ),
                      ],
                    ),
                    Slider(
                      value: current.clamp(0, 100),
                      max: 100,
                      divisions: 20,
                      label: '${current.round()}',
                      onChanged: (v) async {
                        await _setVolume(v);
                        if (context.mounted) setSheet(() {});
                      },
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  void _persistProgress({bool forceFinished = false}) {
    final d = _detail;
    if (d == null) return;
    var pos = _backend.currentPosition.inSeconds;
    var dur = _backend.currentDuration.inSeconds;
    if (dur <= 0) dur = d.durationSeconds;
    if (forceFinished && dur > 0) pos = dur;
    if (pos <= 0 && !forceFinished) return;

    _store.saveWatch(WatchEntry(
          videoId: d.id,
          title: d.title,
          thumb: d.poster,
          path: d.path,
          positionSeconds: pos,
          durationSeconds: dur,
          updatedAt: DateTime.now(),
          streamLabel: _current?.label ?? '',
        )).then((_) {
      // 让片库/列表上的进度条跟着动
      if (mounted) ref.invalidate(historyProvider);
    });
  }

  Future<void> _enterFullscreen() async {
    setState(() => _fullscreen = true);
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    await SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
  }

  Future<void> _exitFullscreen() async {
    setState(() => _fullscreen = false);
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    await SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
    ]);
  }

  void _toggleControls() {
    setState(() => _controlsVisible = !_controlsVisible);
    _hideTimer?.cancel();
    if (_controlsVisible) {
      _hideTimer = Timer(const Duration(seconds: 4), () {
        if (mounted) setState(() => _controlsVisible = false);
      });
    }
  }

  Future<void> _togglePlayback() async {
    if (!_canPlay) return;
    if (_backend.isPlaying) {
      _resumeOnReturn = false;
      await _backend.pause();
    } else if (_pendingPlayback && _detail != null && _current != null) {
      await _startPlayback(_detail!, _current!);
    } else {
      await _backend.resume();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading && _detail == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    // 详情都没拿到（网络失败/解析失败/被屏蔽），这时没有信息可显示，
    // 整页给错误态。
    final d = _detail;
    if (d == null) {
      return Scaffold(
        appBar: AppBar(),
        body: ErrorState(message: _error ?? '没能加载这个视频', onRetry: _load),
      );
    }

    // 走到这里说明详情已经有了，**只有播放这一环可能失败**。
    // 播放失败不该把整页变成错误页——用户还想看标题、标签、简介，
    // 甚至想换一档清晰度再试。所以错误只占据视频区。
    final body = Stack(
      children: [
        // 视频填满区域，后面的手势层与控件按 Stack 顺序接收指针。
        Positioned.fill(
          child: _error != null
              ? _buildPlayerError()
              : Center(child: _backend.videoWidget()),
        ),
        // 手势层。
        //
        // 手势分工：
        // - 单击：显隐控制栏
        // - 双击左/右半屏：退/进 10 秒
        // - 竖向滑：左侧调亮度、右侧调音量（按起手位置锁定）
        // - 横向滑：临时改播放速度，松手恢复原倍速
        //
        // 横向滑用 `onHorizontalDrag*` 而不是在 `onPanUpdate` 里自己判断方向：
        // Flutter 的手势竞技场已经做了方向仲裁，横竖两个回调各自只在
        // 对应方向占优时才触发，比手写阈值可靠，也不会和竖向调音量打架。
        if (_error == null)
          Positioned.fill(
            child: GestureDetector(
              key: _gestureKey,
              behavior: HitTestBehavior.opaque,
              dragStartBehavior: DragStartBehavior.down,
              onTap: _toggleControls,
              onDoubleTapDown: (details) => _doubleTapSeconds =
                  details.localPosition.dx < _gestureWidth / 2 ? -10 : 10,
              onDoubleTap: () => _seekRelative(_doubleTapSeconds),
              onVerticalDragStart: _onVerticalDragStart,
              onVerticalDragUpdate: _onVerticalDragUpdate,
              onVerticalDragEnd: (_) => _onVerticalDragEnd(),
              onVerticalDragCancel: _onVerticalDragEnd,
              onHorizontalDragStart: _onRateDragStart,
              onHorizontalDragUpdate: _onRateDragUpdate,
              onHorizontalDragEnd: (_) => _onRateDragEnd(),
              onHorizontalDragCancel: _onRateDragEnd,
              child: const SizedBox.expand(),
            ),
          ),
        if (_error == null && _controlsVisible) _buildControls(d),
        // 滑动调速的实时提示。放在 Stack 顶层、控制栏之外：
        // 调速时控制栏往往是隐藏的，提示必须自己可见。
        if (_swipeRateVisible) _buildRateIndicator(),
        if (_verticalFeedback != null)
          Positioned.fill(
            child: IgnorePointer(
              child: Center(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: Colors.black87,
                    borderRadius: BorderRadius.circular(R34Radius.pill),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16, vertical: 10,
                    ),
                    child: Text(
                      _verticalFeedback!,
                      style: const TextStyle(color: Colors.white),
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );

    if (_fullscreen) {
      return PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) async {
          if (didPop) return;
          await _exitFullscreen();
        },
        child: Scaffold(backgroundColor: Colors.black, body: SafeArea(child: body)),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(d.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: '刷新',
            icon: const Icon(Icons.refresh_rounded),
            onPressed: _load,
          ),
          // 收藏视频
          IconButton(
            tooltip: ref.watch(isFavoriteProvider(videoKey(d.id)))
                ? '取消收藏'
                : '收藏',
            icon: Icon(
              ref.watch(isFavoriteProvider(videoKey(d.id)))
                  ? Icons.favorite_rounded
                  : Icons.favorite_border_rounded,
              color: ref.watch(isFavoriteProvider(videoKey(d.id)))
                  ? R34Color.accentBright
                  : null,
            ),
            onPressed: _toggleFavorite,
          ),
          const SizedBox(width: R34Space.x1),
        ],
      ),
      body: LayoutBuilder(builder: (context, constraints) {
        // 视频框保持 16:9；横屏将信息面板放在右侧，避免竖向空间溢出。
        final video = AspectRatio(aspectRatio: 16 / 9, child: body);
        final info = _buildInfoPanel(d);
        if (constraints.maxWidth > constraints.maxHeight) {
          return Row(children: [
            Expanded(flex: 3, child: Center(child: video)),
            Expanded(flex: 2, child: info),
          ]);
        }
        return Column(children: [video, Expanded(child: info)]);
      }),
    );
  }

  /// 视频区里的播放错误。
  ///
  /// 只占视频那一块，不遮信息面板：换清晰度、看标签、读简介都还能做，
  /// 用户有机会自己绕过去（比如换一档低码率、或切 libmpv）。
  Widget _buildPlayerError() {
    return Container(
      color: Colors.black,
      padding: const EdgeInsets.all(R34Space.x4),
      child: Center(
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline_rounded,
                  size: 32, color: R34Color.inkMuted),
              const SizedBox(height: R34Space.x2),
              Text(
                _error!,
                textAlign: TextAlign.center,
                maxLines: 6,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: R34Color.inkSecondary,
                  fontSize: 12.5,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: R34Space.x3),
              Wrap(
                spacing: R34Space.x2,
                alignment: WrapAlignment.center,
                children: [
                  FilledButton.tonal(
                    onPressed: _load,
                    child: const Text('重试'),
                  ),
                  if (_current != null && _detail != null &&
                      _detail!.streams.length > 1)
                    TextButton(
                      onPressed: () {
                        // 换一档清晰度：换链最容易解决"这条链坏了"的情况
                        final other = _detail!.streams
                            .firstWhere((s) => s.label != _current!.label);
                        _switchQuality(other);
                      },
                      child: const Text('换清晰度'),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _toggleFavorite() async {
    final d = _detail;
    if (d == null) return;
    final now = await ref.read(favoritesProvider.notifier).toggle(FavoriteEntry(
          kind: FavoriteKind.video,
          target: d.id,
          title: d.title,
          thumb: d.poster,
          path: d.path,
          subtitle: d.duration,
          addedAt: DateTime.now(),
        ));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(now ? '已收藏' : '已取消收藏'), duration: const Duration(seconds: 1)),
    );
  }

  void _seekRelative(int seconds) {
    final cur = _backend.currentPosition;
    final target = cur + Duration(seconds: seconds);
    _backend.seek(target.isNegative ? Duration.zero : target);
    if (mounted && seconds != 0) {
      ScaffoldMessenger.of(context).clearSnackBars();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('${seconds > 0 ? "快进" : "后退"} ${seconds.abs()} 秒'),
          duration: const Duration(milliseconds: 600),
        ),
      );
    }
  }

  // ---------------- 横向滑动调速 ----------------

  void _onRateDragStart(DragStartDetails details) {
    _rateDragDx = 0;
    _rateDragging = false;
    _rateRestoreTimer?.cancel();
    _rateBeforeDrag = _speed;
    setState(() => _swipeRateVisible = false);
  }

  void _onRateDragUpdate(DragUpdateDetails details) {
    _rateDragDx += details.delta.dx;

    // 累计位移还不够判定方向，先不动播放器。
    // 一旦判定成立就锁住（_rateDragging），后续本次手势都按横向处理，
    // 不会滑到一半又"反悔"。
    if (!_rateDragging) {
      if (!isRateSwipe(_rateDragDx, 0)) return;
      _rateDragging = true;
    }

    final width = _gestureWidth;
    final rate = rateForDrag(_rateDragDx, width, initialRate: _rateBeforeDrag);

    setState(() {
      _speed = rate;
      _swipeRate = rate;
      _swipeRateVisible = true;
    });

    // **只在档位真的变了时才下发，并且吞掉异常。**
    //
    // 这里曾经每帧都调一次 `_backend.setRate(rate)` 且不 await：
    // - 拖动 200dp 会连续发出十几次 `setPlaybackSpeed`，
    //   每次都跨 platform channel 打到底层 ExoPlayer；
    // - 播放器尚未 open 完或已经释放时，底层会抛
    //   `StateError('播放器已经释放')` / platform 异常；
    // - 因为没 await，异常变成**未处理的 Future 错误**，
    //   Flutter 会把它升级成致命错误，表现为播放中断甚至整页崩掉。
    //
    // 这正是「手势加速直接播放不了」的原因。现在两道保护：
    // 档位没变就完全不下发（离散档位天然去重），下发的一律 catch。
    if (!_rateGate.shouldApply(rate)) return;
    unawaited(_backend.setRate(rate).catchError((Object _) {}));
  }

  /// 松手：提示留一会儿再消失，速度回到用户原本设定的值。
  ///
  /// **不回弹到 1.0，而是回弹到「进入本次手势前的值」。**
  /// 用户可能本来就设了 1.5x 在看，临时划一下不该把设置清成 1.0。
  void _onRateDragEnd() {
    if (!_rateDragging) {
      _rateDragDx = 0;
      return;
    }
    _rateDragging = false;
    _rateDragDx = 0;

    final restore = _rateBeforeDrag;
    _speed = restore;
    if (_rateGate.shouldApply(restore)) {
      unawaited(_backend.setRate(restore).catchError((Object _) {}));
    }

    _rateRestoreTimer?.cancel();
    // 提示多留 500ms 再消失，让人看清刚才滑到了几倍。
    _rateRestoreTimer = Timer(const Duration(milliseconds: 500), () {
      if (!mounted) return;
      setState(() {
        _swipeRateVisible = false;
      });
    });
  }

  /// 调速提示：屏幕中间一个胶囊，显示当前倍速。
  Widget _buildRateIndicator() {
    return Positioned(
      top: 12,
      left: 0,
      right: 0,
      child: IgnorePointer(
        child: Center(
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.72),
              borderRadius: BorderRadius.circular(R34Radius.pill),
              border: Border.all(color: R34Color.glassBorder),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    _swipeRate > kNormalRate
                        ? Icons.fast_forward_rounded
                        : _swipeRate < kNormalRate
                            ? Icons.fast_rewind_rounded
                            : Icons.play_arrow_rounded,
                    size: 16,
                    color: Colors.white,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    rateLabel(_swipeRate),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildControls(VideoDetail d) {
    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      // 背景渐变只是装饰，不该拦指针。
      //
      // 控制栏一旦显示就盖住视频区下半部分。之前整个 `Container` 是普通
      // 绘制盒，命中测试会把这片区域的空白全部吃掉（按钮之间那点缝隙除外），
      // 于是「点一下显隐控制栏」在控制栏可见时失效、横滑调倍速也只能在
      // 上半屏划。把**背景**放到 IgnorePointer 里，按钮仍是正常子树，
      // 各自响应自己的点击——两者互不影响。
      child: Stack(
        children: [
          Positioned.fill(
            child: IgnorePointer(
              child: DecoratedBox(
                decoration: const BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.bottomCenter,
                    end: Alignment.topCenter,
                    colors: [Colors.black87, Colors.transparent],
                  ),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 24, 8, 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
          children: [
            // media_kit_video 2.x 不再提供 VideoProgressIndicator，
            // 自己用 Player.stream 画一条可拖动进度条，反而更好控制样式。
            _SeekBar(backend: _backend, durationFallback: d.durationSeconds),
            // 上一行：时间 + 全屏，弱信息不抢戏。
            Row(
              children: [
                StreamBuilder<Duration>(
                  stream: _backend.position,
                  initialData: _backend.currentPosition,
                  builder: (context, posSnap) {
                    return StreamBuilder<Duration>(
                      stream: _backend.duration,
                      initialData: _backend.currentDuration,
                      builder: (context, durSnap) {
                        final pos = posSnap.data ?? Duration.zero;
                        final dur = durSnap.data ?? Duration.zero;
                        return Text(
                          '${fmtDuration(pos.inSeconds)} / ${fmtDuration(dur.inSeconds)}',
                          style: const TextStyle(color: Colors.white, fontSize: 12),
                        );
                      },
                    );
                  },
                ),
                const Spacer(),
                IconButton(
                  color: Colors.white,
                  tooltip: _fullscreen ? '退出全屏' : '全屏',
                  icon: Icon(
                    _fullscreen
                        ? Icons.fullscreen_exit_rounded
                        : Icons.fullscreen_rounded,
                  ),
                  onPressed: () => _fullscreen ? _exitFullscreen() : _enterFullscreen(),
                ),
              ],
            ),
            // 下一行：快退 / 播放 / 快进是主控制，右边留给音量、清晰度、倍速。
            Row(
              children: [
                IconButton(
                  color: Colors.white,
                  tooltip: '后退 10 秒',
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.replay_10_rounded),
                  onPressed: () => _seekRelative(-10),
                ),
                StreamBuilder<bool>(
                  stream: _backend.playing,
                  initialData: _backend.isPlaying,
                  builder: (context, snap) {
                    final playing = snap.data ?? false;
                    return Material(
                      color: Colors.white,
                      shape: const CircleBorder(),
                      clipBehavior: Clip.antiAlias,
                      child: InkWell(
                        onTap: _togglePlayback,
                        child: SizedBox(
                          width: 46,
                          height: 46,
                          child: Icon(
                            playing
                                ? Icons.pause_rounded
                                : Icons.play_arrow_rounded,
                            size: 26,
                            color: Colors.black,
                          ),
                        ),
                      ),
                    );
                  },
                ),
                IconButton(
                  color: Colors.white,
                  tooltip: '前进 10 秒',
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.forward_10_rounded),
                  onPressed: () => _seekRelative(10),
                ),
                const Spacer(),
                // 音量：点开一个小面板拖滑块；长按直接静音。
                StreamBuilder<double>(
                  stream: _backend.volume,
                  initialData: _volume ?? _backend.currentVolume,
                  builder: (context, snap) {
                    final v = _muted ? 0.0 : (snap.data ?? _backend.currentVolume);
                    return IconButton(
                      color: Colors.white,
                      visualDensity: VisualDensity.compact,
                      tooltip: _muted ? '取消静音' : '音量',
                      icon: Icon(
                        v <= 0
                            ? Icons.volume_off_rounded
                            : v < 50
                                ? Icons.volume_down_rounded
                                : Icons.volume_up_rounded,
                      ),
                      onPressed: _showVolumeSheet,
                      onLongPress: _toggleMute,
                    );
                  },
                ),
                if (_detail?.streams != null)
                  PopupMenuButton<StreamVariant>(
                    color: Colors.black87,
                    padding: EdgeInsets.zero,
                    iconSize: 22,
                    icon: const Icon(Icons.high_quality_rounded, color: Colors.white),
                    tooltip: '清晰度',
                    onSelected: _switchQuality,
                    itemBuilder: (context) => [
                      for (final s in _detail!.streams)
                        PopupMenuItem(
                          value: s,
                          child: Text(
                            s.label,
                            style: TextStyle(
                              color: s.label == _current?.label ? R34Color.accentBright : Colors.white,
                            ),
                          ),
                        ),
                    ],
                  ),
                PopupMenuButton<double>(
                  color: Colors.black87,
                  padding: EdgeInsets.zero,
                  iconSize: 22,
                  icon: const Icon(Icons.speed_rounded, color: Colors.white),
                  tooltip: '倍速',
                  onSelected: (v) async {
                    setState(() => _speed = v);
                    await _backend.setRate(v);
                    await ref.read(libraryStoreProvider).setPlaybackSpeed(v);
                  },
                  itemBuilder: (context) => [
                    for (final v in const [0.5, 0.75, 1.0, 1.25, 1.5, 2.0])
                      PopupMenuItem(
                        value: v,
                        child: Text(
                          '${v}x',
                          style: TextStyle(
                            color: v == _speed ? R34Color.accentBright : Colors.white,
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ],
        ),
          ), // Padding
        ],
      ), // Stack
    );
  }

  Widget _buildInfoPanel(VideoDetail d) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(
        R34Space.page,
        R34Space.x4,
        R34Space.page,
        R34Space.x8,
      ),
      children: [
        Text(d.title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: R34Space.x2),
        Text(
          '${d.duration}  ·  ${d.viewsText}  ·  ${d.addedText}'
          '${_playingFromCache ? '  ·  本地播放' : ''}',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: R34Color.inkMuted,
              ),
        ),
        const SizedBox(height: R34Space.x2 + 2),
        _buildCacheRow(d),

        // 清晰度：详情页原本也有一组，合并后只留这一处。
        // 点它就换档并从当前位置续播，比去播放器控件里翻菜单好找。
        if (d.streams.isNotEmpty) ...[
          SectionHeader(
            title: '清晰度',
            padding: const EdgeInsets.only(
              top: R34Space.x5,
              bottom: R34Space.x2 + 2,
            ),
          ),
          Wrap(
            spacing: R34Space.x2,
            runSpacing: R34Space.x2,
            children: [
              for (final s in d.streams)
                ActionChip(
                  avatar: Icon(
                    s.isHd ? Icons.hd_outlined : Icons.sd_outlined,
                    size: 17,
                  ),
                  label: Text(s.label),
                  // 当前档位加星标：换档会重建播放器，不标出来会让人
                  // 以为点了没反应。
                  backgroundColor: s.label == _current?.label
                      ? R34Color.accentSoft
                      : null,
                  onPressed: () => _switchQuality(s),
                ),
            ],
          ),
        ],

        // 继续观看：有进度时给一个明确的入口。
        if (_savedProgress?.isResumable ?? false) ...[
          const SizedBox(height: R34Space.x4),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: () async {
                await _backend.seek(Duration(seconds: _savedProgress!.positionSeconds));
                await _backend.resume();
              },
              icon: const Icon(Icons.play_circle_outline_rounded, size: 19),
              label: Text(
                '继续观看 ${fmtDuration(_savedProgress!.positionSeconds)}'
                ' / ${fmtDuration(d.durationSeconds)}',
              ),
            ),
          ),
        ],

        // 四组标签（角色/分类/作者/标签），与详情页一致：
        // 点进去看列表，长按收藏，跟随翻译开关。
        if (d.characters.isNotEmpty)
          GroupSection(
            title: '角色',
            hint: '长按可收藏',
            children: [
              for (final c in d.characters) CharacterChip(slug: c),
            ],
          ),
        if (d.categories.isNotEmpty)
          GroupSection(
            title: '分类',
            children: [
              for (final c in d.categories)
                ActionChip(
                  label: Text(prettySlug(c)),
                  onPressed: () =>
                      context.push('/list/category/${Uri.encodeComponent(c)}'),
                ),
            ],
          ),
        if (d.models.isNotEmpty)
          GroupSection(
            title: '作者',
            hint: '长按可收藏',
            children: [
              for (final m in d.models) ModelChip(slug: m),
            ],
          ),
        if (d.tags.isNotEmpty)
          GroupSection(
            title: '标签',
            hint: '长按可收藏',
            children: [
              for (final t in d.tags) TagChip(slug: t),
            ],
          ),

        if (d.uploaderName.isNotEmpty)
          UploaderRow(name: d.uploaderName, id: d.uploaderId),
        if (d.downloads.isNotEmpty) ...[
          const SizedBox(height: R34Space.x5),
          DownloadSection(video: d),
        ],
        if (d.comments.isNotEmpty) ...[
          const SizedBox(height: R34Space.x5),
          CommentSection(comments: d.comments),
        ],
        if (d.description.isNotEmpty) ...[
          SectionHeader(
            title: '简介',
            padding: const EdgeInsets.only(
              top: R34Space.x5,
              bottom: R34Space.x2,
            ),
          ),
          SelectableText(d.description, style: Theme.of(context).textTheme.bodySmall),
        ],
        if (d.related.isNotEmpty) ...[
          SectionHeader(
            title: '相关推荐',
            padding: const EdgeInsets.only(
              top: R34Space.x5,
              bottom: R34Space.x2 + 2,
            ),
          ),
          SizedBox(
            height: videoTileExtentFor(context, 150, compact: true),
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              clipBehavior: Clip.none,
              itemCount: d.related.length,
              separatorBuilder: (context, i) => const SizedBox(width: R34Space.x3),
              itemBuilder: (context, i) {
                final r = d.related[i];
                return SizedBox(
                  width: 150,
                  child: VideoCardTile(card: r, compact: true),
                );
              },
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildCacheRow(dynamic d) {
    // 还在边下边播：显示缓冲进度，比「缓存」按钮更有信息量
    if (_buffering) {
      final fraction =
          _fetchTotal > 0 ? (_fetchReceived / _fetchTotal).clamp(0.0, 1.0) : null;
      final mb = (_fetchReceived / (1024 * 1024)).toStringAsFixed(1);
      final total = _fetchTotal > 0
          ? '${(_fetchTotal / (1024 * 1024)).toStringAsFixed(0)} MB'
          : '大小未知';
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.downloading_rounded,
                size: 16,
                color: R34Color.accentBright,
              ),
              const SizedBox(width: R34Space.x1 + 2),
              Text(
                '缓冲中  $mb / $total',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: R34Color.accentBright,
                    ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          LinearProgressIndicator(
            value: fraction,
            minHeight: 4,
            borderRadius: BorderRadius.circular(R34Radius.pill),
          ),
        ],
      );
    }

    final cached = ref.watch(isCachedProvider(d.id));
    final entry = ref
        .watch(cacheIndexProvider)
        .where((e) => e.videoId == d.id)
        .firstOrNull;

    if (cached && _cacheProgress == null) {
      return Row(
        children: [
          const Icon(
            Icons.offline_pin_rounded,
            size: 16,
            color: R34Color.accentBright,
          ),
          const SizedBox(width: R34Space.x1 + 2),
          Text(
            '已缓存 ${entry?.quality ?? ""}'
            '${entry == null ? "" : "（${entry.megabytes.toStringAsFixed(0)} MB）"}'
            '，本地播放',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: R34Color.accentBright,
                ),
          ),
        ],
      );
    }

    final prog = _cacheProgress;
    if (prog != null) {
      final fraction = prog.fraction;
      final mb = (prog.received / (1024 * 1024)).toStringAsFixed(0);
      final total = prog.total > 0
          ? '${(prog.total / (1024 * 1024)).toStringAsFixed(0)} MB'
          : '大小未知';
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.downloading_rounded,
                size: 16,
                color: R34Color.inkFaint,
              ),
              const SizedBox(width: R34Space.x1 + 2),
              Text(
                '正在缓存${_cacheReason.isEmpty ? "" : "（$_cacheReason）"}  $mb / $total',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const Spacer(),
              TextButton(
                onPressed: () {
                  _cacheCancel?.cancel('用户取消');
                  setState(() {
                    _caching = false;
                    _cacheProgress = null;
                  });
                },
                child: const Text('取消'),
              ),
            ],
          ),
          LinearProgressIndicator(
            value: fraction,
            minHeight: 4,
            borderRadius: BorderRadius.circular(R34Radius.pill),
          ),
        ],
      );
    }

    return Row(
      children: [
        const Icon(
          Icons.cloud_download_outlined,
          size: 16,
          color: R34Color.inkFaint,
        ),
        const SizedBox(width: R34Space.x1 + 2),
        TextButton(
          style: TextButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            minimumSize: Size.zero,
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
              onPressed: _caching ? null : () => _startCache(d, _current!, reason: '手动'),
              child: const Text('缓存到本地'),
        ),
      ],
    );
  }
}

/// 可拖动的进度条。
///
/// 用 media_kit 的 position/duration 流，拖动时先本地跟手、松手才 seek，
/// 否则每一帧都发 seek 会让播放器卡顿。
class _SeekBar extends StatefulWidget {
  const _SeekBar({required this.backend, required this.durationFallback});

  final PlayerBackend backend;

  /// 播放器还没报时长时用详情页的时长兜底，进度条不会显示成 0。
  final int durationFallback;

  @override
  State<_SeekBar> createState() => _SeekBarState();
}

class _SeekBarState extends State<_SeekBar> {
  double? _dragValue;

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<Duration>(
      stream: widget.backend.position,
      builder: (context, posSnap) {
        return StreamBuilder<Duration>(
          stream: widget.backend.duration,
          builder: (context, durSnap) {
            final pos = posSnap.data?.inMilliseconds ?? 0;
            final durMs = durSnap.data?.inMilliseconds ?? 0;
            final total = durMs > 0 ? durMs : widget.durationFallback * 1000;
            if (total <= 0) return const SizedBox(height: 24);

            final value = _dragValue ?? (pos / total).clamp(0.0, 1.0);

            return Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SliderTheme(
                  data: SliderTheme.of(context).copyWith(
                    trackHeight: 3,
                    thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                    overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
                  ),
                  child: Slider(
                    value: value.toDouble(),
                    onChangeStart: (v) => setState(() => _dragValue = v),
                    onChanged: (v) => setState(() => _dragValue = v),
                    onChangeEnd: (v) async {
                      final target = Duration(milliseconds: (total * v).round());
                      await widget.backend.seek(target);
                      if (mounted) setState(() => _dragValue = null);
                    },
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        fmtDuration(((value * total) / 1000).round()),
                        style: const TextStyle(color: Colors.white70, fontSize: 11),
                      ),
                      Text(
                        fmtDuration((total / 1000).round()),
                        style: const TextStyle(color: Colors.white70, fontSize: 11),
                      ),
                    ],
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }
}
