import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/ranked_entry.dart';
import '../../ui/components.dart';
import '../../ui/net_image.dart';
import '../../ui/tokens.dart';

/// 作者排行榜页（`/top-model/`）。
///
/// 站点用卡片网格：名次 + 头像 + 名字 + 好评率。
class ArtistRankingPage extends ConsumerStatefulWidget {
  const ArtistRankingPage({super.key});

  @override
  ConsumerState<ArtistRankingPage> createState() => _ArtistRankingPageState();
}

class _ArtistRankingPageState extends ConsumerState<ArtistRankingPage> {
  List<RankedEntry> _items = const [];
  bool _loading = true;
  String? _error;

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
      final items = await api.fetchArtistRanking();
      if (!mounted) return;
      setState(() {
        _items = items;
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('作者排行')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
          ? ErrorState(message: _error!, onRetry: _load)
          : _items.isEmpty
          ? const EmptyState(
              icon: Icons.leaderboard_outlined,
              title: '没拿到排行数据',
              message: '站点可能改版了，稍后重试',
            )
          : GridView.builder(
              padding: const EdgeInsets.fromLTRB(
                R34Space.page,
                R34Space.x3,
                R34Space.page,
                R34Space.x8,
              ),
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                maxCrossAxisExtent: 140,
                childAspectRatio: 0.68,
                crossAxisSpacing: R34Space.grid,
                mainAxisSpacing: R34Space.x3,
              ),
              itemCount: _items.length,
              itemBuilder: (context, i) => _ArtistCard(entry: _items[i]),
            ),
    );
  }
}

class _ArtistCard extends StatelessWidget {
  const _ArtistCard({required this.entry});

  final RankedEntry entry;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: () =>
          context.push('/list/model/${Uri.encodeComponent(entry.slug)}'),
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
              child: Stack(
                fit: StackFit.expand,
                children: [
                  _Thumb(url: entry.thumb),
                  // 前三名用不同颜色，一眼能看出头部
                  Positioned(
                    left: R34Space.x1 + 2,
                    top: R34Space.x1 + 2,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: switch (entry.rank) {
                          1 => R34Color.gold,
                          2 => R34Color.silver,
                          3 => R34Color.bronze,
                          _ => Colors.black54,
                        },
                        borderRadius: BorderRadius.circular(R34Radius.pill),
                        border: Border.all(
                          color: Colors.white.withValues(alpha: 0.14),
                        ),
                      ),
                      child: Text(
                        '${entry.rank}',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 10.5,
                          height: 1.2,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: R34Space.x2 - 2),
          Text(
            entry.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontFamily: 'NotoSansSC',
              fontSize: 12.5,
              height: 1.3,
              fontWeight: FontWeight.w500,
              color: R34Color.ink,
            ),
          ),
          if (entry.viewsText.isNotEmpty)
            Text(
              entry.viewsText,
              style: const TextStyle(
                fontSize: 10.5,
                height: 1.4,
                color: R34Color.inkFaint,
              ),
            ),
        ],
      ),
    );
  }
}

/// 角色排行榜：先选作品，再看该作品的角色榜。
///
/// 站点 `/top-characters/` 是两层结构：一层列作品（genshin-impact 等），
/// 点进去才是角色榜。不能当成扁平列表处理。
class CharacterRankingPage extends ConsumerStatefulWidget {
  const CharacterRankingPage({super.key});

  @override
  ConsumerState<CharacterRankingPage> createState() =>
      _CharacterRankingPageState();
}

class _CharacterRankingPageState extends ConsumerState<CharacterRankingPage> {
  List<CharacterWork> _works = const [];
  bool _loading = true;
  String? _error;

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
      final works = await api.fetchCharacterWorks();
      if (!mounted) return;
      setState(() {
        _works = works;
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('角色排行')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
          ? ErrorState(message: _error!, onRetry: _load)
          : ListView.separated(
              padding: const EdgeInsets.fromLTRB(
                R34Space.page,
                R34Space.x3,
                R34Space.page,
                R34Space.x8,
              ),
              itemCount: _works.length,
              separatorBuilder: (context, i) =>
                  const SizedBox(height: R34Space.x2),
              itemBuilder: (context, i) {
                final w = _works[i];
                return SurfaceCard(
                  radius: R34Radius.md,
                  padding: const EdgeInsets.fromLTRB(
                    R34Space.x4,
                    R34Space.x3,
                    R34Space.x3,
                    R34Space.x3,
                  ),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (context) => CharacterWorkRankingPage(work: w),
                    ),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              _pretty(w.name),
                              style: Theme.of(context).textTheme.titleSmall,
                            ),
                            if (w.countText.isNotEmpty) ...[
                              const SizedBox(height: R34Space.x1),
                              Text(
                                w.countText,
                                style: const TextStyle(
                                  fontSize: 11.5,
                                  color: R34Color.inkFaint,
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                      const Icon(
                        Icons.chevron_right_rounded,
                        size: 20,
                        color: R34Color.inkFaint,
                      ),
                    ],
                  ),
                );
              },
            ),
    );
  }
}

