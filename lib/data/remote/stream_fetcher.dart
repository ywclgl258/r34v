import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../core/constants.dart';
import 'dio_proxy.dart';

/// 把远端视频「边下边播」到本地文件。
///
/// **为什么需要这一层，而不是直接把直链交给播放器：**
///
/// media_kit 底层是 libmpv，它的 HTTP 走 ffmpeg 自己实现的协议栈，
/// 用的是应用进程的 socket，**不会经过 Android 系统的 VPN / 代理路由**。
/// 结果就是：列表、详情这些 Dart 网络请求正常（走 Dart 的 HttpClient，能被代理解析），
/// 但视频流一播就 `Failed to open`——这正是真机上「挂了代理也播不了」的原因。
///
/// 解决办法：用 Dart 的 HTTP 客户端（和抓页面同一条通道，代理对它是生效的）
/// 把视频**下载成本地文件**，再把本地文件路径交给播放器。
/// libmpv 读本地文件不涉及网络，问题消失。
///
/// 顺带解决两件事：
/// - 直链 `v-acctoken` 过期不再影响播放（文件已经在本地）；
/// - 进度条可以立刻拖动，不用等缓冲。
///
/// 站点视频的 `moov` 盒在文件开头（实测偏移 36），所以边下边播是可行的。
class StreamFetcher {
  StreamFetcher({String proxy = ''}) {
    applyProxyToDio(_dio, proxy);
  }

  /// 正在进行的下载任务，按 videoId 索引，避免同一视频重复下载。
  final Map<String, _FetchTask> _tasks = {};

  final Dio _dio = Dio(BaseOptions(
    connectTimeout: const Duration(seconds: 20),
    // 逐块读，所以超时按「单块间隔」算而不是整个下载时长
    receiveTimeout: const Duration(seconds: 30),
    headers: {
      'User-Agent': Site.userAgent,
      'Referer': '${Site.base}/',
      'Accept': '*/*',
      // 明确要求不压缩：视频本来就不该被 gzip，且 Range 与压缩不能共存
      'Accept-Encoding': 'identity',
    },
  ));

  /// 开始把一个视频抓到本地。返回本地文件路径（文件会在下载过程中逐步变大）。
  ///
  /// [onReady] 在头部数据到手、可以开始播放时调用一次。
  /// [onProgress] 下载进度；[onDone] 全部完成。
  Future<StreamFetchHandle> fetch({
    required String videoId,
    required String url,
    required String quality,
    void Function(int received, int total)? onProgress,
    void Function(String filePath)? onReady,
    void Function(String? error)? onDone,
  }) async {
    // 已经有同一视频的任务，直接复用
    final existing = _tasks[videoId];
    if (existing != null && !existing.cancelled) {
      return existing.handle;
    }

    final dir = await _streamDir();
    final file = File(p.join(dir.path, '${videoId}_$quality.partial'));
    // 每次播放重新抓，避免用到上一版残缺文件
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
      file: file,
      onProgress: onProgress,
      onReady: onReady,
      onDone: onDone,
    ));

    return handle;
  }

  Future<void> _run({
    required _FetchTask task,
    required String url,
    required File file,
    void Function(int received, int total)? onProgress,
    void Function(String filePath)? onReady,
    void Function(String? error)? onDone,
  }) async {
    RandomAccessFile? raf;
    try {
      final res = await _dio.get<ResponseBody>(
        url,
        options: Options(responseType: ResponseType.stream),
        cancelToken: task.cancelToken,
      );

      final body = res.data;
      if (body == null) throw HttpException('响应为空');

      final total = int.tryParse(res.headers.value('content-length') ?? '') ?? 0;
      raf = await file.open(mode: FileMode.writeOnlyAppend);

      var received = 0;
      var notifiedReady = false;

      // 攒够这么多字节再通知可以播放：太少的话播放器可能还没拿到 moov 就报错
      const readyThreshold = 512 * 1024;

      await for (final chunk in body.stream) {
        if (task.cancelled) break;
        await raf.writeFrom(chunk);
        received += chunk.length;
        onProgress?.call(received, total);

        if (!notifiedReady && received >= readyThreshold) {
          notifiedReady = true;
          task.handle._ready = true;
          onReady?.call(file.path);
        }
        // 数据量很小（小于阈值）的视频，等收完再通知
      }

      await raf.flush();
      await raf.close();
      raf = null;

      if (!notifiedReady) {
        task.handle._ready = true;
        onReady?.call(file.path);
      }

      // 有 Content-Length 时校验完整性
      if (total > 0 && received != total) {
        throw HttpException('下载不完整：$received / $total');
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
      try {
        await raf?.close();
      } catch (_) {}
      _tasks.remove(task.handle.videoId);
    }
  }

  /// 取消某个视频的抓取并删除临时文件。
  Future<void> cancel(String videoId) async {
    final t = _tasks.remove(videoId);
    if (t != null) {
      t.cancelled = true;
      t.cancelToken.cancel('用户取消');
    }
    final path = t?.handle.filePath;
    if (path != null) {
      final f = File(path);
      if (await f.exists()) {
        try {
          await f.delete();
        } catch (_) {}
      }
    }
  }

  /// 清理所有临时抓取文件（应用启动时调一次）。
  Future<void> cleanAll() async {
    try {
      final dir = await _streamDir();
      await for (final e in dir.list()) {
        if (e is File && e.path.endsWith('.partial')) {
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
}

class CancelException {
  static bool isCancel(DioException e) =>
      e.type == DioExceptionType.cancel || CancelToken.isCancel(e);
}
