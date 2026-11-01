import 'package:flutter/widgets.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart' as mk;

import '../../../core/player_bootstrap.dart';
import 'player_backend.dart';

/// libmpv 引擎（media_kit）。
///
/// 软解万能、控制精细，但：要走自带 CA 证书（[configure]）、不认系统代理、
/// 费电发热。作为兜底引擎保留——ExoPlayer 播不了的冷门编码换它。
///
/// 和 [ExoBackend] 同一条铁律：**未 open 时控制调用不能碰底层 Player。**
/// 播放页在 initState 就下发倍速/音量，那时候还没有任何媒体；
/// 提前构造 Player 会在 `MediaKit.ensureInitialized` 之前触到 native 库，
/// 直接抛异常把整页打不开（灰屏）。这里把意图记在字段上，open 后应用。
class MpvBackend implements PlayerBackend {
  Player? _player;
  mk.VideoController? _controller;

  /// 只在真的要播放时才创建底层实例。
  Player get _p => _player ??= Player();

  mk.VideoController get _vc => _controller ??= mk.VideoController(_p);

  bool _configured = false;

  /// open 之前记下的意图值，open 后应用。
  double _rate = 1.0;
  double _volume = 100;
  bool _wantPlaying = false;

  @override
  Future<void> configure() async {
    if (_configured) return;
    _configured = true;
    await PlayerBootstrap.configure(_p);
  }

  @override
  Widget videoWidget() {
    // build 阶段就调用，不能返回 null；未 open 时给一个黑底占位，
    // 首次 open 之后由 setState 触发重建换成真正的 Video。
    if (_player == null) return const ColoredBox(color: Color(0xFF000000));
    return mk.Video(
      controller: _vc,
      controls: mk.NoVideoControls,
      fit: BoxFit.contain,
    );
  }

  @override
  Future<void> open(String url, {required bool play}) async {
    await _p.open(Media(url), play: play);
    // open 会重置倍速和音量，把这一轮记下的值补上
    await _p.setRate(_rate);
    await _p.setVolume(_volume);
    _wantPlaying = play;
  }

  @override
  Future<void> pause() async {
    _wantPlaying = false;
    final p = _player;
    if (p == null) return;
    await p.pause();
  }

  @override
  Future<void> resume() async {
    _wantPlaying = true;
    final p = _player;
    if (p == null) return;
    await p.play();
  }

  @override
  Future<void> seek(Duration position) async {
    final p = _player;
    if (p == null) return;
    await p.seek(position);
  }

  @override
  Future<void> setRate(double rate) async {
    _rate = rate;
    final p = _player;
    if (p == null) return;
    await p.setRate(rate);
  }

  @override
  Future<void> setVolume(double volume) async {
    _volume = volume.clamp(0.0, 100.0);
    final p = _player;
    if (p == null) return;
    await p.setVolume(_volume);
  }

  @override
  Future<void> dispose() async {
    final p = _player;
    _player = null;
    _controller = null;
    if (p == null) return;
    await p.dispose();
  }

  @override
  Stream<Duration> get position =>
      _player?.stream.position ?? const Stream<Duration>.empty();

  @override
  Stream<Duration> get duration =>
      _player?.stream.duration ?? const Stream<Duration>.empty();

  @override
  Stream<double> get volume =>
      _player?.stream.volume ?? const Stream<double>.empty();

  @override
  Stream<String> get errors =>
      _player?.stream.error ?? const Stream<String>.empty();

  @override
  Stream<bool> get completed =>
      _player?.stream.completed ?? const Stream<bool>.empty();

  @override
  Stream<bool> get playing =>
      _player?.stream.playing ?? const Stream<bool>.empty();

  @override
  Duration get currentPosition => _player?.state.position ?? Duration.zero;

  @override
  Duration get currentDuration => _player?.state.duration ?? Duration.zero;

  @override
  bool get isPlaying => _player?.state.playing ?? _wantPlaying;

  @override
  double get currentVolume => _player?.state.volume ?? _volume;
}
