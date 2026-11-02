import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

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
    this.maxWidthHint,
  });

  final String url;
  final BoxFit fit;

  /// 加载失败时是否叠一个破图图标。大封面通常不需要（直接留底色更干净）。
  final bool showBroken;
  final Duration fadeIn;

  /// 期望的显示宽度（逻辑像素），用于限制解码尺寸。
  ///
  /// **不传就会按原始分辨率解码**：站点封面常见 1000×2000 以上，
  /// 一张 RGBA 就吃掉 8MB+。首屏同时挂十几个（轮播 6 张 + 两个横向列表
  /// + 各种卡片）很容易把纹理内存挤爆，系统回收后表现为**图片变黑**，
  /// 而且不会抛任何异常，日志里也查不到。
  ///
  /// 传了之后按 `cacheWidth` 缩小解码，内存能降一个数量级。
  final double? maxWidthHint;

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
      maxWidthHint: maxWidthHint,
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
    this.maxWidthHint,
  });

  final String url;
  final BoxFit fit;
  final bool showBroken;
  final Duration fadeIn;
  final double? maxWidthHint;

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

    // 按显示宽度限制解码尺寸：见 [NetImage.maxWidthHint] 的说明。
    // 取设备像素比放大到物理像素，减少缩放损失；上限 1600 是经验值——
    // 再大对肉眼没区别，但内存会翻倍。
    int? cacheWidth;
    final hint = widget.maxWidthHint;
    if (hint != null && hint > 0) {
      final dpr = MediaQuery.maybeDevicePixelRatioOf(context) ?? 2.0;
      cacheWidth = (hint * dpr).round().clamp(1, 1600);
    }

    return Image.memory(
      bytes,
      fit: widget.fit,
      gaplessPlayback: true,
      cacheWidth: cacheWidth,
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

  /// 距上次磁盘淘汰又写了几张。
  int _writesSinceTrim = 0;

  Uint8List? get(String url) => _lru[url];

  /// 取字节：内存 → 磁盘 → 网络。同一 URL 只发一次请求。
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

    Future<void> run() async {
      // 1) 内存没命中，先看磁盘。
      //
      // 这一步是「二进 App 封面立刻出来」的关键：内存 LRU 重启即失效，
      // 而磁盘上的图还在。线上实测能省掉绝大部分封面请求。
      final disk = _disk;
      if (disk != null) {
        final fromDisk = await disk.read(url);
        if (fromDisk != null && fromDisk.isNotEmpty) {
          _put(url, fromDisk);
          if (!completer.isCompleted) completer.complete(fromDisk);
          return;
        }
      }

      // 2) 磁盘也没有，才真的走网络。
      final bytes = await fetch();
      if (bytes != null && bytes.isNotEmpty) {
        _put(url, bytes);
        // 写盘不 await：不阻塞 UI，失败也无所谓
        unawaited(disk?.write(url, bytes) ?? Future.value());
        // 攒够再淘汰，避免每张图都扫目录
        _writesSinceTrim++;
        if (_writesSinceTrim >= 20) {
          _writesSinceTrim = 0;
          unawaited(disk?.trimIfNeeded() ?? Future.value());
        }
      }
      if (!completer.isCompleted) completer.complete(bytes);
    }

    run().catchError((Object _) {
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
    // 顺带清磁盘，避免「点了清缓存但重启后封面又回来了」
    final disk = _disk;
    if (disk != null) unawaited(disk.clear());
  }

  // ---------- 磁盘持久化 ----------
  //
  // 内存 LRU 一重启就没了。站点封面是「内容不变、地址不变」的资源，
  // 完全可以长期存放——这也是二进 App 时图片能立刻出来的前提。
  //
  // 注意**不能缓存视频封面里的临时签名**：这里缓的是图片本身，
  // 键是完整 URL，URL 里带 acctoken 的那类会自然过期、命中率低，
  // 但不影响正确性（过期后重新取一次即可）。

  ImageDiskCache? _disk;

  /// 注入磁盘缓存。由 App 启动时调用；测试里不调，保持纯内存。
  void attachDisk(ImageDiskCache disk) => _disk = disk;

  /// 磁盘缓存是否已启用。
  bool get hasDisk => _disk != null;
}

/// 封面字节的磁盘缓存。
///
/// 只做很薄的一层：URL → 文件，文件名是 URL 的稳定哈希。
/// 不做 LRU 索引（目录里按修改时间淘汰），因为图片条目数
/// （几百上千）远小于视频，扫目录的成本可以接受。
class ImageDiskCache {
  ImageDiskCache(this.directory, {this.maxBytes = 200 * 1024 * 1024});

  final Directory directory;

  /// 总占用上限，超出后按最后访问时间淘汰最旧的。
  final int maxBytes;

  /// 已确认可用的目录（首次写入前做一次 exists 检查，避免每张图都 stat）。
  bool _ready = false;

  Future<void> _ensure() async {
    if (_ready) return;
    if (!await directory.exists()) {
      await directory.create(recursive: true);
    }
    _ready = true;
  }

  /// 文件名：URL 的稳定哈希 + 扩展名提示。
  ///
  /// 用哈希而不是直接编码 URL：URL 里有 `/`、`?`、`=` 等非法字符，
  /// 而且长度可能超出文件系统限制（255 字节）。这里用 FNV-1a 32 位，
  /// 碰撞概率对几百张图来说可以忽略；真要碰撞了也只是显示错图，
  /// 不会崩——但为了降低风险，再把 URL 长度低 8 位一起编进文件名。
  ///
  /// 做成 static + 可见：测试要按 URL 精确定位到具体文件
  /// （验证淘汰顺序时不能靠猜目录里的顺序）。
  static String fileNameFor(String url) {
    var hash = 0x811c9dc5;
    for (final b in utf8.encode(url)) {
      hash ^= b;
      hash = (hash * 0x01000193) & 0xFFFFFFFF;
    }
    final hex = hash.toRadixString(16).padLeft(8, '0');
    final lenHint = (url.length & 0xFF).toRadixString(16).padLeft(2, '0');
    return '$hex$lenHint.img';
  }

  File fileForUrl(String url) => File('${directory.path}/${fileNameFor(url)}');

  /// 读磁盘。命中时把文件 mtime 更新为「现在」，作为 LRU 的访问标记。
  Future<Uint8List?> read(String url) async {
    try {
      await _ensure();
      final f = fileForUrl(url);
      if (!await f.exists()) return null;
      final bytes = await f.readAsBytes();
      if (bytes.isEmpty) return null;
      // 触碰 mtime，供淘汰时判断「最近用过」
      unawaited(f.setLastModified(DateTime.now()));
      return bytes;
    } catch (_) {
      return null;
    }
  }

  /// 写磁盘（异步、失败静默——缓存失败不该影响看图）。
  Future<void> write(String url, Uint8List bytes) async {
    try {
      await _ensure();
      await fileForUrl(url).writeAsBytes(bytes, flush: false);
    } catch (_) {}
  }

  /// 按总大小淘汰。
  ///
  /// 时机选在「写入若干张之后」，而不是每写一张都扫一遍目录——
  /// 后者在滚动浏览时会造成明显卡顿。
  Future<void> trimIfNeeded() async {
    try {
      await _ensure();
      final files = <FileSystemEntity>[];
      var total = 0;
      await for (final e in directory.list(followLinks: false)) {
        if (e is! File) continue;
        final stat = await e.stat();
        total += stat.size;
        files.add(e);
      }
      if (total <= maxBytes) return;

      // 最旧的先删，直到降到上限的 80%（留余量，避免频繁触发）
      final withTime = <(File, DateTime, int)>[];
      for (final e in files) {
        final f = e as File;
        final st = await f.stat();
        withTime.add((f, st.modified, st.size));
      }
      withTime.sort((a, b) => a.$2.compareTo(b.$2));

      final target = (maxBytes * 0.8).round();
      var cur = total;
      for (final (f, _, size) in withTime) {
        if (cur <= target) break;
        try {
          await f.delete();
          cur -= size;
        } catch (_) {}
      }
    } catch (_) {}
  }

  /// 清空磁盘缓存。
  Future<void> clear() async {
    try {
      if (await directory.exists()) {
        await directory.delete(recursive: true);
      }
      _ready = false;
    } catch (_) {}
  }

  /// 当前占用（设置页展示用）。
  Future<int> sizeBytes() async {
    try {
      if (!await directory.exists()) return 0;
      var total = 0;
      await for (final e in directory.list(followLinks: false)) {
        if (e is File) total += (await e.stat()).size;
      }
      return total;
    } catch (_) {
      return 0;
    }
  }
}

