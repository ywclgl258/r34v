import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'tokens.dart';

/// 加载阶段：一条链路走完分几步，每步都有自己的文案。
///
/// 为什么需要「步骤说明」而不是一个转圈：
///
/// 站点是纯 HTML 解析 + DoH + IP 直连，一轮下来 1–3 秒很常见，
/// 网络差时更久。用户只看到一个转圈时无法判断「是在干活还是卡死了」，
/// 焦虑感主要来自这种**不确定性**，而不是等待本身。
///
/// 所以每一步都明确告诉用户「现在在做哪件事」，即使整体还慢，
/// 至少他知道没有白等。
enum LoadingStage {
  /// 解析域名 / 探测可用 IP。
  resolving('正在连接放映厅', '解析地址 · 寻找可用线路'),

  /// 请求页面 HTML。
  fetching('正在加载片源', '读取页面 · 解析清晰度'),

  /// 换 CDN 直链。
  resolvingStream('正在建立播放通道', '获取直链 · 绕过跳转'),

  /// 真正起播。
  buffering('正在缓冲画面', '等待首帧 · 准备解码'),

  /// 起播成功，等界面切过去。
  ready('即将开始', '放映准备完成');

  const LoadingStage(this.label, this.detail);

  final String label;
  final String detail;
}

/// 全屏加载动画。
///
/// 视觉取自设计稿（暗底 + 细线 + 呼吸光晕 + 环形进度 + 大字号轻声标题），
/// 但进度**由真实状态驱动**，不是定时器假进度：
///
/// - [progress] 为 null 表示「不确定进度」（还在探测），此时环会自己转，
///   表示活着；有值时按比例画弧。
/// - [stage] 变了会切换文案，并让弧线平滑过渡到新起点。
class LoadingScene extends StatefulWidget {
  const LoadingScene({
    super.key,
    required this.stage,
    this.progress,
    this.footNote,
    this.compact = false,
  });

  final LoadingStage stage;

  /// 0..1。null = 不确定进度（画旋转弧）。
  final double? progress;

  /// 底部右侧的补充说明，例如「已重试 2 次」。
  final String? footNote;

  /// 紧凑模式：去掉大留白，用于嵌在页面局部而不是整屏。
  final bool compact;

  @override
  State<LoadingScene> createState() => _LoadingSceneState();
}

