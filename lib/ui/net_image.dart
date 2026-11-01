import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

import 'tokens.dart';

/// 统一的网络图加载。
///
/// 为什么抽出来：
/// - 详情页大图、列表缩略图、片库缩略图原本各写一份
///   `CachedNetworkImage(... placeholder/errorWidget ...)`，样式漂移过两次；
/// - widget 测试要能在不发网络请求的前提下渲染真实页面，
///   [override] 是唯一的注入点（早先只有列表卡片有，详情页一测就
///   撞上 path_provider 的 MissingPluginException）。
class NetImage extends StatelessWidget {
  const NetImage({
    super.key,
    required this.url,
    this.fit = BoxFit.cover,
    this.showBroken = true,
    this.fadeIn = const Duration(milliseconds: 180),
  });

  final String url;
  final BoxFit fit;

  /// 加载失败时是否叠一个破图图标。大封面通常不需要（直接留底色更干净）。
  final bool showBroken;
  final Duration fadeIn;

  /// 测试注入。设为非 null 时所有图片都走它，不碰网络与磁盘缓存。
  ///
  /// 名字不叫 `override`：那会和 `@override` 注解撞名，分析器会把
  /// `@override` 解析成对这个字段的引用并报 invalid_annotation。
  static ImageProvider Function(String url)? imageOverride;

  @override
  Widget build(BuildContext context) {
    const fallback = ColoredBox(color: R34Color.surfaceHigh);

    if (url.isEmpty) {
      return showBroken
          ? const ColoredBox(
              color: R34Color.surfaceHigh,
              child: Icon(Icons.broken_image_outlined, color: R34Color.inkFaint),
            )
          : fallback;
    }

    final o = imageOverride;
    if (o != null) {
      return Image(
        image: o(url),
        fit: fit,
        errorBuilder: (context, error, stack) => fallback,
      );
    }

    return CachedNetworkImage(
      imageUrl: url,
      fit: fit,
      fadeInDuration: fadeIn,
      placeholder: (context, u) => fallback,
      errorWidget: (context, u, e) => showBroken
          ? const ColoredBox(
              color: R34Color.surfaceHigh,
              child: Icon(Icons.broken_image_outlined, color: R34Color.inkFaint),
            )
          : fallback,
    );
  }
}
