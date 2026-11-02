import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../core/constants.dart';
import '../../data/parsing/parse_exception.dart';
import '../../data/remote/site_client.dart';
import '../../domain/video_card.dart';
import '../../ui/components.dart';
import '../../ui/glass.dart';
import '../../ui/tokens.dart';
import '../feed/feed_page.dart';
import '../feed/video_card_tile.dart';
import '../feed/video_grid.dart';

/// 搜索页。
///
/// 与列表页分开的原因：搜索有自己的状态（输入框、历史、空态提示），
/// 而且「点开就聚焦」需要页面自己控制焦点，塞进通用列表页会越来越绕。
class SearchPage extends ConsumerStatefulWidget {
  const SearchPage({super.key, this.initialQuery = ''});

  final String initialQuery;

  @override
  ConsumerState<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends ConsumerState<SearchPage> {
  late final TextEditingController _text = TextEditingController(
    text: widget.initialQuery,
  );
  final _focus = FocusNode();
  final _scroll = ScrollController();

  String _query = '';
  FeedSort _sort = FeedSort.relevance;
  List<VideoCard> _results = const [];
  Pager _pager = Pager.unknown;
  int _page = 1;
  bool _loading = false;
  bool _loadingMore = false;
  String? _error;
  bool _searched = false;
  int _searchRevision = 0;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    if (widget.initialQuery.isNotEmpty) {
      _query = widget.initialQuery;
      _searched = true;
      WidgetsBinding.instance.addPostFrameCallback((_) => _search(1));
    } else {
      WidgetsBinding.instance.addPostFrameCallback(
        (_) => _focus.requestFocus(),
      );
    }
  }