class _LoadingSceneState extends State<LoadingScene>
    with TickerProviderStateMixin {
  /// 不确定进度时环的旋转。
  late final AnimationController _spin = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1600),
  );

  /// 光圈呼吸。
  late final AnimationController _breathe = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 5200),
  );

  /// 进度变化的平滑过渡（避免弧线一跳一跳）。
  late final AnimationController _sweep = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 420),
  );

  double _from = 0;
  double _to = 0;

  /// 是否已经开始循环动画（避免 didChangeDependencies 多次调用时重复启动）。
  bool _animationsStarted = false;

  @override
  void initState() {
    super.initState();
    _to = widget.progress ?? 0;
    _from = _to;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_animationsStarted) return;
    _animationsStarted = true;

    // 只在「允许动画」时启动循环。
    //
    // 两个条件缺一不可：
    // - `MediaQuery.disableAnimations`：用户开了系统「减弱动态效果」
    // - `TickerMode.valuesOf(context).enabled`：所在子树允许 ticker（路由不可见时为 false，
    //   widget 测试里默认也是 false）
    //
    // 循环动画（repeat）的问题是永不静止：`pumpAndSettle` 会一直等，
    // 测试结束时还会留下 pending frame 被判定失败；真机上则是页面
    // 不可见时仍在空转耗电。
    //
    // 关掉后进度环退化成静态弧，加载信息一点不少。
    final disableAnim = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    final tickerOn = TickerMode.valuesOf(context).enabled;
    if (!disableAnim && tickerOn) {
      _spin.repeat();
      _breathe.repeat(reverse: true);
    }
  }

  /// 页面被移出树时立刻停掉循环动画。
  ///
  /// 只靠 dispose 里的 stop() 不够：route 切换期间树可能还在、但已经不可见，
  /// 循环动画会继续空转（真机上是白耗电，测试里是 pending timer 报错）。
  @override
  void deactivate() {
    _spin.stop();
    _breathe.stop();
    _sweep.stop();
    super.deactivate();
  }

  /// 重新插入树时恢复循环动画（deactivate 停掉的那个）。
  @override
  void activate() {
    super.activate();
    final disableAnim = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    final tickerOn = TickerMode.valuesOf(context).enabled;
    if (!disableAnim && tickerOn) {
      if (!_spin.isAnimating) _spin.repeat();
      if (!_breathe.isAnimating) _breathe.repeat(reverse: true);
    }
  }

  @override
  void didUpdateWidget(covariant LoadingScene oldWidget) {
    super.didUpdateWidget(oldWidget);
    final p = widget.progress;
    if (p != null && p != _to) {
      _from = _currentValue;
      _to = p.clamp(0.0, 1.0);
      _sweep.forward(from: 0);
    }
  }

  double get _currentValue {
    if (!_sweep.isAnimating && _sweep.value == 0) return _to;
    final t = Curves.easeOutCubic.transform(_sweep.value);
    return _from + (_to - _from) * t;
  }

  @override
  void dispose() {
    // **必须先停掉循环动画再 dispose。**
    //
    // `repeat()` 的 controller 在被 dispose 时如果仍处于动画中，
    // 会留下一个 pending frame 回调。在测试里这表现为
    // 「A Timer is still pending even after the widget tree was disposed」，
    // 在真机上则是页面切走后还白跑几帧。
    _spin.stop();
    _breathe.stop();
    _sweep.stop();
    _spin.dispose();
    _breathe.dispose();
    _sweep.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final compact = widget.compact;
    final markSize = compact ? 88.0 : 132.0;

    return ColoredBox(
      color: R34Color.canvas,
      child: SafeArea(
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: R34Space.page),
          child: Column(
            children: [
              const _Hairline(),
              Expanded(
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _Mark(
                        size: markSize,
                        spin: _spin,
                        breathe: _breathe,
                        sweep: _sweep,
                        currentValue: () => _currentValue,
                        indeterminate: widget.progress == null,
                      ),
                      SizedBox(height: compact ? 24 : 40),
                      // 标题用较轻的字重 + 字距，和设计稿一致：
                      // 大字号但不用粗体，避免"警告"感。
                      //
                      // 这里不用 AnimatedSwitcher：它在切换时会挂一个
                      // 短暂的 Ticker，页面恰好在这一瞬销毁就会留下
                      // pending timer（测试里直接报错，真机上白跑几帧）。
                      // 文案切换本身很轻微，淡入靠透明度动画即可，
                      // 且它会被 stop() 干净地停掉。
                      _FadeText(
                        text: widget.stage.label,
                        style: TextStyle(
                          fontFamily: 'NotoSansSC',
                          fontSize: compact ? 17 : 22,
                          height: 1.6,
                          letterSpacing: compact ? 1.0 : 2.2,
                          fontWeight: FontWeight.w400,
                          color: R34Color.ink,
                        ),
                      ),
                      const SizedBox(height: 10),
                      _FadeText(
                        text: widget.stage.detail,
                        style: TextStyle(
                          fontFamily: 'NotoSansSC',
                          fontSize: 12.5,
                          letterSpacing: 1.1,
                          height: 1.5,
                          color: R34Color.inkMuted,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              _Foot(
                busy: widget.progress == null || widget.progress! < 1,
                note: widget.footNote,
              ),
              const _Hairline(),
            ],
          ),
        ),
      ),
    );
  }
}

/// 一条极细的横线，上下各一条，用来框住整个场景。
class _Hairline extends StatelessWidget {
  const _Hairline();

  @override
  Widget build(BuildContext context) => Container(
    height: 1,
    color: R34Color.ink.withValues(alpha: 0.07),
  );
}

/// 阶段文案。
///
/// **刻意不做切换动画。** 这里原本用 `AnimatedSwitcher`，但它内部会挂一个
/// `AnimationController`，页面在这个过渡期间被销毁就会留下 pending timer
/// ——测试里直接报 `A Timer is still pending`，真机上也会白跑几帧。
///
/// 而这个文案切换的视觉收益极小（每阶段只变一次，用户注意力在进度环上），
/// 不值得为此承担一个只在特定时机才暴露的生命周期问题。
/// 所以就是一个普通 `Text`，靠 `key` 保证文案变了能正常重建。
class _FadeText extends StatelessWidget {
  const _FadeText({required this.text, required this.style});

