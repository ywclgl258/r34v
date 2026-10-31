import '../core/constants.dart';

/// 列表卡片。字段全部来自列表页 DOM，不含任何需要二次请求的数据。
class VideoCard {
  const VideoCard({
    required this.id,
    required this.url,
    required this.slug,
    required this.title,
    required this.thumb,
    required this.preview,
    required this.duration,
    required this.durationSeconds,
    required this.viewsText,
    required this.ratingText,
    required this.commentsText,
    required this.addedText,
    required this.isHd,
    required this.hasSound,
  });

  final String id;

  /// 详情页完整 URL（站点强制要求带 slug，裸 `/video/<id>/` 会 404）。
  final String url;
  final String slug;
  final String title;

  /// 缩略图。列表页懒加载时 `src` 是 1px 占位图，真链在 `data-rd-src`。
  final String thumb;

  /// 鼠标悬停预览视频（mp4）。可在列表里做静音自动预览。
  final String preview;

  /// 原始时长文本，如 `24:03`。
  final String duration;

  /// 解析出的秒数，用于排序和「续播」比较；解析失败为 0。
  final int durationSeconds;

  final String viewsText;

  /// 如 `97% (81)`。
  final String ratingText;
  final String commentsText;

  /// 如 `11 hours ago`、`2 days ago`。
  final String addedText;

  final bool isHd;
  final bool hasSound;

  /// 用于详情页跳转的路径部分，如 `/video/4703101/moe-love-2-by-doll-house/`。
  String get path => Uri.parse(url).path;

  /// 把 `24:03` / `1:02:03` 解析成秒。
  static int parseDuration(String s) {
    final parts = s.split(':');
    if (parts.isEmpty || parts.length > 3) return 0;
    var total = 0;
    for (final p in parts) {
      final n = int.tryParse(p.trim());
      if (n == null) return 0;
      total = total * 60 + n;
    }
    return total;
  }

  /// 播放量文本转数字（`1.2K` / `3M` / `19,505` → int），解析失败为 0。
  /// 只用于本地排序，不追求精确。
  static int parseCount(String s) {
    final t = s.trim().toUpperCase().replaceAll(',', '');
    if (t.isEmpty) return 0;
    final m = RegExp(r'^([\d.]+)\s*([KM]?)$').firstMatch(t);
    if (m == null) return 0;
    final v = double.tryParse(m.group(1)!) ?? 0;
    final unit = m.group(2)!;
    if (unit == 'K') return (v * 1000).round();
    if (unit == 'M') return (v * 1000000).round();
    return v.round();
  }

  int get views => parseCount(viewsText);

  /// 评分百分比（`97%` → 97），无评分为 -1。
  int get ratingPercent {
    final m = RegExp(r'(\d+)%').firstMatch(ratingText);
    return m == null ? -1 : int.parse(m.group(1)!);
  }

  @override
  String toString() => 'VideoCard($id, $duration, $viewsText, $title)';
}

/// 分页器状态。`total` 是总页数，`current` 是当前页（站点未给出时为 0）。
class Pager {
  const Pager({required this.current, required this.total});

  final int current;
  final int total;

  bool get hasNext => total > 0 && current < total;

  static const Pager unknown = Pager(current: 0, total: 0);

  @override
  String toString() => 'Pager($current/$total)';
}

/// 列表页的解析结果。
class FeedPage {
  const FeedPage({
    required this.request,
    required this.cards,
    required this.pager,
    required this.heading,
  });

  final FeedRequest request;
  final List<VideoCard> cards;
  final Pager pager;

  /// 页面标题区文本，如标签页的 `Sex`、搜索页的 `Search results for "zelda"`。
  final String heading;

  bool get isEmpty => cards.isEmpty;

  FeedPage copyWith({List<VideoCard>? cards, Pager? pager}) => FeedPage(
        request: request,
        cards: cards ?? this.cards,
        pager: pager ?? this.pager,
        heading: heading,
      );

  @override
  String toString() => 'FeedPage($request, ${cards.length} cards, $pager)';
}
