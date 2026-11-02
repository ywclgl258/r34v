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
