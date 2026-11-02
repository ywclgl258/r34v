import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../core/constants.dart';
import 'dio_proxy.dart';
import 'doh_connector.dart';

/// 把远端视频「边下边播」到本地文件。
///
/// **为什么走这条通道**：media_kit 底层 libmpv 对单个 mp4 只开一条
/// HTTP 连接，远端单连接限速时（跨洋 + 代理很常见）那就是速度天花板。
/// 这里用**多线程分段下载**（服务端已实测支持 Range/206）：
///
/// - N 个 worker 并行下载各分段到独立的临时文件（`.c0/.c1/…`）；
/// - 另一个「组装」协程严格按 0,1,2… 顺序把分段追加进播放用的
///   `.partial` 文件。**追加必须有序**——播放器读的是持续增长文件，
///   乱序写入会让它读到空洞；
/// - 播放起点不变：`.partial` 攒够 512KB（含 moov）就通知起播，
///   之后的缓冲填充速度 ≈ N × 单连接速度。
///
/// 这就是 IDM/aria2 的多线程下载思路，套在「边下边播」外面。
///
/// 直链 token 会过期：分段下载中遇到 403 会通过 [fetch] 的 refreshUrl
/// 回调换新链续传。
class StreamFetcher {
  StreamFetcher({String proxy = '', Dio? dio, DnsOverHttpsConnector? connector})
      : _dio = dio ??
            Dio(BaseOptions(
              connectTimeout: const Duration(seconds: 20),
              receiveTimeout: const Duration(seconds: 60),
              headers: {
                'User-Agent': Site.userAgent,
                'Referer': '${Site.base}/',
                'Accept': '*/*',
                'Accept-Encoding': 'identity',
              },
            )) {
    applyProxyToDio(_dio, proxy, connector: connector);
  }

  late final Dio _dio;

  /// 正在进行的下载任务，按 videoId 索引，避免同一视频重复下载。
  final Map<String, _FetchTask> _tasks = {};

  /// 分段大小：文件大的分段大些，最多 4MB，至少 512KB。
  static const int _minChunk = 512 * 1024;
  static const int _maxChunk = 4 * 1024 * 1024;

  /// 并行连接数的默认上限与下限。
  ///
  /// 曾经写死 6。放到可配置里是因为「更多连接 = 更快」并不总成立：
  /// 站点对总并发也有感知，开到 8 反而更容易被限流。默认 4 是
  /// 「明显快于单连接、又不容易触发风控」的平衡点。
  static const int _maxWorkers = 4;
  static const int _hardWorkerCap = 8;

  /// 攒够这么多字节再通知可以播放：太少的话播放器可能还没拿到 moov 就报错。
  static const int _readyThreshold = 512 * 1024;

  /// 开始把一个视频抓到本地。返回本地文件路径（文件会在下载过程中逐步变大）。
  ///
  /// [refreshUrl] 在直链 403（token 过期）时调用，返回新链继续下载。
  /// [onReady] 在头部数据到手、可以开始播放时调用一次。
  Future<StreamFetchHandle> fetch({
    required String videoId,
    required String url,
    required String quality,
    Future<String> Function()? refreshUrl,
    int? chunkSizeOverride,
    int? workers,
    void Function(int received, int total)? onProgress,
    void Function(String filePath)? onReady,
    void Function(String? error)? onDone,
  }) async {
    final existing = _tasks[videoId];
    if (existing != null && !existing.cancelled) {
      return existing.handle;
    }

    final dir = await _streamDir();
    final file = File(p.join(dir.path, '${videoId}_$quality.partial'));
    if (await file.exists()) {
      try {
        await file.delete();
      } catch (_) {}
    }
    await file.create(recursive: true);

    final handle = StreamFetchHandle(file.path, videoId);
    final task = _FetchTask(handle);
    _tasks[videoId] = task;

    unawaited(_run(
      task: task,
      url: url,
      refreshUrl: refreshUrl,
      chunkSizeOverride: chunkSizeOverride,
      workers: workers,
      onProgress: onProgress,
      onReady: onReady,
      onDone: onDone,
    ));

    return handle;
  }

