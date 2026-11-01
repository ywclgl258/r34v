import 'package:flutter/widgets.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart' as mk;

import '../../../core/player_bootstrap.dart';
import 'player_backend.dart';

/// libmpv 引擎（media_kit）。
///
/// 软解万能、控制精细，但：要走自带 CA 证书（[configure]）、不认系统代理、
/// 费电发热。作为兜底引擎保留——ExoPlayer 播不了的冷门编码换它。
class MpvBackend implements PlayerBackend {
  // 懒创建：media_kit 的 Player 构造要求 ensureInitialized 已调用，
  // 测试环境里没初始化，new MpvBackend() 不该炸。
  Player? _player;
  mk.VideoController? _controller;

  Player get _p => _player ??= Player();
  mk.VideoController get _vc =>
      _controller ??= mk.VideoController(_p);

  bool _configured = false;

  @override
  Future<void> configure() async {
    if (_configured) return;
    _configured = true;
    await PlayerBootstrap.configure(_p);
  }

  @override
  Widget videoWidget() => mk.Video(
        controller: _vc,
        controls: mk.NoVideoControls,
        fit: BoxFit.contain,
      );

  @override
  Future<void> open(String url, {required bool play}) =>
      _p.open(Media(url), play: play);

  @override
  Future<void> pause() => _p.pause();

  @override
  Future<void> resume() => _p.play();

  @override
  Future<void> seek(Duration position) => _p.seek(position);

  @override
  Future<void> setRate(double rate) => _p.setRate(rate);

  @override
  Future<void> setVolume(double volume) => _p.setVolume(volume);

  @override
  Future<void> dispose() => _p.dispose();

  @override
  Stream<Duration> get position => _p.stream.position;

  @override
  Stream<Duration> get duration => _p.stream.duration;

  @override
  Stream<double> get volume => _p.stream.volume;

  @override
  Stream<String> get errors => _p.stream.error;

  @override
  Stream<bool> get completed => _p.stream.completed;

  @override
  Stream<bool> get playing => _p.stream.playing;

  @override
  Duration get currentPosition => _p.state.position;

  @override
  Duration get currentDuration => _p.state.duration;

  @override
  bool get isPlaying => _p.state.playing;

  @override
  double get currentVolume => _p.state.volume;
}
