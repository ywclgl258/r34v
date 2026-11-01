import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/video_card.dart';
import '../../ui/components.dart';
import '../../ui/net_image.dart';
import '../../ui/tokens.dart';
import 'video_grid.dart';

/// 列表里的一张视频卡。
///
/// 结构固定为「封面 → 标题两行 → 一行元信息」。
///
/// **高度由内容决定，不接受父级给死高度。**
/// 之前封面用 `Expanded` 吃掉剩余空间，配合网格的 `childAspectRatio: 0.74`，
/// 390dp 宽手机上每张卡会多出约 24dp 被封面吃掉——封面被拉高、画面被裁，
/// 卡片本身也比内容高，叠上网格间距就成了「间隙很大」。现在封面按 16:9
/// 自己定高，标题固定预留两行，卡片高度恰好等于内容高度。
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
  static ImageProvider Function(String url)? get imageProviderOverride =>
      NetImage.imageOverride;

  static set imageProviderOverride(ImageProvider Function(String url)? v) =>
      NetImage.imageOverride = v;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(watchProgressProvider(card.id));

    final tile = Material(
      color: R34Color.surface,
      clipBehavior: Clip.antiAlias,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        side: BorderSide(color: R34Color.hairline),
      ),
      child: InkWell(
        onTap: () => context.push('/video/${card.id}/${card.slug}'),
        splashColor: R34Color.accentSoft,
        highlightColor: Colors.transparent,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            AspectRatio(
              aspectRatio: 16 / 9,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  NetImage(url: card.thumb),
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
              padding: const EdgeInsets.fromLTRB(9, 8, 9, 9),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  _TitleBlock(
                    title: card.title,
                    compact: compact,
                  ),
                  const SizedBox(height: 4),
                  // 窄卡（相关推荐 150dp）里一排放不下两个指标，用 FittedBox 收，
                  // 而不是让它溢出。
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

/// 标题区：固定占两行高度。
///
/// 用 `SizedBox` 硬钉高度而不是 `maxLines: 2` —— 后者只限制「最多两行」，
/// 一行标题的卡片会矮一行，于是同一行里卡片底边参差不齐、下一行的
/// 起始位置也被撑得忽高忽低。视觉上读起来就是「间距乱」。
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
      letterSpacing: 0.1,
      color: R34Color.ink,
    );
    // 和网格共用同一个行高计算（含整像素取整），否则卡片会差零点几像素溢出。
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
