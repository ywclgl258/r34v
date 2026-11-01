import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/features/player/backends/backends.dart';

/// 「播放界面灰屏」的根因回归。
///
/// 现象不是视频解码失败，而是**整个播放页连 build 都没跑到**：
/// 播放页 initState 里要下发上次的倍速和音量（`_backend.setRate/_setVolume`），
/// 而 ExoBackend 早期写的是
///
/// ```dart
/// Future<void> setRate(double rate) => _require.setPlaybackSpeed(rate);
/// ```
///
/// `_require` 在控制器还没创建时同步 throw StateError。
/// 关键点：expression body 里同步抛出的异常**不会**变成失败的 Future，
/// 它直接穿透 async 边界之前的调用栈——也就是直接炸在 initState 上。
/// initState 一崩，页面 build 永远不执行，release 下 Flutter 只剩一块
/// `RenderErrorBox`，颜色 0xF0C0C0C0，看起来就是「灰屏」。
///
/// 正确行为：未 open 时所有控制调用都是安全的 no-op，并把意图记下来，
/// open 成功后一次性补上。
void main() {
  group('ExoBackend 未 open 时', () {
    late ExoBackend backend;

    setUp(() => backend = ExoBackend());
    tearDown(() => backend.dispose());

    test('setRate 不抛异常', () {
      expect(() => backend.setRate(1.5), returnsNormally);
    });

    test('setVolume 不抛异常', () {
      expect(() => backend.setVolume(80), returnsNormally);
    });

    test('pause / resume / seek 不抛异常', () {
      expect(() => backend.pause(), returnsNormally);
      expect(() => backend.resume(), returnsNormally);
      expect(() => backend.seek(const Duration(seconds: 30)), returnsNormally);
    });

    test('Future 本身也不会以错误完成', () async {
      // 这是真正的判据：await 不炸，initState 里同步调用更不会炸。
      await expectLater(backend.setRate(2.0), completes);
      await expectLater(backend.setVolume(0), completes);
      await expectLater(backend.pause(), completes);
      await expectLater(backend.seek(Duration.zero), completes);
    });

    test('configure 是安全的空操作', () async {
      await expectLater(backend.configure(), completes);
    });

    test('dispose 可以重复调', () async {
      await backend.dispose();
      await expectLater(backend.dispose(), completes);
    });

    test('即时状态查询有合理默认值', () {
      expect(backend.currentPosition, Duration.zero);
      expect(backend.currentDuration, Duration.zero);
      // 未 open 时不能谎报音量为 0（会让界面画成静音图标）
      expect(backend.currentVolume, 100);
    });

    test('记下的播放意图在 open 前就生效', () {
      // 页面进来先 resume，随后的 open(play: true) 不该把它翻回去。
      backend.resume();
      expect(backend.isPlaying, isTrue);
      backend.pause();
      expect(backend.isPlaying, isFalse);
    });

    test('videoWidget 在 open 前返回可布局的占位', () {
      // 不能返回 null 或抛异常：build 阶段会直接访问它。
      expect(() => backend.videoWidget(), returnsNormally);
    });
  });

  group('MpvBackend 未 open 时', () {
    test('setRate / setVolume 记住值且不炸', () async {
      final b = MpvBackend();
      await expectLater(b.setRate(1.25), completes);
      await expectLater(b.setVolume(50), completes);
    });
  });
}
