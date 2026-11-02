import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../remote/dio_proxy.dart';
import '../remote/doh_connector.dart';

/// 一条缓存记录。
class CachedVideo {
  const CachedVideo({
    required this.videoId,
    required this.title,
    required this.filePath,
    required this.bytes,
    required this.quality,
    required this.createdAt,
    this.thumb = '',
    this.durationSeconds = 0,
  });

  final String videoId;
  final String title;

  /// 落盘位置。播放时直接把这个路径交给播放器，不走网络。
  final String filePath;

  /// 文件大小（字节），用于显示「已缓存 452MB」和空间统计。
  final int bytes;

  /// 缓存时的清晰度，如 `720p`。
  final String quality;
  final DateTime createdAt;
  final String thumb;
  final int durationSeconds;

  double get megabytes => bytes / (1024 * 1024);

  bool get fileExists => File(filePath).existsSync();

  Map<String, dynamic> toJson() => {
    'id': videoId,
    'title': title,
    'path': filePath,
    'bytes': bytes,
    'q': quality,
    'at': createdAt.millisecondsSinceEpoch,
    'thumb': thumb,
    'dur': durationSeconds,
  };

  static CachedVideo fromJson(Map<String, dynamic> j) => CachedVideo(
    videoId: j['id'] as String? ?? '',
    title: j['title'] as String? ?? '',
    filePath: j['path'] as String? ?? '',
    bytes: (j['bytes'] as num?)?.toInt() ?? 0,
    quality: j['q'] as String? ?? '',
    createdAt: DateTime.fromMillisecondsSinceEpoch(
      (j['at'] as num?)?.toInt() ?? 0,
    ),
    thumb: j['thumb'] as String? ?? '',
    durationSeconds: (j['dur'] as num?)?.toInt() ?? 0,
  );

  @override
  String toString() =>
      'CachedVideo($videoId, $quality, ${megabytes.toStringAsFixed(0)}MB)';
}

/// 缓存进度。
class CacheProgress {
  const CacheProgress({
    required this.received,
    required this.total,
    this.done = false,
    this.error,
  });

  final int received;
  final int total;
  final bool done;
  final String? error;

  /// 0..1；总大小未知时为 null（进度条显示为不确定态）。
  double? get fraction => total > 0 ? (received / total).clamp(0.0, 1.0) : null;

  @override
  String toString() => 'CacheProgress($received/$total${done ? ", done" : ""})';
}

/// 视频缓存。
///
/// 设计取向是「**自动、无感**」而不是「手动下载」：
/// - 自动缓存开启时，边下边播完整抓取的视频会入库；
/// - 也可在播放页手动缓存本次播放的清晰度；
/// - 播放时优先用本地文件，没有才走网络。
///
/// 为什么不直接边播边缓存：站点直链带 `v-acctoken`，用 `Range` 分片抓很容易
/// 在 token 过期时拿到半截脏文件。整段下载到 `.part`，校验完整后再改名；
/// 失败或取消时保留 `.part`，下次通过 Range 断点续传。
class VideoCache {
  VideoCache(
    this._prefs, {
    DnsOverHttpsConnector? connector,
    Dio? dio,
    Directory? cacheDirectory,
  }) : _cacheDirectoryOverride = cacheDirectory {
    _dio =
        dio ??
        Dio(
          BaseOptions(
            connectTimeout: const Duration(seconds: 20),
            receiveTimeout: const Duration(minutes: 10),
            headers: {'User-Agent': Site.userAgent, 'Referer': '${Site.base}/'},
          ),
        );
    // 装上 DoH 连接器后，缓存下载和页面浏览走同一套解析，
    // 不会出现「页面能开、下载连不上」这种割裂。测试注入 Dio 时不改其配置。
    if (dio == null) applyProxyToDio(_dio, '', connector: connector);
  }

  final SharedPreferences _prefs;
  final Directory? _cacheDirectoryOverride;

  static const _kIndex = 'video_cache_v1';
  static const _kAutoCache = 'cache_auto_enabled';
  static const _kBudgetMb = 'cache_budget_mb';

  /// 默认给缓存留 4GB。超过就按「最久没看的」清理。
  static const int defaultBudgetMb = 4096;

  /// 小于这个长度的视频不值得缓存（几秒钟的片段）。
  static const int minDurationSeconds = 30;

  late final Dio _dio;
  bool get autoCache => _prefs.getBool(_kAutoCache) ?? false;

  Future<void> setAutoCache(bool v) => _prefs.setBool(_kAutoCache, v);

  int get budgetMb => _prefs.getInt(_kBudgetMb) ?? defaultBudgetMb;

  Future<void> setBudgetMb(int mb) => _prefs.setInt(_kBudgetMb, mb);

  // ---------- 索引 ----------

