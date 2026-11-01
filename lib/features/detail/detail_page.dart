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
import '../../ui/components.dart';
import '../../ui/glass.dart';
import '../../ui/tokens.dart';
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
      // 封面顶到状态栏下面，滚动时从磨砂栏底下穿过去。
      extendBodyBehindAppBar: true,
      appBar: r34AppBar(
        title: Text(_detail?.title ?? '加载中…', maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [
          if (_detail != null)
            IconButton(
              tooltip: ref.watch(isFavoriteProvider(videoKey(widget.videoId)))
                  ? '取消收藏'
                  : '收藏',
              icon: Icon(
                ref.watch(isFavoriteProvider(videoKey(widget.videoId)))
                    ? Icons.favorite_rounded
                    : Icons.favorite_border_rounded,
                color: ref.watch(isFavoriteProvider(videoKey(widget.videoId)))
                    ? R34Color.accentBright
                    : null,
              ),
              onPressed: _toggleFavorite,
            ),
          IconButton(
            tooltip: '刷新',
            icon: const Icon(Icons.refresh_rounded),
            onPressed: _load,
          ),
          const SizedBox(width: R34Space.x1),
        ],
      ),
      body: switch ((_loading, _error, _detail)) {
        (true, _, _) => const Center(child: CircularProgressIndicator()),
        (_, final String e, _) => ErrorState(message: e, onRetry: _load),
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

    return ListView(
      padding: EdgeInsets.zero,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            R34Space.page,
            R34Space.x2,
            R34Space.page,
            0,
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(R34Radius.lg),
            child: AspectRatio(
              aspectRatio: 16 / 9,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  CachedNetworkImage(
                    imageUrl: detail.poster,
                    fit: BoxFit.cover,
                    placeholder: (context, url) =>
                        const ColoredBox(color: R34Color.surfaceHigh),
                    errorWidget: (context, url, error) =>
                        const ColoredBox(color: R34Color.surfaceHigh),
                  ),
              // 顶部压暗给磨砂栏垫底，底部过渡到画布色。
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      Color(0x66000000),
                      Color(0x1A000000),
                      Color(0x00000000),
                      Color(0xA3000000),
                      Color(0xB3000000),
                    ],
                    stops: [0, 0.22, 0.4, 0.84, 1],
                  ),
                ),
              ),
              Center(
                child: Material(
                  color: R34Color.accent,
                  shape: const CircleBorder(),
                  clipBehavior: Clip.antiAlias,
                  child: InkWell(
                    customBorder: const CircleBorder(),
                    onTap: () => onPlay(detail),
                    child: const SizedBox(
                      width: 64,
                      height: 64,
                      child: Icon(
                        Icons.play_arrow_rounded,
                        size: 36,
                        color: R34Color.onAccent,
                      ),
                    ),
                  ),
                ),
              ),
              Positioned(
                left: R34Space.page,
                bottom: R34Space.x4,
                child: Row(
                  children: [
                    if (detail.duration.isNotEmpty)
                      PillTag(text: detail.duration, fontSize: 11),
                    if (detail.streams.any((s) => s.isHd)) ...[
                      const SizedBox(width: R34Space.x1 + 2),
                      const PillTag(
                        text: 'HD',
                        fontSize: 10,
                        foreground: R34Color.accentBright,
                        background: R34Color.accentSoft,
                      ),
                    ],
                  ],
                ),
              ),
                ],
              ),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(
            R34Space.page,
            R34Space.x4,
            R34Space.page,
            0,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(
                spacing: R34Space.x4,
                runSpacing: R34Space.x2,
                children: [
                  MetaChip(icon: Icons.schedule_rounded, text: detail.duration),
                  MetaChip(
                      icon: Icons.visibility_outlined, text: detail.viewsText),
                  MetaChip(icon: Icons.update_rounded, text: detail.addedText),
                ],
              ),
              if (progress != null && progress.positionSeconds > 0) ...[
                const SizedBox(height: R34Space.x4),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: () => onPlay(detail),
                    icon: const Icon(Icons.play_circle_outline_rounded, size: 19),
                    label: Text(
                      progress.isFinished
                          ? '已经看完，再看一遍'
                          : '继续观看 ${fmtDuration(progress.positionSeconds)} / ${fmtDuration(detail.durationSeconds)}',
                    ),
                  ),
                ),
              ],
              SectionHeader(
                title: '清晰度',
                padding: const EdgeInsets.only(
                  top: R34Space.x5,
                  bottom: R34Space.x2 + 2,
                ),
              ),
              Wrap(
                spacing: R34Space.x2,
                runSpacing: R34Space.x2,
                children: [
                  for (final s in detail.streams)
                    ActionChip(
                      avatar: Icon(
                        s.isHd ? Icons.hd_outlined : Icons.sd_outlined,
                        size: 17,
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
                SectionHeader(
                  title: '简介',
                  padding: const EdgeInsets.only(
                    top: R34Space.x5,
                    bottom: R34Space.x2,
                  ),
                ),
                SelectableText(
                  detail.description,
                  style: Theme.of(context).textTheme.bodySmall,
                  maxLines: 12,
                ),
              ],
              // 上传者：站点的 Uploaded by，指向 /members/<id>/，
              // 和 /models/<slug>/ 的「作者」不是一回事，所以单独一行。
              if (detail.uploaderName.isNotEmpty) ...[
                const SizedBox(height: R34Space.x4),
                UploaderRow(
                  name: detail.uploaderName,
                  id: detail.uploaderId,
                ),
              ],
              if (detail.downloads.isNotEmpty) ...[
                const SizedBox(height: R34Space.x5),
                DownloadSection(video: detail),
              ],
              if (detail.comments.isNotEmpty) ...[
                const SizedBox(height: R34Space.x5),
                CommentSection(comments: detail.comments),
              ],
              if (detail.related.isNotEmpty) ...[
                SectionHeader(
                  title: '相关推荐',
                  padding: const EdgeInsets.only(
                    top: R34Space.x6,
                    bottom: R34Space.x2 + 2,
                  ),
                ),
                SizedBox(
                  height: 208,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    clipBehavior: Clip.none,
                    itemCount: detail.related.length,
                    separatorBuilder: (context, index) =>
                        const SizedBox(width: R34Space.x3),
                    itemBuilder: (context, i) => SizedBox(
                      width: 200,
                      child: VideoCardTile(
                        card: detail.related[i],
                        index: i,
                        animateIn: false,
                      ),
                    ),
                  ),
                ),
              ],
              const SizedBox(height: R34Space.x8),
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