  /// 探测文件总大小。支持 Range 返回 206 + Content-Range，不支持返回 null。
  Future<int?> _probeTotal(String url) async {
    try {
      final res = await _dio.get<void>(
        url,
        options: Options(
          headers: {'Range': 'bytes=0-0'},
          validateStatus: (s) => s != null && s < 500,
        ),
      );
      if (res.statusCode != 206) return null;
      final cr = res.headers.value('content-range') ?? '';
      final m = RegExp(r'/(\d+)\s*$').firstMatch(cr);
      return int.tryParse(m?.group(1) ?? '');
    } catch (_) {
      return null;
    }
  }

  Future<void> _run({
    required _FetchTask task,
    required String url,
    Future<String> Function()? refreshUrl,
    int? chunkSizeOverride,
    int? workers,
    void Function(int received, int total)? onProgress,
    void Function(String filePath)? onReady,
    void Function(String? error)? onDone,
  }) async {
    final file = File(task.handle.filePath);
    try {
      final total = await _probeTotal(url);
      if (total == null || total <= 0) {
        // 服务端不支持 Range：退回单连接顺序下载（老路，仍可用）
        await _runSequential(task, url, total ?? 0, onProgress, onReady, onDone);
        return;
      }

      // 分段大小可注入（测试用小分段验证组装逻辑；生产按文件大小算）
      final chunkSize = chunkSizeOverride ??
          (total / 16).round().clamp(_minChunk, _maxChunk);
      final chunkCount = (total + chunkSize - 1) ~/ chunkSize;

      final chunkFiles = List<File?>.filled(chunkCount, null);
      final chunkDone = List<Completer<void>>.generate(
        chunkCount,
        (_) => Completer<void>(),
      );
      // 取消时要唤醒卡在 await 分段上的组装协程，否则 raf 不关闭、
      // Windows 下文件句柄占着删不掉，任务也永远泄漏。
      task.pendingCompleters = chunkDone;
      var nextChunk = 0;
      // 所有 worker 共享的当前链（403 换新后一起用）
      var currentUrl = url;
      var refreshing = false;

      Future<void> downloadChunk(int i) async {
        final start = i * chunkSize;
        final end =
            (start + chunkSize - 1).clamp(0, total - 1);
        final partFile =
            File('${file.path}.c$i');

        Object? lastErr;
        for (var attempt = 0; attempt < 3; attempt++) {
          if (task.cancelled) return;
          try {
            final res = await _dio.get<ResponseBody>(
              currentUrl,
              options: Options(
                responseType: ResponseType.stream,
                headers: {'Range': 'bytes=$start-$end'},
                receiveTimeout: const Duration(minutes: 5),
              ),
              cancelToken: task.cancelToken,
            );
            if (res.statusCode != 206 && res.statusCode != 200) {
              // 401/403/410 视为 token 过期，和 Dio 异常走同一条换新路
              if (res.statusCode == 401 ||
                  res.statusCode == 403 ||
                  res.statusCode == 410) {
                throw HttpException('__token__${res.statusCode}');
              }
              throw HttpException('分段 $i HTTP ${res.statusCode}');
            }
            final sink = await partFile.open(mode: FileMode.writeOnly);
            var got = 0;
            await for (final chunk in res.data!.stream) {
              if (task.cancelled) break;
              await sink.writeFrom(chunk);
              got += chunk.length;
            }
            await sink.close();
            if (task.cancelled) return;
            final want = end - start + 1;
            if (got != want) {
              throw HttpException('分段 $i 不完整：$got / $want');
            }
            chunkFiles[i] = partFile;
            return;
          } catch (e) {
            lastErr = e;
            // token 过期（HTTP 401/403/410，或 Dio 收到的等价状态）：换新链重试
            final isTokenErr = (e is DioException &&
                    (e.response?.statusCode == 403 ||
                        e.response?.statusCode == 401 ||
                        e.response?.statusCode == 410)) ||
                (e is HttpException &&
                    e.message.startsWith('__token__'));
            if (isTokenErr && refreshUrl != null) {
              try {
                if (!refreshing) {
                  refreshing = true;
                  currentUrl = await refreshUrl();
                }
              } catch (_) {}
              refreshing = false;
            }
          }
        }
        throw HttpException('分段 $i 下载失败：$lastErr');
      }

      final requested = (workers ?? _maxWorkers).clamp(1, _hardWorkerCap);
      final workerCount = chunkCount < requested ? chunkCount : requested;
      final workersList = <Future<void>>[
        for (var w = 0; w < workerCount; w++)
          () async {
            while (true) {
              final i = nextChunk++;
              if (i >= chunkCount) return;
              try {
                await downloadChunk(i);
                if (!chunkDone[i].isCompleted) chunkDone[i].complete();
              } catch (e) {
                if (!chunkDone[i].isCompleted) {
                  chunkDone[i].completeError(e);
                }
              }
            }
          }(),
      ];

      // 组装：严格按序追加，保证 .partial 只增长不出洞
      final raf = await file.open(mode: FileMode.writeOnlyAppend);
      var appended = 0;
      var notifiedReady = false;
      Object? assembleErr;
      try {
        for (var i = 0; i < chunkCount; i++) {
          await chunkDone[i].future;
          // 取消是正常完成（不是 error）唤醒的，在这查标志退出
          if (task.cancelled) throw const HttpException('已取消');
          final f = chunkFiles[i]!;
          await _appendAll(raf, f);
          appended += await f.length();
          try {
            await f.delete();
          } catch (_) {}
          onProgress?.call(appended, total);

          if (!notifiedReady && appended >= _readyThreshold) {
            notifiedReady = true;
            task.handle._ready = true;
            onReady?.call(file.path);
          }
        }
      } catch (e) {
        assembleErr = e;
      }
      await raf.flush();
      await raf.close();

      // 等所有 worker 退出（它们在已失败的组装后仍可能跑完手头的分段）
      await Future.wait(workersList).catchError((_) => <void>[]);

      if (task.cancelled) {
        // 连 .partial 一起删掉（此时 raf 已关闭，句柄不再占着文件）
        try {
          await file.delete();
        } catch (_) {}
        onDone?.call('已取消');
        return;
      }
      if (assembleErr != null) throw assembleErr;
      if (appended != total) {
        throw HttpException('下载不完整：$appended / $total');
      }

      if (!notifiedReady) {
        task.handle._ready = true;
        onReady?.call(file.path);
      }
      task.handle._complete = true;
      onDone?.call(null);
    } on DioException catch (e) {
      if (CancelException.isCancel(e)) {
        onDone?.call('已取消');
      } else {
        onDone?.call('下载失败：${e.message}');
      }
    } catch (e) {
      onDone?.call('$e');
    } finally {
      _deleteChunks(file.path);
      _tasks.remove(task.handle.videoId);
    }
  }

