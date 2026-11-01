import 'exo_backend.dart';
import 'mpv_backend.dart';
import 'player_backend.dart';

export 'exo_backend.dart';
export 'mpv_backend.dart';
export 'player_backend.dart';

/// 按引擎名创建播放器后端。
///
/// 引擎来自设置（LibraryStore.playerEngine），在播放页 initState 时读取，
/// 所以切换引擎对**下一个**播放生效。
PlayerBackend createPlayerBackend(String engine) => switch (engine) {
      'mpv' => MpvBackend(),
      _ => ExoBackend(),
    };

/// 设置页里展示的选项。
const kPlayerEngineOptions = [
  ('exo', 'ExoPlayer（默认 · 硬件解码）'),
  ('mpv', 'libmpv（软解兜底）'),
];
