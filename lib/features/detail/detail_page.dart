import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../common/video_sections.dart';

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

  @override
  void initState() {
    super.initState();
    _load();
  }

  String get _path => '/video/${widget.videoId}/${widget.slug}/';

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
        (_, _, final VideoDetail d) =>
            _DetailBody(detail: d, onPlay: _play),
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
                        : '继续观看 ${fmtDuration(progress.positionSeconds)} / ${fmtDuration(detail.durationSeconds)}',
                  ),
                ),
                const SizedBox(height: 12),
              ],
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  MetaChip(icon: Icons.schedule, text: detail.duration),
                  MetaChip(icon: Icons.visibility_outlined, text: detail.viewsText),
                  MetaChip(icon: Icons.update, text: detail.addedText),
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
              // 四类分组，和站点保持一致：Characters / Categories / Artists / Tags。
              // 站点把角色名和普通标签都标成 tag 类型，解析层靠分组标题分开了，
              // 这里也分开展示，否则「标签」一栏会又长又杂。
              if (detail.characters.isNotEmpty)
                GroupSection(
                  title: '角色',
                  hint: '长按可收藏',
                  children: [
                    for (final c in detail.characters) CharacterChip(slug: c),
                  ],
                ),
              if (detail.categories.isNotEmpty)
                GroupSection(
                  title: '分类',
                  children: [
                    for (final c in detail.categories)
                      ActionChip(
                        label: Text(_prettyTag(c)),
                        onPressed: () =>
                            context.push('/list/category/${Uri.encodeComponent(c)}'),
                      ),
                  ],
                ),
              if (detail.models.isNotEmpty)
                GroupSection(
                  title: '作者',
                  hint: '长按可收藏',
                  children: [
                    for (final m in detail.models) ModelChip(slug: m),
                  ],
                ),
              if (detail.tags.isNotEmpty)
                GroupSection(
                  title: '标签',
                  hint: '长按可收藏',
                  children: [
                    for (final t in detail.tags) TagChip(slug: t),
                  ],
                ),
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
              // 上传者：站点的 Uploaded by，指向 /members/<id>/，
              // 和 /models/<slug>/ 的「作者」不是一回事，所以单独一行。
              if (detail.uploaderName.isNotEmpty) ...[
                const SizedBox(height: 16),
                UploaderRow(
                  name: detail.uploaderName,
                  id: detail.uploaderId,
                ),
              ],
              if (detail.downloads.isNotEmpty) ...[
                const SizedBox(height: 20),
                DownloadSection(video: detail),
              ],
              if (detail.comments.isNotEmpty) ...[
                const SizedBox(height: 20),
                CommentSection(comments: detail.comments),
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
