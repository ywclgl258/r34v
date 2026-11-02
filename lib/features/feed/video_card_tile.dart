import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/video_card.dart';
import '../../ui/components.dart';
import '../../ui/net_image.dart';
import '../../ui/tokens.dart';
import 'video_grid.dart';

/// 无底板视频卡：16:9 小圆角封面，标题与元信息直接排在黑底上。
class VideoCardTile extends ConsumerWidget {
  const VideoCardTile({
    super.key,
    required this.card,
    this.compact = false,
    this.index = 0,
    this.animateIn = true,
  });

  final VideoCard card;
  final bool compact;
  final int index;
  final bool animateIn;

  static ImageProvider Function(String url)? get imageProviderOverride =>
      NetImage.imageOverride;
  static set imageProviderOverride(ImageProvider Function(String url)? v) =>
      NetImage.imageOverride = v;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(watchProgressProvider(card.id));
    final tile = Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => context.push('/video/${card.id}/${card.slug}'),
        borderRadius: BorderRadius.circular(R34Radius.sm),
        splashColor: R34Color.accentSoft,
        highlightColor: Colors.transparent,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(R34Radius.sm),
              child: AspectRatio(
                aspectRatio: 16 / 9,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    // 卡片很小，解码尺寸也压到卡片宽度即可。
                    // 列表里同时挂十几张，不限制的话每张都按原始分辨率
                    // 解码，内存占用会明显偏高。
                    NetImage(
                      url: card.thumb,
                      maxWidthHint: MediaQuery.sizeOf(context).width / 2.2,
                    ),
                    const DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [Colors.transparent, Color(0x66000000)],
                        ),
                      ),
                    ),
                    if (card.duration.isNotEmpty)
                      Positioned(
                        right: 5,
                        bottom: 5,
                        child: PillTag(text: card.duration),
                      ),
                    if (card.isHd)
                      const Positioned(
                        left: 5,
                        bottom: 5,
                        child: PillTag(text: 'HD', background: R34Color.accent),
                      ),
                    if (card.type.isNotEmpty)
                      Positioned(
                        left: 5,
                        bottom: card.isHd ? 26 : 5,
                        child: PillTag(
                          text: card.type,
                          foreground: R34Color.inkSecondary,
                        ),
                      ),
                    if (progress != null && progress.positionSeconds > 0)
                      Positioned(
                        left: 0,
                        right: 0,
                        bottom: 0,
                        child: LinearProgressIndicator(
                          value: progress.progress,
                          minHeight: 3,
                          backgroundColor: Colors.black45,
                        ),
                      ),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(0, 8, 0, 9),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  _TitleBlock(title: card.title, compact: compact),
                  const SizedBox(height: 4),
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    alignment: Alignment.centerLeft,
                    child: Row(
                      children: [
                        const Icon(
                          Icons.play_arrow_rounded,
                          size: 12,
                          color: R34Color.inkFaint,
                        ),
                        const SizedBox(width: 3),
                        Text(card.viewsText, style: _meta),
                        if (card.ratingPercent >= 0) ...[
                          const SizedBox(width: 8),
                          const Icon(
                            Icons.thumb_up_outlined,
                            size: 11,
                            color: R34Color.inkFaint,
                          ),
                          const SizedBox(width: 3),
                          Text('${card.ratingPercent}%', style: _meta),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
    return animateIn ? RiseIn(index: index, child: tile) : tile;
  }

  static const _meta = TextStyle(
    fontFamily: 'NotoSansSC',
    fontSize: 10.5,
    height: 1.2,
    color: R34Color.inkFaint,
  );
}

class _TitleBlock extends StatelessWidget {
  const _TitleBlock({required this.title, required this.compact});

  final String title;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontFamily: 'NotoSansSC',
      fontSize: compact ? 12 : 13,
      height: compact ? 1.34 : 1.3,
      fontWeight: FontWeight.w500,
      color: R34Color.ink,
    );
    final lineHeight = videoTileLineHeight(context, compact: compact);
    return SizedBox(
      height: lineHeight * 2,
      child: Text(
        title,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: style,
      ),
    );
  }
}
