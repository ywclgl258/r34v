import 'dart:io';

import 'package:flutter/services.dart';

/// 调起系统安装器安装下载好的 APK。
///
/// ## 为什么需要原生侧
///
/// Android 7（API 24）起，`file://` URI 不再允许跨应用传递，必须用
/// `FileProvider` 生成 `content://` 并临时授权。纯 Dart 做不到这件事，
/// 所以走一小段 MethodChannel，由 MainActivity 里几行 Kotlin 完成
/// `Intent.ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`。
///
/// ## 还需要用户自己开一次权限
///
/// 从 Android 8 起，「安装未知来源应用」是按应用授权的。系统安装器会弹
/// 一次询问，用户同意后才会真正安装。这不是我们代码能绕过的，
/// 所以 [install] 失败时要把「去设置里允许」这句话说清楚。
class ApkInstaller {
  const ApkInstaller._();

  static const MethodChannel _channel =
      MethodChannel('r34v/apk_installer');

  /// 安装 [path] 指向的 APK。
  ///
  /// 返回 true 表示安装器已被拉起（不代表用户点了安装）。
  /// 返回 false 表示失败，[reason] 说明原因。
  static Future<({bool ok, String reason})> install(String path) async {
    if (!Platform.isAndroid) {
      return (ok: false, reason: '只有 Android 支持直接安装');
    }
    final file = File(path);
    if (!await file.exists()) {
      return (ok: false, reason: '安装包不存在');
    }
    try {
      final ok = await _channel.invokeMethod<bool>('installApk', {
        'path': path,
      });
      if (ok == true) return (ok: true, reason: '');
      return (ok: false, reason: '系统拒绝了安装请求，请在设置里允许「安装未知应用」');
    } on PlatformException catch (e) {
      return (ok: false, reason: e.message ?? '安装失败');
    } catch (e) {
      return (ok: false, reason: '$e');
    }
  }

  /// 打开系统设置里的「安装未知应用」页（安装失败后的引导）。
  static Future<void> openUnknownSourcesSettings() async {
    try {
      await _channel.invokeMethod<void>('openInstallSettings');
    } catch (_) {}
  }
}