  @override
  void dispose() {
    _text.dispose();
    _focus.dispose();
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients || _loading || _loadingMore) return;
    if (_scroll.position.maxScrollExtent - _scroll.position.pixels < 800) {
      _search(_page + 1);
    }
  }

  Future<void> _search(int page) async {
    final q = _text.text.trim();
    final revision = page == 1 ? ++_searchRevision : _searchRevision;
    if (q.isEmpty) {
      if (page == 1 && _loading) {
        setState(() => _loading = false);
      }
      return;
    }

    setState(() {
      if (page == 1) {
        _loading = true;
        _error = null;
        _query = q;
        _searched = true;
        _results = const [];
      } else {
        _loadingMore = true;
      }
    });

    try {
      final api = ref.read(rule34ApiProvider);
      final result = await api.fetchFeed(
        FeedRequest(kind: FeedKind.search, target: q, sort: _sort, page: page),
      );
      if (!mounted || revision != _searchRevision) return;
      // 只把成功完成的搜索词记入历史。
      ref.read(searchHistoryProvider.notifier).add(q);
      setState(() {
        _results = page == 1 ? result.cards : [..._results, ...result.cards];
        _pager = result.pager;
        _page = page;
        _loading = false;
        _loadingMore = false;
      });
    } on HttpException catch (e) {
      if (!mounted || revision != _searchRevision) return;
      setState(() {
        _error = e.message.contains('人机验证')
            ? '站点要求人机验证，暂时搜不了'
            : '搜索失败：${e.message}';
        _loading = false;
        _loadingMore = false;
      });
    } on ParseException catch (e) {
      if (!mounted || revision != _searchRevision) return;
      setState(() {
        _error = '站点结构变了：${e.message}';
        _loading = false;
        _loadingMore = false;
      });
    } catch (e) {
      if (!mounted || revision != _searchRevision) return;
      setState(() {
        _error = '搜索失败：$e';
        _loading = false;
        _loadingMore = false;
      });
    }
  }

  void _submit() {
    _focus.unfocus();
    _search(1);
  }

  @override
  Widget build(BuildContext context) {
    final history = ref.watch(searchHistoryProvider);
    final hasMore = _pager.total == 0 || _page < _pager.total;

    return Scaffold(
      appBar: r34AppBar(
        leading: const BackButton(),
        titleSpacing: 0,
        title: Container(
          height: 42,
          decoration: BoxDecoration(
            color: R34Color.surfaceRaised,
            borderRadius: BorderRadius.circular(R34Radius.sm),
          ),
          child: TextField(
            controller: _text,
            focusNode: _focus,
            autofocus: true,
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _submit(),
            style: const TextStyle(
              fontFamily: 'NotoSansSC',
              fontSize: 14,
              color: R34Color.ink,
            ),
            decoration: InputDecoration(
              hintText: '搜索视频、标签、作者',
              filled: false,
              border: InputBorder.none,
              enabledBorder: InputBorder.none,
              focusedBorder: InputBorder.none,
              contentPadding: const EdgeInsets.symmetric(
                horizontal: R34Space.x4,
                vertical: R34Space.x3 - 1,
              ),
              suffixIcon: _text.text.isEmpty
                  ? null
                  : IconButton(
                      icon: const Icon(Icons.close_rounded, size: 17),
                      color: R34Color.inkFaint,
                      onPressed: () {
                        _text.clear();
                        setState(() {
                          _searchRevision++;
                          _results = const [];
                          _searched = false;
                          _loading = false;
                          _loadingMore = false;
                          _error = null;
                        });
                      },
                    ),
            ),
            onChanged: (_) => setState(() {}),
          ),
        ),
        actions: [
          IconButton(
            tooltip: '搜索',
            icon: const Icon(
              Icons.arrow_forward_rounded,
              color: R34Color.accentBright,
            ),
            onPressed: _submit,
          ),
          const SizedBox(width: R34Space.x1),
        ],
      ),
      body: Column(
        children: [
          // 排序栏：站内搜索支持全部排序，之前只给了默认顺序。
          // 搜「zelda」时按播放量排和按最新排差别很大，这个不能省。
          if (_searched && _results.isNotEmpty)
            SizedBox(
              height: 46,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.fromLTRB(
                  R34Space.page,
                  R34Space.x1,
                  R34Space.page,
                  R34Space.x2,
                ),
                children: [
                  for (final s in FeedSort.values)
                    Padding(
                      padding: const EdgeInsets.only(right: R34Space.x2),
                      child: ChoiceChip(
                        label: Text(
                          s.label,
                          style: const TextStyle(fontSize: 12),
                        ),
                        selected: _sort == s,
                        visualDensity: VisualDensity.compact,
                        onSelected: (_) {
                          if (_sort == s) return;
                          setState(() => _sort = s);
                          _search(1);
                        },
                      ),
                    ),
                ],
              ),
            ),
          Expanded(child: _buildBody(history, hasMore)),
        ],
      ),
    );
  }

  Widget _buildBody(List<String> history, bool hasMore) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return ErrorState(message: _error!, onRetry: () => _search(1));
    }
    // 还没搜过：显示历史 + 提示
    if (!_searched) {
      return _SearchPlaceholder(
        history: history,
        onPick: (q) {
          _text.text = q;
          _submit();
        },
        onClear: () => ref.read(searchHistoryProvider.notifier).clear(),
      );
    }
    if (_results.isEmpty) {
      return EmptyState(
        icon: Icons.search_off_rounded,
        title: '没有找到「$_query」',
        message: '换个关键词试试，或者用更短的词',
      );
    }

    return RefreshIndicator(
      onRefresh: () => _search(1),
      child: CustomScrollView(
        controller: _scroll,
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(
              R34Space.page,
              R34Space.x2,
              R34Space.page,
              0,
            ),
            sliver: SliverGrid(
              gridDelegate: videoGridDelegateFor(context),
              delegate: SliverChildBuilderDelegate(
                (context, i) => VideoCardTile(card: _results[i], index: i),
                childCount: _results.length,
              ),
            ),
          ),
          if (_loadingMore)
            const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.all(R34Space.x4),
                child: Center(child: CircularProgressIndicator()),
              ),
            ),
          if (!hasMore && !_loadingMore)
            const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.all(R34Space.x6),
                child: Center(
                  child: Text(
                    '到底了',
                    style: TextStyle(
                      fontFamily: 'NotoSansSC',
                      fontSize: 12,
                      color: R34Color.inkFaint,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 搜索框为空时的引导：最近搜过什么。
class _SearchPlaceholder extends StatelessWidget {
  const _SearchPlaceholder({
    required this.history,
    required this.onPick,
    required this.onClear,
  });

  final List<String> history;
  final void Function(String) onPick;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    if (history.isEmpty) {
      return const EmptyState(
        icon: Icons.manage_search_rounded,
        title: '输入关键词开始搜索',
        message: '支持视频标题、标签、作者',
      );
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(
        R34Space.page,
        R34Space.x2,
        R34Space.page,
        R34Space.x8,
      ),
      children: [
        Row(
          children: [
            Text('最近搜索', style: Theme.of(context).textTheme.titleSmall),
            const Spacer(),
            TextButton(onPressed: onClear, child: const Text('清空')),
          ],
        ),
        const SizedBox(height: R34Space.x1),
        Wrap(
          spacing: R34Space.x2,
          runSpacing: R34Space.x2,
          children: [
            for (final q in history)
              ActionChip(label: Text(q), onPressed: () => onPick(q)),
          ],
        ),
      ],
    );
  }
}
