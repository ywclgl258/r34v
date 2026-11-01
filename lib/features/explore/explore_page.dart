import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/feed_link.dart';
import '../../domain/ranked_entry.dart';
import '../../ui/components.dart';
import '../../ui/glass.dart';
import '../../ui/tokens.dart';
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
  /// 角色没有扁平索引。站点的 `/characters/` 是 404，
  /// 角色挂在「作品」下面（`/top-characters/<work>/`），
  /// 所以这里存的是作品清单，点进去才是角色榜。
  List<CharacterWork> _works = const [];
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

    final api = ref.read(rule34ApiProvider);

    // 每个索引**独立**加载，一个失败不影响其它。
    //
    // 之前用 Future.wait 一把梭，结果 `/characters/` 返回 404（这个路径站内不存在），
    // 整个索引区就都变成「索引没加载出来 HTTP 404」——一个坏掉拖垮全部。
    Future<List<T>> safe<T>(Future<List<T>> Function() run) async {
      try {
        return await run();
      } catch (_) {
        return <T>[];
      }
    }

    final results = await Future.wait<List<Object>>([
      safe(api.fetchCategories),
      safe(api.fetchModels),
      safe(api.fetchTags),
      // 角色没有扁平索引，只能拿排行榜里的「作品」清单当入口
      safe(api.fetchCharacterWorks),
    ]);

    if (!mounted) return;

    final cats = results[0].cast<FeedLink>();
    final models = results[1].cast<FeedLink>();
    final tags = results[2].cast<FeedLink>();
    final works = results[3].cast<CharacterWork>();

    setState(() {
      _categories = cats;
      _models = models;
      _tags = tags;
      _works = works;
      // 全部为空才算真失败，否则只是部分索引拿不到
      _error = (cats.isEmpty && models.isEmpty && tags.isEmpty && works.isEmpty)
          ? '索引都没拉到，可能是网络或站点限流'
          : null;
      _loading = false;
    });
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
      appBar: r34AppBar(
        large: true,
        title: const Text('发现'),
        actions: [
          IconButton(
            tooltip: session == null ? '登录' : '已登录：${session.username}',
            icon: Icon(
              session == null ? Icons.login_rounded : Icons.account_circle_rounded,
              color: session == null ? null : R34Color.accentBright,
            ),
            onPressed: () {
              if (session == null) {
                context.push('/login');
              } else {
                _showAccountSheet(session.username);
              }
            },
          ),
          const SizedBox(width: R34Space.x2),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        color: R34Color.accent,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(
            R34Space.page,
            R34Space.x2,
            R34Space.page,
            R34Space.x8,
          ),
          children: [
            TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _doSearch(),
              // 不写死边框：用主题里的 surface-2 填充 + 描边，
              // 保持和原型搜索框一致
              decoration: const InputDecoration(
                hintText: '搜索视频、标签、作者',
                prefixIcon: Icon(Icons.search_rounded, size: 20),
                suffixIconColor: R34Color.accent,
              ),
            ),

            // ---- 为你推荐 ----
            if (session != null) ...[
              const SizedBox(height: R34Space.x4),
              _BigEntry(
                icon: Icons.auto_awesome_rounded,
                title: '为你推荐',
                subtitle: '基于你的收藏和观看记录',
                onTap: () => context.push('/feed/forYou'),
              ),
            ],

            const SectionHeader(
              title: '排行榜',
              padding: EdgeInsets.fromLTRB(0, 28, 0, 12),
            ),
            Wrap(
              spacing: R34Space.x2,
              runSpacing: R34Space.x2,
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
              const SizedBox(height: R34Space.x8),
              const Center(child: CircularProgressIndicator()),
            ] else if (_error != null) ...[
              const SizedBox(height: R34Space.x5),
              _ErrorBox(message: _error!, onRetry: _load),
            ] else ...[
              // ---- 分类 ----
              if (_categories.isNotEmpty) ...[
                SectionHeader(
                  title: '分类',
                  count: _categories.length,
                  actionLabel: '全部',
                  onAction: () =>
                      _openIndex('category', '全部分类', _categories),
                  padding: const EdgeInsets.fromLTRB(0, 28, 0, 12),
                ),
                _LinkGrid(
                  links: _categories.take(11).toList(),
                  onTap: (l) =>
                      context.push('/list/category/${Uri.encodeComponent(l.slug)}'),
                ),
              ],

              // ---- 角色 ----
              // 站点没有角色总索引，入口是「作品」：点某部作品才看到它的角色榜。
              if (_works.isNotEmpty) ...[
                SectionHeader(
                  title: '角色',
                  count: _works.length,
                  actionLabel: '排行',
                  onAction: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (context) => const CharacterRankingPage(),
                    ),
                  ),
                  padding: const EdgeInsets.fromLTRB(0, 28, 0, 12),
                ),
                Wrap(
                  spacing: R34Space.x2,
                  runSpacing: R34Space.x2,
                  children: [
                    for (final w in _works.take(15))
                      ActionChip(
                        label: Text(w.name),
                        onPressed: () => Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (context) => CharacterWorkRankingPage(work: w),
                          ),
                        ),
                      ),
                  ],
                ),
              ],

              // ---- 作者 ----
              if (_models.isNotEmpty) ...[
                SectionHeader(
                  title: '作者',
                  count: _models.length,
                  actionLabel: '全部',
                  onAction: () => _openIndex('model', '全部作者', _models),
                  padding: const EdgeInsets.fromLTRB(0, 28, 0, 12),
                ),
                _LinkGrid(
                  links: _models.take(11).toList(),
                  onTap: (l) =>
                      context.push('/list/model/${Uri.encodeComponent(l.slug)}'),
                ),
              ],

              // ---- 标签 ----
              if (_tags.isNotEmpty) ...[
                SectionHeader(
                  title: '标签',
                  count: _tags.length,
                  actionLabel: '全部',
                  onAction: () => _openIndex('tag', '全部标签', _tags),
                  padding: const EdgeInsets.fromLTRB(0, 28, 0, 12),
                ),
                _TagCloud(links: _tags.take(40).toList()),
              ],
            ],
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

  Widget _chip(
    BuildContext context,
    String label,
    VoidCallback onTap, {
    IconData? icon,
  }) => ActionChip(
        avatar: icon == null
            ? null
            : Icon(icon, size: 15, color: R34Color.inkMuted),
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
    return SurfaceCard(
      onTap: onTap,
      padding: const EdgeInsets.all(R34Space.x4),
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: R34Color.accentSoft,
              borderRadius: BorderRadius.circular(R34Radius.md),
              border: Border.all(color: R34Color.accent.withValues(alpha: 0.25)),
            ),
            child: Icon(icon, color: R34Color.accentBright, size: 21),
          ),
          const SizedBox(width: R34Space.x3),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: R34Color.inkMuted,
                      ),
                ),
              ],
            ),
          ),
          const Icon(Icons.chevron_right_rounded, size: 20, color: R34Color.inkFaint),
        ],
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
      spacing: R34Space.x2,
      runSpacing: R34Space.x3,
      children: [
        for (final l in links)
          SizedBox(
            width: 106,
            child: InkWell(
              onTap: () => onTap(l),
              borderRadius: BorderRadius.circular(R34Radius.md),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(R34Radius.md),
                      border: Border.all(color: R34Color.hairline),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: AspectRatio(
                      aspectRatio: 16 / 9,
                      child: _Thumb(url: l.thumb, hasThumb: l.hasThumb),
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    l.label,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontFamily: 'NotoSansSC',
                      fontSize: 12,
                      height: 1.35,
                      color: R34Color.inkSecondary,
                    ),
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
            label: Text(l.label),
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
    const ph = ColoredBox(
      color: R34Color.surfaceHigh,
      child: Center(
        child: Icon(Icons.image_outlined, size: 20, color: R34Color.inkFaint),
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
    return SurfaceCard(
      padding: const EdgeInsets.all(R34Space.x4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.error_outline_rounded, size: 18, color: R34Color.danger),
              const SizedBox(width: 8),
              Text('索引没加载出来', style: Theme.of(context).textTheme.titleSmall),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            message,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: R34Color.inkMuted,
                ),
          ),
          const SizedBox(height: 12),
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
      appBar: r34AppBar(
        title: Text('${widget.title}（${widget.links.length}）'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              R34Space.page,
              R34Space.x2,
              R34Space.page,
              R34Space.x3,
            ),
            child: TextField(
              decoration: const InputDecoration(
                hintText: '在本页筛选',
                prefixIcon: Icon(Icons.filter_alt_outlined, size: 18),
              ),
              onChanged: (v) => setState(() => _q = v.trim()),
            ),
          ),
          Expanded(
            child: GridView.builder(
              padding: const EdgeInsets.fromLTRB(
                R34Space.page,
                0,
                R34Space.page,
                R34Space.x6,
              ),
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 112,
                childAspectRatio: 0.8,
                crossAxisSpacing: R34Space.grid,
                mainAxisSpacing: R34Space.x3,
              ),
              itemCount: filtered.length,
              itemBuilder: (context, i) {
                final l = filtered[i];
                return InkWell(
                  onTap: () => context.push(
                    '/list/${widget.kind}/${Uri.encodeComponent(l.slug)}',
                  ),
                  borderRadius: BorderRadius.circular(R34Radius.md),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Container(
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(R34Radius.md),
                            border: Border.all(color: R34Color.hairline),
                          ),
                          clipBehavior: Clip.antiAlias,
                          child: SizedBox.expand(
                            child: _Thumb(url: l.thumb, hasThumb: l.hasThumb),
                          ),
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        l.label,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'NotoSansSC',
                          fontSize: 12,
                          height: 1.35,
                          color: R34Color.inkSecondary,
                        ),
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
