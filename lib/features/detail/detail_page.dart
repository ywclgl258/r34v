import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../data/local/library_store.dart';
import '../../data/parsing/parse_exception.dart';
import '../../data/remote/site_client.dart';
import '../../domain/video_detail.dart';
import '../feed/video_card_tile.dart';

/// 详情页：标题、封面、标签、清晰度、相关推荐。
class DetailPage extends ConsumerStatefulWidget {
  const DetailPage({super.key, required this.videoId, required this.slug});

  final String videoId;
  final String slug;

  @override
  ConsumerState<DetailPage> createState() => _DetailPageState();
}

class _DetailPageState extends ConsumerState<DetailPage> {
  VideoDetail? _detail;
  String? _error;
  bool _loading = true;

  String get _path => '/video/${widget.videoId}/${widget.slug}/';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final api = ref.read(rule34ApiProvider);
      final d = await api.fetchDetail(_path);
      if (!mounted) return;
      setState(() {
        _detail = d;
        _loading = false;
      });
    } on HttpException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message.contains('人机验证')
            ? '站点要求人机验证，暂时打不开这个视频'
            : '加载失败：${e.message}';
        _loading = false;
      });
    } on ParseException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '站点结构变了，解析失败：${e.message}';
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '加载失败：$e';
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_detail?.title ?? '加载中…', maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          if (_detail != null)
            IconButton(
              tooltip: ref.watch(isFavoriteProvider(videoKey(widget.videoId)))
                  ? '取消收藏'
                  : '收藏',
              icon: Icon(
                ref.watch(isFavoriteProvider(videoKey(widget.videoId)))
                    ? Icons.favorite
                    : Icons.favorite_border,
              ),
              onPressed: _toggleFavorite,
            ),
          IconButton(
            tooltip: '刷新',
            icon: const Icon(Icons.refresh),
            onPressed: _load,
          ),
        ],
      ),
      body: switch ((_loading, _error, _detail)) {
        (true, _, _) => const Center(child: CircularProgressIndicator()),
        (_, final String e, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.error_outline, size: 48),
                  const SizedBox(height: 12),
                  Text(e, textAlign: TextAlign.center),
                  const SizedBox(height: 16),
                  FilledButton(onPressed: _load, child: const Text('重试')),
                ],
              ),
            ),
          ),
        (_, _, final VideoDetail d) => _DetailBody(detail: d, onPlay: _play),
        _ => const SizedBox.shrink(),
      },
    );
  }

  void _play(VideoDetail d) {
    context.push('/play/${d.id}/${d.parentSlug}');
  }

  Future<void> _toggleFavorite() async {
    final d = _detail;
    if (d == null) return;
    final now = await ref.read(favoritesProvider.notifier).toggle(FavoriteEntry(
          kind: FavoriteKind.video,
          target: d.id,
          title: d.title,
          thumb: d.poster,
          path: d.path,
          subtitle: d.duration,
          addedAt: DateTime.now(),
        ));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(now ? '已收藏' : '已取消收藏'), duration: const Duration(seconds: 1)),
    );
  }
}

class _DetailBody extends ConsumerWidget {
  const _DetailBody({required this.detail, required this.onPlay});

  final VideoDetail detail;
  final void Function(VideoDetail) onPlay;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(watchProgressProvider(detail.id));
    final scheme = Theme.of(context).colorScheme;

