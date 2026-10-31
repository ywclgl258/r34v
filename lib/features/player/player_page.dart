import 'dart:async';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dio/dio.dart' show CancelToken;
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';

import '../../app/providers.dart';
import '../../data/local/library_store.dart';
import '../../data/local/video_cache.dart';
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

  String get _path => '/video/${widget.videoId}/${widget.slug}/';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _speed = ref.read(libraryStoreProvider).playbackSpeed;
    _player.setRate(_speed);
    // 上次调过的音量延续过来；没调过就交给系统默认（100）
    final savedVolume = ref.read(libraryStoreProvider).volume;
    if (savedVolume != null) {
      _volume = savedVolume;
      _player.setVolume(savedVolume);
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
    final fetching = _currentFetch;
    if (fetching != null) {
      unawaited(ref.read(streamFetcherProvider).cancel(fetching.videoId));
    }
    _saveTimer?.cancel();
    _hideTimer?.cancel();
    _persistProgress();
    _player.dispose();
    if (_fullscreen) {
      SystemChrome.setPreferredOrientations(DeviceOrientation.values);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
    super.dispose();
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
      // 直链过期是这里最常见的错：回源换链再续播
      if (_refreshCount < _maxRefresh) {
        await _recoverStream(reason: '播放报错：$e');
      } else {
        setState(() => _error = '播放失败：$e\n\n已经自动重试过 $_maxRefresh 次，回到详情页重新打开试试。');
      }
    });

    // 播完之后把进度写成「已完成」
    _player.stream.completed.listen((done) {
      if (done) _persistProgress(forceFinished: true);
    });
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
    // 优先播本地缓存：省流量，而且不受直链 token 过期影响
    final local = cache.localPathFor(d.id);
    if (local != null) {
      await _openAndSeek(local, resumeAt);
      _playingFromCache = true;
      if (mounted) setState(() {});
      return;
    }

    // 没有完整缓存时，**先把流抓到本地再播**。
    //
    // 不能把直链直接交给播放器：media_kit 底层 libmpv 的 HTTP 走 ffmpeg 自己的
    // 协议栈，不经过 Android 的 VPN/代理路由。真机上表现为「列表能刷、视频
    // Failed to open」，即使挂了代理也一样。走 Dart 的 HTTP 客户端下载则和抓页面
    // 同一条通道，代理对它是生效的。
    await _openViaFetch(d, s, resumeAt);
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

  /// 设置音量（0–100）。顺手记下来，下次进播放页沿用。
  Future<void> _setVolume(double v) async {
    final clamped = v.clamp(0.0, 100.0);
    setState(() {
      _volume = clamped;
      _muted = clamped <= 0;
      if (clamped > 0) _volumeBeforeMute = clamped;
    });
    await _player.setVolume(clamped);
    await ref.read(libraryStoreProvider).setVolume(clamped);
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
      await ref.read(libraryStoreProvider).setVolume(0);
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
      ),
      body: Column(
        children: [
          AspectRatio(aspectRatio: 16 / 9, child: body),
          Expanded(child: _buildInfoPanel(d)),
        ],
      ),
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
                          '${_fmt(pos.inSeconds)} / ${_fmt(dur.inSeconds)}',
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
        if (d.tags.isNotEmpty) ...[
          Text('标签', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final t in d.tags.take(24))
                Chip(
                  label: Text(_pretty(t), style: const TextStyle(fontSize: 11)),
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  visualDensity: VisualDensity.compact,
                ),
            ],
          ),
        ],
        if (d.description.isNotEmpty) ...[
          const SizedBox(height: 16),
          Text('简介', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          SelectableText(d.description, style: Theme.of(context).textTheme.bodySmall),
        ],
      ],
    );
  }

  static String _pretty(String slug) => slug
      .replaceAll('-', ' ')
      .split(' ')
      .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1))
      .join(' ');

  static String _fmt(int s) {
    if (s <= 0) return '00:00';
    final h = s ~/ 3600;
    final m = (s % 3600) ~/ 60;
    final sec = s % 60;
    final mm = m.toString().padLeft(2, '0');
    final ss = sec.toString().padLeft(2, '0');
    return h > 0 ? '$h:$mm:$ss' : '$mm:$ss';
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

    final cached = ref.watch(isCachedProvider(d.id as String));
    final entry = ref.watch(cacheIndexProvider).where((e) => e.videoId == d.id).firstOrNull;

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
                        _PlayerPageState._fmt(((value * total) / 1000).round()),
                        style: const TextStyle(color: Colors.white70, fontSize: 11),
                      ),
                      Text(
                        _PlayerPageState._fmt((total / 1000).round()),
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
