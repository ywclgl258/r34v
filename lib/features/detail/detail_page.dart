import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../data/local/tag_dictionary.dart';
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
              // 四类分组，和站点保持一致：Characters / Categories / Artists / Tags。
              // 站点把角色名和普通标签都标成 tag 类型，解析层靠分组标题分开了，
              // 这里也分开展示，否则「标签」一栏会又长又杂。
              if (detail.characters.isNotEmpty)
                _GroupSection(
                  title: '角色',
                  hint: '长按可收藏',
                  children: [
                    for (final c in detail.characters) _CharacterChip(slug: c),
                  ],
                ),
              if (detail.categories.isNotEmpty)
                _GroupSection(
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
                _GroupSection(
                  title: '作者',
                  hint: '长按可收藏',
                  children: [
                    for (final m in detail.models) _ModelChip(slug: m),
                  ],
                ),
              if (detail.tags.isNotEmpty)
                _GroupSection(
                  title: '标签',
                  hint: '长按可收藏',
                  children: [
                    for (final t in detail.tags) _TagChip(slug: t),
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
                _UploaderRow(
                  name: detail.uploaderName,
                  id: detail.uploaderId,
                ),
              ],
              if (detail.downloads.isNotEmpty) ...[
                const SizedBox(height: 20),
                _DownloadSection(video: detail),
              ],
              if (detail.comments.isNotEmpty) ...[
                const SizedBox(height: 20),
                _CommentSection(comments: detail.comments),
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

    // 标签翻译：词典收录的显示中文，未收录显示原文（不猜）。
    final translate = ref.watch(libraryStoreProvider).translateTags;
    final zh = translate ? zhForTag(slug) : '';
    final label = zh.isEmpty ? prettySlug(slug) : zh;

    return GestureDetector(
      onLongPress: () => _toggleGroupFavorite(context, ref, key),
      child: Tooltip(
        // 译文和原文都留着：中文看得懂，原文方便对照站点
        message: zh.isEmpty ? slug : '$zh · $slug',
        child: ActionChip(
          avatar: fav ? const Icon(Icons.star, size: 15) : null,
          label: Text(label),
          onPressed: () =>
              context.push('/list/tag/${Uri.encodeComponent(slug)}'),
        ),
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
      onLongPress: () => _toggleGroupFavorite(context, ref, key),
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
Future<void> _toggleGroupFavorite(
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
class _GroupSection extends StatelessWidget {
  const _GroupSection({
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
/// 所以单独一个组件，别和 _TagChip 混用。
class _CharacterChip extends ConsumerWidget {
  const _CharacterChip({required this.slug});

  final String slug;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = FavoriteKey(FavoriteKind.character, slug);
    final fav = ref.watch(isFavoriteProvider(key));
    final scheme = Theme.of(context).colorScheme;

    return GestureDetector(
      onLongPress: () => _toggleGroupFavorite(context, ref, key),
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
class _UploaderRow extends StatelessWidget {
  const _UploaderRow({required this.name, required this.id});

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
class _DownloadSection extends ConsumerStatefulWidget {
  const _DownloadSection({required this.video});

  final VideoDetail video;

  @override
  ConsumerState<_DownloadSection> createState() => _DownloadSectionState();
}

class _DownloadSectionState extends ConsumerState<_DownloadSection> {
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
class _CommentSection extends StatelessWidget {
  const _CommentSection({required this.comments});

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
