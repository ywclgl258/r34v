import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../data/local/library_store.dart';
import '../../data/local/video_cache.dart';
import '../../ui/components.dart';
import '../../ui/glass.dart';
import '../../ui/net_image.dart';
import '../../ui/tokens.dart';

/// 片库：继续观看 + 收藏（视频 / 作者 / 标签分类）。
class LibraryPage extends ConsumerStatefulWidget {
  const LibraryPage({super.key});

  @override
  ConsumerState<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends ConsumerState<LibraryPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 6, vsync: this);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final history = ref.watch(historyProvider);
    final videos = ref.watch(favoritesOfKindProvider(FavoriteKind.video));
    final characters = ref.watch(favoritesOfKindProvider(FavoriteKind.character));
    final models = ref.watch(favoritesOfKindProvider(FavoriteKind.model));
    final tags = ref.watch(favoritesOfKindProvider(FavoriteKind.tag));
    final cached = ref.watch(cacheIndexProvider);

    return Scaffold(
      appBar: r34AppBar(
        large: true,
        title: const Text('片库'),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_horiz_rounded, size: 22),
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
          const SizedBox(width: R34Space.x2),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              R34Space.page,
              R34Space.x1,
              R34Space.page,
              R34Space.x3,
            ),
            child: Align(
              alignment: Alignment.centerLeft,
              child: PillTabs(
                controller: _tabs,
                labels: [
                  '观看记录 ${history.length}',
                  '视频 ${videos.length}',
                  '角色 ${characters.length}',
                  '作者 ${models.length}',
                  '标签 ${tags.length}',
                  '缓存 ${cached.length}',
                ],
              ),
            ),
          ),
          Expanded(
            child: TabBarView(
              controller: _tabs,
              children: [
                // 用全部历史，而不是只看 `resumable`。
                //
                // 早先这里传的是 resumable（>=15 秒且没看完），于是：
                // - 看过但只看了几秒的，不算「继续观看」，直接消失；
                // - 看完的记录被 isFinished 排除，也消失。
                // 结果就是「历史看过的视频不出现」。看完的用进度条满格区分，
                // 点进去从头播，语义正确。
                _SafeTab(child: _HistoryList(entries: history)),
                _SafeTab(child: _FavoriteVideoList(entries: videos)),
                _SafeTab(
                  child: _FavoriteChipList(
                    entries: characters,
                    kind: FavoriteKind.character,
                    icon: Icons.person_pin_outlined,
                    emptyText: '还没有收藏的角色\n在视频详情页长按角色名就能收藏',
                  ),
                ),
                _SafeTab(
                  child: _FavoriteChipList(
                    entries: models,
                    kind: FavoriteKind.model,
                    icon: Icons.person_outline,
                    emptyText: '还没有收藏的作者\n在视频详情页长按作者名就能收藏',
                  ),
                ),
                _SafeTab(
                  child: _FavoriteChipList(
                    entries: tags,
                    kind: FavoriteKind.tag,
                    icon: Icons.sell_outlined,
                    emptyText: '还没有收藏的标签\n在视频详情页长按标签就能收藏',
                  ),
                ),
                _SafeTab(child: _CachedList(entries: cached)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static Future<bool> _confirm(BuildContext context, String msg) async {    final r = await showDialog<bool>(
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

/// 页签内容的布局兜底。
///
/// `TabBarView` 在左右滑动过渡时，会把**两个子页面同时**按小于整屏的宽度
/// 布局（滑动过程中各占一部分）。此时列表里那些固定尺寸的东西——
/// `Divider(indent: 132)`、104 宽的封面、`InputChip` 的固定内边距——
/// 会算成负的可用宽度，Flutter 抛 overflow，用户看到的就是切换时
/// 闪一下红色错误页。
///
/// 用 `ClipRect` 裁掉过渡期的溢出部分。**不用 OverflowBox**：那会让子树
/// 脱离父约束自行撑开，反而破坏正常布局下的定位（测试里表现为切到某个
/// 页签后找不到本应存在的元素）。
class _SafeTab extends StatelessWidget {
  const _SafeTab({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => ClipRect(child: child);
}

/// 行间分隔线：左边让开封面宽度，右边留一点边距。
///
/// 不能直接写死 `Divider(indent: 132)`：`TabBarView` 左右滑动过渡时子页面
/// 会被压到很窄，132 的缩进加 16 的右边距一旦超过可用宽度，
/// RenderPadding 就算出负尺寸并抛 overflow（用户看到红色错误页）。
/// 这里按实际宽度取最小值，窄的时候自然退化成整条线。
class _RowDivider extends StatelessWidget {
  const _RowDivider();

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final w = constraints.maxWidth;
        // 封面 104 + 间距 12 + 页面左内边距 16 ≈ 132
        final indent = 132.0.clamp(0.0, (w - 16).clamp(0.0, 132.0));
        return Divider(height: 1, indent: indent, endIndent: w > 200 ? 16 : 0);
      },
    );
  }
}

class _HistoryList extends ConsumerWidget {
  const _HistoryList({required this.entries});

  final List<WatchEntry> entries;

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
      separatorBuilder: (context, index) =>
          const _RowDivider(),
      itemBuilder: (context, i) {
        final e = entries[i];
        final finished = e.isFinished;
        final progressText = finished
            ? '已看完'
            : '${_fmt(e.positionSeconds)} / ${_fmt(e.durationSeconds)}';
        return _MediaRow(
          thumb: e.thumb,
          title: e.title,
          subtitle: '$progressText  ·  ${_ago(e.updatedAt)}',
          overlay: Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: LinearProgressIndicator(
              // 看完的进度条满格，和「看了一半」在视觉上区分开。
              value: finished ? 1 : e.progress,
              minHeight: 3,
              backgroundColor: Colors.black45,
            ),
          ),
          trailing: IconButton(
            tooltip: '从记录里移除',
            icon: const Icon(Icons.close_rounded, size: 18),
            color: R34Color.inkFaint,
            onPressed: () => ref.read(historyProvider.notifier).remove(e.videoId),
          ),
          // e.path 是 /video/id/slug/ 形式，换成直达播放页
          onTap: () => context.push(e.path.replaceFirst('/video/', '/play/')),
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
      separatorBuilder: (context, index) =>
          const _RowDivider(),
      itemBuilder: (context, i) {
        final e = entries[i];
        return _MediaRow(
          thumb: e.thumb,
          title: e.title,
          subtitle: [e.subtitle, _ago(e.addedAt)]
              .where((s) => s.isNotEmpty)
              .join('  ·  '),
          trailing: IconButton(
            tooltip: '取消收藏',
            icon: const Icon(Icons.bookmark_rounded, size: 19),
            color: R34Color.accentBright,
            onPressed: () =>
                ref.read(favoritesProvider.notifier).toggle(e),
          ),
          // e.path 是 /video/id/slug/ 形式，换成直达播放页
          onTap: () => context.push(e.path.replaceFirst('/video/', '/play/')),
        );
      },
    );
  }
}

/// 片库通用行：封面 + 标题 + 一行元信息 + 尾部动作。
class _MediaRow extends StatelessWidget {
  const _MediaRow({
    required this.thumb,
    required this.title,
    required this.subtitle,
    required this.trailing,
    required this.onTap,
    this.overlay,
    this.subtitleColor,
  });

  final String thumb;
  final String title;
  final String subtitle;
  final Widget trailing;
  final VoidCallback onTap;
  final Widget? overlay;
  final Color? subtitleColor;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(
          R34Space.page,
          R34Space.x2 + 2,
          6,
          R34Space.x2 + 2,
        ),
        child: Row(
          children: [
            Container(
              width: 104,
              height: 58,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(R34Radius.sm),
                border: Border.all(color: R34Color.hairline),
              ),
              clipBehavior: Clip.antiAlias,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  _Thumb(url: thumb),
                  ?overlay,
                ],
              ),
            ),
            const SizedBox(width: R34Space.x3),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontFamily: 'NotoSansSC',
                      fontSize: 13.5,
                      height: 1.35,
                      fontWeight: FontWeight.w500,
                      color: R34Color.ink,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    subtitle,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontFamily: 'NotoSansSC',
                      fontSize: 11.5,
                      height: 1.35,
                      color: subtitleColor ?? R34Color.inkFaint,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: R34Space.x1),
            trailing,
          ],
        ),
      ),
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
      padding: const EdgeInsets.fromLTRB(
        R34Space.page,
        R34Space.x3,
        R34Space.page,
        R34Space.x6,
      ),
      children: [
        Text(
          '${kind.label} · ${entries.length} 个',
          style: const TextStyle(
            fontFamily: 'NotoSansSC',
            fontSize: 11.5,
            letterSpacing: 0.4,
            fontWeight: FontWeight.w600,
            color: R34Color.inkMuted,
          ),
        ),
        const SizedBox(height: R34Space.x3),
        Wrap(
          spacing: R34Space.x2,
          runSpacing: R34Space.x2,
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
        const SizedBox(height: R34Space.x6),
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
    // 走统一的网络图组件：测试注入点、占位色、失败态只有一处定义。
    return NetImage(url: url, showBroken: false, fadeIn: Duration.zero);
  }
}

