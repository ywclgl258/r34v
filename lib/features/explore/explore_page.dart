import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/feed_link.dart';

/// 发现页：搜索入口 + 分类/标签/榜单导航。
class ExplorePage extends ConsumerStatefulWidget {
  const ExplorePage({super.key});

  @override
  ConsumerState<ExplorePage> createState() => _ExplorePageState();
}

class _ExplorePageState extends ConsumerState<ExplorePage> {
  final _search = TextEditingController();
  List<FeedLink>? _categories;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final api = ref.read(rule34ApiProvider);
      final cats = await api.fetchCategories();
      if (!mounted) return;
      setState(() {
        _categories = cats;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e';
        _loading = false;
      });
    }
  }

  void _doSearch() {
    final q = _search.text.trim();
    if (q.isEmpty) return;
    context.push('/search?q=${Uri.encodeComponent(q)}');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('发现')),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          padding: const EdgeInsets.all(12),
          children: [
            TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _doSearch(),
              decoration: InputDecoration(
                hintText: '搜索视频、标签、作者',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.arrow_forward),
                  onPressed: _doSearch,
                ),
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 20),
            _sectionHeader(context, '榜单'),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _chip(context, '最新更新', () => context.push('/feed/latest')),
                _chip(context, '高分榜', () => context.push('/feed/topRated')),
                _chip(context, '热门榜', () => context.push('/feed/mostPopular')),
              ],
            ),
            const SizedBox(height: 20),
            _sectionHeader(context, '分类'),
            if (_loading)
              const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_error != null)
              Column(
                children: [
                  Text('分类加载失败：$_error', style: Theme.of(context).textTheme.bodySmall),
                  const SizedBox(height: 8),
                  FilledButton.tonal(onPressed: _load, child: const Text('重试')),
                ],
              )
            else if (_categories != null)
              LayoutBuilder(
                builder: (context, constraints) {
                  // 分类名长度差别很大（从 `3d` 到 "Coerced Hero Chronicles
                  // Escape from Everia"），ActionChip 一行一个太浪费，
                  // 一行铺满又会被长名字撑爆。用固定列宽 + 两行截断。
                  final cols = (constraints.maxWidth / 150).floor().clamp(2, 4);
                  final width = (constraints.maxWidth - (cols - 1) * 8) / cols;
                  return Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final c in _categories!)
                        SizedBox(
                          width: width,
                          child: _CategoryTile(
                            label: c.label,
                            hasThumb: c.hasThumb,
                            onTap: () => context.push(
                              '/list/category/${Uri.encodeComponent(c.slug)}',
                            ),
                          ),
                        ),
                    ],
                  );
                },
              ),
            const SizedBox(height: 32),
            Text(
              '站点是纯 HTML 的，没有公开 API；本客户端只读取公开页面，不做登录、不做写入。',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.outline,
                  ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionHeader(BuildContext context, String text) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );

  Widget _chip(BuildContext context, String label, VoidCallback onTap) => ActionChip(
        label: Text(label),
        onPressed: onTap,
      );
}

/// 分类格子。长名字两行截断，不撑破布局。
class _CategoryTile extends StatelessWidget {
  const _CategoryTile({
    required this.label,
    required this.hasThumb,
    required this.onTap,
  });

  final String label;
  final bool hasThumb;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(8),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Container(
          height: 44,
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          alignment: Alignment.centerLeft,
          child: Text(
            label,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: hasThumb ? scheme.onSurface : scheme.onSurfaceVariant,
                ),
          ),
        ),
      ),
    );
  }
}



