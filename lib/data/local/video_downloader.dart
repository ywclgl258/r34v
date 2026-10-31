import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../core/constants.dart';
import '../remote/dio_proxy.dart';

/// 视频下载。
///
/// 站点详情页每档清晰度都带一个下载直链：
///
/// ```html
/// <a class="tag_item tag_item_download" href="/get_file/…/x_1080p.mp4/?v-acctoken=…&download=true&download_filename=mario_1080p.mp4">
/// ```
///
/// 关键点：
/// - `download=true` 让服务端带 `Content-Disposition`，拿到的是附件而不是流
/// - `download_filename` 给出建议文件名，直接用它命名，不要自己拼
/// - 链接同样带 `v-acctoken`，会过期。所以下载前要**重新抓一次详情页**拿新链，
///   不能用播放时缓存下来的旧链
///
/// 和「缓存」的区别：缓存是应用私有的、为了快速重播；下载是落到
/// 系统的「下载」目录，用户能自己拷出去。两件事分开做，不要复用同一套文件。
class VideoDownloader {
  VideoDownloader({String proxy = ''}) {
    _dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 20),
      receiveTimeout: const Duration(minutes: 30),
      headers: {
        'User-Agent': Site.userAgent,
        'Referer': '${Site.base}/',
        'Accept': '*/*',
        'Accept-Encoding': 'identity',
      },
    ));
    applyProxyToDio(_dio, proxy);
  }

  late final Dio _dio;

  final Map<String, _Task> _tasks = {};

  /// 开始下载。返回落盘路径（下载完成后才最终确定）。
  ///
  /// [onProgress] 收到的是字节数。UI 侧自己节流。
  Future<String> start({
    required String videoId,
    required String url,
    required String suggestedName,
    void Function(int received, int total)? onProgress,
    void Function(String path)? onDone,
    void Function(String error)? onError,
  }) async {
    final existing = _tasks[videoId];
    if (existing != null && !existing.cancelled) {
      return existing.handle.path;
    }

    final dir = await _downloadDir();
    final safeName = _sanitizeFileName(suggestedName, videoId);
    final target = File(p.join(dir.path, safeName));
    final part = File('${target.path}.part');
    if (await part.exists()) {
      try {
        await part.delete();
      } catch (_) {}
    }

    final handle = _Handle(target.path);
    final task = _Task(handle);
    _tasks[videoId] = task;

    unawaitedRun(
      () async {
        RandomAccessFile? raf;
        try {
          final res = await _dio.get<ResponseBody>(
            url,
            options: Options(responseType: ResponseType.stream),
            cancelToken: task.token,
          );
          final body = res.data;
          if (body == null) throw Exception('响应为空');

          final total =
              int.tryParse(res.headers.value('content-length') ?? '') ?? 0;
          raf = await part.open(mode: FileMode.writeOnlyAppend);

          var received = 0;
          await for (final chunk in body.stream) {
            if (task.cancelled) break;
            await raf.writeFrom(chunk);
            received += chunk.length;
            onProgress?.call(received, total);
          }
          await raf.flush();
          await raf.close();
          raf = null;

          if (task.cancelled) {
            if (await part.exists()) await part.delete();
            return;
          }

          if (total > 0 && received != total) {
            throw Exception('下载不完整：$received / $total 字节');
          }

          if (await target.exists()) await target.delete();
          await part.rename(target.path);
          handle.done = true;
          onDone?.call(target.path);
        } catch (e) {
          try {
            await raf?.close();
          } catch (_) {}
          if (await part.exists()) {
            try {
              await part.delete();
            } catch (_) {}
          }
          if (!task.cancelled) onError?.call('$e');
        } finally {
          _tasks.remove(videoId);
        }
      }(),
    );

    return target.path;
  }

  Future<void> cancel(String videoId) async {
    final t = _tasks.remove(videoId);
    if (t == null) return;
    t.cancelled = true;
    t.token.cancel('用户取消');
  }

  /// 建议文件名：优先用站点给的 `download_filename`，没有就用标题。
  String suggestedName({
    required String title,
    required String quality,
    String fileName = '',
  }) {
    final fromServer = fileName.trim();
    if (fromServer.isNotEmpty) return fromServer;
    final safe = title.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_').trim();
    final base = safe.isEmpty ? 'video' : safe;
    final q = quality.replaceAll(RegExp(r'[^0-9a-zA-Z]'), '');
    return '$base${q.isEmpty ? '' : '_$q'}.mp4';
  }

  String _sanitizeFileName(String name, String videoId) {
    var n = name.replaceAll(RegExp(r'[\\/:*?"<>|\x00-\x1F]'), '_').trim();
    if (n.isEmpty) n = videoId;
    if (!n.toLowerCase().endsWith('.mp4')) n = '$n.mp4';
    // 太长的文件名在部分安卓机型上会写失败
    if (n.length > 120) {
      final dot = n.lastIndexOf('.');
      final ext = dot > 0 ? n.substring(dot) : '.mp4';
      n = n.substring(0, 120 - ext.length) + ext;
    }
    return n;
  }

  /// 下载目录。
  ///
  /// 优先 `getDownloadsDirectory()`（桌面/部分安卓），
  /// 拿不到就退回应用私有目录——安卓 10+ 的分区存储不允许随便写公共目录，
  /// 强行写会抛权限异常。私有目录至少保证「能下下来」。
  Future<Directory> _downloadDir() async {
    Directory? dir;
    try {
      dir = await getDownloadsDirectory();
    } catch (_) {}
    if (dir == null) {
      final base = await getApplicationDocumentsDirectory();
      dir = Directory(p.join(base.path, 'downloads'));
    }
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }
}

class _Handle {
  _Handle(this.path);

  final String path;
  bool done = false;
}

class _Task {
  _Task(this.handle);

  final _Handle handle;
  final CancelToken token = CancelToken();
  bool cancelled = false;
}

/// 不等待的异步执行（下载是后台任务）。
void unawaitedRun(Future<void> future) {
  future.catchError((Object _) {});
}
