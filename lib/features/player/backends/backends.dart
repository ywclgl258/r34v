import 'package:flutter_riverpod/flutter_riverpod.dart';

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
/// 严格尊重用户选择的引擎。Dart 层目前不能向 libmpv 注入 DoH 解析出的 IP，
/// 因此不再根据网络开关暗中换引擎；DNS 不通时使用 StreamFetcher 或显式代理。
final playerBackendFactoryProvider = Provider<PlayerBackend Function(String)>(
  (ref) => createPlayerBackend,
);

/// 设置页里展示的选项。
const kPlayerEngineOptions = [
  ('exo', 'ExoPlayer（硬件解码 · 默认）'),
  ('mpv', 'libmpv（软解 · 编码兜底）'),
];
