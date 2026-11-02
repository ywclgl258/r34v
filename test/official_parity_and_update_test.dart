import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/update_service.dart';
import 'package:r34v/features/player/playback_guards.dart';
import 'package:r34v/features/player/swipe_rate.dart';

/// 本轮两项改动的回归测试：
/// 1. 手势参数对齐官方 kt_player / kt_seek.js
/// 2. 版本号比较（自动更新的判定基础）
void main() {
  group('双击分区：对齐官方 40%/60%，中央留安全带', () {
    test('左 40% 是后退区', () {
      // 官方 kt_seek.js: SIDE_LEFT = 0.40
      expect(doubleTapZoneFor(0, 1000), DoubleTapZone.back);
      expect(doubleTapZoneFor(399, 1000), DoubleTapZone.back);
    });

    test('右 40% 是前进区', () {
      // 官方 SIDE_RIGHT = 0.60
      expect(doubleTapZoneFor(601, 1000), DoubleTapZone.forward);
      expect(doubleTapZoneFor(1000, 1000), DoubleTapZone.forward);
    });

    test('中央 20% 不接管双击（旧实现是零宽度，正中偏一点就误触）', () {
      expect(doubleTapZoneFor(400, 1000), DoubleTapZone.center);
      expect(doubleTapZoneFor(500, 1000), DoubleTapZone.center);
      expect(doubleTapZoneFor(600, 1000), DoubleTapZone.center);
    });

    test('宽度为 0 时不崩，按中央处理', () {
      expect(doubleTapZoneFor(0, 0), DoubleTapZone.center);
    });

    test('两侧各 10 秒，中央不 seek', () {
      expect(seekSecondsForZone(DoubleTapZone.back), -10);
      expect(seekSecondsForZone(DoubleTapZone.forward), 10);
      expect(seekSecondsForZone(DoubleTapZone.center), 0);
    });
  });

  group('双击后恢复播放状态（官方 restorePlay）', () {
    test('本来在播、双击后暂停 → 要恢复', () {
      expect(
        shouldRestorePlaying(wasPlaying: true, nowPaused: true),
        isTrue,
      );
    });

    test('本来就暂停、双击后仍暂停 → 不能自己播起来', () {
      // 用户只是双击看那一段，不该被搞成开始播放
      expect(
        shouldRestorePlaying(wasPlaying: false, nowPaused: true),
        isFalse,
      );
    });

    test('本来在播、双击后还在播 → 不用动', () {
      expect(
        shouldRestorePlaying(wasPlaying: true, nowPaused: false),
        isFalse,
      );
    });
  });

  group('倍速档位补了 0.25（对齐 flowplayer speeds）', () {
    test('最低档是 0.25', () {
      expect(kSwipeRateSteps.first, 0.25);
    });

    test('档位严格递增且包含标准值', () {
      for (var i = 1; i < kSwipeRateSteps.length; i++) {
        expect(kSwipeRateSteps[i], greaterThan(kSwipeRateSteps[i - 1]));
      }
      expect(kSwipeRateSteps, contains(0.5));
      expect(kSwipeRateSteps, contains(1.0));
      expect(kSwipeRateSteps, contains(2.0));
    });

    test('左滑到底能到 0.25', () {
      expect(rateForDrag(-99999, 400), 0.25);
    });
  });

  group('版本号比较：自动更新的判定基础', () {
    test('1.10.0 比 1.9.0 新（字符串比较会错）', () {
      // 这是最容易写错的一处：字符串比 "1.10.0" < "1.9.0"
      expect(UpdateService.isNewer('1.10.0', '1.9.0'), isTrue);
    });

    test('1.9.0 不比 1.10.0 新', () {
      expect(UpdateService.isNewer('1.9.0', '1.10.0'), isFalse);
    });

    test('同版本不算新', () {
      expect(UpdateService.isNewer('1.10.0', '1.10.0'), isFalse);
    });

    test('补丁号比较', () {
      expect(UpdateService.isNewer('1.10.1', '1.10.0'), isTrue);
      expect(UpdateService.isNewer('1.10.0', '1.10.1'), isFalse);
    });

    test('主版本号优先', () {
      expect(UpdateService.isNewer('2.0.0', '1.99.99'), isTrue);
    });
  });
}