  final String text;
  final TextStyle style;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      key: ValueKey(text),
      textAlign: TextAlign.center,
      style: style,
    );
  }
}

/// 中央图形：细线圆环 + 进度弧 + 播放三角 + 呼吸光晕。
class _Mark extends StatelessWidget {
  const _Mark({
    required this.size,
    required this.spin,
    required this.breathe,
    required this.sweep,
    required this.currentValue,
    required this.indeterminate,
  });

  final double size;
  final AnimationController spin;
  final AnimationController breathe;
  final AnimationController sweep;
  final double Function() currentValue;
  final bool indeterminate;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          // 光晕：径向渐变 + 缓慢缩放
          AnimatedBuilder(
            animation: breathe,
            builder: (context, _) {
              final t = 1 + 0.06 * Curves.easeInOut.transform(breathe.value);
              return Transform.scale(
                scale: t,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(
                      colors: [
                        R34Color.accent.withValues(alpha: 0.28),
                        R34Color.accent.withValues(alpha: 0.07),
                        R34Color.accent.withValues(alpha: 0),
                      ],
                      stops: const [0.0, 0.44, 0.7],
                    ),
                  ),
                  child: SizedBox(width: size * 2, height: size * 2),
                ),
              );
            },
          ),

          // 进度环
          AnimatedBuilder(
            animation: Listenable.merge([spin, sweep]),
            builder: (context, _) {
              return CustomPaint(
                size: Size(size, size),
                painter: _RingPainter(
                  progress: indeterminate ? null : currentValue(),
                  spin: spin.value,
                  trackColor: R34Color.ink.withValues(alpha: 0.12),
                  arcColor: R34Color.accent,
                ),
              );
            },
          ),

          // 中央播放三角：轻微脉动，暗示「这里是要播放的东西」
          AnimatedBuilder(
            animation: breathe,
            builder: (context, _) {
              final t = breathe.value;
              return Opacity(
                opacity: 0.62 + 0.34 * (t < 0.5 ? t * 2 : (1 - t) * 2),
                child: Icon(
                  Icons.play_arrow_rounded,
                  size: size * 0.26,
                  color: R34Color.ink,
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  _RingPainter({
    required this.progress,
    required this.spin,
    required this.trackColor,
    required this.arcColor,
  });

  /// null 表示不确定进度。
  final double? progress;
  final double spin;
  final Color trackColor;
  final Color arcColor;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = math.min(size.width, size.height) / 2 - 1;

    // 底环
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1
        ..color = trackColor,
    );

    final arcPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..color = arcColor;

    final rect = Rect.fromCircle(center: center, radius: radius);

    if (progress == null) {
      // 不确定：画一段约 1/4 的弧，整体旋转。
      // 这是「活着」的信号，比静止的圆更让人安心。
      const sweepAngle = math.pi * 0.5;
      final start = spin * 2 * math.pi - math.pi / 2;
      canvas.drawArc(rect, start, sweepAngle, false, arcPaint);
      return;
    }

    final value = progress!.clamp(0.0, 1.0);
    if (value <= 0) return;
    canvas.drawArc(rect, -math.pi / 2, 2 * math.pi * value, false, arcPaint);
  }

  @override
  bool shouldRepaint(covariant _RingPainter old) =>
      old.progress != progress ||
      old.spin != spin ||
      old.arcColor != arcColor ||
      old.trackColor != trackColor;
}

/// 底部状态行：左边状态，右边补充说明。
class _Foot extends StatelessWidget {
  const _Foot({required this.busy, this.note});

  final bool busy;
  final String? note;

  @override
  Widget build(BuildContext context) {
    const style = TextStyle(
      fontFamily: 'NotoSansSC',
      fontSize: 12,
      letterSpacing: 1.6,
      color: R34Color.inkMuted,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 16),
      child: Row(
        children: [
          Text(
            busy ? '缓冲中' : '可以播放',
            style: style.copyWith(
              color: busy ? R34Color.inkMuted : R34Color.accent,
            ),
          ),
          const Spacer(),
          Text(note ?? '请稍候', style: style),
        ],
      ),
    );
  }
}


