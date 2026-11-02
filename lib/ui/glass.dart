import 'dart:ui';

import 'package:flutter/material.dart';

import 'tokens.dart';

/// 保留性能开关，供仍使用磨砂的二级界面读取。
final ValueNotifier<bool> glassBlurEnabled = ValueNotifier<bool>(true);

/// 兼容旧二级界面的磨砂背板；主导航与新首页不再使用模糊层。
class GlassBackdrop extends StatelessWidget {
  const GlassBackdrop({
    super.key,
    this.blur = 20,
    this.tint = R34Color.glassTint,
    this.border = const Border(bottom: BorderSide(color: R34Color.glassBorder)),
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
          decoration: BoxDecoration(color: tint, border: border),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class GlassPanel extends StatelessWidget {
  const GlassPanel({
    super.key,
    required this.child,
    this.radius = R34Radius.lg,
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
    return ClipRRect(
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
            color: R34Color.surfaceRaised,
            border: border ? Border.all(color: R34Color.hairlineSoft) : null,
          ),
          child: Padding(padding: padding, child: child),
        ),
      ),
    );
  }
}

/// 纯黑画布。封面与文字直接分层，不再铺彩色环境光。
class AmbientBackdrop extends StatelessWidget {
  const AmbientBackdrop({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return ColoredBox(color: R34Color.canvas, child: child);
  }
}

AppBar r34AppBar({
  Widget? title,
  List<Widget>? actions,
  Widget? leading,
  bool automaticallyImplyLeading = true,
  bool large = false,
  bool frosted = false,
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
    toolbarHeight: toolbarHeight ?? (large ? 68 : kToolbarHeight),
    centerTitle: false,
    backgroundColor: R34Color.canvas,
    surfaceTintColor: Colors.transparent,
    elevation: 0,
    scrolledUnderElevation: 0,
    flexibleSpace: frosted ? const GlassBackdrop() : null,
    titleTextStyle: large
        ? const TextStyle(
            fontFamily: 'NotoSansSC',
            fontSize: 27,
            height: 1.15,
            fontWeight: FontWeight.w800,
            letterSpacing: -0.5,
            color: R34Color.ink,
          )
        : const TextStyle(
            fontFamily: 'NotoSansSC',
            fontSize: 18,
            height: 1.2,
            fontWeight: FontWeight.w700,
            color: R34Color.ink,
          ),
  );
}
