import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/feed_link.dart';
import 'ranking_page.dart';

/// 发现页：榜单入口 + 三类索引（角色/作者/标签）+ 分类。
///
/// 之前只有三个榜单和分类方块，缺了原站的：
/// - 角色排行榜（/top-characters/）、作者排行榜（/top-model/）
/// - 标签、角色、作者三种总索引
///
/// 「不知道看什么、想随便逛逛」的路径基本靠这一页，所以补全它比加新功能更重要。
class ExplorePage extends ConsumerStatefulWidget {
  const ExplorePage({super.key});

  @override
  ConsumerState<ExplorePage> createState() => _ExplorePageState();
}

class _ExplorePageState extends ConsumerState<ExplorePage> {
  final _search = TextEditingController();
  bool _loading = true;
  String? _error;

  List<FeedLink> _categories = const [];
  List<FeedLink> _characters = const [];
  List<FeedLink> _models = const [];
  List<FeedLink> _tags = const [];

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
      // 四个索引并发拉，串行会慢一倍多
      final results = await Future.wait([
        api.fetchCategories(),
        api.fetchCharacters(),
        api.fetchModels(),
        api.fetchTags(),
      ]);
      if (!mounted) return;
      setState(() {
        _categories = results[0];
        _characters = results[1];
        _models = results[2];
        _tags = results[3];
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
    final session = ref.watch(authProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('发现'),
        actions: [
          IconButton(
            tooltip: session == null ? '登录' : '已登录：${session.username}',
            icon: Icon(
              session == null ? Icons.login : Icons.account_circle,
              color: session == null ? null : Theme.of(context).colorScheme.primary,
            ),
            onPressed: () {
              if (session == null) {
                context.push('/login');
              } else {
                _showAccountSheet(session.username);
              }
            },
          ),
        ],
      ),
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

            // ---- 为你推荐 ----
            if (session != null) ...[
              const SizedBox(height: 16),
              _BigEntry(
                icon: Icons.auto_awesome,
                title: '为你推荐',
                subtitle: '基于你的收藏和观看记录',
                onTap: () => context.push('/feed/forYou'),
              ),
            ],

            const SizedBox(height: 20),
            _sectionHeader(context, '排行榜'),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _chip(context, '最新更新', () => context.push('/feed/latest')),
                _chip(context, '高分榜', () => context.push('/feed/topRated')),
                _chip(context, '热门榜', () => context.push('/feed/mostPopular')),
                _chip(
                  context,
                  '角色排行',
                  () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (context) => const CharacterRankingPage(),
                    ),
                  ),
                  icon: Icons.person_pin_outlined,
                ),
                _chip(
                  context,
                  '作者排行',
                  () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (context) => const ArtistRankingPage(),
                    ),
                  ),
                  icon: Icons.brush_outlined,
                ),
              ],
            ),

            if (_loading) ...[
              const SizedBox(height: 40),
              const Center(child: CircularProgressIndicator()),
            ] else if (_error != null) ...[
              const SizedBox(height: 20),
              _ErrorBox(message: _error!, onRetry: _load),
            ] else ...[
              // ---- 分类 ----
              if (_categories.isNotEmpty) ...[
                const SizedBox(height: 24),
                _sectionHeader(
                  context,
                  '分类',
                  count: _categories.length,
                  action: () => _openIndex('category', '全部分类', _categories),
                ),
                _LinkGrid(
                  links: _categories.take(11).toList(),
                  onTap: (l) =>
                      context.push('/list/category/${Uri.encodeComponent(l.slug)}'),
                ),
              ],

              // ---- 角色 ----
              if (_characters.isNotEmpty) ...[
                const SizedBox(height: 24),
                _sectionHeader(
                  context,
                  '角色',
                  count: _characters.length,
                  action: () => _openIndex('character', '全部角色', _characters),
                ),
                _LinkGrid(
                  links: _characters.take(11).toList(),
                  onTap: (l) =>
                      context.push('/list/character/${Uri.encodeComponent(l.slug)}'),
                ),
              ],

              // ---- 作者 ----
              if (_models.isNotEmpty) ...[
                const SizedBox(height: 24),
                _sectionHeader(
                  context,
                  '作者',
                  count: _models.length,
                  action: () => _openIndex('model', '全部作者', _models),
                ),
                _LinkGrid(
                  links: _models.take(11).toList(),
                  onTap: (l) =>
                      context.push('/list/model/${Uri.encodeComponent(l.slug)}'),
                ),
              ],

              // ---- 标签 ----
              if (_tags.isNotEmpty) ...[
                const SizedBox(height: 24),
                _sectionHeader(
                  context,
                  '标签',
                  count: _tags.length,
                  action: () => _openIndex('tag', '全部标签', _tags),
                ),
                _TagCloud(links: _tags.take(40).toList()),
              ],
            ],
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  void _showAccountSheet(String username) {
    showModalBottomSheet<void>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.account_circle),
              title: Text(username),
              subtitle: const Text('已登录'),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.logout),
              title: const Text('退出登录'),
              onTap: () async {
                Navigator.pop(context);
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (context) => AlertDialog(
                    content: const Text('退出后「为你推荐」会不可用。'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(context, false),
                        child: const Text('取消'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.pop(context, true),
                        child: const Text('退出'),
                      ),
                    ],
                  ),
                );
                if (ok != true) return;
                await ref.read(authProvider.notifier).logout();
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('已退出登录')),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  /// 打开一个完整索引页（列表形式，可搜索）。
  void _openIndex(String kind, String title, List<FeedLink> links) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (context) => _IndexPage(kind: kind, title: title, links: links),
    ));
  }

  Widget _sectionHeader(
    BuildContext context,
    String text, {
    int? count,
    VoidCallback? action,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        children: [
          Text(text, style: Theme.of(context).textTheme.titleMedium),
          if (count != null) ...[
            const SizedBox(width: 6),
            Text(
              '$count',
              style: Theme.of(context)
                  .textTheme
                  .labelSmall
                  ?.copyWith(color: Theme.of(context).colorScheme.outline),
            ),
          ],
          const Spacer(),
          if (action != null)
            TextButton(
              onPressed: action,
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 8),
              ),
              child: const Text('全部'),
            ),
        ],
      ),
    );
  }

  Widget _chip(
    BuildContext context,
    String label,
    VoidCallback onTap, {
    IconData? icon,
  }) => ActionChip(
        avatar: icon == null ? null : Icon(icon, size: 16),
        label: Text(label),
        onPressed: onTap,
      );
}

