import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../data/local/library_store.dart';
import '../../domain/video_card.dart';
import '../../ui/net_image.dart';
import '../../ui/tokens.dart';

/// 首页顶部的热门视频轮播。
///
/// ## 为什么轮播而不是单张大图
///
/// 早先是「取列表第一条」铺满屏。问题有两个：列表默认按时间排序，
/// 第一条既不是最热也不是最相关；而且每次进来都是同一张，
/// 作为首屏第一眼没有信息量。
///
/// 现在轮播**按播放量排的前几名**（用已解析的 `viewsText` 本地重排，
/// 不额外发请求），可以左右滑、也会自动切换。
///
/// ## 自动播放的克制
///
/// 每 6 秒切一张，但**用户一碰就暂停 12 秒**。不这么做的话，
/// 正在读标题时画面被切走会非常恼人——自动轮播的负面体验
/// 几乎全部来自「不理会用户意图」。
class HotCarousel extends ConsumerStatefulWidget {
  const HotCarousel({super.key, required this.cards});

  final List<VideoCard> cards;

  /// 按播放量挑出最热的几条。
  ///
  /// 数量不足时按原顺序补齐，保证轮播不为空。
  static List<VideoCard> topViewed(List<VideoCard> cards, int n) {
    if (cards.isEmpty) return const [];
    final sorted = [...cards]..sort((a, b) => b.views.compareTo(a.views));
    return sorted.take(n).toList();
  }

  @override
  ConsumerState<HotCarousel> createState() => _HotCarouselState();
}

class _HotCarouselState extends ConsumerState<HotCarousel> {
  late final PageController _pager = PageController();
  Timer? _timer;
  int _index = 0;

  /// 手动操作后暂停自动播放的截止时间。
  DateTime? _resumeAt;

  static const Duration _interval = Duration(seconds: 6);
  static const Duration _pauseAfterTouch = Duration(seconds: 12);

  @override
  void initState() {
    super.initState();
    _restartTimer();
  }

  @override
  void didUpdateWidget(covariant HotCarousel oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 刷新后榜单可能变化，把索引钳回合法范围
    if (widget.cards.length != oldWidget.cards.length) {
      if (_index >= widget.cards.length) {
        _index = 0;
        if (_pager.hasClients) _pager.jumpToPage(0);
      }
      _restartTimer();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _pager.dispose();
    super.dispose();
  }

  void _restartTimer() {
    _timer?.cancel();
    if (widget.cards.length < 2) return;
    _timer = Timer.periodic(_interval, (_) {
      final resumeAt = _resumeAt;
      if (resumeAt != null && DateTime.now().isBefore(resumeAt)) return;
      if (!mounted || !_pager.hasClients) return;
      _pager.animateToPage(
        (_index + 1) % widget.cards.length,
        duration: R34Motion.slide,
        curve: R34Motion.easeOut,
      );
    });
  }

  void _markTouched() => _resumeAt = DateTime.now().add(_pauseAfterTouch);

  @override
  Widget build(BuildContext context) {
    final cards = widget.cards;
    if (cards.isEmpty) return const SizedBox.shrink();

    final size = MediaQuery.sizeOf(context);
    final isLandscape = size.width > size.height;

    // 高度按屏高取比例，但**横屏必须单独算**。
    //
    // 早先固定 `height * 0.62` + `clamp(440, 580)`：竖屏时约 540 没问题，
    // 可一旦横屏，屏高只有 800 左右，0.62 算出 496 仍被下限 440 托着，
    // 轮播几乎占满整屏，下面的栏目全被推出屏幕——看起来就像
    // 「横屏没有适配主题」。
    //
    // 竖屏也要给上限：clamp 的下限若定得太高，小屏（600/800 高的矮屏）
    // 会被轮播占满，下面的网格被挤出可视区——Flutter 的 sliver 只在
    // 进入视口时才构建，所以表现是「卡片完全不存在」，不是「被裁掉」。
    //
    // 这里保证轮播最多占屏高的 55%，且不超过 520。
    final portraitCap = math.min(480.0, size.height * 0.48);
    final height = isLandscape
        ? (size.height * 0.86).clamp(220.0, size.height - 80).toDouble()
        : (size.height * 0.62).clamp(180.0, portraitCap).toDouble();

    return SizedBox(
      height: height,
      child: Stack(
        fit: StackFit.expand,
        children: [
          NotificationListener<ScrollNotification>(
            onNotification: (n) {
              // 开始拖动就暂停自动轮播
              if (n is ScrollStartNotification && n.dragDetails != null) {
                _markTouched();
              }
              return false;
            },
            child: PageView.builder(
              controller: _pager,
              itemCount: cards.length,
              onPageChanged: (i) => setState(() => _index = i),
              itemBuilder: (context, i) =>
                  _Slide(card: cards[i], isLandscape: isLandscape),
            ),
          ),
          const _TopBar(),
          if (cards.length > 1)
            Positioned(
              left: 0,
              right: 0,
              bottom: 6,
              child: _Dots(
                count: cards.length,
                index: _index,
                onTap: (i) {
                  _markTouched();
                  _pager.animateToPage(
                    i,
                    duration: R34Motion.slide,
                    curve: R34Motion.easeOut,
                  );
                },
              ),
            ),
        ],
      ),
    );
  }
}

/// 轮播里的一屏。
class _Slide extends ConsumerWidget {
  const _Slide({required this.card, required this.isLandscape});

  final VideoCard card;

