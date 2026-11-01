import 'dart:ui';

import 'package:flutter/material.dart';

import 'tokens.dart';

/// 玻璃模糊开关。
///
/// 关掉后所有玻璃退化成"半透明色块"，省掉最贵的一层 BackdropFilter；
/// 低端机/省电模式下肉眼差别很小，滑动会顺不少。
final ValueNotifier<bool> glassBlurEnabled = ValueNotifier<bool>(true);

/// 磨砂背板：模糊背后内容 + 一层顶亮底暗的玻璃 + 发丝边。
///
/// 只负责"看起来像玻璃"，不带内边距和文字，方便塞进 AppBar 的
/// flexibleSpace 或者包住底部导航。
class GlassBackdrop extends StatelessWidget {
  const GlassBackdrop({
    super.key,
    this.blur = 20,
    this.tint = R34Color.glassTint,
    this.border = const Border(
      bottom: BorderSide(color: R34Color.glassBorder),
    ),
  });

  final double blur;
  final Color tint;
  final BoxBorder? border;

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: ValueListenableBuilder<bool>(
        valueListenable: glassBlurEnabled,
        builder: (context, enabled, child) {
          if (!enabled) return child!;
          return BackdropFilter(
            filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
            child: child,
          );
        },
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [tint, tint.withValues(alpha: 0.86)],
            ),
            border: border,
          ),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

/// 玻璃面板：圆角、模糊、玻璃渐变、外侧发丝边。
///
/// 用在底部导航、悬浮卡片这类"浮在内容上"的位置；
/// 普通内容卡片不用它，避免一屏叠太多模糊层把低端机拖卡。
class GlassPanel extends StatelessWidget {
  const GlassPanel({
    super.key,
    required this.child,
    this.radius = R34Radius.xl,
    this.blur = 18,
    this.padding = EdgeInsets.zero,
    this.border = true,
  });

  final Widget child;
  final double radius;
  final double blur;
  final EdgeInsetsGeometry padding;
  final bool border;

  @override
  Widget build(BuildContext context) {
    final r = BorderRadius.circular(radius);
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: r,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.45),
            blurRadius: 28,
            offset: const Offset(0, 14),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: r,
        child: ValueListenableBuilder<bool>(
          valueListenable: glassBlurEnabled,
          builder: (context, enabled, panel) {
            if (!enabled) return panel!;
            return BackdropFilter(
              filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
              child: panel,
            );
          },
          child: DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: r,
              gradient: const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [R34Color.glassFillTop, R34Color.glassFillBottom],
              ),
              border: border
                  ? Border.all(color: R34Color.glassBorder)
                  : null,
            ),
            child: Padding(padding: padding, child: child),
          ),
        ),
      ),
    );
  }
}

/// 环境光：黑底上三团极淡的冷蓝、暖橙、紫。
///
/// 没有它，纯黑界面上的玻璃和半透明都没有可透的东西，
/// 看起来会像一块块贴上去的灰色方块。
class AmbientBackdrop extends StatelessWidget {
  const AmbientBackdrop({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        const ColoredBox(color: R34Color.canvas),
        const _Glow(
          center: Alignment(-1.15, -1.05),
          color: R34Color.glowCool,
          radius: 1.15,
        ),
        const _Glow(
          center: Alignment(1.05, 0.62),
          color: R34Color.glowWarm,
          radius: 1.0,
        ),
        const _Glow(
          center: Alignment(0.15, 1.1),
          color: R34Color.glowViolet,
          radius: 0.9,
        ),
        child,
      ],
    );
  }
}

class _Glow extends StatelessWidget {
  const _Glow({
    required this.center,
    required this.color,
    required this.radius,
  });

  final Alignment center;
  final Color color;
  final double radius;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: RadialGradient(
          center: center,
          radius: radius,
          colors: [color, color.withValues(alpha: 0)],
        ),
      ),
    );
  }
}

/// 顶部磨砂栏。
///
/// 包一层 AppBar 而不是自己实现：状态栏内边距、返回键、overflow 菜单
/// 这些系统行为都还在，只是把底换成玻璃、标题放大。
AppBar r34AppBar({
  Widget? title,
  List<Widget>? actions,
  Widget? leading,
  bool automaticallyImplyLeading = true,
  bool large = false,
  bool frosted = true,
  PreferredSizeWidget? bottom,
  double? toolbarHeight,
  double? titleSpacing,
}) {
  return AppBar(
    title: title,
    actions: actions,
    leading: leading,
    automaticallyImplyLeading: automaticallyImplyLeading,
    bottom: bottom,
    titleSpacing: titleSpacing,
    toolbarHeight: toolbarHeight ?? (large ? 64 : kToolbarHeight),
    centerTitle: false,
    backgroundColor: Colors.transparent,
    surfaceTintColor: Colors.transparent,
    elevation: 0,
    scrolledUnderElevation: 0,
    flexibleSpace: frosted ? const GlassBackdrop() : null,
    titleTextStyle: large
        ? const TextStyle(
            fontFamily: 'NotoSansSC',
            fontSize: 26,
            height: 1.15,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.5,
            color: R34Color.ink,
          )
        : null,
  );
}