  List<CachedVideo> loadIndex() {
    final raw = _prefs.getString(_kIndex);
    if (raw == null || raw.isEmpty) return const [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .whereType<Map<String, dynamic>>()
          .map(CachedVideo.fromJson)
          .where((e) => e.videoId.isNotEmpty)
          .toList()
        ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
    } catch (_) {
      return const [];
    }
  }

  Future<void> _saveIndex(List<CachedVideo> list) => _prefs.setString(
    _kIndex,
    jsonEncode(list.map((e) => e.toJson()).toList()),
  );

  CachedVideo? find(String videoId) {
    for (final e in loadIndex()) {
      if (e.videoId == videoId) return e;
    }
    return null;
  }

  bool isCached(String videoId) {
    final e = find(videoId);
    return e != null && e.fileExists;
  }

  /// 已用空间（只算真的还在磁盘上的）。
  int usedBytes() =>
      loadIndex().where((e) => e.fileExists).fold(0, (sum, e) => sum + e.bytes);

  /// 不在本次播放清晰度列表里的旧缓存可以清掉。
  Future<void> remove(String videoId) async {
    final list = loadIndex().toList();
    final hit = list.where((e) => e.videoId == videoId).toList();
    for (final e in hit) {
      final f = File(e.filePath);
      if (await f.exists()) await f.delete();
    }
    list.removeWhere((e) => e.videoId == videoId);
    await _saveIndex(list);
  }

  Future<void> clearAll() async {
    for (final e in loadIndex()) {
      final f = File(e.filePath);
      if (await f.exists()) await f.delete();
    }
    await _prefs.remove(_kIndex);
  }

  /// 把索引里指向不存在的文件的记录清掉（用户手动清了应用数据、或系统回收了存储）。
  Future<void> pruneMissing() async {
    final list = loadIndex();
    final alive = list.where((e) => e.fileExists).toList();
    if (alive.length != list.length) await _saveIndex(alive);
  }

  /// 超出预算时，从最旧的开始删，直到降到预算的 80%（留点余量，避免每次都删）。
  Future<void> enforceBudget() async {
    final list = loadIndex().where((e) => e.fileExists).toList();
    var total = list.fold<int>(0, (s, e) => s + e.bytes);
    final budget = budgetMb * 1024 * 1024;
    if (total <= budget) return;

    final target = (budget * 0.8).round();
    // list 已按时间倒序，从尾部（最旧）删
    for (var i = list.length - 1; i >= 0 && total > target; i--) {
      final e = list[i];
      final f = File(e.filePath);
      if (await f.exists()) await f.delete();
      total -= e.bytes;
      list.removeAt(i);
    }
    await _saveIndex(list);
  }

  // ---------- 下载 ----------

