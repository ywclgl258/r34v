/// 横向拖动调播放速度的判定逻辑。
///
/// 抽成纯函数是为了能被单测覆盖：手势本身的坐标计算写死在 widget 里，
/// 只能靠人肉滑动验证，而「滑多少算几倍速」这种边界最能出错、也最
/// 值得钉住。
///
/// 交互：
/// - 横向滑动距离转成速度档位，右滑加速、左滑减速；
/// - 松手恢复进入手势前的倍速，提示额外保留 500ms，不修改默认设置；
/// - 有上限，避免用户一把划到 8 倍速后音频全糊。
library;

/// 可选档位。线性拖动映射到离散档位，滑动时能感觉到"咔"的落位。
const List<double> kSwipeRateSteps = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0];

/// 正常播放速度。
const double kNormalRate = 1.0;

/// 判定「这是一次横向拖动」的最小水平位移（逻辑像素）。
///
/// 太小会和单击、竖向调音量打架；太大则要划很远才有反应。
const double kSwipeRateSlop = 24.0;

/// 把水平位移换算成倍速。
///
/// [dxTotal] 是本次手势累计的水平位移（右正左负）。
/// [width] 是视频区宽度，用来归一化——同样划半屏，大屏小屏的手感应当一致。
///
/// 映射：从手势前的倍速出发，划满整屏到最高档，反向划满整屏到最低档。
/// 用比例而不是固定像素，是为了平板和手机上"划一半"的实际速度增量相同。
double rateForDrag(
  double dxTotal,
  double width, {
  double initialRate = kNormalRate,
}) {
  final start = initialRate.clamp(kSwipeRateSteps.first, kSwipeRateSteps.last);
  if (width <= 0) return start;

  // 归一化到 [-1, 1]，超出一屏的部分按满值算，不做无限加速。
  final fraction = (dxTotal / width).clamp(-1.0, 1.0);

  // 在档位表里找与「起始倍速 + fraction 对应的增量」最接近的一档。
  //
  // 以手势前的倍速为中心，两侧分别映射到最高/最低档。
  final target = fraction >= 0
      ? start + fraction * (kSwipeRateSteps.last - start)
      : start + fraction * (start - kSwipeRateSteps.first);

  return nearestStep(target);
}

/// 找最接近的档位。
double nearestStep(double target) {
  var best = kSwipeRateSteps.first;
  var bestDiff = (best - target).abs();
  for (final s in kSwipeRateSteps) {
    final d = (s - target).abs();
    if (d < bestDiff) {
      best = s;
      bestDiff = d;
    }
  }
  return best;
}

/// 这次手势算不算「调速度」。
///
/// 要求水平位移明显大于垂直位移：斜着划的时候用户多半想调音量或滚动，
/// 不该突然改倍速。
bool isRateSwipe(double dxTotal, double dyTotal) {
  return dxTotal.abs() >= kSwipeRateSlop && dxTotal.abs() > dyTotal.abs() * 1.5;
}

/// 倍速的展示文案（`1.0` → `1.0x`）。
String rateLabel(double rate) =>
    '${rate.toStringAsFixed(rate == rate.roundToDouble() ? 1 : 2)}x';
