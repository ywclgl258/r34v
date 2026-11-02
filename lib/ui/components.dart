import 'package:flutter/material.dart';

import 'tokens.dart';

/// 分区标题：可选右侧动作。
class SectionHeader extends StatelessWidget {
  const SectionHeader({
    super.key,
    required this.title,
    this.count,
    this.actionLabel,
    this.onAction,
    this.padding = const EdgeInsets.fromLTRB(
      R34Space.page,
      28,
      R34Space.page,
      12,
    ),
  });

  final String title;
  final int? count;
  final String? actionLabel;
  final VoidCallback? onAction;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: padding,
      child: Row(
        children: [
          Text(
            title,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
              fontSize: 17,
              fontWeight: FontWeight.w700,
              letterSpacing: 0,
              color: R34Color.ink,
            ),
          ),
          if (count != null) ...[
            const SizedBox(width: 8),
            Text(
              '$count',
              style: Theme.of(
                context,
              ).textTheme.labelSmall?.copyWith(color: R34Color.inkFaint),
            ),
          ],
          const Spacer(),
          if (onAction != null)
            TextButton(
              onPressed: onAction,
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 10),
                minimumSize: const Size(0, 32),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(actionLabel ?? '全部'),
            ),
        ],
      ),
    );
  }
}

/// 卡片浮入：按序号错峰，从下方 16px 处淡入到位。
///
/// 只给列表前几屏用（序号超过 [_maxStagger] 直接不包动画），
/// 滚动回收后重建的卡片也不会再跳一次。
class RiseIn extends StatefulWidget {
  const RiseIn({super.key, required this.index, required this.child});

  final int index;
  final Widget child;

  static const int _maxStagger = 8;

  @override
  State<RiseIn> createState() => _RiseInState();
}

class _RiseInState extends State<RiseIn> with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final CurvedAnimation _curve;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 620),
    );
    final start = (widget.index * 0.06).clamp(0.0, 0.48);
    _curve = CurvedAnimation(
      parent: _controller,
      curve: Interval(
        start,
        (start + 0.5).clamp(0.0, 1.0),
        curve: R34Motion.easeOut,
      ),
    );
    _controller.forward();
  }

  @override
  void dispose() {
    _curve.dispose();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.index >= RiseIn._maxStagger ||
        MediaQuery.disableAnimationsOf(context)) {
      return widget.child;
    }
    return AnimatedBuilder(
      animation: _curve,
      child: widget.child,
      builder: (context, child) => Opacity(
        opacity: _curve.value,
        child: Transform.translate(
          offset: Offset(0, (1 - _curve.value) * 16),
          child: child,
        ),
      ),
    );
  }
}

/// 基础卡片：表面色 + 发丝描边 + 统一圆角。
///
/// 不用 Material 的 Card，因为这里的圆角和描边要跟令牌一致，
/// 而且卡片本身不该带阴影。
class SurfaceCard extends StatelessWidget {
  const SurfaceCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(R34Space.x4),
    this.onTap,
    this.radius = R34Radius.lg,
    this.color,
    this.borderColor,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final VoidCallback? onTap;
  final double radius;
  final Color? color;
  final Color? borderColor;

  @override
  Widget build(BuildContext context) {
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(radius),
      side: BorderSide(color: borderColor ?? R34Color.hairline),
    );

    return Material(
      color: color ?? R34Color.surface,
      shape: shape,
      clipBehavior: Clip.antiAlias,
      child: onTap == null
          ? Padding(padding: padding, child: child)
          : InkWell(
              onTap: onTap,
              child: Padding(padding: padding, child: child),
            ),
    );
  }
}

/// 空状态：图标 + 一句主文案 + 可选说明与动作。
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.message,
    this.actionLabel,
    this.onAction,
    this.secondaryLabel,
    this.onSecondary,
  });

  final IconData icon;
  final String title;
  final String? message;
  final String? actionLabel;
  final VoidCallback? onAction;

  /// 次要动作。用在「重试没用、得换个办法」的场景，
  /// 例如被人机验证拦住时给一个「用浏览器打开」的出口。
  final String? secondaryLabel;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(R34Space.x8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: R34Color.surface,
                shape: BoxShape.circle,
                border: Border.all(color: R34Color.hairline),
              ),
              child: Icon(icon, color: R34Color.inkMuted, size: 26),
            ),
            const SizedBox(height: R34Space.x4),
            Text(
              title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleSmall,
            ),
            if (message != null) ...[
              const SizedBox(height: R34Space.x2),
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 280),
                child: Text(
                  message!,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: R34Color.inkMuted,
                    height: 1.55,
                  ),
                ),
              ),
            ],
            if (onAction != null) ...[
              const SizedBox(height: R34Space.x5),
              FilledButton(
                onPressed: onAction,
                child: Text(actionLabel ?? '重试'),
              ),
            ],
            if (onSecondary != null) ...[
              const SizedBox(height: R34Space.x2),
              TextButton(
                onPressed: onSecondary,
                child: Text(secondaryLabel ?? ''),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// 错误状态：和空状态同一套骨架，只是图标与语义不同。
class ErrorState extends StatelessWidget {
  const ErrorState({
    super.key,
    required this.message,
    this.onRetry,
    this.icon = Icons.cloud_off_rounded,
    this.title = '加载失败',
    this.secondaryLabel,
    this.onSecondary,
  });

  final String message;
  final VoidCallback? onRetry;
  final IconData icon;
  final String title;
  final String? secondaryLabel;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) {
    return EmptyState(
      icon: icon,
      title: title,
      message: message,
      actionLabel: '重试',
      onAction: onRetry,
      secondaryLabel: secondaryLabel,
      onSecondary: onSecondary,
    );
  }
}

/// 小胶囊标签：用于时长、清晰度、类型这类叠在封面上的信息。
class PillTag extends StatelessWidget {
  const PillTag({
    super.key,
    required this.text,
    this.background = const Color(0xB3000000),
    this.foreground = Colors.white,
    this.fontSize = 10,
  });

  final String text;
  final Color background;
  final Color foreground;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2.5),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(R34Radius.sm),
        border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
      ),
      child: Text(
        text,
        style: TextStyle(
          color: foreground,
          fontSize: fontSize,
          height: 1.2,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.1,
        ),
      ),
    );
  }
}