    return ListView(
      padding: EdgeInsets.zero,
      children: [
        AspectRatio(
          aspectRatio: 16 / 9,
          child: Stack(
            fit: StackFit.expand,
            children: [
              CachedNetworkImage(
                imageUrl: detail.poster,
                fit: BoxFit.cover,
                errorWidget: (context, url, error) => ColoredBox(color: scheme.surfaceContainerHighest),
              ),
              ColoredBox(color: Colors.black26),
              Center(
                child: IconButton.filled(
                  iconSize: 48,
                  onPressed: () => onPlay(detail),
                  icon: const Icon(Icons.play_arrow),
                ),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (progress != null && progress.positionSeconds > 0) ...[
                FilledButton.tonalIcon(
                  onPressed: () => onPlay(detail),
                  icon: const Icon(Icons.play_circle_outline),
                  label: Text(
                    progress.isFinished
                        ? '已经看完，再看一遍'
                        : '继续观看 ${_fmt(progress.positionSeconds)} / ${_fmt(detail.durationSeconds)}',
                  ),
                ),
                const SizedBox(height: 12),
              ],
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _MetaChip(icon: Icons.schedule, text: detail.duration),
                  _MetaChip(icon: Icons.visibility_outlined, text: detail.viewsText),
                  _MetaChip(icon: Icons.update, text: detail.addedText),
                ],
              ),
              const SizedBox(height: 16),
              Text(
                '清晰度',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: [
                  for (final s in detail.streams)
                    ActionChip(
                      avatar: Icon(
                        s.isHd ? Icons.hd_outlined : Icons.sd_outlined,
                        size: 18,
                      ),
                      label: Text(s.label),
                      onPressed: () => onPlay(detail),
                    ),
                ],
              ),
              if (detail.tags.isNotEmpty) ...[
                const SizedBox(height: 20),
                Row(
                  children: [
                    Text('标签', style: Theme.of(context).textTheme.titleSmall),
                    const SizedBox(width: 6),
                    Text(
                      '长按可收藏',
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                            color: Theme.of(context).colorScheme.outline,
                          ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final t in detail.tags) _TagChip(slug: t),
                  ],
                ),
              ],
              if (detail.categories.isNotEmpty || detail.models.isNotEmpty) ...[
                const SizedBox(height: 20),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (detail.categories.isNotEmpty)
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('分类', style: Theme.of(context).textTheme.titleSmall),
                            const SizedBox(height: 8),
                            Wrap(
                              spacing: 6,
                              children: [
                                for (final c in detail.categories)
                                  ActionChip(
                                    label: Text(_prettyTag(c)),
                                    onPressed: () => context
                                        .push('/list/category/${Uri.encodeComponent(c)}'),
                                  ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    if (detail.models.isNotEmpty)
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text('作者', style: Theme.of(context).textTheme.titleSmall),
                                const SizedBox(width: 6),
                                Text(
                                  '长按可收藏',
                                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                                        color: Theme.of(context).colorScheme.outline,
                                      ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            Wrap(
                              spacing: 6,
                              children: [
                                for (final m in detail.models) _ModelChip(slug: m),
                              ],
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ],
              if (detail.description.isNotEmpty) ...[
                const SizedBox(height: 20),
                Text('简介', style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 8),
                SelectableText(
                  detail.description,
                  style: Theme.of(context).textTheme.bodySmall,
                  maxLines: 12,
                ),
              ],
              if (detail.related.isNotEmpty) ...[
                const SizedBox(height: 24),
                Text('相关推荐', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                SizedBox(
                  height: 200,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    itemCount: detail.related.length,
                    separatorBuilder: (context, index) => const SizedBox(width: 8),
                    itemBuilder: (context, i) => SizedBox(
                      width: 200,
                      child: VideoCardTile(card: detail.related[i]),
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 32),
            ],
          ),
        ),
      ],
    );
  }

  static String _prettyTag(String slug) => slug
      .replaceAll('-', ' ')
      .split(' ')
      .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1))
      .join(' ');
}

class _MetaChip extends StatelessWidget {
  const _MetaChip({required this.icon, required this.text});

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

String _fmt(int seconds) {
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
class _TagChip extends ConsumerWidget {
  const _TagChip({required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = FavoriteKey(FavoriteKind.tag, slug);
    final fav = ref.watch(isFavoriteProvider(key));

    return GestureDetector(
      onLongPress: () => _toggle(context, ref, key),
      child: ActionChip(
        avatar: fav
            ? const Icon(Icons.star, size: 15)
            : null,
        label: Text(prettySlug(slug)),
        onPressed: () => context.push('/list/tag/${Uri.encodeComponent(slug)}'),
      ),
    );
  }
}

/// 作者 chip：点进去看这个作者的作品，长按收藏。
class _ModelChip extends ConsumerWidget {
  const _ModelChip({required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = FavoriteKey(FavoriteKind.model, slug);
    final fav = ref.watch(isFavoriteProvider(key));

    return GestureDetector(
      onLongPress: () => _toggle(context, ref, key),
      child: ActionChip(
        avatar: fav ? const Icon(Icons.star, size: 15) : null,
        label: Text(prettySlug(slug)),
        onPressed: () => context.push('/list/model/${Uri.encodeComponent(slug)}'),
      ),
    );
  }
}

Future<void> _toggle(BuildContext context, WidgetRef ref, FavoriteKey key) async {
  final now = await ref.read(favoritesProvider.notifier).toggle(FavoriteEntry(
        kind: key.kind,
        target: key.target,
        title: prettySlug(key.target),
        path: key.kind == FavoriteKind.tag
            ? '/list/tag/${Uri.encodeComponent(key.target)}'
            : '/list/model/${Uri.encodeComponent(key.target)}',
        addedAt: DateTime.now(),
      ));
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(
        now
            ? '已收藏${key.kind.label}「${prettySlug(key.target)}」'
            : '已取消收藏',
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