  Future<void> _appendAll(RandomAccessFile raf, File src) async {
    final inRaf = await src.open(mode: FileMode.read);
    try {
      const bufSize = 256 * 1024;
      while (true) {
        final buf = await inRaf.read(bufSize);
        if (buf.isEmpty) break;
        await raf.writeFrom(buf);
      }
    } finally {
      await inRaf.close();
    }
  }

  /// 服务端不支持 Range 时的单连接顺序下载（旧路径兜底）。
  Future<void> _runSequential(
    _FetchTask task,
    String url,
    int total,
    void Function(int received, int total)? onProgress,
    void Function(String filePath)? onReady,
    void Function(String? error)? onDone,
  ) async {
    final file = File(task.handle.filePath);
    RandomAccessFile? raf;
    try {
      final res = await _dio.get<ResponseBody>(
        url,
        options: Options(responseType: ResponseType.stream),
        cancelToken: task.cancelToken,
      );
      final body = res.data;
      if (body == null) throw HttpException('响应为空');
      final len = total > 0
          ? total
          : int.tryParse(res.headers.value('content-length') ?? '') ?? 0;

      raf = await file.open(mode: FileMode.writeOnlyAppend);
      var received = 0;
      var notifiedReady = false;
      await for (final chunk in body.stream) {
        if (task.cancelled) break;
        await raf.writeFrom(chunk);
        received += chunk.length;
        onProgress?.call(received, len);
        if (!notifiedReady && received >= _readyThreshold) {
          notifiedReady = true;
          task.handle._ready = true;
          onReady?.call(file.path);
        }
      }
      await raf.flush();
      await raf.close();
      raf = null;

      if (!notifiedReady) {
        task.handle._ready = true;
        onReady?.call(file.path);
      }
      if (len > 0 && received != len) {
        throw HttpException('下载不完整：$received / $len');
      }
      task.handle._complete = true;
      onDone?.call(null);
    } on DioException catch (e) {
      onDone?.call(
        CancelException.isCancel(e) ? '已取消' : '下载失败：${e.message}',
      );
    } catch (e) {
      onDone?.call('$e');
    } finally {
      try {
        await raf?.close();
      } catch (_) {}
    }
  }

