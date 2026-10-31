/// 解析失败时抛这个，带诊断信息。
///
/// 站点是第三方 HTML，随时可能改版。解析器不能静默返回空列表——那会让 UI
/// 显示「这里没有视频」而实际是改版了。宁可抛一个带上下文的异常，让 UI 明确
/// 告诉用户「站点结构变了」。
class ParseException implements Exception {
  ParseException(this.message, {this.hint, this.snippet});

  final String message;

  /// 给排查者看的下一步提示。
  final String? hint;

  /// 出错位置附近的 HTML 片段（已截断），便于对照 fixture 定位。
  final String? snippet;

  @override
  String toString() {
    final b = StringBuffer('ParseException: $message');
    if (hint != null) b.write('\n  提示: $hint');
    if (snippet != null) b.write('\n  片段: $snippet');
    return b.toString();
  }
}
