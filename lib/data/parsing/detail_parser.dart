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
    final groups = _parseGroupsByTitle(doc);

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
      tags: groups.tags,
      uploaderName: _parseUploaderName(doc),
      uploaderId: _parseUploaderId(doc),
      downloads: _parseDownloads(doc),
      comments: _parseComments(doc),
      characters: groups.characters,
      categories: groups.categories,
      models: groups.models,
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

  /// 按站点自己的四类分组解析标签。
  ///
  /// 为什么不能只看 `data-suggest-type`：
  /// 站点把 **Characters** 和 **Tags** 都标成 `data-item-type="tag"`，
  /// 光靠类型完全分不开——之前就是这样把角色名混进了标签里，
  /// 详情页的「标签」一栏又长又杂。
  ///
  /// 真正的分界在分组标题上：
  /// ```html
  /// <div class="label vp-group__title">Characters</div>
  /// <span data-item-type="tag">…</span>
  /// ```
  /// 所以这里按 `vp-group__title` 把整块切成若干段，逐段读取它的标题，
  /// 再到这一段里取链接。四类映射：
  ///
  /// | 站点标题 | 归到 | 链接路径 |
  /// |---|---|---|
  /// | Characters | characters | `/characters/<slug>/` |
  /// | Categories | categories | `/categories/<slug>/` |
  /// | Artists | models | `/models/<slug>/` |
  /// | Tags | tags | `/tags/<slug>/` |
  ///
  /// 注意 Artists 的链接路径是 `models`，站点内部一直叫 model，不要在界面上
  /// 显示成「模特」——它是作者。
  ({List<String> characters, List<String> categories, List<String> models, List<String> tags})
      _parseGroupsByTitle(Document doc) {
    final characters = <String>[];
    final categories = <String>[];
    final models = <String>[];
    final tags = <String>[];

    void add(List<String> target, String slug) {
      if (slug.isEmpty) return;
      if (!target.contains(slug)) target.add(slug);
    }

    // 找出所有分组标题，按文档顺序处理。
    //
    // 结构是：`<div class="label vp-group__title">Characters</div>` 后面紧跟
    // 若干个 chip。这些 chip 可能是兄弟节点，也可能被包在同一父容器里，
    // 所以取「标题之后、下一个标题之前」的所有兄弟，逐个收集。
    final titles = doc.querySelectorAll('.vp-group__title');
    for (final titleEl in titles) {
      final name = _squash(titleEl.text).toLowerCase();
      final slugs = <String>[];

      for (var node = titleEl.nextElementSibling;
          node != null;
          node = node.nextElementSibling) {
        // 撞到下一个分组标题就停：再往后是别的组的东西
        if (node.classes.contains('vp-group__title')) break;
        for (final a in node.querySelectorAll('a[href]')) {
          final slug = _slugFromGroupHref(a.attributes['href'] ?? '');
          if (slug != null && !slugs.contains(slug)) slugs.add(slug);
        }
        // 有的页面 chip 直接是兄弟节点本身
        final selfSlug = _slugFromGroupHref(node.attributes['href'] ?? '');
        if (selfSlug != null && !slugs.contains(selfSlug)) slugs.add(selfSlug);
      }

      switch (name) {
        case 'characters':
          for (final s in slugs) {
            add(characters, s);
          }
        case 'categories':
          for (final s in slugs) {
            add(categories, s);
          }
        case 'artists':
        case 'artist':
          for (final s in slugs) {
            add(models, s);
          }
        case 'tags':
          for (final s in slugs) {
            add(tags, s);
          }
      }
    }

    // 标题分段失败时退回旧逻辑（至少不会全空）
    if (characters.isEmpty && categories.isEmpty && models.isEmpty && tags.isEmpty) {
      final (t, c, m) = _parseGroups(doc);
      return (characters: const [], categories: c, models: m, tags: t);
    }

    return (
      characters: characters,
      categories: categories,
      models: models,
      tags: tags,
    );
  }

  /// 从分组里的链接取出 slug，认这四种路径。
  String? _slugFromGroupHref(String href) {
    for (final seg in const ['characters', 'categories', 'models', 'tags']) {
      final m = RegExp('/$seg/([^/"?]+)/?').firstMatch(href);
      if (m != null) return m.group(1);
    }
    return null;
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


  /// 解析下载链接。
  ///
  /// 站点结构（每档一个）：
  /// ```html
  /// <a class="tag_item tag_item_download vp-sheet__item"
  ///    href="…/x_1080p.mp4/?v-acctoken=…&download=true&download_filename=mario_1080p.mp4">
  /// ```
  ///
  /// 取清晰度有两个来源，优先用链接里的 `_1080p` 这种后缀，
  /// 因为它最稳；没有就从 `download_filename` 里抠。
  List<DownloadOption> _parseDownloads(Document doc) {
    final out = <DownloadOption>[];
    final seen = <String>{};

    for (final a in doc.querySelectorAll('a.tag_item_download, a[href*="download=true"]')) {
      final href = a.attributes['href'] ?? '';
      if (href.isEmpty || !href.contains('download=true')) continue;

      final url = Site.rewrite(href.startsWith('http') ? href : '${Site.base}$href');

      // 文件名：优先 download_filename 参数
      String fileName = '';
      final fnMatch = RegExp(r'download_filename=([^&]+)').firstMatch(href);
      if (fnMatch != null) {
        fileName = Uri.decodeComponent(fnMatch.group(1)!);
      }

      // 清晰度：先从 URL 路径里找 `_1080p.` / `_360.` 这类后缀。
      //
      // 注意低档位没有 p：实测 360 档的文件名是 `mario_360.mp4`，
      // 而 480/720/1080 是 `mario_480p.mp4`。只匹配 `_Np.` 会漏掉最低档，
      // 然后回退到「用链接文本」——那个文本是 `MP4 360p2 MB`，把文件大小也吃进去了。
      String label = '';
      final lm = RegExp(r'_(\d{3,4})p?\.mp4').firstMatch(href);
      if (lm != null) {
        label = '${lm.group(1)}p';
      } else if (fileName.isNotEmpty) {
        final lm2 = RegExp(r'_(\d{3,4})p?\.mp4').firstMatch(fileName);
        if (lm2 != null) label = '${lm2.group(1)}p';
      }
      if (label.isEmpty) {
        // 兜底：从链接文本里只取「数字+p」，不要把大小之类的文字带进来
        final t = _squash(a.text);
        final tm = RegExp(r'(\d{3,4})\s*p').firstMatch(t);
        if (tm != null) label = '${tm.group(1)}p';
      }
      if (label.isEmpty) label = '原画';

      if (!seen.add(label)) continue;
      out.add(DownloadOption(
        label: label,
        url: url,
        fileName: fileName,
        sizeText: '',
      ));
    }

    // 按清晰度从高到低
    out.sort((a, b) {
      final na = int.tryParse(RegExp(r'\d+').firstMatch(a.label)?.group(0) ?? '') ?? 0;
      final nb = int.tryParse(RegExp(r'\d+').firstMatch(b.label)?.group(0) ?? '') ?? 0;
      return nb.compareTo(na);
    });
    return out;
  }

  /// 解析评论列表（只读）。
  ///
  /// 站点的评论在 `#tab_comments` 里。没有评论时该区块为空或不存在，
  /// 返回空列表即可——不要把「没有评论」当成解析失败。
  List<VideoComment> _parseComments(Document doc) {
    final box = doc.querySelector('#tab_comments');
    if (box == null) return const [];

    final out = <VideoComment>[];
    // 站点用 .comment_item 包每条；类名有过变化，几种都试
    final items = box.querySelectorAll(
      '.comment_item, .comment-item, .comment',
    );

    for (final it in items) {
      final authorEl = it.querySelector('a[href*="/members/"], .comment_author, .author');
      final author = _squash(authorEl?.text);
      if (author.isEmpty) continue;

      // 正文：优先 .comment_text / .text / .message
      final bodyEl = it.querySelector('.comment_text, .comment-text, .text, .message');
      final text = _squash(bodyEl?.text);
      if (text.isEmpty) continue;

      final avatar = it.querySelector('img')?.attributes['src'] ?? '';
      final dateText = _squash(
        it.querySelector('.comment_date, .date, time')?.text,
      );

      out.add(VideoComment(
        author: author,
        text: text,
        avatarUrl: avatar.isEmpty
            ? ''
            : Site.rewrite(avatar.startsWith('http') ? avatar : '${Site.base}$avatar'),
        dateText: dateText,
        authorUrl: authorEl?.attributes['href'] ?? '',
      ));
      if (out.length >= 100) break;
    }

    return out;
  }

  /// 上传者显示名（`Uploaded by` 那一行）。
  ///
  /// 站点结构：
  /// ```html
  /// <div class="col">
  ///   <div class="label">Uploaded by</div>
  ///   <a class="item btn_link video_meta_pill" href="/members/2186844/">taradohentai</a>
  /// </div>
  /// ```
  String _parseUploaderName(Document doc) {
    // 先按 label 精确找，最稳
    for (final label in doc.querySelectorAll('.label')) {
      if (_squash(label.text).toLowerCase() != 'uploaded by') continue;
      final a = label.parent?.querySelector('a[href*="/members/"]');
      if (a != null) return _squash(a.text);
    }
    // 兜底：直接找解释不了的 members 链接里的第一个
    final a = doc.querySelector('.vp-credits a[href*="/members/"]');
    return _squash(a?.text);
  }

  /// 上传者数字 id（`/members/<id>/`）。
  String _parseUploaderId(Document doc) {
    for (final label in doc.querySelectorAll('.label')) {
      if (_squash(label.text).toLowerCase() != 'uploaded by') continue;
      final a = label.parent?.querySelector('a[href*="/members/"]');
      final href = a?.attributes['href'] ?? '';
      final m = RegExp(r'/members/(\d+)/?').firstMatch(href);
      if (m != null) return m.group(1)!;
    }
    final a = doc.querySelector('.vp-credits a[href*="/members/"]');
    final href = a?.attributes['href'] ?? '';
    return RegExp(r'/members/(\d+)/?').firstMatch(href)?.group(1) ?? '';
  }
