import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// 更新失败的原因分类。
///
/// ## 为什么要分这么细
///
/// GitHub 的 REST 接口用「状态码 + 响应体提示」表达失败，而**同一个状态码
/// 可能有两种完全不同的含义**：
///
/// - `403` 既可能是**请求频率超限**（等几分钟就好），也可能是
///   **权限不足**（仓库私有、Artifact 过期，重试一百次也没用）
/// - `401` 是 Token 失效（本项目不用 Token，但保留分类以免误判）
/// - `404` / `410` 是**根本没有可用版本**（Release 被删、Artifact 过期）
///
/// 以前一律报「检查失败：网络不通」，用户根本不知道该等还是该做别的。
/// 实测本机访问 `api.github.com` 会直接返回 403，如果 UI 只说"网络不通"，
/// 排查方向就完全错了。
enum UpdateFailure {
  /// 网络层失败（DNS、超时、连接被重置）。可以重试。
  network,

  /// 触发 GitHub 限流（429，或 403 且响应体含 rate limit 字样）。
  /// **等一段时间再试**，重试只会更糟。
  rateLimited,

  /// 认证失败（401）。
  badCredentials,

  /// 权限不足（403 但不含限流字样）。重试无用。
  forbidden,

  /// 没有可用版本（404/410，或接口正常但没有任何 release）。
  noRelease,

  /// 服务端错误（5xx）。
  server,
}

/// 带分类的更新异常。
///
/// [message] 是**可以直接展示给用户**的中文文案；[detail] 留给诊断面板。
class UpdateException implements Exception {
  const UpdateException(this.failure, {this.message = '', this.detail = ''});

  final UpdateFailure failure;
  final String message;
  final String detail;

  /// 用户能看懂的一句话。
  String get userMessage => message.isNotEmpty ? message : switch (failure) {
        UpdateFailure.network => '网络不通，稍后再试',
        UpdateFailure.rateLimited => 'GitHub 限流了，等几分钟再试',
        UpdateFailure.badCredentials => '授权失效，请重新安装正式版',
        UpdateFailure.forbidden => '没有访问权限（可能是仓库状态变化）',
        UpdateFailure.noRelease => '暂时没有可用版本',
        UpdateFailure.server => 'GitHub 服务异常，稍后再试',
      };

  @override
  String toString() => 'UpdateException($failure, $userMessage)';
}

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
  /// 成功返回 [UpdateInfo]，**没有任何可用 release 时返回 null**（这与
  /// "失败" 是两件事，调用方要能区分）；真正的失败抛 [UpdateException]。
  ///
  /// 注意 `validateStatus` 放行 <500，是为了让 4xx 也能拿到响应体——
  /// GitHub 把限流提示写在响应体里（`API rate limit exceeded`），
  /// 不放行的话 Dio 会直接抛异常，那段提示就读不到了。
  Future<UpdateInfo?> checkLatest() async {
    final Response<List<dynamic>> res;
    try {
      res = await _dio.get<List<dynamic>>('$_apiBase/releases');
    } on DioException catch (e) {
      throw UpdateException(
        UpdateFailure.network,
        detail: '${e.type.name}: ${e.message}',
      );
    } catch (e) {
      throw UpdateException(UpdateFailure.network, detail: '$e');
    }

    final code = res.statusCode ?? 0;
    if (code != 200) {
      throw _classify(code, res.data?.toString() ?? res.statusMessage ?? '');
    }

    final list = res.data;
    if (list == null) {
      throw const UpdateException(
        UpdateFailure.server,
        detail: 'GitHub 返回了空响应体',
      );
    }

    for (final raw in list) {
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

    // 接口正常但没有任何可用的 release（全是草稿/预发布/没有 apk）。
    return null;
  }

  /// 把 HTTP 状态码 + 响应体分类成可展示的失败原因。
  ///
  /// 关键在 **403 的两种含义**：响应体里出现限流字样就是被限流（等一会儿
  /// 就好），否则就是权限问题（重试无用）。GitHub 的限流提示固定是
  /// `API rate limit exceeded for ...`，还有二级限流的
  /// `secondary rate limit`。
  static UpdateException _classify(int code, String body) {
    final text = body.toLowerCase();
    const rateHints = [
      'rate limit',
      'secondary rate',
      'too many requests',
      'abuse detection',
    ];
    final looksRateLimited = rateHints.any(text.contains);

    final failure = switch (code) {
      429 => UpdateFailure.rateLimited,
      401 => UpdateFailure.badCredentials,
      // 403 两种含义，靠响应体区分
      403 => looksRateLimited ? UpdateFailure.rateLimited : UpdateFailure.forbidden,
      404 || 410 => UpdateFailure.noRelease,
      >= 500 => UpdateFailure.server,
      _ => UpdateFailure.server,
    };

    return UpdateException(
      failure,
      // 诊断信息里保留响应体片段，方便排查。截断避免刷屏。
      detail: 'HTTP $code${body.isEmpty ? '' : '  ${body.length > 200 ? '${body.substring(0, 200)}…' : body}'}',
    );
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
  ///
  /// 失败抛 [UpdateException]。**必须先看 `isSuccessful` 再落盘**——
  /// 曾经的做法是只要文件写完就当成功，结果 403 的错误 JSON
  /// （`{"message":"..."}`）会被原样写成 `update.apk`，用户看到的是
  /// 「安装失败」，真正的原因在第一步就被丢掉了。
  Future<String> download(
    UpdateInfo info, {
    void Function(double progress)? onProgress,
    CancelToken? cancelToken,
  }) async {
    final dir = await getTemporaryDirectory();
    final path = p.join(dir.path, 'update_${info.version}.apk');
    final file = File(path);
    // 旧的重下：一半的包没法验证，留着只会占地方
    if (await file.exists()) await file.delete();

    final Response<dynamic> res;
    try {
      res = await _dio.download(
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
    } on DioException catch (e) {
      await _tryDelete(file);
      if (CancelToken.isCancel(e)) rethrow;
      throw UpdateException(
        UpdateFailure.network,
        detail: '${e.type.name}: ${e.message}',
      );
    } catch (e) {
      await _tryDelete(file);
      throw UpdateException(UpdateFailure.network, detail: '$e');
    }

    final code = res.statusCode ?? 0;
    if (code < 200 || code >= 300) {
      // 错误响应体已经被写进文件了，删掉再说
      final errText = await _tryReadThenDelete(file);
      throw _classify(code, errText);
    }

    // 服务器给了 size 就校验一下，防止半截包被当成好包
    if (info.sizeBytes > 0) {
      final size = await file.length();
      if (size != info.sizeBytes) {
        await _tryDelete(file);
        throw UpdateException(
          UpdateFailure.network,
          detail: '文件不完整：期望 ${info.sizeBytes}，实际 $size',
        );
      }
    }
    return path;
  }

  static Future<void> _tryDelete(File f) async {
    try {
      if (await f.exists()) await f.delete();
    } catch (_) {}
  }

  /// 读出错误响应体（用于分类），再把文件删掉。
  static Future<String> _tryReadThenDelete(File f) async {
    String text = '';
    try {
      if (await f.exists()) {
        final raw = await f.readAsBytes();
        // 只要前面一小段就够了，避免把一个几百 MB 的包读进内存
        final take = raw.length > 512 ? raw.sublist(0, 512) : raw;
        text = String.fromCharCodes(take.where((b) => b >= 32 && b < 127));
        await f.delete();
      }
    } catch (_) {}
    return text;
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
