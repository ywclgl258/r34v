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
    this.uploaderName = '',
    this.uploaderId = '',
    this.downloads = const [],
    this.comments = const [],
    this.characters = const [],
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

  /// 角色 slug 列表（如 `zelda`）。
  ///
  /// 站点把它和 tags 都标成 `data-item-type="tag"`，只能靠分组标题
  /// `Characters` 来区分。混进 tags 会让标签栏又长又乱。
  final List<String> characters;

  /// 分类 slug 列表（如 `3d`）。同样只认分组。
  final List<String> categories;

  /// 作者（站点内部叫 model）slug 列表。同样只认分组。
  final List<String> models;

  /// 上传者显示名（站点详情页的 Uploaded by）。
  ///
  /// 注意：**上传者不是作者**。上传者指向 `/members/{数字id}/`，
  /// 作者指向 `/models/{slug}/`。同一个人可能两个都有，也可能只有一个。
  final String uploaderName;

  /// 上传者的数字 id（/members/{id}/）。空表示页面没给。
  final String uploaderId;

  /// 可下载的清晰度（站点每档一个 download 直链）。
  ///
  /// 与 [streams] 的区别：streams 是播放用的流地址，downloads 带
  /// download=true 和 download_filename，服务端会返回附件。
  final List<DownloadOption> downloads;

  /// 评论（只读展示）。站点要登录才能发，我们只解析列表。
  final List<VideoComment> comments;

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

/// 一个可下载的清晰度。
///
/// 站点在详情页给出每一档的下载直链，形如：
/// `/get_file/…/x_1080p.mp4/?v-acctoken=…&download=true&download_filename=mario_1080p.mp4`
///
/// 两个要点：
/// - 链接带 `v-acctoken`，**会过期**。真正下载前要重新抓详情页拿新链。
/// - `download_filename` 是站点建议的文件名，直接用它比自己拼标题可靠。
class DownloadOption {
  const DownloadOption({
    required this.label,
    required this.url,
    required this.fileName,
    this.sizeText = '',
  });

  /// 清晰度，如 `1080p`。
  final String label;

  final String url;

  /// 站点建议的文件名（取自 `download_filename` 参数）。
  final String fileName;

  /// 文件大小（如果页面给了）。多数情况拿不到，留空。
  final String sizeText;

  @override
  String toString() => 'DownloadOption($label)';
}

/// 一条评论（只读）。
class VideoComment {
  const VideoComment({
    required this.author,
    required this.text,
    this.avatarUrl = '',
    this.dateText = '',
    this.authorUrl = '',
  });

  final String author;
  final String text;
  final String avatarUrl;
  final String dateText;
  final String authorUrl;

  @override
  String toString() => '$author: $text';
}
