// ignore_for_file: use_key_in_widget_constructors
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../data/local/library_store.dart';
import '../../domain/video_detail.dart';

// 详情页和播放页共用的视频信息组件。

class MetaChip extends StatelessWidget {
  const MetaChip({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    if (text.isEmpty) return const SizedBox.shrink();
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: Theme.of(context).colorScheme.outline),
        const SizedBox(width: 4),
        Text(text, style: Theme.of(context).textTheme.labelMedium),
      ],
    );
  }
}

String fmtDuration(int seconds) {
  if (seconds <= 0) return '00:00';
  final h = seconds ~/ 3600;
  final m = (seconds % 3600) ~/ 60;
  final s = seconds % 60;
  final mm = m.toString().padLeft(2, '0');
  final ss = s.toString().padLeft(2, '0');
  return h > 0 ? '$h:$mm:$ss' : '$mm:$ss';
}


/// 标签 chip：点进去看这个标签下的视频，长按收藏/取消收藏。
///
/// 用长按而不是在 chip 上加一个小星标，是因为详情页标签有 20 多个，
/// 每个都塞图标会挤成一团，反而看不清标签本身。
class TagChip extends ConsumerWidget {
  const TagChip({required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = FavoriteKey(FavoriteKind.tag, slug);
    final fav = ref.watch(isFavoriteProvider(key));

    // 标签翻译：词典命中立即显示；没命中走联网翻译（有缓存，只翻一次）。
    // 拿不到译文时显示原文，不显示占位符。
    final translate = ref.watch(translateTagsProvider);
    final original = prettySlug(slug);

    return GestureDetector(
      onLongPress: () => toggleGroupFavorite(context, ref, key),
      child: translate
          ? FutureBuilder<String>(
              future: ref.read(tagTranslatorProvider).translate(slug),
              builder: (context, snap) {
                final zh = snap.data ?? '';
                final label = zh.isEmpty ? original : zh;
                return Tooltip(
                  message: zh.isEmpty ? slug : '$zh · $slug',
                  child: ActionChip(
                    avatar: fav ? const Icon(Icons.star, size: 15) : null,
                    label: Text(label),
                    onPressed: () => context
                        .push('/list/tag/${Uri.encodeComponent(slug)}'),
                  ),
                );
              },
            )
          : Tooltip(
              message: slug,
              child: ActionChip(
                avatar: fav ? const Icon(Icons.star, size: 15) : null,
                label: Text(original),
                onPressed: () =>
                    context.push('/list/tag/${Uri.encodeComponent(slug)}'),
              ),
            ),
    );
  }
}

/// 作者 chip：点进去看这个作者的作品，长按收藏。
class ModelChip extends ConsumerWidget {
  const ModelChip({required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = FavoriteKey(FavoriteKind.model, slug);
    final fav = ref.watch(isFavoriteProvider(key));

    return GestureDetector(
      onLongPress: () => toggleGroupFavorite(context, ref, key),
      child: ActionChip(
        avatar: fav ? const Icon(Icons.star, size: 15) : null,
        label: Text(prettySlug(slug)),
        onPressed: () => context.push('/list/model/${Uri.encodeComponent(slug)}'),
      ),
    );
  }
}

/// 收藏/取消收藏一个分组项（角色、作者、标签）。
///
/// 三类走同一个函数：它们的差别只在路由段名上，各自写一遍容易漏改。
Future<void> toggleGroupFavorite(
  BuildContext context,
  WidgetRef ref,
  FavoriteKey key,
) async {
  final segment = switch (key.kind) {
    FavoriteKind.character => 'character',
    FavoriteKind.model => 'model',
    FavoriteKind.tag => 'tag',
    FavoriteKind.video => 'video',
  };
  final now = await ref.read(favoritesProvider.notifier).toggle(FavoriteEntry(
        kind: key.kind,
        target: key.target,
        title: prettySlug(key.target),
        path: '/list/$segment/${Uri.encodeComponent(key.target)}',
        addedAt: DateTime.now(),
      ));
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(
        now ? '已收藏${key.kind.label}「${prettySlug(key.target)}」' : '已取消收藏',
      ),
      duration: const Duration(seconds: 1),
    ),
  );
}

/// slug → 展示名（`big-breasts` → `Big Breasts`）。
String prettySlug(String slug) => slug
    .replaceAll('-', ' ')
    .split(' ')
    .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1))
    .join(' ');

/// 一组标签的容器：标题 + 提示 + chip 墙。
///
/// 抽出来是因为详情页有四组（角色/分类/作者/标签），
/// 每组的排版完全一样，重复四遍既啰嗦又容易改漏。
class GroupSection extends StatelessWidget {
  const GroupSection({
    required this.title,
    required this.children,
    this.hint,
  });

  final String title;
  final String? hint;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(title, style: Theme.of(context).textTheme.titleSmall),
              if (hint != null) ...[
                const SizedBox(width: 6),
                Text(
                  hint!,
                  style: Theme.of(context)
                      .textTheme
                      .labelSmall
                      ?.copyWith(color: scheme.outline),
                ),
              ],
            ],
          ),
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 6, children: children),
        ],
      ),
    );
  }
}

