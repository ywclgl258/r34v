import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/ranked_entry.dart';

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
              ? _ErrorView(message: _error!, onRetry: _load)
              : _items.isEmpty
                  ? const _EmptyView(text: '没拿到排行数据，站点可能改版了')
                  : GridView.builder(
                      padding: const EdgeInsets.all(12),
                      gridDelegate:
                          const SliverGridDelegateWithMaxCrossAxisExtent(
                        maxCrossAxisExtent: 130,
                        childAspectRatio: 0.72,
                        crossAxisSpacing: 10,
                        mainAxisSpacing: 12,
                      ),
                      itemCount: _items.length,
                      itemBuilder: (context, i) =>
                          _ArtistCard(entry: _items[i]),
                    ),
    );
  }
}

class _ArtistCard extends StatelessWidget {
  const _ArtistCard({required this.entry});

  final RankedEntry entry;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: () =>
          context.push('/list/model/${Uri.encodeComponent(entry.slug)}'),
      borderRadius: BorderRadius.circular(8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Stack(
              children: [
                Positioned.fill(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: _Thumb(url: entry.thumb),
                  ),
                ),
                // 前三名用不同颜色，一眼能看出头部
                Positioned(
                  left: 4,
                  top: 4,
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: switch (entry.rank) {
                        1 => const Color(0xFFD4AF37),
                        2 => const Color(0xFF9EA7AD),
                        3 => const Color(0xFFB87333),
                        _ => Colors.black54,
                      },
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      '${entry.rank}',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 5),
          Text(
            entry.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
          ),
          if (entry.viewsText.isNotEmpty)
            Text(
              entry.viewsText,
              style: TextStyle(fontSize: 10.5, color: scheme.outline),
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
              ? _ErrorView(message: _error!, onRetry: _load)
              : ListView.separated(
                  itemCount: _works.length,
                  separatorBuilder: (context, i) => const Divider(height: 1),
                  itemBuilder: (context, i) {
                    final w = _works[i];
                    return ListTile(
                      title: Text(_pretty(w.name)),
                      subtitle: w.countText.isEmpty ? null : Text(w.countText),
                      trailing: const Icon(Icons.chevron_right, size: 18),
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (context) => _WorkRankingPage(work: w),
                        ),
                      ),
                    );
                  },
                ),
    );
  }
}

/// 某个作品下的角色榜。
class _WorkRankingPage extends ConsumerStatefulWidget {
  const _WorkRankingPage({required this.work});

  final CharacterWork work;

  @override
  ConsumerState<_WorkRankingPage> createState() => _WorkRankingPageState();
}

class _WorkRankingPageState extends ConsumerState<_WorkRankingPage> {
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
              ? _ErrorView(message: _error!, onRetry: _load)
              : ListView.separated(
                  itemCount: _items.length,
                  separatorBuilder: (context, i) => const Divider(height: 1),
                  itemBuilder: (context, i) =>
                      _RankRow(entry: _items[i], maxRatio: 1),
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
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: () {
        // 角色链接是 tag 页，作者链接是 model 页
        final segment = entry.isTag ? 'tag' : 'model';
        context.push('/list/$segment/${Uri.encodeComponent(entry.slug)}');
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          children: [
            SizedBox(
              width: 24,
              child: Text(
                '${entry.rank}',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: entry.rank <= 3 ? scheme.primary : scheme.outline,
                ),
              ),
            ),
            const SizedBox(width: 8),
            SizedBox(
              width: 64,
              height: 36,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: _Thumb(url: entry.thumb),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    entry.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13),
                  ),
                  if (entry.fullName.isNotEmpty &&
                      entry.fullName.toLowerCase() !=
                          entry.name.toLowerCase())
                    Text(
                      entry.fullName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 10.5, color: scheme.outline),
                    ),
                  const SizedBox(height: 3),
                  // 播放量相对榜首的横条，一眼看出差距
                  if (entry.viewsRatio > 0)
                    ClipRRect(
                      borderRadius: BorderRadius.circular(2),
                      child: LinearProgressIndicator(
                        value: entry.viewsRatio / (maxRatio <= 0 ? 1 : maxRatio),
                        minHeight: 3,
                        backgroundColor: scheme.surfaceContainerHighest,
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 10),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (entry.viewsText.isNotEmpty)
                  Text(
                    entry.viewsText,
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                if (entry.favouritesText.isNotEmpty)
                  Text(
                    '♥ ${entry.favouritesText}',
                    style: TextStyle(fontSize: 10, color: scheme.outline),
                  ),
                if (entry.videosText.isNotEmpty)
                  Text(
                    '${entry.videosText} 个',
                    style: TextStyle(fontSize: 10, color: scheme.outline),
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
    final ph = ColoredBox(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Center(
        child: Icon(
          Icons.image_outlined,
          size: 16,
          color: Theme.of(context).colorScheme.outline,
        ),
      ),
    );
    if (url.isEmpty) return ph;
    return Image.network(
      url,
      fit: BoxFit.cover,
      errorBuilder: (context, e, s) => ph,
      loadingBuilder: (context, child, p) => p == null ? child : ph,
    );
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.cloud_off,
                size: 40, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton(onPressed: onRetry, child: const Text('重试')),
          ],
        ),
      ),
    );
  }
}

class _EmptyView extends StatelessWidget {
  const _EmptyView({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Center(child: Text(text, textAlign: TextAlign.center));
  }
}

String _pretty(String s) => s
    .replaceAll('-', ' ')
    .split(' ')
    .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1))
    .join(' ');
