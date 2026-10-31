import 'dart:io';

import 'package:media_kit/media_kit.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// 原生平台（安卓）的 mpv 属性设置。
///
/// 单独一个文件是因为 Web 端的 `NativePlayer` 没有 `setProperty`，
/// 写在同一个文件里会让 web 编译直接失败（`flutter analyze` 看不出来）。
Future<void> applyPlatformProps(
  Player player,
  Map<String, String> props,
) async {
  final native = player.platform;
  if (native is! NativePlayer) return;
  for (final e in props.entries) {
    try {
      await native.setProperty(e.key, e.value);
    } catch (_) {
      // 单个属性失败不该阻止播放
    }
  }
  try {
    await native.setProperty(
      'user-agent',
      'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 '
          '(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
    );
  } catch (_) {}
}

/// 把 CA 证书包解到真实文件路径（libmpv 读不了 asset://）。
Future<String?> prepareCaBundle(List<int> bytes) async {
  try {
    final dir = await getTemporaryDirectory();
    final f = File(p.join(dir.path, 'cacert.pem'));
    if (!await f.exists() || await f.length() < 1024) {
      await f.writeAsBytes(bytes, flush: true);
    }
    return f.path;
  } catch (_) {
    return null;
  }
}
