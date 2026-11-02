import 'package:flutter/material.dart';

/// 全局视觉令牌：黑色画布、紧凑表面、红色强调。
abstract final class R34Color {
  static const canvas = Color(0xFF000000);
  static const surface = Color(0xFF141414);
  static const surfaceRaised = Color(0xFF1F1F1F);
  static const surfaceHigh = Color(0xFF2A2A2A);
  static const hairline = Color(0xFF303030);
  static const hairlineSoft = Color(0xFF222222);

  static const ink = Color(0xFFFFFFFF);
  static const inkSecondary = Color(0xFFB3B3B3);
  static const inkMuted = Color(0xFF8C8C8C);
  static const inkFaint = Color(0xFF666666);

  static const accent = Color(0xFFE50914);
  static const accentFill = Color(0xFFE50914);
  static const accentBright = Color(0xFFFF3B45);
  static const accentSoft = Color(0x26E50914);
  static const onAccent = Color(0xFFFFFFFF);

  static const danger = Color(0xFFFF453A);
  static const success = Color(0xFF46D369);
  static const warn = Color(0xFFFFD60A);

  // 保留旧组件接口；新主题不再使用磨砂渐变。
  static const glassTint = canvas;
  static const glassFillTop = Color(0x00FFFFFF);
  static const glassFillBottom = Color(0x00FFFFFF);
  static const glassBorder = hairlineSoft;
  static const glassHighlight = Color(0x00FFFFFF);
  static const glowCool = Color(0x00000000);
  static const glowWarm = Color(0x00000000);
  static const glowViolet = Color(0x00000000);

  static const gold = Color(0xFFD4AF37);
  static const silver = Color(0xFF9EA7AD);
  static const bronze = Color(0xFFB87333);
}

abstract final class R34Space {
  static const x1 = 4.0;
  static const x2 = 8.0;
  static const x3 = 12.0;
  static const x4 = 16.0;
  static const x5 = 20.0;
  static const x6 = 24.0;
  static const x8 = 32.0;
  static const x12 = 48.0;
  static const page = 20.0;
  static const grid = 12.0;
}

abstract final class R34Radius {
  static const xs = 2.0;
  static const sm = 4.0;
  static const md = 4.0;
  static const lg = 8.0;
  static const xl = 12.0;
  static const pill = 999.0;
}

abstract final class R34Motion {
  static const fast = Duration(milliseconds: 150);
  static const base = Duration(milliseconds: 220);
  static const slow = Duration(milliseconds: 320);
  static const slide = Duration(milliseconds: 320);
  static const ease = Cubic(0.28, 0, 0.22, 1);
  static const easeOut = Curves.easeOutCubic;
  static const spring = Cubic(0.34, 1.28, 0.64, 1);
}
