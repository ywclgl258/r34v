import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../data/local/library_store.dart';
import '../../data/local/video_cache.dart';

/// 片库：继续观看 + 收藏（视频 / 作者 / 标签分类）。
class LibraryPage extends ConsumerStatefulWidget {
  const LibraryPage({super.key});

  @override
  ConsumerState<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends ConsumerState<LibraryPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 5, vsync: this);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final history = ref.watch(historyProvider);
    final resumable = ref.watch(resumableProvider);
    final videos = ref.watch(favoritesOfKindProvider(FavoriteKind.video));
    final models = ref.watch(favoritesOfKindProvider(FavoriteKind.model));
    final tags = ref.watch(favoritesOfKindProvider(FavoriteKind.tag));
    final cached = ref.watch(cacheIndexProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('片库'),
        bottom: TabBar(
          controller: _tabs,
          isScrollable: true,
          tabAlignment: TabAlignment.start,
          tabs: [
            Tab(text: '继续观看 ${resumable.length}'),
            Tab(text: '视频 ${videos.length}'),
            Tab(text: '作者 ${models.length}'),
            Tab(text: '标签 ${tags.length}'),
            Tab(text: '已缓存 ${cached.length}'),
          ],
        ),
        actions: [
          PopupMenuButton<String>(
            onSelected: (v) async {
              if (v == 'clear_history') {
                final ok = await _confirm(context, '清空全部观看记录？');
                if (!ok) return;
                await ref.read(historyProvider.notifier).clear();
              } else if (v == 'clear_fav') {
                final ok = await _confirm(context, '清空全部收藏？（含视频、作者、标签）');
                if (!ok) return;
                await ref.read(libraryStoreProvider).clearFavorites();
                ref.invalidate(favoritesProvider);
              }
            },
            itemBuilder: (context) => const [
              PopupMenuItem(value: 'clear_history', child: Text('清空观看记录')),
              PopupMenuItem(value: 'clear_fav', child: Text('清空收藏')),
            ],
          ),
        ],
      ),
      body: TabBarView(
        controller: _tabs,
        children: [
          _HistoryList(entries: resumable, all: history),
          _FavoriteVideoList(entries: videos),
          _FavoriteChipList(
            entries: models,
            kind: FavoriteKind.model,
            icon: Icons.person_outline,
            emptyText: '还没有收藏的作者\n在视频详情页长按作者名就能收藏',
          ),
          _FavoriteChipList(
            entries: tags,
            kind: FavoriteKind.tag,
            icon: Icons.sell_outlined,
            emptyText: '还没有收藏的标签\n在视频详情页长按标签就能收藏',
          ),
          _CachedList(entries: cached),
        ],
      ),
    );
  }

  static Future<bool> _confirm(BuildContext context, String msg) async {
    final r = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        content: Text(msg),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('确定')),
        ],
      ),
    );
    return r ?? false;
  }
}

class _HistoryList extends ConsumerWidget {
  const _HistoryList({required this.entries, required this.all});

  final List<WatchEntry> entries;
  final List<WatchEntry> all;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (entries.isEmpty) {
      return const _EmptyHint(
        icon: Icons.play_circle_outline,
        text: '还没有看过的视频\n看过的会出现在这里，能接着上次的进度继续',
      );
    }
    return ListView.separated(
      itemCount: entries.length,
      separatorBuilder: (context, index) => const Divider(height: 1),
      itemBuilder: (context, i) {
        final e = entries[i];
        return ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          leading: SizedBox(
            width: 96,
            height: 54,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  _Thumb(url: e.thumb),
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    child: LinearProgressIndicator(
                      value: e.progress,
                      minHeight: 3,
                      backgroundColor: Colors.black45,
                    ),
                  ),
                ],
              ),
            ),
          ),
          title: Text(e.title, maxLines: 2, overflow: TextOverflow.ellipsis),
          subtitle: Text(
            '${_fmt(e.positionSeconds)} / ${_fmt(e.durationSeconds)}  ·  ${_ago(e.updatedAt)}',
          ),
          trailing: IconButton(
            tooltip: '从记录里移除',
            icon: const Icon(Icons.close),
            onPressed: () => ref.read(historyProvider.notifier).remove(e.videoId),
          ),
          onTap: () => context.push(e.path),
        );
      },
    );
  }
}

/// 收藏的视频：带封面，和继续观看一样的行布局。
class _FavoriteVideoList extends ConsumerWidget {
  const _FavoriteVideoList({required this.entries});

  final List<FavoriteEntry> entries;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (entries.isEmpty) {
      return const _EmptyHint(
        icon: Icons.bookmark_border,
        text: '还没有收藏的视频\n在视频详情页右上角点心形就能收藏',
      );
    }
    return ListView.separated(
      itemCount: entries.length,
      separatorBuilder: (context, index) => const Divider(height: 1),
      itemBuilder: (context, i) {
        final e = entries[i];
        return ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          leading: SizedBox(
            width: 96,
            height: 54,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: _Thumb(url: e.thumb),
            ),
          ),
          title: Text(e.title, maxLines: 2, overflow: TextOverflow.ellipsis),
          subtitle: Text(
            [e.subtitle, _ago(e.addedAt)].where((s) => s.isNotEmpty).join('  ·  '),
          ),
          trailing: IconButton(
            tooltip: '取消收藏',
            icon: const Icon(Icons.bookmark),
            onPressed: () =>
                ref.read(favoritesProvider.notifier).toggle(e),
          ),
          onTap: () => context.push(e.path),
        );
      },
    );
  }
}

