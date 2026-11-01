import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:video_player/video_player.dart';

import '../../../core/constants.dart';
import 'player_backend.dart';

/// ExoPlayer 引擎（video_player，默认）。
///
/// 相对 libmpv 的三个实打实优势：
/// - **硬件解码**：省电、不烫、兼容性由 Google 维护；
/// - **走系统网络栈**：代理/VPN 天然生效，不需要自带 CA 那套；
/// - 起播失败（initialize 抛异常）是同步可见的，探测逻辑简单可靠。
///
/// 代价：video_player 没有错误/进度流，用轮询补（250ms），够用了。
///
/// **未 open 时所有控制调用都必须是安全的 no-op。**
///
/// 这条不是洁癖：播放页在 initState 里就要把上次的倍速和音量下发下来，
/// 那时候控制器还不存在。早先的写法是 `=> _require.xxx()`，
/// 而 `_require` 在同步阶段就 throw StateError——expression body 不会把它
/// 变成失败的 Future，而是直接同步抛出，把 initState 整个炸掉。
/// 结果是播放页连 build 都没跑到，屏幕上只剩 Flutter 的错误框
/// （release 下就是一块灰屏，看起来像「视频加载失败」而不是崩溃）。
/// 现在倍速/音量先记着，open 成功后一次性应用。
class ExoBackend implements PlayerBackend {
  ExoBackend({VideoPlayerController Function(String)? controllerFactory})
    : _createController = controllerFactory ?? _networkController;

  final VideoPlayerController Function(String) _createController;
  bool _disposed = false;
  int _openRevision = 0;

  static VideoPlayerController _networkController(String url) =>
      VideoPlayerController.networkUrl(
        Uri.parse(url),
        httpHeaders: {'User-Agent': Site.userAgent, 'Referer': '${Site.base}/'},
      );

  bool _isCurrent(int revision) => !_disposed && revision == _openRevision;
  VideoPlayerController? _c;
  Timer? _poll;

  /// open 之前记下的意图值，open 后应用。
  double _rate = 1.0;
  double _volume = 100;
  bool _wantPlaying = false;

  final _position = StreamController<Duration>.broadcast();
  final _duration = StreamController<Duration>.broadcast();
  final _volumeStream = StreamController<double>.broadcast();
  final _errors = StreamController<String>.broadcast();
  final _completed = StreamController<bool>.broadcast();
  final _playing = StreamController<bool>.broadcast();

  String? _lastError;
  bool _completedSent = false;

  @override
  Future<void> configure() async {
    // ExoPlayer 用系统网络栈和系统证书，无需配置
  }

  @override
  Widget videoWidget() {
    final c = _c;
    if (c == null) return const SizedBox.shrink();
    // 按视频真实比例做信箱，不再硬编码 16:9——竖屏片段会被拉扁。
    // 用 ValueListenableBuilder 是因为初始化完成的那一刻要重排一次。
    return ValueListenableBuilder<VideoPlayerValue>(
      valueListenable: c,
      builder: (context, value, _) {
        final ar = value.isInitialized && value.aspectRatio > 0
            ? value.aspectRatio
            : 16 / 9;
        return Center(
          child: AspectRatio(aspectRatio: ar, child: VideoPlayer(c)),
        );
      },
    );
  }

  @override
  Future<void> open(String url, {required bool play}) async {
    if (_disposed) throw StateError('播放器已经释放');
    final revision = ++_openRevision;
    _wantPlaying = play;
    await _teardown();
    if (!_isCurrent(revision)) return;
    final c = _createController(url);
    _c = c;
    // initialize 失败（网络/格式）会直接抛异常——调用方据此判失败
    try {
      await c.initialize();
    } catch (_) {
      if (_isCurrent(revision)) rethrow;
      return;
    }
    if (!_isCurrent(revision)) {
      await c.dispose();
      return;
    }
    _completedSent = false;
    _lastError = null;

    c.addListener(_onValueChanged);
    _poll = Timer.periodic(const Duration(milliseconds: 250), (_) {
      _emitPosition();
    });

    // 把 open 之前记下的倍速/音量补上
    await c.setPlaybackSpeed(_rate);
    if (!_isCurrent(revision)) return;
    await c.setVolume((_volume / 100).clamp(0.0, 1.0));

    if (_isCurrent(revision) && _wantPlaying) {
      await c.play();
    }
  }

  void _onValueChanged() {
    final c = _c;
    if (c == null) return;
    final v = c.value;
    _duration.add(v.duration);
    _playing.add(v.isPlaying);
    _volumeStream.add(v.volume * 100);
    if (v.hasError && v.errorDescription != _lastError) {
      _lastError = v.errorDescription;
      if (v.errorDescription != null) {
        _errors.add(v.errorDescription!);
      }
    }
    if (v.isInitialized &&
        v.duration > Duration.zero &&
        v.position >= v.duration &&
        !_completedSent) {
      _completedSent = true;
      _completed.add(true);
    }
  }

  void _emitPosition() {
    final c = _c;
    if (c == null || !c.value.isInitialized) return;
    _position.add(c.value.position);
  }

  @override
  Future<void> pause() async {
    _wantPlaying = false;
    final c = _c;
    if (c == null) return;
    await c.pause();
  }

  @override
  Future<void> resume() async {
    if (_disposed) return;
    _wantPlaying = true;
    final c = _c;
    if (c == null) return;
    await c.play();
  }

  @override
  Future<void> seek(Duration position) async {
    final c = _c;
    if (c == null) return;
    await c.seekTo(position);
  }

  @override
  Future<void> setRate(double rate) async {
    _rate = rate;
    final c = _c;
    if (c == null) return;
    await c.setPlaybackSpeed(rate);
  }

  @override
  Future<void> setVolume(double volume) async {
    _volume = volume.clamp(0.0, 100.0);
    final c = _c;
    if (c == null) return;
    await c.setVolume((_volume / 100).clamp(0.0, 1.0));
  }

  @override
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    _openRevision++;
    _wantPlaying = false;
    await _teardown();
    await Future.wait([
      _position.close(),
      _duration.close(),
      _volumeStream.close(),
      _errors.close(),
      _completed.close(),
      _playing.close(),
    ]);
  }

  Future<void> _teardown() async {
    _poll?.cancel();
    _poll = null;
    final c = _c;
    _c = null;
    if (c != null) {
      c.removeListener(_onValueChanged);
      await c.dispose();
    }
  }

  @override
  Stream<Duration> get position => _position.stream;

  @override
  Stream<Duration> get duration => _duration.stream;

  @override
  Stream<double> get volume => _volumeStream.stream;

  @override
  Stream<String> get errors => _errors.stream;

  @override
  Stream<bool> get completed => _completed.stream;

  @override
  Stream<bool> get playing => _playing.stream;

  @override
  Duration get currentPosition =>
      _c?.value.isInitialized ?? false ? _c!.value.position : Duration.zero;

  @override
  Duration get currentDuration =>
      _c?.value.isInitialized ?? false ? _c!.value.duration : Duration.zero;

  @override
  bool get isPlaying => _c?.value.isPlaying ?? _wantPlaying;

  @override
  double get currentVolume => (_c?.value.volume ?? (_volume / 100)) * 100;
}