/// 角色 chip：点进去看这个角色下的视频，长按收藏。
///
/// 角色和标签在站点上是两种东西（Characters / Tags），链接路径也不同，
/// 所以单独一个组件，别和 TagChip 混用。
class CharacterChip extends ConsumerWidget {
  const CharacterChip({required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = FavoriteKey(FavoriteKind.character, slug);
    final fav = ref.watch(isFavoriteProvider(key));
    final scheme = Theme.of(context).colorScheme;

    return GestureDetector(
      onLongPress: () => toggleGroupFavorite(context, ref, key),
      child: ActionChip(
        avatar: fav
            ? const Icon(Icons.star, size: 15)
            : Icon(Icons.person_pin_outlined, size: 15, color: scheme.outline),
        label: Text(prettySlug(slug)),
        onPressed: () =>
            context.push('/list/character/${Uri.encodeComponent(slug)}'),
      ),
    );
  }
}

/// 上传者一行。
///
/// 和「作者」分开显示，因为站点里它们是两套：
/// 上传者 = `/members/<数字id>/`（谁传的），作者 = `/models/<slug>/`（谁画的）。
class UploaderRow extends StatelessWidget {
  const UploaderRow({required this.name, required this.id});

  final String name;
  final String id;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      children: [
        Icon(Icons.cloud_upload_outlined, size: 16, color: scheme.outline),
        const SizedBox(width: 6),
        Text(
          '上传者',
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: scheme.outline,
              ),
        ),
        const SizedBox(width: 6),
        Flexible(
          child: Text(
            name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ),
      ],
    );
  }
}

/// 下载区：每档清晰度一个按钮。
class DownloadSection extends ConsumerStatefulWidget {
  const DownloadSection({required this.video});

  final VideoDetail video;

  @override
  ConsumerState<DownloadSection> createState() => DownloadSectionState();
}

class DownloadSectionState extends ConsumerState<DownloadSection> {
  String? _activeLabel;
  int _received = 0;
  int _total = 0;

  Future<void> _download(DownloadOption opt) async {
    if (_activeLabel != null) return;
    setState(() {
      _activeLabel = opt.label;
      _received = 0;
      _total = 0;
    });

    final downloader = ref.read(videoDownloaderProvider);
    final name = downloader.suggestedName(
      title: widget.video.title,
      quality: opt.label,
      fileName: opt.fileName,
    );

    try {
      await downloader.start(
        videoId: widget.video.id,
        url: opt.url,
        suggestedName: name,
        onProgress: (received, total) {
          if (!mounted) return;
          setState(() {
            _received = received;
            _total = total;
          });
        },
        onDone: (path) {
          if (!mounted) return;
          setState(() => _activeLabel = null);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('已下载到 $path'),
              duration: const Duration(seconds: 3),
            ),
          );
        },
        onError: (err) {
          if (!mounted) return;
          setState(() => _activeLabel = null);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('下载失败：$err')),
          );
        },
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _activeLabel = null);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('下载失败：$e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fraction = _total > 0 ? (_received / _total).clamp(0.0, 1.0) : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text('下载', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(width: 6),
            Text(
              '存到设备下载目录',
              style: Theme.of(context)
                  .textTheme
                  .labelSmall
                  ?.copyWith(color: scheme.outline),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final opt in widget.video.downloads)
              OutlinedButton.icon(
                onPressed: _activeLabel == null ? () => _download(opt) : null,
                icon: _activeLabel == opt.label
                    ? const SizedBox(
                        width: 14,
                        height: 14,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.download, size: 16),
                label: Text(opt.label),
              ),
          ],
        ),
        if (_activeLabel != null) ...[
          const SizedBox(height: 8),
          LinearProgressIndicator(value: fraction, minHeight: 3),
          const SizedBox(height: 4),
          Text(
            _total > 0
                ? '${(_received / 1048576).toStringAsFixed(1)} / '
                    '${(_total / 1048576).toStringAsFixed(1)} MB'
                : '${(_received / 1048576).toStringAsFixed(1)} MB',
            style: Theme.of(context)
                .textTheme
                .labelSmall
                ?.copyWith(color: scheme.outline),
          ),
        ],
      ],
    );
  }
}

/// 评论区（只读）。
class CommentSection extends StatelessWidget {
  const CommentSection({required this.comments});

  final List<VideoComment> comments;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '评论 ${comments.length}',
          style: Theme.of(context).textTheme.titleSmall,
        ),
        const SizedBox(height: 8),
        for (final c in comments.take(20))
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                CircleAvatar(
                  radius: 12,
                  backgroundColor: scheme.surfaceContainerHighest,
                  backgroundImage:
                      c.avatarUrl.isEmpty ? null : NetworkImage(c.avatarUrl),
                  child: c.avatarUrl.isEmpty
                      ? Text(
                          c.author.isEmpty ? '?' : c.author[0].toUpperCase(),
                          style: const TextStyle(fontSize: 11),
                        )
                      : null,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Flexible(
                            child: Text(
                              c.author,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          if (c.dateText.isNotEmpty) ...[
                            const SizedBox(width: 8),
                            Text(
                              c.dateText,
                              style: TextStyle(
                                fontSize: 10,
                                color: scheme.outline,
                              ),
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 2),
                      Text(
                        c.text,
                        style: const TextStyle(fontSize: 12),
                        maxLines: 6,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}