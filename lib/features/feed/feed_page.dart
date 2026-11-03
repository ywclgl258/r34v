import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../data/parsing/parse_exception.dart';
import '../../data/remote/site_client.dart';
import '../../domain/video_card.dart';
import '../../app/providers.dart';
import '../../ui/components.dart';
import '../../ui/glass.dart';
import '../../ui/loading_scene.dart';
import '../../ui/tokens.dart';
import 'hot_carousel.dart';
import 'video_card_tile.dart';
import 'video_grid.dart';

export 'hot_carousel.dart' show HotCarousel;

export 'video_grid.dart' show feedColumnsFor, videoGridDelegateFor;

/// 列表页的加载状态。
sealed class FeedState {
  const FeedState();
}

class FeedLoading extends FeedState {
  const FeedLoading();
}

class FeedError extends FeedState {
  const FeedError(this.message, {this.isChallenge = false, this.canRetry = true});
  final String message;
  final bool isChallenge;
  final bool canRetry;
}

class FeedReady extends FeedState {
  const FeedReady({
    required this.cards,
    required this.pager,
    required this.page,
    required this.loadingMore,
    this.moreError,
    this.fromCache = false,
  });

  final List<VideoCard> cards;
  final Pager pager;
  final int page;
  final bool loadingMore;
  final String? moreError;

  /// 当前内容来自本地缓存，后台刷新还没回来。
  final bool fromCache;

  bool get hasMore => pager.total == 0 || page < pager.total;

  FeedReady copyWith({
    List<VideoCard>? cards,
    Pager? pager,
    int? page,
    bool? loadingMore,
    String? moreError,
    bool? fromCache,
  }) =>
      FeedReady(
        cards: cards ?? this.cards,
        pager: pager ?? this.pager,
        page: page ?? this.page,
        loadingMore: loadingMore ?? this.loadingMore,
        moreError: moreError,
        fromCache: fromCache ?? this.fromCache,
      );
}

/// 单个列表的数据源。
///
/// 做成 `NotifierProvider.family` 而不是在页面里 setState，
/// 是因为首页/搜索/标签共用同一套分页逻辑，且切走再回来要保留已加载的页。
class FeedController extends Notifier<FeedState> {
  FeedController(this.request);

  /// Riverpod 3 的 family 通过构造函数传入参数。
  final FeedRequest request;

  @override
  FeedState build() {
    // 首次进入：先同步吐出缓存（如果有），再后台拉新。
    //
    // 这是「每次点进来都要等」的主要解法。站点是纯 HTML，一轮 200KB+
    // 解析下来 1–3 秒；有缓存时用户几乎立刻看到内容，新数据到了再替换。
    final cached = ref.read(feedCacheProvider).read(_cacheKey);
    Future.microtask(() => _revalidate(hadCache: cached != null));
    if (cached != null) {
      _accumulated
        ..clear()
        ..addAll(cached.cards);
      return FeedReady(
        cards: List.unmodifiable(_accumulated),
        // 缓存只存第 1 页，分页器未知；用 unknown 让「到底了」不误判。
        pager: Pager.unknown,
        page: 1,
        loadingMore: false,
        fromCache: true,
      );
    }
    return const FeedLoading();
  }

  /// 缓存键：能唯一标识这一页列表（种类 + 目标 + 排序）。
  /// 不含页码——只缓存第 1 页。
  String get _cacheKey =>
      '${request.kind.name}:${request.target}:${request.sort.param}';

  final List<VideoCard> _accumulated = [];