class _EmptyHint extends StatelessWidget {
  const _EmptyHint({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final parts = text.split('\n');
    return EmptyState(
      icon: icon,
      title: parts.first,
      message: parts.length > 1 ? parts.skip(1).join('\n') : null,
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
          padding: const EdgeInsets.fromLTRB(
            R34Space.page,
            R34Space.x3,
            R34Space.x2,
            0,
          ),
          child: Row(
            children: [
              Text(
                '共 ${entries.length} 个 · ${totalMb.toStringAsFixed(0)} MB',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: R34Color.inkMuted,
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
          _MediaRow(
            thumb: e.thumb,
            title: e.title,
            subtitle: '${e.megabytes.toStringAsFixed(0)} MB  ·  ${_ago(e.createdAt)}'
                '${e.fileExists ? "" : "  ·  文件已丢失"}',
            subtitleColor: e.fileExists ? R34Color.inkFaint : scheme.error,
            overlay: Positioned(
              left: 4,
              bottom: 4,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.72),
                  borderRadius: BorderRadius.circular(R34Radius.sm),
                ),
                child: Text(
                  e.quality,
                  style: const TextStyle(
                    color: R34Color.ink,
                    fontSize: 9,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.3,
                  ),
                ),
              ),
            ),
            trailing: IconButton(
              tooltip: '删除缓存',
              icon: const Icon(Icons.delete_outline_rounded, size: 19),
              color: R34Color.inkFaint,
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
