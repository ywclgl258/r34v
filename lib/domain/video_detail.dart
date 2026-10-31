import 'video_card.dart';

/// 一个清晰度档位。
class StreamVariant {
  const StreamVariant({
    required this.label,
    required this.url,
    required this.isHd,
  });

  /// 站点给的档位名，如 `720p`。
  final String label;

  /// 直链，带 `?v-acctoken=…`。**会过期**，403/410 时必须回源重抓。
  final String url;
  final bool isHd;

  /// 分辨率高度，用于排序与「记住上次清晰度」；解析失败为 0。
  int get height {
    final m = RegExp(r'(\d+)p').firstMatch(label);
    return m == null ? 0 : int.parse(m.group(1)!);
  }

  /// 文件名部分，如 `4703101_720p.mp4`，用于日志与下载命名。
  String get fileName {
    final segs = Uri.parse(url).pathSegments.where((s) => s.isNotEmpty).toList();
    return segs.isEmpty ? label : segs.last;
  }

  @override
  String toString() => 'StreamVariant($label${isHd ? ", hd" : ""})';
}

/// 详情页解析结果。
class VideoDetail {
  const VideoDetail({
    required this.id,
    required this.title,
    required this.duration,
    required this.durationSeconds,
    required this.viewsText,
    required this.addedText,
    required this.parentSlug,
    required this.authorSlug,
    required this.authorName,
    required this.avatarUrl,
    required this.tags,
    required this.categories,
    required this.models,
    required this.description,
    required this.poster,
    required this.streams,
    required this.timelineTemplate,
    required this.timelineIntervalSeconds,
    required this.related,
  });

  final String id;
  final String title;
  final String duration;

  /// 秒数，用于续播与进度条。
  final int durationSeconds;
  final String viewsText;
  final String addedText;

  /// 详情页 URL 里的 slug 段。站点要求 `/video/<id>/<slug>/`，裸 id 会 404。
  final String parentSlug;

  /// 作者（上传者）的 members id；无则空串。
  final String authorSlug;
  final String authorName;
  final String avatarUrl;

  /// 标签 slug 列表（如 `big-breasts`）。
  ///
  /// 必须从 `div[data-suggest-type="tag"]` 分组取——全页扫 `a[href*="/tags/"]`
  /// 会把导航、页脚、相关推荐区的链接一起吞进来（实测 21 个真标签被扫成 46 个）。
  final List<String> tags;

  /// 分类 slug 列表（如 `3d`）。同样只认分组。
  final List<String> categories;

  /// 模型/作者 slug 列表。同样只认分组。
  final List<String> models;

  final String description;

  /// 封面大图，站点的 `preview.jpg`（实测 1279×719）。
  final String poster;

  /// 清晰度档位，按高度降序。
  final List<StreamVariant> streams;

  /// 拖动预览缩略图模板，含 `{time}` 占位。首版不用，留给后续。
  final String timelineTemplate;
  final int timelineIntervalSeconds;

  /// 相关推荐。
  final List<VideoCard> related;

  /// 详情页路径。
  String get path => '/video/$id/$parentSlug/';

  /// 默认清晰度：优先 720p（体积与画质平衡），其次不超过 1080p 的最高档，最后取最高档。
  StreamVariant? get preferredStream {
    if (streams.isEmpty) return null;
    for (final s in streams) {
      if (s.height == 720) return s;
    }
    for (final s in streams) {
      if (s.height > 0 && s.height <= 1080) return s;
    }
    return streams.first;
  }

  VideoDetail copyWith({List<StreamVariant>? streams, List<VideoCard>? related}) =>
      VideoDetail(
        id: id,
        title: title,
        duration: duration,
        durationSeconds: durationSeconds,
        viewsText: viewsText,
        addedText: addedText,
        parentSlug: parentSlug,
        authorSlug: authorSlug,
        authorName: authorName,
        avatarUrl: avatarUrl,
        tags: tags,
        categories: categories,
        models: models,
        description: description,
        poster: poster,
        streams: streams ?? this.streams,
        timelineTemplate: timelineTemplate,
        timelineIntervalSeconds: timelineIntervalSeconds,
        related: related ?? this.related,
      );

  @override
  String toString() =>
      'VideoDetail($id, $duration, ${streams.map((s) => s.label).join("/")}, '
      '${tags.length} tags, ${related.length} related)';
}
