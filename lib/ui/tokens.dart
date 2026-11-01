import 'package:flutter/material.dart';

/// 视觉令牌。
///
/// 一套地方定义颜色、间距、圆角与动效，页面只引用令牌，
/// 不再各写各的十六进制色值。改主题时只改这里。
///
/// 颜色思路：纯黑画布 → 石墨卡片 → 浮层，靠明度差和一层极淡的环境光分层，
/// 描边只做发丝级分割；强调色只有一种系统蓝，用在按钮、选中态和进度上。
abstract final class R34Color {
  /// 画布：纯黑。
  static const canvas = Color(0xFF000000);

  /// 卡片表面（surface-1）。
  static const surface = Color(0xFF121214);

  /// 浮起一级：弹层、输入框、选中态（surface-2，也是底部面板色）。
  static const surfaceRaised = Color(0xFF1C1C1E);

  /// 再高一级：缩略图占位、进度条轨道（surface-3）。
  static const surfaceHigh = Color(0xFF2A2A2C);

  /// 发丝描边。
  static const hairline = Color(0xFF2F2F33);
  static const hairlineSoft = Color(0xFF232326);

  /// 文本四级（fg / fg-2 / muted / meta）。
  static const ink = Color(0xFFF5F5F7);
  static const inkSecondary = Color(0xFFC7C7CC);
  static const inkMuted = Color(0xFF98989F);
  static const inkFaint = Color(0xFF7D7D85);

  /// 系统蓝：高亮、选中、进度用 accent；实心按钮用 accentFill（更深）。
  static const accent = Color(0xFF2997FF);
  static const accentFill = Color(0xFF0071E3);
  static const accentBright = Color(0xFF5CADFF);
  static const accentSoft = Color(0x242997FF);
  static const onAccent = Color(0xFFFFFFFF);

  static const danger = Color(0xFFFF453A);
  static const success = Color(0xFF30D158);
  static const warn = Color(0xFFFFD60A);

  /// 玻璃：同一套配方，顶亮底暗 + 一道内侧高光。
  static const glassTint = Color(0xCC0D0D10);
  static const glassFillTop = Color(0x1FFFFFFF);
  static const glassFillBottom = Color(0x0AFFFFFF);
  static const glassBorder = Color(0x1FFFFFFF);
  static const glassHighlight = Color(0x26FFFFFF);

  /// 环境光：极淡的冷蓝与暖橙，铺在黑底上给玻璃一点可透的东西。
  static const glowCool = Color(0x332E63C8);
  static const glowWarm = Color(0x267A4425);
  static const glowViolet = Color(0x1F5B3A86);

  /// 榜单前三名。
  static const gold = Color(0xFFD4AF37);
  static const silver = Color(0xFF9EA7AD);
  static const bronze = Color(0xFFB87333);
}

/// 间距按 4 的倍数走。
abstract final class R34Space {
  static const x1 = 4.0;
  static const x2 = 8.0;
  static const x3 = 12.0;
  static const x4 = 16.0;
  static const x5 = 20.0;
  static const x6 = 24.0;
  static const x8 = 32.0;
  static const x12 = 48.0;

  /// 页面左右留白。
  static const page = 16.0;

  /// 网格卡片间距。
  static const grid = 10.0;
}

/// 圆角分档，按原型：sm 8 / md 12 / lg 18 / xl 26 / 胶囊。
abstract final class R34Radius {
  static const sm = 8.0;
  static const md = 12.0;
  static const lg = 18.0;
  static const xl = 26.0;
  static const pill = 999.0;
}

/// 动效：进入减速、退出加速，位移带一点回弹。
abstract final class R34Motion {
  static const fast = Duration(milliseconds: 150);
  static const base = Duration(milliseconds: 220);
  static const slow = Duration(milliseconds: 320);

  /// 高亮块在导航/分段之间滑动用的时长，比普通切换慢一点，
  /// 让"块先走、页面跟上"看得出来。
  static const slide = Duration(milliseconds: 420);

  static const ease = Cubic(0.28, 0, 0.22, 1);
  static const easeOut = Curves.easeOutCubic;

  /// 轻微过冲，用在滑动的块和按下的图标上。
  static const spring = Cubic(0.34, 1.28, 0.64, 1);
}
