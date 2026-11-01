import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dio/dio.dart' show CancelToken;
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../../app/providers.dart';
import '../../data/local/library_store.dart';
import '../../data/local/video_cache.dart';
import '../common/video_sections.dart';
import '../feed/video_card_tile.dart';
import '../../core/player_bootstrap.dart';
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

class _PlayerPageState extends ConsumerState<PlayerPage> with WidgetsBindingObserver {
  late final Player _player = Player();

  /// CA 证书与 UA 要在 open 之前设置好，否则第一次握手就失败。
  Future<void> _ensurePlayerConfigured() async {
    if (_playerConfigured) return;
    _playerConfigured = true;
    await PlayerBootstrap.configure(_player);
  }

  bool _playerConfigured = false;
  late final VideoController _controller = VideoController(_player);

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

  /// 音量为 null 表示「跟随系统」——不去动它。
  /// 一旦用户手动调过，就记下来，下次进播放页沿用。
  double? _volume;
  bool _muted = false;
  double _volumeBeforeMute = 100;

  /// 本次播放是不是用的本地缓存文件。
  bool _playingFromCache = false;

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
    _speed = ref.read(libraryStoreProvider).playbackSpeed;
    _player.setRate(_speed);
    // 上次调过的音量延续过来；没调过就交给系统默认（100）。
    // 恢复时下限 10：曾经有用户滑到 0 被永久记住，之后所有视频都没声音，
    // 还以为播放器坏了。静音是当下状态，不该变成默认状态。
    final savedVolume = ref.read(libraryStoreProvider).volume;
    if (savedVolume != null) {
      _volume = savedVolume < 10 ? 10 : savedVolume;
      _player.setVolume(_volume!);
    }
    _listen();
    _load();
    _saveTimer = Timer.periodic(const Duration(seconds: 5), (_) => _persistProgress());
    if (ref.read(libraryStoreProvider).autoFullscreen) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _enterFullscreen());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _saveTimer?.cancel();
    _hideTimer?.cancel();

    // 先记下位置：dispose 之后 player 的状态就不可靠了
    final posNow = _player.state.position.inSeconds;
    final durNow = _player.state.duration.inSeconds;

    // **先停掉声音，再处理其它事。**
    //
    // 之前顺序是「存进度 → dispose(player)」，而存进度里若抛异常
    // （prefs 写失败、player 状态已失效等），后面的 _player.dispose()
    // 就永远执行不到——表现就是退出页面后视频还在后台放，有声音。
    // 所以这里每一步都单独兜住，任何一步失败都不影响停止播放。
    try {
      unawaited(_player.pause());
    } catch (_) {}
    try {
      unawaited(_player.stop());
    } catch (_) {}
    try {
      unawaited(_player.dispose());
    } catch (_) {}

    // 再存进度
    try {
      _persistProgressAt(posNow, durNow);
    } catch (_) {}

    final fetching = _currentFetch;
    if (fetching != null) {
      try {
        unawaited(ref.read(streamFetcherProvider).cancel(fetching.videoId));
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
    ref.read(libraryStoreProvider).saveWatch(WatchEntry(
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
      _persistProgress();
      _player.pause();
    }
  }

  void _listen() {
    _player.stream.error.listen((e) async {
      if (!mounted) return;
      // 直连探测期间由 _openRemoteDirect 自己判断成败，
      // 这里若也抢着回源换链，会两路同时开打。
      if (_probingDirect) return;
      if (_refreshCount >= _maxRefresh) {
        setState(() => _error = '播放失败：$e\n\n已经自动重试过 $_maxRefresh 次，回到详情页重新打开试试。');
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
    });

    // 播完之后把进度写成「已完成」
    _player.stream.completed.listen((done) {
      if (done) _persistProgress(forceFinished: true);
    });
  }

  /// 错误信息里出现 401/403/410 才算 token 过期。
  static bool _looksLikeTokenExpiry(String err) =>
      err.contains('401') || err.contains('403') || err.contains('410');

  /// 不重回源，直接用当前链重开一次（从当前位置续）。
  /// 网络抖动类错误多半这样就好了。
  Future<void> _retrySameStream({required String reason}) async {
    final d = _detail;
    final s = _current;
    if (d == null || s == null) return;
    _refreshCount++;
    try {
      final pos = _player.state.position;
      final wasPlaying = _player.state.playing;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('播放中断，正在重试…（$reason）'),
            duration: const Duration(seconds: 2),
          ),
        );
      }
      await _player.open(Media(s.url), play: wasPlaying);
      if (pos.inSeconds > 0) {
        await Future<void>.delayed(const Duration(milliseconds: 350));
        await _player.seek(pos);
      }
      await _player.setRate(_speed);
      if (_volume != null) await _player.setVolume(_muted ? 0 : _volume!);
    } catch (e) {
      // 重试还失败，按 token 过期处理
      await _recoverStream(reason: '重试失败（$e）');
    }
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
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
        });
        return;
      }

      setState(() {
        _detail = d;
        _current = stream;
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
    // 从上次看到的位置接着播
    final saved = ref.read(libraryStoreProvider).findWatch(d.id);
    final resumeAt = (saved != null && saved.isResumable) ? saved.positionSeconds : 0;

    final cache = ref.read(videoCacheProvider);
    // 优先播本地完整缓存：省流量、不受 token 过期影响、拖动也准
    final local = cache.localPathFor(d.id);
    if (local != null) {
      await _openAndSeek(local, resumeAt);
      _playingFromCache = true;
      if (mounted) setState(() {});
      return;
    }

    // 先直接播远端。
    //
    // 为什么优先直连而不是先下载：libmpv 自己支持 HTTP Range，拖动进度条会
    // 按需请求对应区间，跳转是准确的。之前用「边下边播」读正在写入的 .part
    // 文件，seek 到还没写到的位置时 mpv 会以为文件就到这里，进度条直接弹到结尾。
    //
    // CA 证书和代理现在都在启动时配好了，直连具备条件。
    final ok = await _openRemoteDirect(s.url, resumeAt);
    if (ok) return;

    // 直连失败（代理只对 Dart 生效等情况），退回边下边播保底。
    // 这条路能看，但拖动只在前端已下载范围内有效。
    await _openViaFetch(d, s, resumeAt);
  }

  /// 直接打开远端地址，返回是否成功起播。
  ///
  /// media_kit 的 `open` 不会因为网络失败而抛异常，错误是从 `stream.error`
  /// 出来的，所以这里同时监听 duration（成功）和 error（失败），加超时兜底。
  Future<bool> _openRemoteDirect(String url, int resumeAt) async {
    final done = Completer<bool>();
    StreamSubscription<String>? errSub;
    StreamSubscription<Duration>? durSub;
    StreamSubscription<Duration>? posSub;

    void finish(bool v) {
      if (!done.isCompleted) done.complete(v);
    }

    // 探测期间抑制 _listen 里的自动换链，避免直连失败时又触发一轮回源
    _probingDirect = true;
    errSub = _player.stream.error.listen((e) {
      if (_player.state.duration.inMilliseconds <= 0) finish(false);
    });
    // 有任一时长或播放位置推进，就说明连通了
    durSub = _player.stream.duration.listen((dur) {
      if (dur.inMilliseconds > 0) finish(true);
    });
    posSub = _player.stream.position.listen((pos) {
      if (pos.inMilliseconds > 0) finish(true);
    });

    try {
      await _ensurePlayerConfigured();
      await _player.open(Media(url), play: true);
      // 站点实测首字节要 5–11 秒，给足余量；太短会把能直连的误判成失败，
      // 一退回边下边播拖动就又坏了。
      final ok = await done.future.timeout(
        const Duration(seconds: 20),
        onTimeout: () => false,
      );
      if (!ok) return false;

      if (resumeAt > 0) {
        await Future<void>.delayed(const Duration(milliseconds: 350));
        await _player.seek(Duration(seconds: resumeAt));
      }
      await _player.setRate(_speed);
      if (_volume != null) await _player.setVolume(_muted ? 0 : _volume!);
      if (mounted) {
        setState(() {
          _buffering = false;
          _error = null;
          _playingFromCache = false;
        });
      }
      return true;
    } catch (_) {
      return false;
    } finally {
      _probingDirect = false;
      await errSub.cancel();
      await durSub.cancel();
      await posSub.cancel();
    }
  }

  /// 通过「边下边播」打开远端流。
  Future<void> _openViaFetch(VideoDetail d, StreamVariant s, int resumeAt) async {
    final fetch = ref.read(streamFetcherProvider);
    setState(() {
      _buffering = true;
      _cacheProgress = null;
    });

    var opened = false;
    String? firstError;

    final handle = await fetch.fetch(
      videoId: d.id,
      url: s.url,
      quality: s.label,
      // 分段下载中途 token 过期：回源换新链续传（换的是同清晰度的链）
      refreshUrl: () async {
        final api = ref.read(rule34ApiProvider);
        final fresh = await api.fetchDetail(_path);
        if (!mounted) return s.url;
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
        if (!mounted) return;
        setState(() {
          _fetchReceived = received;
          _fetchTotal = total;
        });
      },
      onReady: (filePath) {
        // 头部数据够了，可以起播
        if (opened || !mounted) return;
        opened = true;
        _openAndSeek(filePath, resumeAt).catchError((Object e) {
          firstError = '$e';
        });
      },
      onDone: (err) {
        if (!mounted) return;
        final fatal = (err != null || firstError != null) && !opened;
        if (fatal) {
          // 还没起播就失败，才是真失败
          setState(() {
            _buffering = false;
            _error = '打不开视频流：${firstError ?? err}';
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

  Future<void> _openAndSeek(String source, int resumeAt) async {
    await _ensurePlayerConfigured();
    await _player.open(Media(source), play: true);
    if (resumeAt > 0) {
      // media_kit 在 open 后立刻 seek 有时会被忽略，等一小会儿再跳
      await Future<void>.delayed(const Duration(milliseconds: 350));
      await _player.seek(Duration(seconds: resumeAt));
    }
    await _player.setRate(_speed);
    if (_volume != null) await _player.setVolume(_muted ? 0 : _volume!);
    if (mounted) {
      setState(() {
        _buffering = false;
        _error = null;
      });
    }
  }

  void _startCache(VideoDetail d, StreamVariant s, {String reason = ''}) {
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
    cache
        .cache(
      videoId: d.id,
      title: d.title,
      url: s.url,
      quality: s.label,
      thumb: d.poster,
      durationSeconds: d.durationSeconds,
      cancelToken: _cacheCancel,
      onProgress: (prog) {
        if (!mounted) return;
        setState(() => _cacheProgress = prog);
      },
    )
        .then((entry) {
      if (!mounted) return;
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
      if (!mounted) return;
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

  /// 直链过期时的恢复：回源详情页拿新链，从当前位置续播。
  Future<void> _recoverStream({required String reason}) async {
    if (_restoringStream) return;
    _restoringStream = true;
    _refreshCount++;
    try {
      final pos = _player.state.position;
      final wasPlaying = _player.state.playing;
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
      if (!mounted) return;

      final label = _current?.label ?? '';
      final stream = fresh.streams.firstWhere(
        (s) => s.label == label,
        orElse: () => fresh.preferredStream ?? fresh.streams.first,
      );

      setState(() {
        _detail = fresh;
        _current = stream;
      });

      await _player.open(Media(stream.url), play: wasPlaying);
      if (pos.inSeconds > 0) {
        await Future<void>.delayed(const Duration(milliseconds: 350));
        await _player.seek(pos);
      }
      await _player.setRate(_speed);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '换链接失败：$e\n这个视频现在打不开，稍后再试。');
    } finally {
      _restoringStream = false;
    }
  }

  Future<void> _switchQuality(StreamVariant s) async {
    final d = _detail;
    if (d == null || s.label == _current?.label) return;
    final pos = _player.state.position;
    setState(() => _current = s);
    await ref.read(libraryStoreProvider).setPreferredQuality(s.label);
    await _player.open(Media(s.url), play: true);
    if (pos.inSeconds > 0) {
      await Future<void>.delayed(const Duration(milliseconds: 350));
      await _player.seek(pos);
    }
    await _player.setRate(_speed);
    // open() 之后音量可能被重置，再设一次
    if (_volume != null) await _player.setVolume(_muted ? 0 : _volume!);
  }

  /// 设置音量（0–100）。
  ///
  /// 持久化有个下限 10：拖到 0 只代表「这次想安静」，不该让以后每次
  /// 打开播放页都无声。静音状态本身也不落盘。
  Future<void> _setVolume(double v) async {
    final clamped = v.clamp(0.0, 100.0);
    setState(() {
      _volume = clamped;
      _muted = clamped <= 0;
      if (clamped > 0) _volumeBeforeMute = clamped;
    });
    await _player.setVolume(clamped);
    await ref.read(libraryStoreProvider).setVolume(LibraryStore.persistableVolume(clamped));
  }

  /// 静音/取消静音。静音记的是「静音前那一刻的音量」，能原样恢复。
  Future<void> _toggleMute() async {
    if (_muted) {
      final restore = _volumeBeforeMute <= 0 ? 100.0 : _volumeBeforeMute;
      setState(() => _muted = false);
      await _setVolume(restore);
    } else {
      final cur = _player.state.volume;
      setState(() {
        _volumeBeforeMute = cur > 0 ? cur : 100;
        _muted = true;
        _volume = 0;
      });
      await _player.setVolume(0);
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
            final current = _muted ? 0.0 : (_volume ?? _player.state.volume);
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
                            setSheet(() {});
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
                        setSheet(() {});
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
    var pos = _player.state.position.inSeconds;
    var dur = _player.state.duration.inSeconds;
    if (dur <= 0) dur = d.durationSeconds;
    if (forceFinished && dur > 0) pos = dur;
    if (pos <= 0 && !forceFinished) return;

    ref.read(libraryStoreProvider).saveWatch(WatchEntry(
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
      ref.invalidate(historyProvider);
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

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_error != null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.error_outline, size: 48),
                const SizedBox(height: 12),
                Text(_error!, textAlign: TextAlign.center),
                const SizedBox(height: 16),
                FilledButton(onPressed: _load, child: const Text('重试')),
              ],
            ),
          ),
        ),
      );
    }

    final d = _detail!;
    final body = Stack(
      children: [
        Center(
          child: Video(
            controller: _controller,
            controls: NoVideoControls,
            fit: BoxFit.contain,
          ),
        ),
        // 点一下切换控制栏；左右区域上下滑调音量 / 亮度
        Positioned.fill(
          child: GestureDetector(
            behavior: HitTestBehavior.translucent,
            onTap: _toggleControls,
            onDoubleTapDown: (details) => _seekRelative(
              details.localPosition.dx < MediaQuery.sizeOf(context).width / 2 ? -10 : 10,
            ),
            onDoubleTap: () {},
            // 左侧上下滑 = 调音量。手机上看视频的肌肉记忆，
            // 比每次都点开面板拖滑块快得多。
            onVerticalDragUpdate: (details) {
              final width = MediaQuery.sizeOf(context).width;
              if (details.localPosition.dx > width * 0.5) return;
              final delta = -details.delta.dy;
              if (delta == 0) return;
              final cur = _muted ? 0.0 : (_volume ?? _player.state.volume);
              _setVolume(cur + delta * 0.4);
            },
          ),
        ),
        if (_controlsVisible) _buildControls(d),
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
          // 收藏视频（和详情页同一逻辑，现在详情/播放合一了）
          IconButton(
            tooltip: ref.watch(isFavoriteProvider(videoKey(d.id)))
                ? '取消收藏'
                : '收藏',
            icon: Icon(
              ref.watch(isFavoriteProvider(videoKey(d.id)))
                  ? Icons.favorite
                  : Icons.favorite_border,
            ),
            onPressed: _toggleFavorite,
          ),
        ],
      ),
      body: Column(
        children: [
          AspectRatio(aspectRatio: 16 / 9, child: body),
          Expanded(child: _buildInfoPanel(d)),
        ],
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
    final cur = _player.state.position;
    final target = cur + Duration(seconds: seconds);
    _player.seek(target.isNegative ? Duration.zero : target);
    if (mounted) {
      ScaffoldMessenger.of(context).clearSnackBars();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('${seconds > 0 ? "快进" : "后退"} ${seconds.abs()} 秒'),
          duration: const Duration(milliseconds: 600),
        ),
      );
    }
  }

  Widget _buildControls(VideoDetail d) {
    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.bottomCenter,
            end: Alignment.topCenter,
            colors: [Colors.black87, Colors.transparent],
          ),
        ),
        padding: const EdgeInsets.fromLTRB(8, 24, 8, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // media_kit_video 2.x 不再提供 VideoProgressIndicator，
            // 自己用 Player.stream 画一条可拖动进度条，反而更好控制样式。
            _SeekBar(player: _player, durationFallback: d.durationSeconds),
            Row(
              children: [
                StreamBuilder<bool>(
                  stream: _player.stream.playing,
                  initialData: _player.state.playing,
                  builder: (context, snap) {
                    final playing = snap.data ?? false;
                    return IconButton(
                      color: Colors.white,
                      icon: Icon(playing ? Icons.pause : Icons.play_arrow),
                      onPressed: () => playing ? _player.pause() : _player.play(),
                    );
                  },
                ),
                StreamBuilder<Duration>(
                  stream: _player.stream.position,
                  initialData: _player.state.position,
                  builder: (context, posSnap) {
                    return StreamBuilder<Duration>(
                      stream: _player.stream.duration,
                      initialData: _player.state.duration,
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
                // 音量：点开一个小面板拖滑块；长按直接静音。
                StreamBuilder<double>(
                  stream: _player.stream.volume,
                  initialData: _volume ?? _player.state.volume,
                  builder: (context, snap) {
                    final v = _muted ? 0.0 : (snap.data ?? _player.state.volume);
                    return IconButton(
                      color: Colors.white,
                      tooltip: _muted ? '取消静音' : '音量',
                      icon: Icon(
                        v <= 0
                            ? Icons.volume_off
                            : v < 50
                                ? Icons.volume_down
                                : Icons.volume_up,
                      ),
                      onPressed: _showVolumeSheet,
                      onLongPress: _toggleMute,
                    );
                  },
                ),
                if (_detail?.streams != null)
                  PopupMenuButton<StreamVariant>(
                    color: Colors.black87,
                    icon: const Icon(Icons.high_quality, color: Colors.white),
                    tooltip: '清晰度',
                    onSelected: _switchQuality,
                    itemBuilder: (context) => [
                      for (final s in _detail!.streams)
                        PopupMenuItem(
                          value: s,
                          child: Text(
                            s.label,
                            style: TextStyle(
                              color: s.label == _current?.label ? Colors.amber : Colors.white,
                            ),
                          ),
                        ),
                    ],
                  ),
                PopupMenuButton<double>(
                  color: Colors.black87,
                  icon: const Icon(Icons.speed, color: Colors.white),
                  tooltip: '倍速',
                  onSelected: (v) async {
                    setState(() => _speed = v);
                    await _player.setRate(v);
                    await ref.read(libraryStoreProvider).setPlaybackSpeed(v);
                  },
                  itemBuilder: (context) => [
                    for (final v in const [0.5, 0.75, 1.0, 1.25, 1.5, 2.0])
                      PopupMenuItem(
                        value: v,
                        child: Text(
                          '${v}x',
                          style: TextStyle(
                            color: v == _speed ? Colors.amber : Colors.white,
                          ),
                        ),
                      ),
                  ],
                ),
                IconButton(
                  color: Colors.white,
                  tooltip: _fullscreen ? '退出全屏' : '全屏',
                  icon: Icon(_fullscreen ? Icons.fullscreen_exit : Icons.fullscreen),
                  onPressed: () => _fullscreen ? _exitFullscreen() : _enterFullscreen(),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoPanel(VideoDetail d) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(d.title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Text(
          '${d.duration}  ·  ${d.viewsText}  ·  ${d.addedText}'
          '${_playingFromCache ? '  ·  本地播放' : ''}',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 10),
        _buildCacheRow(d),
        const SizedBox(height: 12),

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
          const SizedBox(height: 20),
          DownloadSection(video: d),
        ],
        if (d.comments.isNotEmpty) ...[
          const SizedBox(height: 20),
          CommentSection(comments: d.comments),
        ],
        if (d.description.isNotEmpty) ...[
          const SizedBox(height: 16),
          Text('简介', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          SelectableText(d.description, style: Theme.of(context).textTheme.bodySmall),
        ],
        if (d.related.isNotEmpty) ...[
          const SizedBox(height: 20),
          Text('相关推荐', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          SizedBox(
            height: 200,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: d.related.length,
              separatorBuilder: (context, i) => const SizedBox(width: 8),
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
    final scheme = Theme.of(context).colorScheme;
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
              Icon(Icons.downloading, size: 16, color: scheme.primary),
              const SizedBox(width: 6),
              Text(
                '缓冲中  $mb / $total',
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: scheme.primary),
              ),
            ],
          ),
          const SizedBox(height: 4),
          LinearProgressIndicator(value: fraction, minHeight: 3),
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
          Icon(Icons.offline_pin, size: 16, color: scheme.primary),
          const SizedBox(width: 6),
          Text(
            '已缓存 ${entry?.quality ?? ""}'
            '${entry == null ? "" : "（${entry.megabytes.toStringAsFixed(0)} MB）"}'
            '，本地播放',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(color: scheme.primary),
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
              Icon(Icons.downloading, size: 16, color: scheme.outline),
              const SizedBox(width: 6),
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
          LinearProgressIndicator(value: fraction, minHeight: 3),
        ],
      );
    }

    return Row(
      children: [
        Icon(Icons.cloud_download_outlined, size: 16, color: scheme.outline),
        const SizedBox(width: 6),
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
  const _SeekBar({required this.player, required this.durationFallback});

  final Player player;

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
      stream: widget.player.stream.position,
      builder: (context, posSnap) {
        return StreamBuilder<Duration>(
          stream: widget.player.stream.duration,
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
                      await widget.player.seek(target);
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
