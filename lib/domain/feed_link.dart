/// 索引页（`/categories/`、`/tags/`、`/models/`）里的一个跳转项。
///
/// 放在 domain 层是因为它同时被解析层产出、被 UI 消费，
/// 放在任一实现文件里都会造成循环依赖。
class FeedLink {
  const FeedLink({
    required this.slug,
    required this.label,
    this.thumb = '',
  });

  /// URL 里的标识，如 `3d`、`big-breasts`、`derpixon`。
  final String slug;

  /// 显示名。分类页给的是原始名称（如 `Gankutsuou`），
  /// 标签页给的是 slug 转写（如 `big-breasts` → 展示时再美化）。
  final String label;

  /// 封面图；标签与多数分类没有，实测分类页 36 条里 21 条是 `no image`。
  final String thumb;

  bool get hasThumb => thumb.isNotEmpty;

  @override
  String toString() => 'FeedLink($slug, $label)';
}
