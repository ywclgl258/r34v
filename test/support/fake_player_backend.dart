import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:r34v/features/player/backends/backends.dart';

class FakePlayerBackend implements PlayerBackend {
  late final _duration = StreamController<Duration>.broadcast();
  late final _errors = StreamController<String>.broadcast();
  final opened = <String>[];

  /// 每次 open 传入的 Referer，用来验证「用视频页而不是站点根」。
  final referers = <String?>[];
  final rates = <double>[];
  bool failOpen = false;
  bool disposed = false;
  double rate = 1;

  @override
  Duration currentPosition = Duration.zero;
  @override
  Duration currentDuration = const Duration(minutes: 10);
  @override
  bool isPlaying = false;
  @override
  double currentVolume = 100;

  @override
  Future<void> configure() async {}
  @override
  Widget videoWidget() => const SizedBox.expand();
  @override
  Future<void> open(String url, {required bool play, String? referer}) async {
    opened.add(url);
    referers.add(referer);
    if (failOpen) throw StateError('test playback failure');
    isPlaying = play;
    _duration.add(currentDuration);
  }

  @override
  Future<void> pause() async => isPlaying = false;
  @override
  Future<void> resume() async => isPlaying = true;
  @override
  Future<void> seek(Duration position) async => currentPosition = position;
  @override
  Future<void> setRate(double value) async {
    rate = value;
    rates.add(value);
  }

  @override
  Future<void> setVolume(double value) async => currentVolume = value;
  @override
  Future<void> dispose() async {
    disposed = true;
    await _duration.close();
    await _errors.close();
  }

  @override
  Stream<Duration> get position => const Stream.empty();
  @override
  Stream<Duration> get duration => _duration.stream;
  @override
  Stream<String> get errors => _errors.stream;
  @override
  Stream<double> get volume => const Stream.empty();
  @override
  Stream<bool> get completed => const Stream.empty();
  @override
  Stream<bool> get playing => const Stream.empty();

  /// 全屏朝向测试用：可自由设定，模拟竖屏（<1）或横屏（>1）视频。
  double? fakeAspectRatio;

  @override
  double? get aspectRatio => fakeAspectRatio;
}
