import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/video_card.dart';
import '../../ui/components.dart';
import '../../ui/tokens.dart';

/// 列表里的一张视频卡。
///
/// 结构固定为「封面 → 标题两行 → 一行元信息」，
/// 保证三列网格里每张卡的高度、文字基线都对齐。
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

  /// 在列表里的位置，用来做交错浮入。
  final int index;

  /// 网格首屏才需要浮入，滚动复用时关掉能省一层动画。
  final bool animateIn;

  /// 测试里替换成不发网络请求的加载器，避免 widget 测试真的去连图床。
  static ImageProvider Function(String url)? imageProviderOverride;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(watchProgressProvider(card.id));

    final tile = Material(
      color: R34Color.surface,
      clipBehavior: Clip.antiAlias,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.lg)),
        side: BorderSide(color: R34Color.hairline),
      ),
      child: InkWell(
        onTap: () => context.push('/video/${card.id}/${card.slug}'),
        splashColor: R34Color.accentSoft,
        highlightColor: Colors.transparent,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  _Thumb(url: card.thumb),
                  // 封面高光：左上角一道斜向反光，往下压暗，
                  // 让角标文字在任何封面上都读得出来。
                  const DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0x1FFFFFFF),
                          Color(0x00FFFFFF),
                          Color(0x38000000),
                        ],
                        stops: [0, 0.42, 1],
                      ),
                    ),
                  ),
                  if (card.duration.isNotEmpty)
                    Positioned(
                      right: 6,
                      bottom: 6,
                      child: PillTag(text: card.duration),
                    ),
                  if (card.isHd)
                    const Positioned(
                      left: 6,
                      bottom: 6,
                      child: PillTag(
                        text: 'HD',
                        background: Color(0xD9D1404A),
                      ),
                    ),
                  // 类型角标放左下，HD 占了下沿时往上让一行，避免叠字。
                  if (card.type.isNotEmpty)
                    Positioned(
                      left: 6,
                      bottom: card.isHd ? 26 : 6,
                      child: PillTag(
                        text: card.type,
                        background: const Color(0xCC1C1C1F),
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
                        minHeight: 2.5,
                        backgroundColor: Colors.black45,
                      ),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 7, 8, 9),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    card.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontFamily: 'NotoSansSC',
                      fontSize: 12,
                      height: 1.34,
                      fontWeight: FontWeight.w500,
                      letterSpacing: 0.1,
                      color: R34Color.ink,
                    ),
                  ),
                  const SizedBox(height: 4),
                  // 三列时一排放不下两个指标，用 FittedBox 收，而不是让它溢出。
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    alignment: Alignment.centerLeft,
                    child: Row(
                      children: [
                        const Icon(
                          Icons.visibility_outlined,
                          size: 11,
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

    if (!animateIn) return tile;
    return RiseIn(index: index, child: tile);
  }

  static const _meta = TextStyle(
    fontFamily: 'NotoSansSC',
    fontSize: 10.5,
    height: 1.2,
    color: R34Color.inkFaint,
  );
}

/// 缩略图。
///
/// 单独抽出来是因为测试要绕开网络：`imageProviderOverride` 一旦被设，
/// 就用它代替 CachedNetworkImage。
class _Thumb extends StatelessWidget {
  const _Thumb({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    const fallbackColor = ColoredBox(color: R34Color.surfaceHigh);

    if (url.isEmpty) {
      return const ColoredBox(
        color: R34Color.surfaceHigh,
        child: Icon(Icons.broken_image_outlined, color: R34Color.inkFaint),
      );
    }

    final override = VideoCardTile.imageProviderOverride;
    if (override != null) {
      return Image(
        image: override(url),
        fit: BoxFit.cover,
        errorBuilder: (context, error, stack) => fallbackColor,
      );
    }

    return CachedNetworkImage(
      imageUrl: url,
      fit: BoxFit.cover,
      fadeInDuration: const Duration(milliseconds: 180),
      placeholder: (context, u) => const ColoredBox(color: R34Color.surfaceHigh),
      errorWidget: (context, u, e) => const ColoredBox(
        color: R34Color.surfaceHigh,
        child: Icon(Icons.broken_image_outlined, color: R34Color.inkFaint),
      ),
    );
  }
}
