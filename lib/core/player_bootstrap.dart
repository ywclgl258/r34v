import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';

import 'player_platform_io.dart'
    if (dart.library.js_interop) 'player_platform_web.dart';

/// 原生播放器的启动配置。
///
/// **为什么需要这个文件**（这是真机上「挂了代理还是播不了」的根因）：
///
/// media_kit 底层是 libmpv，它的 HTTPS 由 ffmpeg 自己的 TLS 栈处理，有两条硬性要求：
///
/// 1. **必须有 CA 证书包**。APK 里默认没有，ffmpeg 无法验证服务器证书，
///    握手直接失败，报 `Failed to open <url>`。这和网络是否通畅无关——
///    所以「挂代理」无效，流量本来就已经出去了，卡在 TLS 验证。
/// 2. **不认系统代理设置**。ffmpeg 用自己的 socket，不看 Android 的 VPN/代理配置，
///    也不读 HTTP_PROXY 环境变量。要让它走代理必须显式设置 mpv 的 `http-proxy`。
///
/// 参照 Han1mePlus 的做法：安卓上它默认用 ExoPlayer（走系统网络栈，代理天然生效），
/// libmpv 只在鸿蒙或用户手动切换时启用，还要用户自己填 mpv 参数。
/// 我们保留 libmpv（格式兼容性更好），把这两件事在启动时配好。
class PlayerBootstrap {
  const PlayerBootstrap._();

  static bool _initDone = false;

  /// 待应用的 mpv 属性。用 Map 而不是直接设置，是因为属性必须在
  /// 每个 Player 实例创建后才能下发。
  static final Map<String, String> _props = {};

  /// 应用启动时调一次。
  static Future<void> init() async {
    MediaKit.ensureInitialized();
    if (kIsWeb) return;
    if (_initDone) return;
    _initDone = true;

    try {
      final data = await rootBundle.load('assets/certs/cacert.pem');
      final path = await prepareCaBundle(data.buffer.asUint8List());
      if (path != null) {
        _props['tls-ca-file'] = path;
        debugPrint('CA 证书已准备：$path');
      }
    } catch (e) {
      debugPrint('CA 证书准备失败（https 可能不可用）：$e');
    }
  }

  /// 设置播放器代理。
  ///
  /// libmpv 不读系统代理，必须显式告诉它。格式用 curl 那套：
  /// `PROXY 127.0.0.1:7897; DIRECT`（Han1mePlus 也这么传）。
  /// 传空字符串表示取消代理。
  ///
  /// 注意：只用 VPN（透明路由）时通常不用填，VPN 在系统层就把流量接走了。
  /// 用 HTTP 代理软件时才需要。
  static void setProxy(String? hostPort) {
    final v = (hostPort ?? '').trim();
    if (v.isEmpty) {
      _props.remove('http-proxy');
    } else {
      _props['http-proxy'] = 'PROXY $v; DIRECT';
    }
  }

  /// 把已收集的属性应用到某个播放器上。每次 open 之前调。
  static Future<void> configure(Player player) async {
    if (kIsWeb) return;
    if (_props.isEmpty) return;
    await applyPlatformProps(player, Map.of(_props));
  }

  /// 便于测试查看当前设置。
  @visibleForTesting
  static Map<String, String> get debugProps => Map.unmodifiable(_props);
}
