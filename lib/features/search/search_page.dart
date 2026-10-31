import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../core/constants.dart';
import '../../data/parsing/parse_exception.dart';
import '../../data/remote/site_client.dart';
import '../../domain/video_card.dart';
import '../feed/feed_page.dart';
import '../feed/video_card_tile.dart';

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
  late final TextEditingController _text =
      TextEditingController(text: widget.initialQuery);
  final _focus = FocusNode();
  final _scroll = ScrollController();

  String _query = '';
  List<VideoCard> _results = const [];
  Pager _pager = Pager.unknown;
  int _page = 1;
  bool _loading = false;
  bool _loadingMore = false;
  String? _error;
  bool _searched = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    if (widget.initialQuery.isNotEmpty) {
      _query = widget.initialQuery;
      _searched = true;
      WidgetsBinding.instance.addPostFrameCallback((_) => _search(1));
    } else {
      WidgetsBinding.instance.addPostFrameCallback((_) => _focus.requestFocus());
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
    if (q.isEmpty) return;

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

    // 记一笔历史（只记成功的搜索词）
    ref.read(searchHistoryProvider.notifier).add(q);

    try {
      final api = ref.read(rule34ApiProvider);
      final result = await api.fetchFeed(
        FeedRequest(kind: FeedKind.search, target: q, page: page),
      );
      if (!mounted) return;
      setState(() {
        _results = page == 1 ? result.cards : [..._results, ...result.cards];
        _pager = result.pager;
        _page = page;
        _loading = false;
        _loadingMore = false;
      });
    } on HttpException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message.contains('人机验证')
            ? '站点要求人机验证，暂时搜不了'
            : '搜索失败：${e.message}';
        _loading = false;
        _loadingMore = false;
      });
    } on ParseException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '站点结构变了：${e.message}';
        _loading = false;
        _loadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
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
      appBar: AppBar(
        leading: const BackButton(),
        titleSpacing: 0,
        title: TextField(
          controller: _text,
          focusNode: _focus,
          autofocus: true,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _submit(),
          decoration: InputDecoration(
            hintText: '搜索视频、标签、作者',
            border: InputBorder.none,
            isDense: true,
            suffixIcon: _text.text.isEmpty
                ? null
                : IconButton(
                    icon: const Icon(Icons.clear, size: 18),
                    onPressed: () {
                      _text.clear();
                      setState(() {
                        _results = const [];
                        _searched = false;
                        _error = null;
                      });
                    },
                  ),
          ),
          onChanged: (_) => setState(() {}),
        ),
        actions: [
          IconButton(
            tooltip: '搜索',
            icon: const Icon(Icons.arrow_forward),
            onPressed: _submit,
          ),
        ],
      ),
      body: _buildBody(history, hasMore),
    );
  }

  Widget _buildBody(List<String> history, bool hasMore) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline, size: 48),
              const SizedBox(height: 12),
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              FilledButton(onPressed: () => _search(1), child: const Text('重试')),
            ],
          ),
        ),
      );
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
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search_off, size: 48, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text('没有找到「$_query」相关的内容'),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => _search(1),
      child: CustomScrollView(
        controller: _scroll,
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.all(8),
            sliver: SliverGrid(
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: feedColumnsFor(MediaQuery.sizeOf(context).width),
                childAspectRatio: 0.72,
                crossAxisSpacing: 8,
                mainAxisSpacing: 8,
              ),
              delegate: SliverChildBuilderDelegate(
                (context, i) => VideoCardTile(card: _results[i]),
                childCount: _results.length,
              ),
            ),
          ),
          if (_loadingMore)
            const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator()),
              ),
            ),
          if (!hasMore && !_loadingMore)
            const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: Text('到底了')),
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
    final scheme = Theme.of(context).colorScheme;
    if (history.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.manage_search, size: 48, color: scheme.outline),
            const SizedBox(height: 12),
            Text(
              '输入关键词开始搜索\n支持视频标题、标签、作者',
              textAlign: TextAlign.center,
              style: TextStyle(color: scheme.outline),
            ),
          ],
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Text('最近搜索', style: Theme.of(context).textTheme.titleSmall),
            const Spacer(),
            TextButton(onPressed: onClear, child: const Text('清空')),
          ],
        ),
        const SizedBox(height: 4),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final q in history)
              ActionChip(
                label: Text(q),
                onPressed: () => onPick(q),
              ),
          ],
        ),
      ],
    );
  }
}



