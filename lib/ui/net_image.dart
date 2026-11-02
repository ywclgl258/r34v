import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../core/constants.dart';
import 'tokens.dart';

/// 统一的网络图加载。
///
/// ## 为什么不直接用 CachedNetworkImage
///
/// 曾经是 `CachedNetworkImage(imageUrl: url)`。它内部走
/// `flutter_cache_manager` → 系统 `HttpClient`，**不经过 SiteClient 的
/// DoH + IP 直连兜底**。在没有代理、系统 DNS 被污染的网络下，这条路
/// 表现为所有封面卡在 `SYN_SENT` 直到超时——而同一页面的 HTML 因为有
/// 兜底反而能拿到，于是用户看到「列表有字、封面全灰」。
///
/// 现在改成：字节由 `SiteClient.getBytes()` 取（复用同一套建连逻辑：
/// 代理 → DoH 解析的 IP → 系统 DNS），再交给 `Image.memory`。
///
/// 代价与对策：
/// - 失去 `flutter_cache_manager` 的磁盘缓存 → 这里自己用内存 LRU 顶上，
///   并按 URL 去重并发请求（列表滚动时同一个 URL 会被反复 build）。
/// - 字节常驻内存 → 用小容量 LRU（[maxEntries]）限制，超出就淘汰。
///
/// 图片 URL 与站点同域（`rule34video.com/contents/videos_screenshots/...`），
/// 所以能吃到 `_isSiteHost` 的直连分支；将来若换成独立 CDN 域名，
/// 该分支会自然退回系统 DNS，不会坏掉。
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
    final fallback = ColoredBox(color: R34Color.surfaceHigh);

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

    return _RemoteImage(
      url: url,
      fit: fit,
      showBroken: showBroken,
      fadeIn: fadeIn,
    );
  }
}

/// 真正取图的那一层。用 StatefulWidget 是为了在 URL 变化时取消旧请求。
class _RemoteImage extends ConsumerStatefulWidget {
  const _RemoteImage({
    required this.url,
    required this.fit,
    required this.showBroken,
    required this.fadeIn,
  });

  final String url;
  final BoxFit fit;
  final bool showBroken;
  final Duration fadeIn;

  @override
  ConsumerState<_RemoteImage> createState() => _RemoteImageState();
}

class _RemoteImageState extends ConsumerState<_RemoteImage> {
  Uint8List? _bytes;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant _RemoteImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      _bytes = null;
      _failed = false;
      _load();
    }
  }

  void _load() {
    final url = widget.url;
    // 同步命中缓存时直接出图，避免闪一下占位。
    final hit = ImageByteCache.instance.get(url);
    if (hit != null) {
      _bytes = hit;
      return;
    }
    ImageByteCache.instance.load(url, () {
      final client = ref.read(rule34ApiProvider).client;
      return client.getBytes(url, referer: '${Site.base}/');
    }).then((bytes) {
      if (!mounted || widget.url != url) return;
      setState(() {
        _bytes = bytes;
        _failed = bytes == null;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final fallback = ColoredBox(color: R34Color.surfaceHigh);

    if (_failed) {
      return widget.showBroken
          ? const ColoredBox(
              color: R34Color.surfaceHigh,
              child: Icon(Icons.broken_image_outlined, color: R34Color.inkFaint),
            )
          : fallback;
    }

    final bytes = _bytes;
    if (bytes == null) return fallback;

    return Image.memory(
      bytes,
      fit: widget.fit,
      gaplessPlayback: true,
      errorBuilder: (context, error, stack) => fallback,
    );
  }
}

/// 图片字节的内存 LRU + 并发去重。
///
/// 为什么需要去重：列表滚动时同一个 URL 的 widget 会被反复 build，
/// 若每次都发请求，一屏能打出几十个重复连接——站点那边就是风控信号。
class ImageByteCache {
  ImageByteCache._();

  static final ImageByteCache instance = ImageByteCache._();

  /// 最多留多少张。缩略图约 10–40KB，300 张约 6–12MB，可接受。
  static const int maxEntries = 300;

  final Map<String, Uint8List> _lru = {};
  final Map<String, Future<Uint8List?>> _inflight = {};

  Uint8List? get(String url) => _lru[url];

  /// 取字节：命中缓存直接返回；否则复用进行中的请求，保证同一 URL 只发一次。
  Future<Uint8List?> load(String url, Future<Uint8List?> Function() fetch) {
    final cached = _lru[url];
    if (cached != null) {
      // 命中后挪到队尾，维持 LRU 顺序。
      _lru.remove(url);
      _lru[url] = cached;
      return Future.value(cached);
    }

    final running = _inflight[url];
    if (running != null) return running;

    // 注意：这里必须先建好「最终结果的 Future」，再挂到 _inflight。
    // 早先写成 `fut = fetch().whenComplete(...)` 再 `return fut.then(...)`，
    // 导致 _inflight 里存的是中间态、调用方拿到的是另一条链——两者各自独立，
    // 一旦中间链的异常没被观察就会静默丢失，表现为图片永远停在占位色。
    final completer = Completer<Uint8List?>();
    _inflight[url] = completer.future;

    fetch().then((bytes) {
      if (bytes != null) _put(url, bytes);
      if (!completer.isCompleted) completer.complete(bytes);
    }).catchError((Object _) {
      if (!completer.isCompleted) completer.complete(null);
    }).whenComplete(() {
      _inflight.remove(url);
    });

    return completer.future;
  }

  void _put(String url, Uint8List bytes) {
    _lru.remove(url);
    _lru[url] = bytes;
    while (_lru.length > maxEntries) {
      _lru.remove(_lru.keys.first);
    }
  }

  /// 清空（设置页「清缓存」或测试用）。
  void clear() {
    _lru.clear();
    _inflight.clear();
  }
}