  /// 拉第 1 页。
  ///
  /// [hadCache] 为 true 时**不清空当前内容、不进入 loading**：用户正在看的
  /// 缓存不应该在刷新瞬间消失。拿到新数据后整页替换。
  Future<void> _revalidate({bool hadCache = false}) async {
    if (!hadCache) {
      _accumulated.clear();
      state = const FeedLoading();
    }
    try {
      final api = ref.read(rule34ApiProvider);
      final page = await api.fetchFeed(request.copyWith(page: 1));
      // 首页去重：**单页内部也可能有重复**。
      //
      // 站点在「随机」「最高评分」这类排序下偶尔会把同一个视频吐两次，
      // 一旦重复进入列表，ListView 的 key 冲突会直接抛
      // `Key "..." was already used`（参考项目在 Compose 上踩过同样的坑）。
      final seen = <String>{};
      final fresh = page.cards.where((c) => seen.add(c.id)).toList();

      _accumulated
        ..clear()
        ..addAll(fresh);
      state = FeedReady(
        cards: List.unmodifiable(_accumulated),
        pager: page.pager,
        page: 1,
        loadingMore: false,
      );
      // 写缓存不 await：UI 不该等磁盘
      unawaited(ref.read(feedCacheProvider).write(_cacheKey, fresh));
    } on HttpException catch (e) {
      if (_keepStaleOnError(hadCache, '更新失败：${_humanizeHttp(e)}')) return;
      state = FeedError(
        _humanizeHttp(e),
        isChallenge: e.message.contains('人机验证'),
        canRetry: true,
      );
    } on ParseException catch (e) {
      if (_keepStaleOnError(hadCache, '站点结构变了：${e.message}')) return;
      state = FeedError('站点结构变了：${e.message}', canRetry: false);
    } catch (e) {
      if (_keepStaleOnError(hadCache, '更新失败：$e')) return;
      state = FeedError('加载失败：$e');
    }
  }

  /// 有缓存时刷新失败：保留已有内容，只在顶部挂一条提示。
  ///
  /// 直接把用户正在看的内容换成错误页是最糟的处理——他明明有东西可看。
  bool _keepStaleOnError(bool hadCache, String message) {
    if (!hadCache || _accumulated.isEmpty) return false;
    state = FeedReady(
      cards: List.unmodifiable(_accumulated),
      pager: Pager.unknown,
      page: 1,
      loadingMore: false,
      fromCache: true,
      moreError: message,
    );
    return true;
  }

  Future<void> loadFirst() => _revalidate();

  Future<void> loadMore() async {
    final cur = state;
    if (cur is! FeedReady) return;
    if (cur.loadingMore || !cur.hasMore) return;

    state = cur.copyWith(loadingMore: true, moreError: null);
    final next = cur.page + 1;
    try {
      final api = ref.read(rule34ApiProvider);
      final page = await api.fetchFeed(request.copyWith(page: next));
      // 去重：站点内容更新时可能出现跨页重复
      final existing = _accumulated.map((c) => c.id).toSet();
      final fresh = page.cards.where((c) => !existing.contains(c.id));
      _accumulated.addAll(fresh);
      state = FeedReady(
        cards: List.unmodifiable(_accumulated),
        pager: page.pager,
        page: next,
        loadingMore: false,
      );
    } on HttpException catch (e) {
      state = cur.copyWith(loadingMore: false, moreError: _humanizeHttp(e));
    } on ParseException catch (e) {
      state = cur.copyWith(loadingMore: false, moreError: '站点结构变了：${e.message}');
    } catch (e) {
      state = cur.copyWith(loadingMore: false, moreError: '加载失败：$e');
    }
  }

  /// 下拉刷新：明确要新数据，走无缓存的完整重载。
  Future<void> refresh() => _revalidate();
}

/// 按屏宽决定每行几个视频。实现见 `video_grid.dart`。
///
/// 这里保留一层转发，是因为 `feed_page.dart` 是历史入口，
/// 测试与页面都在 import 它；真正的规则只有一份，不在这里。

String _humanizeHttp(HttpException e) {  if (e.message.contains('人机验证')) {
    return '站点要求人机验证。客户端过不去这一步，需要等一段时间或换网络。';
  }
  if (e.message.contains('超时')) return '网络太慢，请求超时了。检查一下网络再重试。';
  if (e.message.contains('网络不可用')) return '当前没有可用网络。';
  final code = e.status;
  if (code != null) return '站点返回 $code，稍后重试。';
  return e.message;
}

final feedControllerProvider =
    NotifierProvider.family<FeedController, FeedState, FeedRequest>(
  FeedController.new,
);