  /// 缓存一个视频。返回落盘后的记录。
  ///
  /// [onProgress] 会被频繁调用，UI 侧自己节流。
  ///
  /// [pageUrl] 是视频详情页地址，用作 Referer——站点有反盗链白名单，
  /// 实测外站 Referer 直接 403。传空时退回站点根（能过，但不是浏览器行为）。
  Future<CachedVideo> cache({
    required String videoId,
    required String title,
    required String url,
    required String quality,
    String thumb = '',
    String pageUrl = '',
    int durationSeconds = 0,
    void Function(CacheProgress)? onProgress,
    CancelToken? cancelToken,
  }) async {
    if (isCached(videoId)) {
      final existing = find(videoId)!;
      onProgress?.call(
        CacheProgress(
          received: existing.bytes,
          total: existing.bytes,
          done: true,
        ),
      );
      return existing;
    }

    final dir = await _cacheDir();
    final safeQuality = quality.replaceAll(RegExp(r'[^0-9a-zA-Z]'), '');
    final target = File(p.join(dir.path, '${videoId}_$safeQuality.mp4'));
    final part = File('${target.path}.part');

    // **断点续传：不删已有的半截文件。**
    //
    // 以前这里无条件 `part.delete()`，每次进来都从头下。而缓存任务已经
    // 改成可以后台继续、也可以中途取消，用户取消/退出/切网之后重进，
    // 之前下的全白费——等于把同一份内容重复拉了好几遍，
    // 这是最容易招来限流的行为（流量几倍于视频本身）。
    //
    // 现在保留 .part，用 `Range: bytes=<已有长度>-` 接着下。
    // 服务端不支持 Range（回 200 而非 206）时才退回整下。
    var resumeFrom = 0;
    if (await part.exists()) {
      resumeFrom = await part.length();
      // 半截文件小到没有意义（比如刚创建就失败），直接重来更省事
      if (resumeFrom < 64 * 1024) {
        await part.delete();
        resumeFrom = 0;
      }
    }

    try {
      final res = await _dio.get<ResponseBody>(
        url,
        cancelToken: cancelToken,
        options: Options(
          responseType: ResponseType.stream,
          headers: {
            // 反盗链：站点那一层会校验 Referer（实测外站直接 403）。
            // CDN 那一层实测不校验，但带上无害。
            'Referer': pageUrl.isEmpty ? '${Site.base}/' : pageUrl,
            if (resumeFrom > 0) 'Range': 'bytes=$resumeFrom-',
          },
        ),
      );

      final ranged = res.statusCode == HttpStatus.partialContent;
      if (res.statusCode != HttpStatus.ok && !ranged) {
        throw HttpException('下载失败：HTTP ${res.statusCode}');
      }
      // 服务端忽略 Range 时返回的是完整文件，必须覆盖旧 part，不能追加。
      if (!ranged) resumeFrom = 0;

      final body = res.data;
      if (body == null) throw const HttpException('下载响应为空');
      final responseBytes = int.tryParse(
        res.headers.value(HttpHeaders.contentLengthHeader) ?? '',
      );
      final contentRange =
          res.headers.value(HttpHeaders.contentRangeHeader) ?? '';
      final totalFromRange = int.tryParse(
        RegExp(r'/(\d+)\s*$').firstMatch(contentRange)?.group(1) ?? '',
      );
      final expectedSize = ranged
          ? (totalFromRange ??
                (responseBytes == null ? null : resumeFrom + responseBytes))
          : responseBytes;

      final raf = await part.open(
        mode: ranged ? FileMode.writeOnlyAppend : FileMode.writeOnly,
      );
      var received = 0;
      try {
        await for (final chunk in body.stream) {
          await raf.writeFrom(chunk);
          received += chunk.length;
          onProgress?.call(
            CacheProgress(
              received: resumeFrom + received,
              total: expectedSize ?? -1,
            ),
          );
        }
        await raf.flush();
      } finally {
        await raf.close();
      }

      final size = await part.length();
      if (size <= 0) {
        throw const HttpException('下载得到的文件是空的');
      }
      if (expectedSize != null && expectedSize != size) {
        throw HttpException('文件不完整：期望 $expectedSize 字节，实际 $size');
      }

      if (await target.exists()) await target.delete();
      await part.rename(target.path);

      final entry = CachedVideo(
        videoId: videoId,
        title: title,
        filePath: target.path,
        bytes: size,
        quality: quality,
        createdAt: DateTime.now(),
        thumb: thumb,
        durationSeconds: durationSeconds,
      );

      final list = loadIndex().where((e) => e.videoId != videoId).toList()
        ..insert(0, entry);
      await _saveIndex(list);
      await enforceBudget();

      onProgress?.call(CacheProgress(received: size, total: size, done: true));
      return entry;
    } catch (_) {
      // 网络失败、取消和短响应都保留 .part，下次用 Range 继续。
      rethrow;
    }
  }

  /// 把已经下好的文件直接登记为缓存（不再重复下载）。
  ///
  /// 用于「边下边播」那条路径：播放时文件已经在本地了，没必要再跑一遍下载。
  /// 会把文件从临时目录**改名搬到缓存目录**，避免被系统当临时文件清掉。
  Future<CachedVideo> adoptFile({
    required String videoId,
    required String title,
    required String sourcePath,
    required String quality,
    String thumb = '',
    int durationSeconds = 0,
  }) async {
    final src = File(sourcePath);
    if (!await src.exists()) {
      throw HttpException('源文件不存在：$sourcePath');
    }

    final dir = await _cacheDir();
    final safeQuality = quality.replaceAll(RegExp(r'[^0-9a-zA-Z]'), '');
    final target = File(p.join(dir.path, '${videoId}_$safeQuality.mp4'));

    // 同 id 同清晰度已有正式缓存就不动
    final existing = find(videoId);
    if (existing != null &&
        existing.fileExists &&
        existing.filePath == target.path) {
      return existing;
    }

    if (await target.exists()) await target.delete();
    await src.rename(target.path);

    final size = await target.length();
    final entry = CachedVideo(
      videoId: videoId,
      title: title,
      filePath: target.path,
      bytes: size,
      quality: quality,
      createdAt: DateTime.now(),
      thumb: thumb,
      durationSeconds: durationSeconds,
    );

    final list = loadIndex().where((e) => e.videoId != videoId).toList()
      ..insert(0, entry);
    await _saveIndex(list);
    await enforceBudget();
    return entry;
  }

  /// 本地已有的文件路径（没有就 null）。播放器优先用它。
  String? localPathFor(String videoId) {
    final e = find(videoId);
    if (e == null) return null;
    return e.fileExists ? e.filePath : null;
  }

  Future<Directory> _cacheDir() async {
    final override = _cacheDirectoryOverride;
    final dir =
        override ??
        Directory(
          p.join((await getApplicationSupportDirectory()).path, 'video_cache'),
        );
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }

  /// 清掉所有 `.part` 残留（应用被杀时留下的）。
  Future<void> cleanTempFiles() async {
    try {
      final dir = await _cacheDir();
      await for (final f in dir.list()) {
        if (f is File && f.path.endsWith('.part')) {
          await f.delete();
        }
      }
    } catch (_) {
      // 目录还不存在时忽略
    }
  }
}
