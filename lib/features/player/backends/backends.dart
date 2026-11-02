import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/providers.dart';
import 'exo_backend.dart';
import 'mpv_backend.dart';
import 'player_backend.dart';

export 'exo_backend.dart';
export 'mpv_backend.dart';
export 'player_backend.dart';

/// 按引擎名创建播放器后端。
///
/// 只做「名字 → 实例」的映射，不掺业务判断。
PlayerBackend createPlayerBackend(String engine) => switch (engine) {
      'mpv' => MpvBackend(),
      _ => ExoBackend(),
    };

/// 播放器后端工厂。
///
/// ## 为什么直连时要避开 ExoPlayer
///
/// 直连（无代理）时，站点的系统 DNS 解析常被污染，实测会拿到
/// `202.160.128.40` 这种 TCP 完全不通的 IP。此时两个引擎的能力差异是：
///
/// | 引擎 | 能否绕开污染 DNS |
/// |---|---|
/// | libmpv（media_kit） | ✅ 认 `stream-lavf-o` 的 `connect_to`，可精确覆盖 |
/// | ExoPlayer（video_player） | ❌ 用 OkHttp 自己的连接栈，Dart 层无法干预 |
///
/// **ExoPlayer 在污染 DNS 下必然连不上视频**，而它恰好是默认引擎。
/// 所以这里在「没配代理 + 用户没主动改过引擎 + DoH 可用」时自动改用 mpv，
/// 不把用户留在一个必然失败的配置里。
///
/// 三个条件缺一不可：
/// - 有代理时 Exo 完全正常，没必要降级成软解；
/// - 用户显式选过 exo 就尊重其选择（可能他的网络 DNS 本来就没问题）；
/// - DoH 关掉时我们手里没有可注入的 IP，换 mpv 也没有收益。
final playerBackendFactoryProvider = Provider<PlayerBackend Function(String)>(
  (ref) {
    final store = ref.watch(libraryStoreProvider);

    return (String engine) {
      final hasProxy = store.playerProxy.trim().isNotEmpty;
      if (hasProxy) return createPlayerBackend(engine);
      if (engine == 'mpv') return MpvBackend();

      final canInject = store.dohEnabled && store.playerEngineIsDefault;
      return canInject ? MpvBackend() : ExoBackend();
    };
  },
);

/// 设置页里展示的选项。
const kPlayerEngineOptions = [
  ('exo', 'ExoPlayer（硬件解码 · 需代理）'),
  ('mpv', 'libmpv（软解 · 支持直连）'),
];
