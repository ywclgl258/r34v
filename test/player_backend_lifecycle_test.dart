import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:media_kit/media_kit.dart' as mk;
import 'package:r34v/features/player/backends/backends.dart';
import 'package:video_player/video_player.dart';

class _VideoController extends ValueNotifier<VideoPlayerValue>
    implements VideoPlayerController {
  _VideoController(this.ready) : super(VideoPlayerValue.uninitialized());

  final Completer<void> ready;
  int plays = 0;
  bool released = false;

  @override
  Future<void> initialize() async {
    await ready.future;
    value = VideoPlayerValue(
      duration: const Duration(minutes: 2),
      size: const Size(1920, 1080),
      isInitialized: true,
    );
  }

  @override
  Future<void> play() async {
    plays++;
    value = value.copyWith(isPlaying: true);
  }

  @override
  Future<void> pause() async => value = value.copyWith(isPlaying: false);
  @override
  Future<void> setPlaybackSpeed(double speed) async =>
      value = value.copyWith(playbackSpeed: speed);
  @override
  Future<void> setVolume(double volume) async =>
      value = value.copyWith(volume: volume);
  @override
  Future<void> dispose() async {
    if (released) return;
    released = true;
    super.dispose();
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _MpvPlatform extends mk.PlatformPlayer {
  _MpvPlatform() : super(configuration: const mk.PlayerConfiguration());

  Completer<void>? ready;
  int plays = 0;
  bool released = false;

  @override
  Future<void> open(mk.Playable playable, {bool play = true}) async {
    await ready?.future;
    if (released) return;
    state = state.copyWith(duration: const Duration(minutes: 2), playing: play);
    durationController.add(state.duration);
  }

  @override
  Future<void> play() async {
    plays++;
    state = state.copyWith(playing: true);
    playingController.add(true);
  }

  @override
  Future<void> pause() async {
    state = state.copyWith(playing: false);
    playingController.add(false);
  }

  @override
  Future<void> setRate(double rate) async => state = state.copyWith(rate: rate);
  @override
  Future<void> setVolume(double volume) async {
    state = state.copyWith(volume: volume);
    volumeController.add(volume);
  }

  void signal() {
    positionController.add(const Duration(seconds: 5));
    durationController.add(const Duration(minutes: 2));
    volumeController.add(70);
    errorController.add('test error');
    completedController.add(true);
    playingController.add(true);
  }

  @override
  Future<void> dispose() async {
    released = true;
    await super.dispose();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('libmpv 初始化前订阅的全部状态流在初始化后仍有效', () async {
    final platform = _MpvPlatform();
    var creations = 0;
    final backend = MpvBackend(
      playerFactory: () {
        creations++;
        return mk.Player(platformPlayer: platform);
      },
    );
    final events = <Object>[];
    final subscriptions = [
      backend.position.listen(events.add),
      backend.duration.listen(events.add),
      backend.volume.listen(events.add),
      backend.errors.listen(events.add),
      backend.completed.listen(events.add),
      backend.playing.listen(events.add),
    ];
    expect(creations, 0, reason: '订阅本身不能提前创建原生播放器');
    await backend.configure();
    platform.signal();
    await Future<void>.delayed(Duration.zero);
    expect(creations, 1);
    expect(
      events,
      containsAll([
        const Duration(seconds: 5),
        const Duration(minutes: 2),
        70.0,
        'test error',
        true,
        true,
      ]),
    );
    for (final subscription in subscriptions) {
      await subscription.cancel();
    }
    await backend.dispose();
  });

  test('libmpv 打开过程中暂停，打开完成也不自动起播', () async {
    final platform = _MpvPlatform()..ready = Completer<void>();
    final backend = MpvBackend(
      playerFactory: () => mk.Player(platformPlayer: platform),
    );
    final opening = backend.open('https://example.invalid/a.mp4', play: true);
    await backend.pause();
    platform.ready!.complete();
    await opening;
    expect(platform.plays, 0);
    expect(backend.isPlaying, isFalse);
    await backend.resume();
    expect(platform.plays, 1);
    await backend.dispose();
  });

  test('libmpv 打开过程中释放，完成后不能起播或重新创建实例', () async {
    final platform = _MpvPlatform()..ready = Completer<void>();
    final backend = MpvBackend(
      playerFactory: () => mk.Player(platformPlayer: platform),
    );
    final opening = backend.open('https://example.invalid/a.mp4', play: true);
    await backend.dispose();
    platform.ready!.complete();
    await opening;
    expect(platform.plays, 0);
    await expectLater(
      backend.open('https://example.invalid/b.mp4', play: true),
      throwsStateError,
    );
    await backend.dispose();
  });

  test('ExoPlayer 初始化期间暂停，初始化完成保持暂停', () async {
    final ready = Completer<void>();
    final controller = _VideoController(ready);
    final backend = ExoBackend(controllerFactory: (_) => controller);
    final opening = backend.open('https://example.invalid/a.mp4', play: true);
    await Future<void>.delayed(Duration.zero);
    await backend.pause();
    ready.complete();
    await opening;
    expect(controller.plays, 0);
    expect(backend.isPlaying, isFalse);
    await backend.dispose();
  });

  test('ExoPlayer 初始化期间释放，初始化完成不能起播', () async {
    final ready = Completer<void>();
    final controller = _VideoController(ready);
    final backend = ExoBackend(controllerFactory: (_) => controller);
    final opening = backend.open('https://example.invalid/a.mp4', play: true);
    await Future<void>.delayed(Duration.zero);
    await backend.dispose();
    ready.complete();
    await opening;
    expect(controller.plays, 0);
    expect(controller.released, isTrue);
  });

  test('ExoPlayer 释放后拒绝打开，不再创建控制器', () async {
    var creations = 0;
    final backend = ExoBackend(
      controllerFactory: (_) {
        creations++;
        return _VideoController(Completer<void>());
      },
    );
    await backend.dispose();
    await expectLater(
      backend.open('https://example.invalid/a.mp4', play: true),
      throwsStateError,
    );
    expect(creations, 0);
    await backend.dispose();
  });
}
