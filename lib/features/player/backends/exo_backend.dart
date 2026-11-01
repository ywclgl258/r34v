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
class ExoBackend implements PlayerBackend {
  VideoPlayerController? _c;
  Timer? _poll;

  final _position = StreamController<Duration>.broadcast();
  final _duration = StreamController<Duration>.broadcast();
  final _volume = StreamController<double>.broadcast();
  final _errors = StreamController<String>.broadcast();
  final _completed = StreamController<bool>.broadcast();
  final _playing = StreamController<bool>.broadcast();

  String? _lastError;
  bool _completedSent = false;

  VideoPlayerController get _require {
    final c = _c;
    if (c == null) {
      throw StateError('ExoBackend: 还没 open 就取控制器');
    }
    return c;
  }

  @override
  Future<void> configure() async {
    // ExoPlayer 用系统网络栈和系统证书，无需配置
  }

  @override
  Widget videoWidget() {
    final c = _c;
    if (c == null) return const SizedBox.shrink();
    return ClipRect(
      child: FittedBox(
        fit: BoxFit.contain,
        child: SizedBox(
          // VideoPlayer 内部按视频原始尺寸布局，FittedBox 负责信箱
          width: 640,
          height: 360,
          child: VideoPlayer(c),
        ),
      ),
    );
  }

  @override
  Future<void> open(String url, {required bool play}) async {
    await _teardown();
    final c = VideoPlayerController.networkUrl(
      Uri.parse(url),
      httpHeaders: {
        'User-Agent': Site.userAgent,
        'Referer': '${Site.base}/',
      },
    );
    _c = c;
    // initialize 失败（网络/格式）会直接抛异常——调用方据此判失败
    await c.initialize();
    _completedSent = false;

    c.addListener(_onValueChanged);
    _poll = Timer.periodic(const Duration(milliseconds: 250), (_) {
      _emitPosition();
    });

    if (play) {
      await c.play();
    }
  }

  void _onValueChanged() {
    final c = _c;
    if (c == null) return;
    final v = c.value;
    _duration.add(v.duration);
    _playing.add(v.isPlaying);
    _volume.add(v.volume * 100);
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
  Future<void> pause() => _require.pause();

  @override
  Future<void> resume() => _require.play();

  @override
  Future<void> seek(Duration position) => _require.seekTo(position);

  @override
  Future<void> setRate(double rate) => _require.setPlaybackSpeed(rate);

  @override
  Future<void> setVolume(double volume) =>
      _require.setVolume(volume / 100.0);

  @override
  Future<void> dispose() => _teardown();

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
  Stream<double> get volume => _volume.stream;

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
  bool get isPlaying => _c?.value.isPlaying ?? false;

  @override
  double get currentVolume => (_c?.value.volume ?? 1.0) * 100;
}
