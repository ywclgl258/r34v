import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/features/player/swipe_rate.dart';

/// 横向滑动调倍速的判定与映射。
///
/// 这些数字决定了"划多少给几倍速"，是最容易一边调一边改坏的地方。
void main() {
  group('档位映射 rateForDrag', () {
    const w = 390.0; // 常见手机宽度

    test('从任意已选倍速开始，右滑不减速、左滑不加速', () {
      for (final start in kSwipeRateSteps) {
        expect(rateForDrag(0, w, initialRate: start), start);
        for (var dx = 24.0; dx <= w; dx += 13) {
          expect(
            rateForDrag(dx, w, initialRate: start),
            greaterThanOrEqualTo(start),
          );
          expect(
            rateForDrag(-dx, w, initialRate: start),
            lessThanOrEqualTo(start),
          );
        }
      }
    });

    test('不划就是正常速度', () {
      expect(rateForDrag(0, w), kNormalRate);
    });

    test('右滑满屏到最高档', () {
      expect(rateForDrag(w, w), kSwipeRateSteps.last);
      expect(
        rateForDrag(w * 2, w),
        kSwipeRateSteps.last,
        reason: '超出满屏按满值算，不无限加速',
      );
    });

    test('左滑满屏到最低档', () {
      expect(rateForDrag(-w, w), kSwipeRateSteps.first);
      expect(rateForDrag(-w * 2, w), kSwipeRateSteps.first);
    });

    test('返回的一定是表里的档位，不会出现 1.37x 这种值', () {
      for (var dx = -w; dx <= w; dx += 7.0) {
        expect(
          kSwipeRateSteps,
          contains(rateForDrag(dx, w)),
          reason: 'dx=$dx 算出了档位表外的值',
        );
      }
    });

    test('右滑与左滑的灵敏度分别按各自跨度映射', () {
      // 1.0→3.0 跨度 2.0，1.0→0.5 跨度 0.5。
      // 同样划半屏：右滑走到 1.0 + 0.5×2.0 = 2.0，左滑走到 1.0 - 0.5×0.5 = 0.75。
      // 关键在于两侧都有反应；若用同一个系数（比如都按 2.0 算），
      // 左滑半屏会算出 0.0，夹到最低档后"划一点点就到底"，手感很怪。
      expect(rateForDrag(w * 0.5, w), closeTo(2.0, 0.01));
      expect(rateForDrag(-w * 0.5, w), closeTo(0.75, 0.01));
      // 左滑到底才到最低档
      expect(rateForDrag(-w, w), kSwipeRateSteps.first);
    });

    test('宽度非正时安全兜底', () {
      expect(rateForDrag(100, 0), kNormalRate);
      expect(rateForDrag(100, -5), kNormalRate);
    });

    test('单调：往右划不会反而变慢', () {
      var prev = rateForDrag(-w, w);
      for (var dx = -w; dx <= w; dx += 10.0) {
        final cur = rateForDrag(dx, w);
        expect(cur, greaterThanOrEqualTo(prev), reason: 'dx=$dx 处出现回落');
        prev = cur;
      }
    });
  });

  group('手势判定 isRateSwipe', () {
    test('纯横向够远才算', () {
      expect(isRateSwipe(kSwipeRateSlop, 0), isTrue);
      expect(isRateSwipe(-kSwipeRateSlop, 0), isTrue);
    });

    test('位移太小不算（避免和单击打架）', () {
      expect(isRateSwipe(kSwipeRateSlop - 1, 0), isFalse);
      expect(isRateSwipe(5, 0), isFalse);
    });

    test('斜着划、竖向为主时不算（那是调音量）', () {
      expect(isRateSwipe(60, 60), isFalse);
      expect(isRateSwipe(40, 100), isFalse);
      // 横向明显占优才算
      expect(isRateSwipe(100, 20), isTrue);
    });
  });

  group('nearestStep', () {
    test('落在两档之间时取更近的', () {
      expect(nearestStep(1.1), 1.0);
      expect(nearestStep(1.13), 1.25);
      expect(nearestStep(2.9), 3.0);
      expect(nearestStep(0.1), 0.5);
    });
  });

  group('rateLabel', () {
    test('整数速度保留一位小数', () {
      expect(rateLabel(1.0), '1.0x');
      expect(rateLabel(2.0), '2.0x');
      expect(rateLabel(3.0), '3.0x');
    });

    test('非整数速度两位小数', () {
      expect(rateLabel(1.25), '1.25x');
      expect(rateLabel(0.75), '0.75x');
    });
  });
}
