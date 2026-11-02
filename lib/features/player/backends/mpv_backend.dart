import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart' as mk;

import '../../../core/constants.dart';
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
  MpvBackend({Player Function()? playerFactory})
    : _createPlayer = playerFactory ?? (() => Player());

  final Player Function() _createPlayer;
  bool _disposed = false;
  int _openRevision = 0;
  final _position = StreamController<Duration>.broadcast();
  final _duration = StreamController<Duration>.broadcast();
  final _volumeStream = StreamController<double>.broadcast();
  final _errors = StreamController<String>.broadcast();
  final _completed = StreamController<bool>.broadcast();
  final _playing = StreamController<bool>.broadcast();
  final _subscriptions = <StreamSubscription<dynamic>>[];
  Player? _player;
  mk.VideoController? _controller;

  /// 只在真的要播放时才创建底层实例。
  Player get _p {
    if (_disposed) throw StateError('播放器已经释放');
    if (_player != null) return _player!;
    final player = _player = _createPlayer();
    _subscriptions.addAll([
      player.stream.position.listen(_position.add),
      player.stream.duration.listen(_duration.add),
      player.stream.volume.listen(_volumeStream.add),
      player.stream.error.listen(_errors.add),
      player.stream.completed.listen(_completed.add),
      player.stream.playing.listen(_playing.add),
    ]);
    return player;
  }

  bool _isCurrent(int revision) => !_disposed && revision == _openRevision;

  mk.VideoController get _vc => _controller ??= mk.VideoController(_p);

  bool _configured = false;

  /// open 之前记下的意图值，open 后应用。
  double _rate = 1.0;
  double _volume = 100;
  bool _wantPlaying = false;

  @override
  Future<void> configure() async {
    if (_disposed) throw StateError('播放器已经释放');
    if (_configured) return;
    await PlayerBootstrap.configure(_p);
    if (!_disposed) _configured = true;
  }

  /// 把 DoH 解析出的站点 IP 交给 libmpv。
  ///
  /// 必须在这里注入、而不是在 [configure] 里一次性做完：`configure` 是
  /// 「播放器属性就绪」时调用的，那时还没有本次播放的 URL，也就无从知道
  /// 该解析哪个域名。open 之前拿到 IP 再下发，语义最清晰。
  Future<void> applySiteIp(String? ip) async {
    if (_disposed || _player == null) return;
    try {
      await PlayerBootstrap.applySiteIpTo(_player!, ip);
    } catch (_) {
      // 属性下发失败不该阻断播放：mpv 会退回系统 DNS
    }
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
  Future<void> open(String url, {required bool play, String? referer}) async {
    if (_disposed) throw StateError('播放器已经释放');
    final revision = ++_openRevision;
    _wantPlaying = play;
    final player = _p;
    // Referer 用视频详情页地址（与 ExoBackend 一致：站点的反盗链
    // 白名单认这个值，外站 Referer 会 403）。
    // media_kit 的 `Media.httpHeaders` 会被转成 mpv 的
    // `--http-header-fields`，所以直接传 map 即可。
    final media = Media(
      url,
      httpHeaders: {
        'User-Agent': Site.userAgent,
        'Referer': referer ?? '${Site.base}/',
      },
    );
    // 起播意图可能在等待 open 时被 pause/dispose 改变。
    await player.open(media, play: false);
    if (!_isCurrent(revision)) return;
    // open 会重置倍速和音量，把这一轮记下的值补上
    await player.setRate(_rate);
    if (!_isCurrent(revision)) return;
    await player.setVolume(_volume);
    if (_isCurrent(revision) && _wantPlaying) await player.play();
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
    if (_disposed) return;
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
    if (_disposed) return;
    _disposed = true;
    _openRevision++;
    _wantPlaying = false;
    final p = _player;
    _player = null;
    _controller = null;
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    if (p != null) await p.dispose();
    await Future.wait([
      _position.close(),
      _duration.close(),
      _volumeStream.close(),
      _errors.close(),
      _completed.close(),
      _playing.close(),
    ]);
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
  Duration get currentPosition => _player?.state.position ?? Duration.zero;

  @override
  Duration get currentDuration => _player?.state.duration ?? Duration.zero;

  @override
  bool get isPlaying => _player?.state.playing ?? _wantPlaying;

  @override
  double get currentVolume => _player?.state.volume ?? _volume;
}