/// 大号入口卡片（为你推荐）。
class _BigEntry extends StatelessWidget {
  const _BigEntry({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(10),
          gradient: LinearGradient(
            colors: [
              scheme.primary.withValues(alpha: 0.22),
              scheme.primary.withValues(alpha: 0.06),
            ],
          ),
          border: Border.all(color: scheme.primary.withValues(alpha: 0.3)),
        ),
        child: Row(
          children: [
            Icon(icon, color: scheme.primary),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: Theme.of(context).textTheme.titleSmall),
                  Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, size: 18),
          ],
        ),
      ),
    );
  }
}

/// 索引网格：封面 + 名字。
class _LinkGrid extends StatelessWidget {
  const _LinkGrid({required this.links, required this.onTap});

  final List<FeedLink> links;
  final void Function(FeedLink) onTap;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final l in links)
          SizedBox(
            width: 104,
            child: InkWell(
              onTap: () => onTap(l),
              borderRadius: BorderRadius.circular(8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: AspectRatio(
                      aspectRatio: 16 / 9,
                      child: _Thumb(url: l.thumb, hasThumb: l.hasThumb),
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    l.label,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11.5),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

/// 标签云：标签没有封面，用密集 chip。
class _TagCloud extends StatelessWidget {
  const _TagCloud({required this.links});

  final List<FeedLink> links;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        for (final l in links)
          ActionChip(
            label: Text(l.label, style: const TextStyle(fontSize: 11)),
            visualDensity: VisualDensity.compact,
            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            onPressed: () => context.push(
              '/list/tag/${Uri.encodeComponent(l.slug)}',
            ),
          ),
      ],
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url, required this.hasThumb});

  final String url;
  final bool hasThumb;

  @override
  Widget build(BuildContext context) {
    final ph = ColoredBox(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Center(
        child: Icon(
          Icons.image_outlined,
          size: 20,
          color: Theme.of(context).colorScheme.outline,
        ),
      ),
    );
    if (!hasThumb || url.isEmpty) return ph;
    return Image.network(
      url,
      fit: BoxFit.cover,
      errorBuilder: (context, e, s) => ph,
      loadingBuilder: (context, child, progress) =>
          progress == null ? child : ph,
    );
  }
}

class _ErrorBox extends StatelessWidget {
  const _ErrorBox({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('索引没加载出来', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 6),
          Text(message, style: const TextStyle(fontSize: 12)),
          const SizedBox(height: 10),
          FilledButton.tonal(onPressed: onRetry, child: const Text('重试')),
        ],
      ),
    );
  }
}

/// 完整索引页：搜索 + 网格。
class _IndexPage extends StatefulWidget {
  const _IndexPage({required this.kind, required this.title, required this.links});

  final String kind;
  final String title;
  final List<FeedLink> links;

  @override
  State<_IndexPage> createState() => _IndexPageState();
}

class _IndexPageState extends State<_IndexPage> {
  String _q = '';

  @override
  Widget build(BuildContext context) {
    final filtered = _q.isEmpty
        ? widget.links
        : widget.links
            .where((l) => l.label.toLowerCase().contains(_q.toLowerCase()))
            .toList();

    return Scaffold(
      appBar: AppBar(
        title: Text('${widget.title}（${widget.links.length}）'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: TextField(
              decoration: const InputDecoration(
                isDense: true,
                hintText: '在本页筛选',
                prefixIcon: Icon(Icons.filter_alt_outlined, size: 18),
                border: OutlineInputBorder(),
              ),
              onChanged: (v) => setState(() => _q = v.trim()),
            ),
          ),
          Expanded(
            child: GridView.builder(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 110,
                childAspectRatio: 0.78,
                crossAxisSpacing: 8,
                mainAxisSpacing: 8,
              ),
              itemCount: filtered.length,
              itemBuilder: (context, i) {
                final l = filtered[i];
                return InkWell(
                  onTap: () => context.push(
                    '/list/${widget.kind}/${Uri.encodeComponent(l.slug)}',
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: SizedBox.expand(
                            child: _Thumb(url: l.thumb, hasThumb: l.hasThumb),
                          ),
                        ),
                      ),
                      const SizedBox(height: 3),
                      Text(
                        l.label,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 11),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
