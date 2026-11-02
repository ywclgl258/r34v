/// 播放页里两段值得单独测试的判定逻辑。
///
/// 从 `player_page.dart` 抽出来是因为它们都被真实事故验证过：
/// 一段写错会让站点被反复打（限流），另一段写错会让手势把播放器搞崩。
/// 放在页面里的私有方法只能靠整页 widget 测试间接覆盖，太脆。
library;

/// 倍速下发闸门。
///
/// 横滑调速是逐帧回调，但档位只有 8 档。每一档都该只下发一次：
/// - 每一次下发都要跨 platform channel 打到底层播放器；
/// - 逐帧下发会把主线程和播放器一起拖住，播放看起来就像卡死。
///
/// 同时它保证「回弹到原档位」这一次一定要下发，否则速度回不去。
class RateApplyGate {
  RateApplyGate({required double initial}) : _applied = initial;

  double _applied;

  /// 当前已下发的倍速。
  double get applied => _applied;

  /// 该不该把 [rate] 下发给播放器；该则记账并返回 true。
  bool shouldApply(double rate) {
    if (rate == _applied) return false;
    _applied = rate;
    return true;
  }

  /// 强制下一次同值也下发（例如播放器被重建后需要重新应用）。
  void reset({required double applied}) => _applied = applied;
}

/// 双击落点属于哪个区域。
///
/// 对齐 kt_player 的 `kt_seek.js`（站点官方播放器脚本）：
///
/// ```js
/// var SIDE_LEFT  = 0.40;    // 左边缘到 40% 是「后退」区
/// var SIDE_RIGHT = 0.60;    // 60% 往右是「前进」区
/// ```
///
/// **中间留 20% 安全带是关键。** 之前我们是 `dx < width/2` 的二分，
/// 中央零宽度——手指落在正中间稍微偏一点就误触发快进或后退。
/// 官方给中央留了一条不接管双击的地带，交给播放器自己做「双击全屏」。
enum DoubleTapZone { back, center, forward }

const double kSideLeft = 0.40;
const double kSideRight = 0.60;

DoubleTapZone doubleTapZoneFor(double dx, double width) {
  if (width <= 0) return DoubleTapZone.center;
  final rel = dx / width;
  if (rel < kSideLeft) return DoubleTapZone.back;
  if (rel > kSideRight) return DoubleTapZone.forward;
  return DoubleTapZone.center;
}

/// 双击的跳转秒数（官方 TAP_SKIP = 10）。中央返回 0 表示不 seek。
int seekSecondsForZone(DoubleTapZone zone) => switch (zone) {
      DoubleTapZone.back => -10,
      DoubleTapZone.forward => 10,
      DoubleTapZone.center => 0,
    };

/// 双击判定窗口的参考值。官方 `kt_seek.js` 的 `DOUBLE_MS = 300`。
///
/// 注意：**Flutter 侧的实际仲裁由框架自己做**（`GestureDetector` 同时挂
/// onTap/onDoubleTap 时会自动把单击推迟到双击窗口结束）。这个常量只用于
/// 文档对照和测试，不用来自己实现去抖——试过，会把单击延迟叠成两倍。
const Duration kDoubleTapWindow = Duration(milliseconds: 300);

/// 双击后要不要把播放状态纠正回来。
///
/// 官方 `kt_seek.js` 的 `restorePlay`：
/// - 双击前若是**暂停**，双击后不能自己播起来（用户只是想看看那一段）；
/// - 双击前若是**播放中**，双击可能被系统 click 副作用暂停，要纠正回来。
///
/// 所以返回值是「是否需要 resume」，且**只 resume、从不 pause**。
bool shouldRestorePlaying({
  required bool wasPlaying,
  required bool nowPaused,
}) => wasPlaying && nowPaused;

/// 双击 seek 后延迟多久做状态纠正。
///
/// 官方用 80ms：等原生 click/tap 的副作用落定再纠正，
/// 太早纠正会被后面的 toggle 覆盖掉。
const Duration kRestorePlayingDelay = Duration(milliseconds: 80);

/// 判断错误信息是否真的表示「直链 token 过期」（HTTP 401/403/410）。
///
/// **不能写成 `contains('403')`。**
///
/// 播放器的错误文本经常带数字——位置、字节偏移、缓冲区大小。早期实现
/// 用裸 `contains`，于是 `position 403000` 会被判定成 403 过期，进而
/// 触发 `_recoverStream` **回源重新抓一次详情页**。一次播放里误判几次，
/// 就等于对站点连打好几个详情页请求，这正是「线路被限流」的来源。
///
/// 所以要求状态码以 HTTP 形态出现：前面跟 `http` / `code` / `status`，
/// 后面不接数字（避免从 403000 里抠出 403）。
bool looksLikeTokenExpiry(String err) {
  final lower = err.toLowerCase();
  if (lower.contains('unauthorized') ||
      lower.contains('forbidden') ||
      lower.contains('gone')) {
    return true;
  }
  // 形如：
  //   http 403 / http/1.1 403 / status: 403 / code=403
  //   Server returned 410 / response code 403 / HTTP error 401
  //
  // `returned` 与 `response`/`error` 也放进前缀，是因为 ExoPlayer 的真实
  // 错误文本是 `HttpDataSourceException ... response code: 403`、
  // `Server returned 410` 这类形态。加了前缀仍然安全：末尾的 `(?!\d)`
  // 保证 `returned 403000` 这种不会被误判。
  final re = RegExp(
    r'(?:http(?:/\d(?:\.\d)?)?|code|status|returned|error)'
    r'\s*[:=]?\s*(401|403|410)(?!\d)',
  );
  return re.hasMatch(lower);
}