/// 某个作品下的角色榜。
/// 某个作品下的角色榜（公开，供发现页直接跳转）。
class CharacterWorkRankingPage extends ConsumerStatefulWidget {
  const CharacterWorkRankingPage({super.key, required this.work});

  final CharacterWork work;

  @override
  ConsumerState<CharacterWorkRankingPage> createState() =>
      _CharacterWorkRankingPageState();
}

class _CharacterWorkRankingPageState
    extends ConsumerState<CharacterWorkRankingPage> {
  List<RankedEntry> _items = const [];
  bool _loading = true;
  String? _error;

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
      final items = await api.fetchCharacterRanking(widget.work.slug);
      if (!mounted) return;
      setState(() {
        _items = items;
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_pretty(widget.work.name))),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
          ? ErrorState(message: _error!, onRetry: _load)
          : ListView.separated(
              padding: const EdgeInsets.fromLTRB(
                R34Space.page,
                R34Space.x3,
                R34Space.page,
                R34Space.x8,
              ),
              itemCount: _items.length,
              separatorBuilder: (context, i) =>
                  const SizedBox(height: R34Space.x2),
              itemBuilder: (context, i) => SurfaceCard(
                radius: R34Radius.md,
                padding: EdgeInsets.zero,
                child: _RankRow(entry: _items[i], maxRatio: 1),
              ),
            ),
    );
  }
}

/// 榜单行：名次 + 缩略图 + 名字 + 数据 + 相对比例条。
class _RankRow extends StatelessWidget {
  const _RankRow({required this.entry, required this.maxRatio});

  final RankedEntry entry;
  final double maxRatio;

  @override
  Widget build(BuildContext context) {
    final medal = switch (entry.rank) {
      1 => R34Color.gold,
      2 => R34Color.silver,
      3 => R34Color.bronze,
      _ => null,
    };

    return InkWell(
      onTap: () {
        // 角色链接是 tag 页，作者链接是 model 页
        final segment = entry.isTag ? 'tag' : 'model';
        context.push('/list/$segment/${Uri.encodeComponent(entry.slug)}');
      },
      borderRadius: BorderRadius.circular(R34Radius.md),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(
          R34Space.x3,
          R34Space.x2 + 2,
          R34Space.x3 + 2,
          R34Space.x2 + 2,
        ),
        child: Row(
          children: [
            Container(
              width: 26,
              height: 26,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: medal == null
                    ? R34Color.surfaceHigh
                    : medal.withValues(alpha: 0.16),
                shape: BoxShape.circle,
                border: Border.all(
                  color: medal == null
                      ? R34Color.hairline
                      : medal.withValues(alpha: 0.45),
                ),
              ),
              child: Text(
                '${entry.rank}',
                style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w700,
                  color: medal ?? R34Color.inkMuted,
                ),
              ),
            ),
            const SizedBox(width: R34Space.x2 + 2),
            Container(
              width: 64,
              height: 38,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(R34Radius.sm),
                border: Border.all(color: R34Color.hairline),
              ),
              clipBehavior: Clip.antiAlias,
              child: _Thumb(url: entry.thumb),
            ),
            const SizedBox(width: R34Space.x3),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    entry.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontFamily: 'NotoSansSC',
                      fontSize: 13,
                      height: 1.3,
                      fontWeight: FontWeight.w500,
                      color: R34Color.ink,
                    ),
                  ),
                  if (entry.fullName.isNotEmpty &&
                      entry.fullName.toLowerCase() != entry.name.toLowerCase())
                    Text(
                      entry.fullName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 10.5,
                        height: 1.35,
                        color: R34Color.inkFaint,
                      ),
                    ),
                  const SizedBox(height: R34Space.x1),
                  // 播放量相对榜首的横条，一眼看出差距
                  if (entry.viewsRatio > 0)
                    ClipRRect(
                      borderRadius: BorderRadius.circular(R34Radius.pill),
                      child: LinearProgressIndicator(
                        value:
                            entry.viewsRatio / (maxRatio <= 0 ? 1 : maxRatio),
                        minHeight: 3,
                        backgroundColor: R34Color.surfaceHigh,
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(width: R34Space.x3),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (entry.viewsText.isNotEmpty)
                  Text(
                    entry.viewsText,
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: R34Color.ink,
                    ),
                  ),
                if (entry.favouritesText.isNotEmpty)
                  Text(
                    '♥ ${entry.favouritesText}',
                    style: const TextStyle(
                      fontSize: 10,
                      height: 1.5,
                      color: R34Color.accentBright,
                    ),
                  ),
                if (entry.videosText.isNotEmpty)
                  Text(
                    '${entry.videosText} 个',
                    style: const TextStyle(
                      fontSize: 10,
                      height: 1.5,
                      color: R34Color.inkFaint,
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    final ph = const ColoredBox(
      color: R34Color.surfaceHigh,
      child: Center(
        child: Icon(Icons.image_outlined, size: 16, color: R34Color.inkFaint),
      ),
    );
    if (url.isEmpty) return ph;
    return NetImage(url: url, showBroken: false, fadeIn: Duration.zero);
  }
}

String _pretty(String s) => s
    .replaceAll('-', ' ')
    .split(' ')
    .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1))
    .join(' ');