/// 分段药丸：替掉滚动 TabBar，一屏能看到全部入口。
class PillTabs extends StatelessWidget {
  const PillTabs({
    super.key,
    required this.controller,
    required this.labels,
    this.onTap,
  });

  final TabController controller;
  final List<String> labels;
  final void Function(int)? onTap;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return Wrap(
          spacing: R34Space.x2,
          runSpacing: R34Space.x2,
          children: [
            for (var i = 0; i < labels.length; i++)
              _PillTab(
                label: labels[i],
                selected: controller.index == i,
                onTap: () {
                  controller.animateTo(i);
                  onTap?.call(i);
                },
              ),
          ],
        );
      },
    );
  }
}

class _PillTab extends StatelessWidget {
  const _PillTab({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    // 选中不是换一层底色，而是这颗胶囊自己变形：填白、变宽、文字转黑。
    // 用隐式动画做，切换时能看见"形状在变"，不需要额外状态管理。
    return AnimatedContainer(
      duration: R34Motion.base,
      // **这里必须用不过冲的曲线。**
      //
      // `R34Motion.spring` 是 `Cubic(0.34, 1.28, 0.64, 1)`，控制点 y=1.28
      // 意味着插值参数 t 会冲过 1.0（回弹效果就是这么来的）。
      // 而 `BoxShadow.lerp` 在 t > 1 时会按线性外推算 blurRadius，
      // 一旦越过端点就得到**负数**，命中 dart:ui 的断言：
      //
      //   'Text shadow blur radius should be non-negative.'
      //
      // 表现：点页签切换的那一瞬间整页变红（release 下灰屏）。
      // 只在那一帧出现，很容易被误判成布局溢出。
      //
      // 其他属性（color / border / padding）用 spring 没问题，只有阴影
      // 经不起外推，所以整段动画统一换成 easeOut——视觉差别很小，
      // 但彻底避开这个断言。
      curve: R34Motion.easeOut,
      decoration: BoxDecoration(
        color: selected ? R34Color.ink : R34Color.surface,
        borderRadius: BorderRadius.circular(R34Radius.sm),
        border: Border.all(
          color: selected ? Colors.transparent : R34Color.hairline,
        ),
        boxShadow: [
          // 始终给等长列表（不要写 `selected ? [...] : null`），
          // 让 lerp 只在两个同构 BoxShadow 之间发生。
          BoxShadow(
            color: selected
                ? Colors.black.withValues(alpha: 0.35)
                : Colors.transparent,
            blurRadius: 14,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Material(
        type: MaterialType.transparency,
        child: InkWell(
          customBorder: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(R34Radius.sm),
          ),
          onTap: onTap,
          splashColor: selected ? Colors.black26 : R34Color.accentSoft,
          highlightColor: Colors.transparent,
          child: AnimatedPadding(
            duration: R34Motion.base,
            curve: R34Motion.spring,
            padding: EdgeInsets.symmetric(
              horizontal: selected ? 15 : 12,
              vertical: selected ? 8 : 7,
            ),
            child: AnimatedDefaultTextStyle(
              duration: R34Motion.fast,
              style: TextStyle(
                fontFamily: 'NotoSansSC',
                fontSize: 12.5,
                height: 1.2,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                color: selected ? R34Color.canvas : R34Color.inkSecondary,
              ),
              child: Text(label),
            ),
          ),
        ),
      ),
    );
  }
}

/// 带图标的小信息块（图标 + 文案），用于详情页元信息一行。
class MetaItem extends StatelessWidget {
  const MetaItem({super.key, required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    if (text.isEmpty) return const SizedBox.shrink();
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 13, color: R34Color.inkFaint),
        const SizedBox(width: 4),
        Text(
          text,
          style: Theme.of(
            context,
          ).textTheme.labelMedium?.copyWith(color: R34Color.inkMuted),
        ),
      ],
    );
  }
}
