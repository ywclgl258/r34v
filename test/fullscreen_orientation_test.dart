import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// 全屏朝向决策的单元测试。
///
/// `PlayerPage._enterFullscreen` 依赖 `PlayerBackend.aspectRatio` 决定转
/// 横屏还是竖屏。这里不去 pump 整个播放页（那要 mock 一大堆依赖），
/// 而是直接验证「决策规则」本身——它才是容易写错、也最容易回归的部分。
///
/// 规则：
/// - 宽高比 < 1（竖屏视频）→ 竖屏
/// - 宽高比 >= 1（横屏/方形视频）→ 横屏
/// - 拿不到元数据（null）→ 横屏（最常见比例，且横屏更适合未知内容）
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  /// 与 `player_page.dart` 中的实现保持一致。
  List<DeviceOrientation> orientationsFor(double? aspectRatio) {
    final isPortrait = aspectRatio != null && aspectRatio < 1.0;
    return isPortrait
        ? const [DeviceOrientation.portraitUp, DeviceOrientation.portraitDown]
        : const [
            DeviceOrientation.landscapeLeft,
            DeviceOrientation.landscapeRight,
          ];
  }

  group('全屏朝向决策', () {
    test('竖屏视频（9:16）转竖屏', () {
      // 手机拍摄的竖屏：1080x1920
      expect(orientationsFor(1080 / 1920), [
        DeviceOrientation.portraitUp,
        DeviceOrientation.portraitDown,
      ]);
    });

    test('横屏视频（16:9）转横屏', () {
      expect(orientationsFor(1920 / 1080), [
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
    });

    test('方形视频（1:1）按横屏处理', () {
      // 边界：恰好等于 1 不算竖屏，否则方形视频会被转成竖屏
      expect(orientationsFor(1.0), [
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
    });

    test('略小于 1 的视频算竖屏', () {
      expect(orientationsFor(0.99).first, DeviceOrientation.portraitUp);
    });

    test('未知宽高比退回横屏', () {
      expect(orientationsFor(null), [
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
    });
  });

  group('SystemChrome 调用', () {
    test('设置朝向会走到 platform channel', () async {
      final calls = <MethodCall>[];
      TestWidgetsFlutterBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
            calls.add(call);
            return null;
          });

      await SystemChrome.setPreferredOrientations(
        orientationsFor(1080 / 1920),
      );

      final set = calls.where(
        (c) => c.method == 'SystemChrome.setPreferredOrientations',
      );
      expect(set, isNotEmpty);
      // 参数是字符串列表，如 ['DeviceOrientation.portraitUp', ...]
      final values = (set.last.arguments as List<dynamic>)
          .map((e) => e.toString())
          .toList();
      expect(values, contains('DeviceOrientation.portraitUp'));
      expect(
        values.any((v) => v.contains('landscape')),
        isFalse,
        reason: '竖屏视频不应请求横屏方向',
      );
    });
  });
}
