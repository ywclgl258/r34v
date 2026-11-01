import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:r34v/features/player/backends/backends.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 双播放器引擎：默认值、切换、工厂映射。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('播放器引擎设置', () {
    test('默认 ExoPlayer', () async {
      SharedPreferences.setMockInitialValues({});
      final store = LibraryStore(await SharedPreferences.getInstance());
      expect(store.playerEngine, 'exo');
    });

    test('可切到 libmpv 并读回', () async {
      SharedPreferences.setMockInitialValues({});
      final store = LibraryStore(await SharedPreferences.getInstance());
      await store.setPlayerEngine('mpv');
      expect(store.playerEngine, 'mpv');
      await store.setPlayerEngine('exo');
      expect(store.playerEngine, 'exo');
    });

    test('未知值兜底 ExoPlayer', () async {
      SharedPreferences.setMockInitialValues({});
      final store = LibraryStore(await SharedPreferences.getInstance());
      await store.setPlayerEngine('something-weird');
      // 工厂层兜底（设置层原样存，工厂负责不认识的一律 Exo）
      expect(createPlayerBackend(store.playerEngine), isA<ExoBackend>());
    });
  });

  group('引擎工厂', () {
    test('exo → ExoBackend', () {
      expect(createPlayerBackend('exo'), isA<ExoBackend>());
    });

    test('mpv → MpvBackend', () {
      expect(createPlayerBackend('mpv'), isA<MpvBackend>());
    });

    test('缺省 → ExoBackend', () {
      expect(createPlayerBackend(''), isA<ExoBackend>());
    });

    test('选项表与工厂一致', () {
      for (final opt in kPlayerEngineOptions) {
        expect(() => createPlayerBackend(opt.$1), returnsNormally);
      }
    });
  });
}