  /// 横屏时压缩竖向留白与字号，避免矮屏下内容顶出轮播。
  final bool isLandscape;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final favorite = ref.watch(isFavoriteProvider(videoKey(card.id)));
    return Stack(
      fit: StackFit.expand,
      children: [
        // 必须限制解码尺寸：站点封面常见 1000×2000+，一张 RGBA 就 8MB+，
        // 轮播 6 张同时挂树上很容易吃满纹理内存，表现为图片纯黑，
        // 且不抛异常、日志里也查不到。
        NetImage(
          url: card.thumb,
          maxWidthHint: MediaQuery.sizeOf(context).width,
        ),
        const DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Color(0x66000000),
                Color(0x00000000),
                Color(0xCC000000),
                R34Color.canvas,
              ],
              stops: [0, 0.25, 0.68, 1],
            ),
          ),
        ),
        Positioned(
          left: R34Space.page,
          right: R34Space.page,
          bottom: isLandscape ? 24 : 34,
          // 高度上限 + 底部对齐：内容再多也只在这个框里排，
          // 不会画到轮播外面（那会盖住状态栏）。
          child: LayoutBuilder(
            builder: (context, constraints) {
              final maxH = constraints.maxHeight;
              return ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: maxH.isFinite ? maxH * 0.62 : 400,
                ),
                child: OverflowBox(
                  alignment: Alignment.bottomLeft,
                  maxHeight: double.infinity,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        '热门视频',
                        style: Theme.of(context).textTheme.labelMedium?.copyWith(
                          color: R34Color.inkSecondary,
                          letterSpacing: 1.2,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        card.title,
                        // 横屏时只留 1 行并**显式缩小字号**。
                        //
                        // 只写 maxLines: 1 不够：headlineMedium 是给竖屏
                        // 大图配的（接近 30sp），横屏时轮播被压矮，一行
                        // 大字号标题加上下面几行元信息会顶出上边界，
                        // 表现是标题盖住状态栏、还压到时长那一行。
                        maxLines: isLandscape ? 1 : 2,
                        overflow: TextOverflow.ellipsis,
                        style: isLandscape
                            ? Theme.of(context).textTheme.titleLarge?.copyWith(
                                fontWeight: FontWeight.w700,
                              )
                            : Theme.of(context).textTheme.headlineMedium,
                      ),
                      SizedBox(height: isLandscape ? 6 : 10),
                      Row(
                        children: [
                          if (card.duration.isNotEmpty) ...[
                            Text(
                              card.duration,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                            const SizedBox(width: 12),
                          ],
                          const Icon(
                            Icons.play_arrow_rounded,
                            size: 14,
                            color: R34Color.inkSecondary,
                          ),
                          const SizedBox(width: 3),
                          Text(
                            card.viewsText,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                          if (card.ratingPercent >= 0) ...[
                            const SizedBox(width: 12),
                            const Icon(
                              Icons.thumb_up_rounded,
                              size: 12,
                              color: R34Color.success,
                            ),
                            const SizedBox(width: 3),
                            Text(
                              '${card.ratingPercent}%',
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ],
                        ],
                      ),
                      SizedBox(height: isLandscape ? 12 : 18),
                      Row(
                        children: [
                          Expanded(
                            child: FilledButton.icon(
                              onPressed: () =>
                                  context.push('/play/${card.id}/${card.slug}'),
                              icon: const Icon(
                                Icons.play_arrow_rounded,
                                size: 23,
                              ),
                              label: const Text('播放'),
                            ),
                          ),
                          const SizedBox(width: 10),
                          IconButton.outlined(
                            tooltip: favorite ? '取消收藏' : '收藏',
                            style: IconButton.styleFrom(
                              minimumSize: const Size(46, 44),
                              side: const BorderSide(color: R34Color.hairline),
                              backgroundColor: R34Color.surface,
                            ),
                            icon: Icon(
                              favorite
                                  ? Icons.favorite_rounded
                                  : Icons.favorite_border_rounded,
                              color: favorite ? R34Color.accent : R34Color.ink,
                            ),
                            onPressed: () => ref
                                .read(favoritesProvider.notifier)
                                .toggle(
                                  FavoriteEntry(
                                    kind: FavoriteKind.video,
                                    target: card.id,
                                    title: card.title,
                                    path: card.path,
                                    addedAt: DateTime.now(),
                                    thumb: card.thumb,
                                    subtitle: card.duration,
                                  ),
                                ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

/// 轮播顶部的 Logo 与搜索按钮。
class _TopBar extends StatelessWidget {
  const _TopBar();

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 8, 0),
        child: Align(
          alignment: Alignment.topCenter,
          child: Row(
            children: [
              const Text(
                'R34V',
                style: TextStyle(
                  fontSize: 24,
                  height: 1,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -1,
                  color: R34Color.accent,
                ),
              ),
              const Spacer(),
              IconButton(
                tooltip: '搜索',
                icon: const Icon(Icons.search_rounded, size: 25),
                onPressed: () => context.push('/search'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 轮播指示器。当前项拉长，其余是圆点。
class _Dots extends StatelessWidget {
  const _Dots({
    required this.count,
    required this.index,
    required this.onTap,
  });

  final int count;
  final int index;
  final void Function(int) onTap;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < count; i++)
          GestureDetector(
            onTap: () => onTap(i),
            behavior: HitTestBehavior.opaque,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 3, vertical: 6),
              child: AnimatedContainer(
                duration: R34Motion.fast,
                curve: R34Motion.easeOut,
                width: i == index ? 18 : 6,
                height: 6,
                decoration: BoxDecoration(
                  color: i == index ? R34Color.ink : R34Color.inkFaint,
                  borderRadius: BorderRadius.circular(R34Radius.pill),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
