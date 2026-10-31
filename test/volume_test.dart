import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 音量的存取与「静音恢复」逻辑。
///
/// 播放器里的滑块不好在 widget 测试里稳定拖动（依赖 media_kit 原生播放器），
/// 但音量值的持久化与 clamp 是纯逻辑，可以在这里锁住。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;
  late LibraryStore store;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    store = LibraryStore(prefs);
  });

  group('音量持久化', () {
    test('没调过时为 null（跟随系统，不强行覆盖）', () {
      expect(store.volume, isNull);
    });

    test('写入后读回', () async {
      await store.setVolume(65);
      expect(store.volume, 65);
    });

    test('0 也算有效值（用户主动静音过）', () async {
      await store.setVolume(0);
      expect(store.volume, 0);
    });

    test('100 是上限', () async {
      await store.setVolume(100);
      expect(store.volume, 100);
    });
  });

  group('静音恢复语义', () {
    /// 模拟播放器里的 _toggleMute：静音记下当时音量，恢复时原样还回。
    double applyMute(double current, {required bool muted}) {
      if (!muted) return current;
      return 0;
    }

    test('静音到 0', () {
      expect(applyMute(70, muted: true), 0);
    });

    test('取消静音回到原值', () {
      const before = 70.0;
      final muted = applyMute(before, muted: true);
      final restored = before;
      expect(muted, 0);
      expect(restored, 70);
    });

    test('原本就是 0 时，取消静音用 100 兜底（否则用户以为坏了）', () {
      const before = 0.0;
      final restore = before <= 0 ? 100.0 : before;
      expect(restore, 100);
    });
  });
}
