import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;

import '../../core/constants.dart';
import '../../domain/video_card.dart';
import '../../domain/video_detail.dart';
import 'listing_parser.dart';
import 'parse_exception.dart';

/// 详情页解析：HTML → `VideoDetail`。
///
/// 数据来源分三块，都不能混：
/// 1. `flashvars = { … }` —— 清晰度直链、封面、时间轴模板、video_id；
/// 2. `div[data-suggest-type="tag|category|model"]` —— 标签/分类/作者，**只认分组**；
/// 3. `.item_info` / `h1` / `.vp-desc` —— 时长、播放量、标题、简介。
class DetailParser {
  const DetailParser({this.listingParser = const ListingParser()});

  final ListingParser listingParser;

  VideoDetail? parse(String html, {String expectedId = ''}) {
    if (html.trim().isEmpty) return null;

    final doc = html_parser.parse(html);
    final fv = _parseFlashvars(doc);
    if (fv.isEmpty) return null;

    final id = fv['video_id']?.trim() ?? '';
    if (id.isEmpty) return null;
    if (expectedId.isNotEmpty && expectedId != id) {
      throw ParseException(
        '详情页 id 不符：期望 $expectedId，实际 $id',
        hint: '可能是站点重定向到了别的视频；用返回的 id 继续即可',
      );
    }

    final h1 = _squash(doc.querySelector('h1')?.text);
    final infos = doc.querySelectorAll('span.item_info').map((e) => _squash(e.text)).toList();

    var added = '';
    var views = '';
    var duration = '';
    for (final s in infos) {
      if (s.isEmpty) continue;
      if (RegExp(r'^\d+:\d{2}(:\d{2})?$').hasMatch(s)) {
        duration = s;
      } else if (RegExp(r'ago|yesterday|just now|today', caseSensitive: false).hasMatch(s)) {
        added = s;
      } else if (RegExp(r'^[\d.,]+\s*[KM]?\b').hasMatch(s) || s.contains('(')) {
        views = s;
      }
    }
    // 兜底：从 custom-time 图标那格取时长（列表与详情共用同一套 svg 图标）
    if (duration.isEmpty) {
      final t = doc.querySelector('.custom-time')?.parent?.text;
      final m = RegExp(r'\d+:\d{2}(?::\d{2})?').firstMatch(_squash(t));
      if (m != null) duration = m.group(0)!;
    }

    final streams = _parseStreams(fv);
    final (tags, cats, models) = _parseGroups(doc);

    final authorSlug = _parseAuthorSlug(doc);
    final authorName = _squash(doc.querySelector('a[href*="/members/"]')?.text);
    final avatar = doc.querySelector('a[href*="/members/"] img')?.attributes['src'] ?? '';

    final description = _parseDescription(doc);
    final related = _parseRelated(doc);

    return VideoDetail(
      id: id,
      title: h1.isNotEmpty ? h1 : (fv['video_title']?.trim() ?? ''),
      duration: duration,
      durationSeconds: VideoCard.parseDuration(duration),
      viewsText: views,
      addedText: added,
      parentSlug: _slugFromCanonical(doc),
      authorSlug: authorSlug,
      authorName: authorName,
      avatarUrl: Site.rewrite(avatar.startsWith('http') ? avatar : '${Site.base}$avatar'),
      tags: tags,
      categories: cats,
      models: models,
      description: description,
      poster: Site.rewrite(fv['preview_url'] ?? ''),
      streams: streams,
      timelineTemplate: fv['timeline_screens_url'] ?? '',
      timelineIntervalSeconds: int.tryParse(fv['timeline_screens_interval'] ?? '') ?? 0,
      related: related,
    );
  }

  /// 结构哨兵：能解析出 id 但没有直链，说明播放器配置改了。
  void assertPlayable(VideoDetail d) {
    if (d.streams.isEmpty) {
      throw ParseException(
        '详情页没有任何清晰度直链（video ${d.id}）',
        hint: 'flashvars 里 video_url / video_alt_url* 全空，站点可能改了播放器配置',
      );
    }
  }

  /// 解析 `flashvars = { key: 'value', … }`。
  ///
  /// 站点把播放器配置塞在一段内联 script 里，是 JS 对象字面量（键不带引号），
  /// 不是 JSON。这里手写大括号配对扫描，因为值里可能出现 `}` 之外的转义，
  /// 用正则整体匹配容易在第一个 `}` 就截断。
  Map<String, String> _parseFlashvars(Document doc) {
    for (final script in doc.querySelectorAll('script')) {
      final text = script.text;
      final i = text.indexOf('flashvars');
      if (i < 0) continue;
      final obj = _sliceObject(text, i);
      if (obj == null) continue;
      final out = <String, String>{};
      final re = RegExp(r"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*'((?:[^'\\]|\\.)*)'");
      for (final m in re.allMatches(obj)) {
        out[m.group(1)!] = m.group(2)!;
      }
      if (out.isNotEmpty) return out;
    }
    return const {};
  }