/// 收藏的作者 / 标签：用 chip 墙，因为它们的「内容」只是一个名字，
/// 用带封面的行布局会显得空。
class _FavoriteChipList extends ConsumerWidget {
  const _FavoriteChipList({
    required this.entries,
    required this.kind,
    required this.icon,
    required this.emptyText,
  });

  final List<FavoriteEntry> entries;
  final FavoriteKind kind;
  final IconData icon;
  final String emptyText;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (entries.isEmpty) {
      return _EmptyHint(icon: icon, text: emptyText);
    }
    final scheme = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          '${kind.label} · ${entries.length} 个',
          style: Theme.of(context).textTheme.labelLarge?.copyWith(color: scheme.primary),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final e in entries)
              InputChip(
                avatar: Icon(icon, size: 16),
                label: Text(e.title),
                onPressed: () => context.push(e.path),
                onDeleted: () => ref.read(favoritesProvider.notifier).toggle(e),
                deleteIcon: const Icon(Icons.close, size: 15),
                deleteButtonTooltipMessage: '取消收藏',
              ),
          ],
        ),
        const SizedBox(height: 24),
        Text(
          kind == FavoriteKind.tag
              ? '提示：点标签看这个标签下的视频。标签规模很大（最热的有近两千页），'
                  '收藏它比收藏单个视频更有用。'
              : '提示：点作者看他的全部作品。',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(color: scheme.outline),
        ),
      ],
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    if (url.isEmpty) {
      return ColoredBox(color: Theme.of(context).colorScheme.surfaceContainerHighest);
    }
    return CachedNetworkImage(
      imageUrl: url,
      fit: BoxFit.cover,
      placeholder: (context, u) =>
          ColoredBox(color: Theme.of(context).colorScheme.surfaceContainerHighest),
      errorWidget: (context, u, e) =>
          ColoredBox(color: Theme.of(context).colorScheme.surfaceContainerHighest),
    );
  }
}

class _EmptyHint extends StatelessWidget {
  const _EmptyHint({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 48, color: Theme.of(context).colorScheme.outline),
          const SizedBox(height: 12),
          Text(text, textAlign: TextAlign.center),
        ],
      ),
    );
  }
}

String _fmt(int seconds) {
  if (seconds <= 0) return '00:00';
  final h = seconds ~/ 3600;
  final m = (seconds % 3600) ~/ 60;
  final s = seconds % 60;
  final mm = m.toString().padLeft(2, '0');
  final ss = s.toString().padLeft(2, '0');
  return h > 0 ? '$h:$mm:$ss' : '$mm:$ss';
}

String _ago(DateTime t) {
  final d = DateTime.now().difference(t);
  if (d.inMinutes < 1) return '刚刚';
  if (d.inHours < 1) return '${d.inMinutes} 分钟前';
  if (d.inDays < 1) return '${d.inHours} 小时前';
  if (d.inDays < 30) return '${d.inDays} 天前';
  return '${t.year}-${t.month.toString().padLeft(2, '0')}-${t.day.toString().padLeft(2, '0')}';
}

/// 已缓存的视频。
///
/// 与「收藏」的区别：这里的东西**在磁盘上**，点开是本地播放，不跑网络。
/// 所以在右侧放了文件大小和删除按钮——用户需要知道占了多大空间。
class _CachedList extends ConsumerWidget {
  const _CachedList({required this.entries});

  final List<CachedVideo> entries;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (entries.isEmpty) {
      return const _EmptyHint(
        icon: Icons.offline_pin_outlined,
        text: '还没有缓存的视频\n收藏的视频和看过的长视频会自动缓存到本地',
      );
    }

    final scheme = Theme.of(context).colorScheme;
    final totalMb = entries.fold<double>(0, (s, e) => s + e.megabytes);

    return ListView(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Row(
            children: [
              Text(
                '共 ${entries.length} 个 · ${totalMb.toStringAsFixed(0)} MB',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: scheme.outline,
                    ),
              ),
              const Spacer(),
              TextButton(
                onPressed: () async {
                  final ok = await showDialog<bool>(
                    context: context,
                    builder: (context) => AlertDialog(
                      content: const Text('清空全部缓存？下次播放要重新下载。'),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(context, false),
                          child: const Text('取消'),
                        ),
                        FilledButton(
                          onPressed: () => Navigator.pop(context, true),
                          child: const Text('清空'),
                        ),
                      ],
                    ),
                  );
                  if (ok != true) return;
                  await ref.read(videoCacheProvider).clearAll();
                  ref.invalidate(cacheIndexProvider);
                },
                child: const Text('全部清空'),
              ),
            ],
          ),
        ),
        for (final e in entries)
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            leading: SizedBox(
              width: 96,
              height: 54,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(6),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    _Thumb(url: e.thumb),
                    Positioned(
                      left: 4,
                      bottom: 4,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.7),
                          borderRadius: BorderRadius.circular(3),
                        ),
                        child: Text(
                          e.quality,
                          style: const TextStyle(color: Colors.white, fontSize: 9),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            title: Text(e.title, maxLines: 2, overflow: TextOverflow.ellipsis),
            subtitle: Text(
              '${e.megabytes.toStringAsFixed(0)} MB  ·  ${_ago(e.createdAt)}'
              '${e.fileExists ? "" : "  ·  文件已丢失"}',
              style: e.fileExists ? null : TextStyle(color: scheme.error),
            ),
            trailing: IconButton(
              tooltip: '删除缓存',
              icon: const Icon(Icons.delete_outline),
              onPressed: () async {
                await ref.read(videoCacheProvider).remove(e.videoId);
                ref.invalidate(cacheIndexProvider);
              },
            ),
            onTap: () => context.push('/play/${e.videoId}/_'),
          ),
      ],
    );
  }
}
