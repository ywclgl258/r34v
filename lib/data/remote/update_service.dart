import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// 检查并下载 App 自身的新版本。
///
/// 数据源是 GitHub Releases（仓库 `ywclgl258/r34v`）。每次 CI 构建都会把
/// 签名过的 arm64 APK 传成 release asset，文件名形如 `R34V-v1.10.0.apk`。
///
/// ## 为什么不用 GitHub API 的 `/releases/latest`
///
/// 那个接口有**未认证限流**（60 次/小时/IP）。检查更新是低频操作，但一旦
/// 用户共用出口 IP（校园网、公司网）就容易撞限流，表现为「检查更新失败」，
/// 很难解释。所以走 `/releases` 列表接口自己取第一个非 draft、非
/// prerelease 的，语义等价、限流一样但至少能一次拿多条做判断。
///
/// 请求都带 `Accept: application/vnd.github+json`，不带的话老端点返回格式不同。
class UpdateService {
  UpdateService({Dio? dio})
      : _dio = dio ??
            Dio(BaseOptions(
              connectTimeout: const Duration(seconds: 15),
              receiveTimeout: const Duration(seconds: 30),
              headers: {
                'Accept': 'application/vnd.github+json',
                'User-Agent': 'r34v-updater',
              },
              validateStatus: (s) => s != null && s < 500,
            ));

  final Dio _dio;

  static const String repo = 'ywclgl258/r34v';
  static const String _apiBase = 'https://api.github.com/repos/$repo';

  /// 查询最新版本。
  ///
  /// 返回 null 表示「网络失败或没有可用 release」——调用方要区分
  /// 「已是最新」和「查不到」，所以用 null 而不是抛异常。
  Future<UpdateInfo?> checkLatest() async {
    try {
      final res = await _dio.get<List<dynamic>>('$_apiBase/releases');
      if (res.statusCode != 200 || res.data == null) return null;

      for (final raw in res.data!) {
        if (raw is! Map) continue;
        // draft 是草稿、prerelease 是预发布，都不该推给普通用户
        if (raw['draft'] == true || raw['prerelease'] == true) continue;

        final tag = (raw['tag_name'] as String? ?? '').trim();
        if (tag.isEmpty) continue;

        final version = _normalizeVersion(tag);
        if (version == null) continue;

        String? url;
        int bytes = 0;
        final assets = raw['assets'];
        if (assets is List) {
          for (final a in assets) {
            if (a is! Map) continue;
            final name = (a['name'] as String? ?? '').toLowerCase();
            if (!name.endsWith('.apk')) continue;
            url = (a['browser_download_url'] as String? ?? '').trim();
            bytes = (a['size'] as num?)?.toInt() ?? 0;
            break;
          }
        }
        if (url == null || url.isEmpty) continue;

        return UpdateInfo(
          version: version,
          tag: tag,
          apkUrl: url,
          sizeBytes: bytes,
          notes: (raw['body'] as String? ?? '').trim(),
          publishedAt: DateTime.tryParse(raw['published_at'] as String? ?? ''),
        );
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  /// 当前安装包的版本名（来自 pubspec 的 `version:` 前半段）。
  Future<String> currentVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      return info.version;
    } catch (_) {
      return '0.0.0';
    }
  }

  /// 下载 APK 到应用缓存目录，返回本地路径。
  ///
  /// [onProgress] 拿到 0..1 的比例（总量未知时传 -1）。
  Future<String?> download(
    UpdateInfo info, {
    void Function(double progress)? onProgress,
    CancelToken? cancelToken,
  }) async {
    try {
      final dir = await getTemporaryDirectory();
      final path = p.join(dir.path, 'update_${info.version}.apk');
      final file = File(path);
      // 旧的重下：一半的包没法验证，留着只会占地方
      if (await file.exists()) await file.delete();

      final res = await _dio.download(
        info.apkUrl,
        path,
        cancelToken: cancelToken,
        deleteOnError: true,
        onReceiveProgress: (received, total) {
          if (total <= 0) {
            onProgress?.call(-1);
          } else {
            onProgress?.call((received / total).clamp(0.0, 1.0));
          }
        },
      );
      if (res.statusCode != 200) {
        await file.delete().catchError((_) => file);
        return null;
      }

      // 服务器给了 size 就校验一下，防止半截包被当成好包
      if (info.sizeBytes > 0) {
        final size = await file.length();
        if (size != info.sizeBytes) {
          await file.delete().catchError((_) => file);
          return null;
        }
      }
      return path;
    } catch (_) {
      return null;
    }
  }

  /// 把 `v1.10.0` / `1.10.0` 统一成 `1.10.0`；无法解析返回 null。
  static String? _normalizeVersion(String tag) {
    final m = RegExp(r'(\d+)\.(\d+)\.(\d+)').firstMatch(tag);
    if (m == null) return null;
    return '${m.group(1)}.${m.group(2)}.${m.group(3)}';
  }

  /// [a] 是否比 [b] 新。逐段比较数字，不用字符串比较
  /// （否则 `1.9.0` 会被判成比 `1.10.0` 新）。
  static bool isNewer(String a, String b) {
    final pa = _parts(a);
    final pb = _parts(b);
    for (var i = 0; i < 3; i++) {
      final x = i < pa.length ? pa[i] : 0;
      final y = i < pb.length ? pb[i] : 0;
      if (x != y) return x > y;
    }
    return false;
  }

  static List<int> _parts(String v) {
    final m = RegExp(r'(\d+)\.(\d+)\.(\d+)').firstMatch(v);
    if (m == null) return const [0, 0, 0];
    return [
      int.tryParse(m.group(1)!) ?? 0,
      int.tryParse(m.group(2)!) ?? 0,
      int.tryParse(m.group(3)!) ?? 0,
    ];
  }

  /// 是否支持「下载后自动安装」。
  ///
  /// 只有 Android 能装 APK。其它平台给个下载链接就好。
  static bool get canInstall => !kIsWeb && Platform.isAndroid;
}

class UpdateInfo {
  const UpdateInfo({
    required this.version,
    required this.tag,
    required this.apkUrl,
    required this.sizeBytes,
    required this.notes,
    this.publishedAt,
  });

  final String version;
  final String tag;
  final String apkUrl;
  final int sizeBytes;
  final String notes;
  final DateTime? publishedAt;

  String get sizeLabel =>
      sizeBytes > 0 ? '${(sizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB' : '';
}
