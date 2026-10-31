import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../domain/video_card.dart';

/// 列表里的一张视频卡。
class VideoCardTile extends ConsumerWidget {
  const VideoCardTile({super.key, required this.card, this.compact = false});

  final VideoCard card;
  final bool compact;

  /// 测试里替换成不发网络请求的加载器，避免 widget 测试真的去连图床。
  static ImageProvider Function(String url)? imageProviderOverride;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(watchProgressProvider(card.id));
    final scheme = Theme.of(context).colorScheme;

    return Card(
      clipBehavior: Clip.antiAlias,
      margin: EdgeInsets.zero,
      child: InkWell(
        onTap: () => context.push('/video/${card.id}/${card.slug}'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  _Thumb(url: card.thumb, scheme: scheme),
                  // 时长角标
                  if (card.duration.isNotEmpty)
                    Positioned(
                      right: 6,
                      bottom: 6,
                      child: _Badge(text: card.duration),
                    ),
                  if (card.isHd)
                    const Positioned(
                      left: 6,
                      bottom: 6,
                      child: _Badge(text: 'HD', accent: true),
                    ),
                  // 类型角标（Futa / Gay / …）。放在左下、HD 之上，
                  // 因为两者位置会撞——HD 在最底，类型往上挪一行。
                  if (card.type.isNotEmpty)
                    Positioned(
                      left: 6,
                      bottom: card.isHd ? 24 : 6,
                      child: _TypeBadge(text: card.type),
                    ),
                  // 看过的话画一条进度
                  if (progress != null && progress.positionSeconds > 0)
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      child: LinearProgressIndicator(
                        value: progress.progress,
                        minHeight: 3,
                        backgroundColor: Colors.black38,
                      ),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(6, 5, 6, 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    card.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          fontSize: 11.5,
                          height: 1.25,
                        ),
                  ),
                  const SizedBox(height: 3),
                  // 三列时一行放不下两个指标，会溢出。用 FittedBox 收缩而不是让它报错。
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    alignment: Alignment.centerLeft,
                    child: Row(
                      children: [
                        Icon(Icons.visibility_outlined, size: 11, color: scheme.outline),
                        const SizedBox(width: 2),
                        Text(card.viewsText, style: _meta(context)),
                        if (card.ratingPercent >= 0) ...[
                          const SizedBox(width: 6),
                          Icon(Icons.thumb_up_outlined, size: 11, color: scheme.outline),
                          const SizedBox(width: 2),
                          Text('${card.ratingPercent}%', style: _meta(context)),
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
  }

  TextStyle? _meta(BuildContext context) => Theme.of(context)
      .textTheme
      .labelSmall
      ?.copyWith(color: Theme.of(context).colorScheme.outline);
}

/// 缩略图。
///
/// 单独抽出来是因为测试要绕开网络：`imageProviderOverride` 一旦被设，
/// 就用它代替 CachedNetworkImage。
class _Thumb extends StatelessWidget {
  const _Thumb({required this.url, required this.scheme});

  final String url;
  final ColorScheme scheme;

  @override
  Widget build(BuildContext context) {
    final fallback = ColoredBox(
      color: scheme.surfaceContainerHighest,
      child: Icon(Icons.broken_image_outlined, color: scheme.outline),
    );

    if (url.isEmpty) return fallback;

    final override = VideoCardTile.imageProviderOverride;
    if (override != null) {
      return Image(
        image: override(url),
        fit: BoxFit.cover,
        errorBuilder: (context, error, stack) => fallback,
      );
    }

    return CachedNetworkImage(
      imageUrl: url,
      fit: BoxFit.cover,
      fadeInDuration: const Duration(milliseconds: 150),
      // 不用转圈占位：那是个无限动画，会让 widget 测试永远等不到稳定帧
      placeholder: (context, u) => ColoredBox(color: scheme.surfaceContainerHighest),
      errorWidget: (context, u, e) => fallback,
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.text, this.accent = false});

  final String text;
  final bool accent;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
      decoration: BoxDecoration(
        color: accent
            ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.85)
            : Colors.black.withValues(alpha: 0.7),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        text,
        style: const TextStyle(color: Colors.white, fontSize: 9, fontWeight: FontWeight.w600),
      ),
    );
  }
}


/// 类型角标（Futa / Gay / Straight / Music / Iwara）。
///
/// 用琥珀色和 HD 的白色区分开：HD 是画质信息，类型是内容标记，
/// 两者混在一起看不出重点。
class _TypeBadge extends StatelessWidget {
  const _TypeBadge({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
      decoration: BoxDecoration(
        color: const Color(0xCCB8860B),
        borderRadius: BorderRadius.circular(3),
      ),
      child: Text(
        text,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 8.5,
          fontWeight: FontWeight.w600,
          height: 1.2,
        ),
      ),
    );
  }
}
