/// 排行榜条目。
///
/// 站点 `/top-characters/` 和 `/top-model/` 不是普通列表，而是**带名次的榜单**：
///
/// ```html
/// <li class="cs-row">
///   <span class="cs-row__rk">1</span>            名次
///   <img src="…/336x189/9.jpg">                  缩略图
///   <a href="/tags/aether-genshin-impact/">      链接（角色指向 tags，作者指向 models）
///     Aether                                     显示名
///   </a>
///   <small class="cs-row__t">aether (…)</small>  全名
///   <span class="cs-row__v"><b>6.6M</b>          播放量
///   <em>Favourites</em>26K                       收藏数
///   <em>Videos</em>70                            视频数
/// </li>
/// ```
///
/// 角色榜还有一层：`/top-characters/` 先列作品（genshin-impact 等），
/// 点进去才是该作品的角色榜。作者榜是扁平的。
class RankedEntry {
  const RankedEntry({
    required this.rank,
    required this.slug,
    required this.name,
    required this.fullName,
    required this.thumb,
    required this.viewsText,
    required this.favouritesText,
    required this.videosText,
    required this.viewsRatio,
    this.href = '',
  });

  /// 名次，从 1 开始。
  final int rank;

  /// 链接里的 slug（角色是 `/tags/<slug>/`，作者是 `/models/<slug>/`）。
  final String slug;

  /// 显示名，如 `Aether`。
  final String name;

  /// 全名，如 `aether (genshin impact)`。
  final String fullName;

  final String thumb;

  /// 播放量，已带单位（`6.6M`）。
  final String viewsText;

  /// 收藏数文本（`26K`）。
  final String favouritesText;

  /// 视频数文本（`70`）。
  final String videosText;

  /// 播放量占榜首的比例（0..1），榜单里那根横条用。
  final double viewsRatio;

  /// 原始链接路径，用于判断该跳 tags 还是 models。
  final String href;

  bool get isTag => href.contains('/tags/');

  @override
  String toString() => '#$rank $name ($viewsText)';
}

/// 角色排行榜下的作品分类（`/top-characters/<work>/`）。
class CharacterWork {
  const CharacterWork({
    required this.slug,
    required this.name,
    required this.countText,
  });

  final String slug;
  final String name;

  /// 如 `57 characters`。
  final String countText;
}