/// 一个可复用的列表视图（首页 / 搜索 / 标签 / 分类 / 作者都用它）。
class FeedPageTab extends ConsumerStatefulWidget {
  const FeedPageTab({
    super.key,
    required this.kind,
    this.target = '',
    this.sortName,
    this.showBackButton = true,
    this.headingOverride,
  });

  final FeedKind kind;
  final String target;
  final String? sortName;
  final bool showBackButton;
  final String? headingOverride;

  @override
  ConsumerState<FeedPageTab> createState() => _FeedPageTabState();
}

class _FeedPageTabState extends ConsumerState<FeedPageTab> {
  late FeedSort _sort;
  final _scroll = ScrollController();

  FeedRequest get _request => FeedRequest(kind: widget.kind, target: widget.target, sort: _sort);

  @override
  void initState() {
    super.initState();
    _sort = FeedSort.values.firstWhere(
      (s) => s.name == widget.sortName,
      orElse: () => FeedSort.newest,
    );
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final remaining = _scroll.position.maxScrollExtent - _scroll.position.pixels;
    if (remaining < 800) {
      ref.read(feedControllerProvider(_request).notifier).loadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(feedControllerProvider(_request));
    final controller = ref.read(feedControllerProvider(_request).notifier);

    // 首页（最新更新）把搜索框摆在标题下面的第一行，
    // 不占标题栏；其他列表页标题已有内容，不塞搜索。
    final showSearch = widget.kind == FeedKind.latest && widget.target.isEmpty;

    // 顶部栏高度（含状态栏）：内容从栏底下穿过去，滚动时才看得见磨砂。
    final topInset = MediaQuery.paddingOf(context).top + 64 + R34Space.x2;

    // 首页（最新更新）顶部换成热门视频轮播。
    //
    // 轮播展示的就是热门榜，所以下方不再单独排一个「热门视频」栏目——
    // 那是重复信息。
    final isHome = showSearch;

    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: isHome
          ? null
          : r34AppBar(
              large: true,
              leading: widget.showBackButton && Navigator.canPop(context)
                  ? const BackButton()
                  : null,
              title: Text(widget.headingOverride ?? _titleFor()),
              titleSpacing: widget.showBackButton && Navigator.canPop(context)
                  ? 0
                  : R34Space.page,
              actions: [
                PopupMenuButton<FeedSort>(
                  icon: const Icon(Icons.sort_rounded, size: 21),
                  tooltip: '排序',
                  initialValue: _sort,
                  onSelected: (s) {
                    setState(() => _sort = s);
                    // 换排序等于换数据源，重建 controller
                    ref
                        .read(feedControllerProvider(_request).notifier)
                        .loadFirst();
                  },
                  itemBuilder: (context) => [
                    for (final s in FeedSort.values)
                      PopupMenuItem(value: s, child: Text(s.label)),
                  ],
                ),
                IconButton(
                  tooltip: '刷新',
                  icon: const Icon(Icons.refresh_rounded, size: 21),
                  onPressed: controller.refresh,
                ),
                const SizedBox(width: R34Space.x2),
              ],
            ),
      body: switch (state) {
        FeedLoading() => const LoadingScene(
          // 列表页只走一条链路（请求 + 解析），所以用 fetching 阶段；
          // 步骤文案对应用户真正在等的两件事。
          stage: LoadingStage.fetching,
          footNote: '首次加载会慢一些',
        ),
        FeedError(:final message, :final isChallenge, :final canRetry) => ErrorState(
            message: message,
            title: isChallenge ? '需要人机验证' : '加载失败',
            icon: isChallenge ? Icons.shield_outlined : Icons.cloud_off_rounded,
            onRetry: canRetry ? controller.refresh : null,
          ),
        FeedReady(:final cards) when cards.isEmpty => const EmptyState(
            icon: Icons.search_off_rounded,
            title: '这里没有内容',
            message: '换个标签或排序再看看。',
          ),        FeedReady(:final cards, :final loadingMore, :final moreError, :final hasMore) =>
          RefreshIndicator(
            onRefresh: controller.refresh,
            color: R34Color.accent,
            child: CustomScrollView(
              controller: _scroll,
              slivers: [
                if (isHome)
                  SliverToBoxAdapter(
                    child: HotCarousel(
                      cards: HotCarousel.topViewed(cards, 6),
                    ),
                  )
                else
                  SliverToBoxAdapter(child: SizedBox(height: topInset)),
                if (isHome)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(
                        R34Space.page,
                        R34Space.x3,
                        R34Space.page,
                        R34Space.x2,
                      ),
                      child: Row(
                        children: [
                          Text(
                            '最新更新',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const Spacer(),
                          PopupMenuButton<FeedSort>(
                            icon: const Icon(Icons.sort_rounded, size: 20),
                            tooltip: '排序',
                            initialValue: _sort,
                            onSelected: (s) {
                              setState(() => _sort = s);
                              ref
                                  .read(feedControllerProvider(_request).notifier)
                                  .loadFirst();
                            },
                            itemBuilder: (context) => [
                              for (final s in FeedSort.values)
                                PopupMenuItem(value: s, child: Text(s.label)),
                            ],
                          ),
                          IconButton(
                            tooltip: '刷新',
                            icon: const Icon(Icons.refresh_rounded, size: 20),
                            onPressed: controller.refresh,
                          ),
                        ],
                      ),
                    ),
                  ),
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(
                    R34Space.page,
                    R34Space.x2,
                    R34Space.page,
                    R34Space.x6,
                  ),
                  sliver: SliverGrid(
                    // 列数按屏宽算，不用 maxCrossAxisExtent。
                    // 用固定宽度会在手机上退化成 2 列（420px 宽塞不下两个 320+），
                    // 试过 320 的结果就是「一屏只有 2 个视频」，翻页太累。
                    //
                    // 高度给 mainAxisExtent 而不是 childAspectRatio：
                    // 卡片内容高度是常数，用比例去凑会在底部留白。
                    gridDelegate: videoGridDelegateFor(context),
                    delegate: SliverChildBuilderDelegate(
                      (context, i) => VideoCardTile(card: cards[i], index: i),
                      childCount: cards.length,
                    ),
                  ),
                ),
                if (loadingMore)
                  const SliverToBoxAdapter(
                    child: Padding(
                      padding: EdgeInsets.all(R34Space.x4),
                      child: Center(
                        child: SizedBox(
                          width: 22,
                          height: 22,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      ),
                    ),
                  ),
                if (moreError != null)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 4, 16, 20),
                      child: SurfaceCard(
                        padding: const EdgeInsets.all(14),
                        child: Column(
                          children: [
                            Text(
                              moreError,
                              textAlign: TextAlign.center,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                            const SizedBox(height: R34Space.x3),
                            FilledButton.tonal(
                              onPressed: controller.loadMore,
                              child: const Text('重试'),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                if (!hasMore && cards.isNotEmpty)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                      child: Center(
                        child: Text(
                          '到底了',
                          style: Theme.of(context).textTheme.labelSmall?.copyWith(
                                color: R34Color.inkFaint,
                              ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
      },
    );
  }

  String _titleFor() {
    if (widget.target.isNotEmpty) {
      return '${widget.kind.label} · ${_prettyTarget(widget.target)}';
    }
    switch (widget.kind) {
      case FeedKind.latest:
        return '最新更新';
      case FeedKind.topRated:
        return '高分榜';
      case FeedKind.mostPopular:
        return '热门榜';
      case FeedKind.search:
        return '搜索';
      case FeedKind.tag:
        return '标签';
      case FeedKind.character:
        return '角色';
      case FeedKind.category:
        return '分类';
      case FeedKind.model:
        return '作者';
      case FeedKind.forYou:
        return '为你推荐';
      case FeedKind.topCharacters:
        return '角色排行';
      case FeedKind.topArtists:
        return '作者排行';
    }
  }

  static String _prettyTarget(String t) =>
      t.replaceAll('-', ' ').split(' ').map((w) {
        if (w.isEmpty) return w;
        return w[0].toUpperCase() + w.substring(1);
      }).join(' ');
}

/// 标题栏里的搜索框。
///
/// 首页直接可搜，不用先进「发现」再点一下。点它跳搜索页并自动聚焦输入框，
/// 这样「点开就打字」，比在首页里塞个输入框再原地换列表要顺手。