  void _deleteChunks(String partialPath) {
    try {
      final dir = File(partialPath).parent;
      final base = p.basename(partialPath);
      for (final e in dir.listSync()) {
        if (e is File && p.basename(e.path).startsWith('$base.c')) {
          try {
            e.deleteSync();
          } catch (_) {}
        }
      }
    } catch (_) {}
  }

  /// 取消某个视频的抓取。临时文件由 _run 的清理逻辑删
  ///（它要等文件句柄关闭，直接在这删 Windows 上会失败）。
  Future<void> cancel(String videoId) async {
    final t = _tasks[videoId];
    if (t == null) return;
    t.cancelled = true;
    t.cancelToken.cancel('用户取消');
    // 用正常完成（不用 completeError——后续分段的 future 可能还没人
    // await，error 版会变成未捕获异步异常）。组装协程醒来后自己查
    // cancelled 标志退出。
    for (final c in t.pendingCompleters ?? const <Completer<void>>[]) {
      if (!c.isCompleted) {
        c.complete();
      }
    }
  }

  /// 清理所有临时抓取文件（应用启动时调一次）。
  Future<void> cleanAll() async {
    try {
      final dir = await _streamDir();
      await for (final e in dir.list()) {
        if (e is File && (e.path.endsWith('.partial'))) {
          try {
            await e.delete();
          } catch (_) {}
        }
      }
    } catch (_) {}
  }

  Future<Directory> _streamDir() async {
    final base = await getApplicationCacheDirectory();
    final dir = Directory(p.join(base.path, 'streams'));
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }
}

/// 一次抓取的句柄。
class StreamFetchHandle {
  StreamFetchHandle(this.filePath, this.videoId);

  final String filePath;
  final String videoId;

  bool _ready = false;
  bool _complete = false;

  /// 已经可以交给播放器了（头部数据够了）。
  bool get ready => _ready;

  /// 全部下完了。
  bool get complete => _complete;
}

class _FetchTask {
  _FetchTask(this.handle);

  final StreamFetchHandle handle;
  final CancelToken cancelToken = CancelToken();
  bool cancelled = false;

  /// 组装协程正在等待的分段完成器。取消时用错误完成它们，
  /// 让组装协程醒来关闭文件句柄、走清理流程。
  List<Completer<void>>? pendingCompleters;
}

class CancelException {
  static bool isCancel(DioException e) =>
      e.type == DioExceptionType.cancel || CancelToken.isCancel(e);
}