  /// 从 `start` 后的第一个 `{` 开始，按大括号配对接出对象体（不含最外层括号）。
  String? _sliceObject(String text, int start) {
    final open = text.indexOf('{', start);
    if (open < 0) return null;
    var depth = 0;
    var inStr = false;
    var quote = '';
    for (var k = open; k < text.length; k++) {
      final c = text[k];
      if (inStr) {
        if (c == r'\') {
          k++; // 跳过转义字符
          continue;
        }
        if (c == quote) inStr = false;
        continue;
      }
      if (c == "'" || c == '"') {
        inStr = true;
        quote = c;
        continue;
      }
      if (c == '{') depth++;
      if (c == '}') {
        depth--;
        if (depth == 0) return text.substring(open + 1, k);
      }
    }
    return null;
  }

  List<StreamVariant> _parseStreams(Map<String, String> fv) {
    final out = <StreamVariant>[];
    void put(String? url, String? label, {bool hd = false}) {
      final u = url?.trim() ?? '';
      final l = label?.trim() ?? '';
      if (u.isEmpty || l.isEmpty) return;
      if (out.any((e) => e.label == l)) return;
      out.add(StreamVariant(
        label: l,
        url: Site.rewrite(u.startsWith('http') ? u : '${Site.base}$u'),
        isHd: hd,
      ));
    }

    put(fv['video_url'], fv['video_url_text']);
    put(fv['video_alt_url'], fv['video_alt_url_text']);
    put(fv['video_alt_url2'], fv['video_alt_url2_text'], hd: fv['video_alt_url2_hd'] == '1');
    put(fv['video_alt_url3'], fv['video_alt_url3_text'], hd: fv['video_alt_url3_hd'] == '1');
    // 部分视频有第 5 档
    put(fv['video_alt_url4'], fv['video_alt_url4_text'], hd: fv['video_alt_url4_hd'] == '1');

    out.sort((a, b) => b.height.compareTo(a.height));
    return out;
  }

  /// 标签 / 分类 / 模型 —— **只认视频自己的分组**。
  ///
  /// 站点把这三类各自包在 `<div data-suggest-type="tag|category|model">` 里。
  /// 全页扫 `a[href*="/tags/"]` 会把导航栏、页脚热门标签、相关推荐卡的标签
  /// 全部吞进来（实测：21 个真标签被扫成 46 个，分类多出 10 个无关项）。
  (List<String>, List<String>, List<String>) _parseGroups(Document doc) {
    List<String> group(String type, String seg) {
      final roots = doc.querySelectorAll('div[data-suggest-type="$type"]');
      final out = <String>[];
      final seen = <String>{};
      for (final root in roots) {
        for (final a in root.querySelectorAll('a[href]')) {
          final href = a.attributes['href'] ?? '';
          final m = RegExp('/$seg/([^/"?]+)/?').firstMatch(href);
          if (m == null) continue;
          final slug = m.group(1)!;
          if (seen.add(slug)) out.add(slug);
        }
      }
      return out;
    }

    return (group('tag', 'tags'), group('category', 'categories'), group('model', 'models'));
  }

  /// 上传者 id。站点有两处：头像链接 `members/<id>/`，和简介里作者自贴的链接。
  /// 取头像那一处最可靠——它在视频信息块内。
  String _parseAuthorSlug(Document doc) {
    final info = doc.querySelector('#tab_video_info') ?? doc;
    final a = info.querySelector('a[href*="/members/"]');
    final href = a?.attributes['href'] ?? '';
    final m = RegExp(r'/members/([^/"?]+)/?').firstMatch(href);
    return m?.group(1) ?? '';
  }

  String _parseDescription(Document doc) {
    final el = doc.querySelector('.vp-desc') ?? doc.querySelector('.js-vp-desc');
    if (el == null) return '';
    // 站点用 <br> 分段，先换成换行再取文本，否则整段会粘成一行
    final html = el.innerHtml
        .replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'</p>', caseSensitive: false), '\n');
    final text = html_parser.parseFragment(html).text ?? '';
    return text
        .replaceAll('\u200b', '')
        .replaceAll('\u00a0', ' ')
        .split('\n')
        .map((l) => l.replaceAll(RegExp(r'[ \t]+'), ' ').trim())
        .join('\n')
        .trim();
  }

  List<VideoCard> _parseRelated(Document doc) {
    const rootId = '#custom_list_videos_related_videos_items';
    final root = doc.querySelector(rootId);
    if (root == null) return const [];
    // 复用列表解析器：把容器包一层，因为它的选择器从 div.thumbs 起算。
    final wrapped = '<div class="thumbs" id="custom_list_videos_related_videos_items">'
        '${root.innerHtml}</div>';
    try {
      return listingParser
          .parse(wrapped, const FeedRequest(kind: FeedKind.latest))
          .cards;
    } on ParseException {
      return const [];
    }
  }

  /// 从 canonical / og:url 里取详情页 slug。
  String _slugFromCanonical(Document doc) {
    for (final sel in ['link[rel="canonical"]', 'meta[property="og:url"]']) {
      final el = doc.querySelector(sel);
      final v = el?.attributes['href'] ?? el?.attributes['content'] ?? '';
      final segs = Uri.tryParse(v)?.pathSegments.where((s) => s.isNotEmpty).toList() ?? const [];
      if (segs.length >= 3 && segs[0] == 'video') return segs[2];
    }
    return '';
  }
}

String _squash(String? s) {
  if (s == null) return '';
  return s
      .replaceAll('\u200b', '')
      .replaceAll('\u00a0', ' ')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
}

