import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;

import '../../core/constants.dart';
import '../../domain/video_card.dart';
import '../../domain/feed_link.dart';
import 'parse_exception.dart';

/// 列表页解析：HTML → `FeedPage`。
///
/// 站点列表结构（实测，2026-10 快照）：
/// ```html
/// <div class="item thumb video_18" data-video-card-id="4703941">
///   <a class="th" href="https://rule34video.com/video/4703941/slug/" title="…">
///     <div class="img wrap_image" data-preview="…preview.mp4">
///       <img class="thumb rd-lz" src="data:image/gif;base64,…"
///            data-rd-src="…/336x189/3.jpg" data-rd-jpg="…/320x180/3.jpg" alt="…"/>
///       <div class="quality">…</div>
///       <div class="time">4:00</div>
///       <div class="sound">…</div>
///     </div>
///     <div class="thumb_title">…</div>
///     <div class="thumb_info video-card-meta">
///       <div class="added …">… 12 minutes ago</div>
///       <div class="rating …">… 97% (81)</div>
///       <div class="views video-comments-count …">… 0</div>
///       <div class="views video-views-count …">… 20K</div>
///     </div>
///   </a>
/// </div>
/// ```
class ListingParser {
  const ListingParser();

  /// 解析列表页。`request` 只用于回填 `FeedPage.request`，不参与解析。
  ///
  /// 空 HTML 或完全没有卡片容器时返回空列表（列表页本来就可能 0 条，
  /// 例如搜索无结果），这不算异常。真正的结构变更由 [assertStructure] 侦测。
  FeedPage parse(String html, FeedRequest request) {
    if (html.trim().isEmpty) {
      return FeedPage(
        request: request,
        cards: const [],
        pager: Pager.unknown,
        heading: '',
      );
    }

    final doc = html_parser.parse(html);
    final cards = _parseCards(doc);
    return FeedPage(
      request: request,
      cards: cards,
      pager: _parsePager(doc),
      heading: _parseHeading(doc),
    );
  }

  /// 结构哨兵：页面明显是列表页但一张卡片都没解析出来，说明站点改版了。
  ///
  /// 与「搜索无结果」区分：无结果时站点也不会有 `data-video-card-id`，
  /// 但同样不会有 `.thumbs` 容器里的条目 —— 所以检测判据是「有 thumbs 容器
  /// 且容器里存在 `div.item`，却没有任何一个带 video 链接」。
  void assertStructure(String html, FeedRequest request) {
    if (html.trim().isEmpty) return;
    final doc = html_parser.parse(html);
    final container = _findThumbsContainer(doc);
    if (container == null) {
      if (doc.querySelectorAll('div.item.thumb').isEmpty &&
          doc.querySelectorAll('a[href*="/video/"]').isEmpty) {
        throw ParseException(
          '列表页结构无法识别',
          hint: '先确认站点是否改版，或该 URL 是否需要登录',
          snippet: _snippet(doc),
        );
      }
      return;
    }
    final items = container.querySelectorAll('div.item');
    if (items.isEmpty) return; // 空列表是正常状态（搜索无结果）
    final withVideo = items.where((e) => e.querySelector('a[href*="/video/"]') != null).length;
    if (withVideo == 0) {
      throw ParseException(
        '列表容器存在但没有任何视频链接',
        hint: '站点可能改了卡片结构；用最新页面重存一份 fixture 再对照',
        snippet: container.outerHtml.length > 600
            ? '${container.outerHtml.substring(0, 600)}…'
            : container.outerHtml,
      );
    }
  }

  List<VideoCard> _parseCards(Document doc) {
    final container = _findThumbsContainer(doc);
    final scope = container ?? doc;
    // 优先用 data-video-card-id 定位（最稳定）；退回到 div.item.thumb。
    var items = scope.querySelectorAll('div[data-video-card-id]');
    if (items.isEmpty) {
      items = scope.querySelectorAll('div.item.thumb');
    }

    final out = <VideoCard>[];
    final seen = <String>{};
    for (final item in items) {
      final card = _parseOne(item);
      if (card == null) continue;
      if (!seen.add(card.id)) continue;
      out.add(card);
    }
    return out;
  }

  VideoCard? _parseOne(Element item) {
    final a = item.querySelector('a.th') ?? item.querySelector('a[href*="/video/"]');
    final href = a?.attributes['href'] ?? '';
    if (href.isEmpty) return null;

    final uri = Uri.tryParse(href);
    if (uri == null) return null;
    final segs = uri.pathSegments.where((s) => s.isNotEmpty).toList();
    // /video/<id>/<slug>/
    final idFromUrl = (segs.length >= 2 && segs[0] == 'video') ? segs[1] : '';
    final slug = (segs.length >= 3 && segs[0] == 'video') ? segs[2] : '';
    final id = item.attributes['data-video-card-id'] ?? idFromUrl;
    if (id.isEmpty) return null;

    final img = item.querySelector('img.thumb') ?? item.querySelector('img');
    final wrap = item.querySelector('.wrap_image');

    final title = _firstNonEmpty([
      _squash(item.querySelector('.thumb_title')?.text),
      img?.attributes['alt']?.trim(),
      a?.attributes['title']?.trim(),
    ]);

    return VideoCard(
      id: id,
      url: Site.rewrite(href.startsWith('http') ? href : '${Site.base}$href'),
      slug: slug,
      title: title,
      thumb: Site.rewrite(_resolveThumb(img)),
      preview: Site.rewrite(_absUrl(wrap?.attributes['data-preview'] ?? '')),
      duration: _squash(item.querySelector('.time')?.text),
      durationSeconds: VideoCard.parseDuration(_squash(item.querySelector('.time')?.text)),
      viewsText: _squash(item.querySelector('.video-views-count')?.text),
      ratingText: _squash(item.querySelector('.video-card-meta__rating')?.text),
      commentsText: _squash(item.querySelector('.video-comments-count')?.text),
      addedText: _squash(item.querySelector('.video-card-meta__date')?.text) ,
      isHd: item.querySelector('.quality') != null,
      hasSound: item.querySelector('.sound') != null,
      type: _videoType(item),
    );
  }

  /// 取封面上的类型角标文本（`Futa` / `Gay` / …）。
  ///
  /// 站点的角标是 `<div class="futa">Futa</div>`——**class 名统一是 `futa`**，
  /// 它只是个样式名，真正有区分度的是文本。所以按「位置 + 文本白名单」取，
  /// 不能按 class 名匹配（按 class 取会把所有类型都识别成 Futa）。
  String _videoType(Element item) {
    // 类型角标出现在封面容器内、紧跟 .quality 之后
    const known = {
      'straight',
      'gay',
      'futa',
      'music',
      'iwara',
      'lesbian',
      'trans',
      'other',
    };
    final thumb = item.querySelector('.thumb, .wrap_image') ?? item;
    for (final div in thumb.querySelectorAll('div')) {
      final text = _squash(div.text).toLowerCase();
      if (text.isEmpty || text.length > 12) continue;
      if (known.contains(text)) {
        // 首字母大写返回，和站点显示一致
        return text[0].toUpperCase() + text.substring(1);
      }
    }
    return '';
  }

  /// 缩略图真链。
  ///
  /// 坑：列表页图片是懒加载的，`src` 是一个 1px 的 base64 gif，真链在
  /// `data-rd-src`；而且 `336x189/xxx.jpg` 实际是 **WebP**（扩展名在骗人），
  /// `data-rd-jpg` 指向的 `320x180/xxx.jpg` 才是真 JPEG。
  /// Flutter 按字节解码，两种都能显示，所以优先取体积更小的 `336x189`。
  String _resolveThumb(Element? img) {
    if (img == null) return '';
    final lazy = img.attributes['data-rd-src']?.trim() ?? '';
    if (lazy.isNotEmpty) return _absUrl(lazy);
    final src = img.attributes['src']?.trim() ?? '';
    if (src.isEmpty || src.startsWith('data:')) {
      return _absUrl(img.attributes['data-rd-jpg']?.trim() ?? '');
    }
    return _absUrl(src);
  }

  Pager _parsePager(Document doc) {
    // 首选带 title 的分页按钮：`Page 2 of 14,632`
    for (final el in doc.querySelectorAll('button.page-of, [data-rd-jump], nav.fs-pager button')) {
      final m = RegExp(r'Page\s+([\d,]+)\s+of\s+([\d,]+)').firstMatch(_squash(el.text));
      if (m != null) {
        return Pager(current: _int(m.group(1)!), total: _int(m.group(2)!));
      }
    }
    // 退回：从页码链接的 data-page 取最大值
    var maxPage = 0;
    for (final a in doc.querySelectorAll('nav.fs-pages a[data-page]')) {
      final p = _int(a.attributes['data-page'] ?? '');
      if (p > maxPage) maxPage = p;
    }
    if (maxPage > 0) return Pager(current: 0, total: maxPage);

    // 再退回：直接读 `/latest-updates/14632/` 这类末页链接
    for (final a in doc.querySelectorAll('a[href]')) {
      final href = a.attributes['href'] ?? '';
      final m = RegExp(r'/(\d+)/?$').firstMatch(href);
      if (m == null) continue;
      final p = _int(m.group(1)!);
      if (p > maxPage) maxPage = p;
    }
    return maxPage > 0 ? Pager(current: 0, total: maxPage) : Pager.unknown;
  }

  String _parseHeading(Document doc) {
    final h1 = _squash(doc.querySelector('h1')?.text);
    if (h1.isNotEmpty) return h1;
    final title = _squash(doc.querySelector('title')?.text);
    return title;
  }

  Element? _findThumbsContainer(Document doc) {
    return doc.querySelector('div#custom_list_videos_latest_videos_list_items')
        ?? doc.querySelector('div.thumbs[id]')
        ?? doc.querySelector('div.thumbs.clearfix')
        ?? doc.querySelector('div.thumbs');
  }

  /// 从索引页（`/categories/`、`/tags/`）里抽跳转项。
  ///
  /// 两类页面的条目结构一致，都是 `div.thumb.item > a.th > .wrap_image + .thumb_title`：
  /// - 分类：可能有封面 `img`（`contents/categories/<id>/s1_<id>.jpg`），
  ///   也可能只有 `<span class="no-thumb">no image</span>`（实测 36 条里 21 条没图）；
  /// - 标签：通常没有图。
  List<FeedLink> extractLinks(String html, {required String segment}) {
    if (html.trim().isEmpty) return const [];
    final doc = html_parser.parse(html);

    final container = doc.querySelector('div#list_categories_categories_list_items') ??
        doc.querySelector('div#list_tags_tags_list_items') ??
        doc.querySelector('div.thumbs') ??
        doc;

    final out = <FeedLink>[];
    final seen = <String>{};
    for (final a in container.querySelectorAll('a[href]')) {
      final href = a.attributes['href'] ?? '';
      final m = RegExp('/$segment/([^/"?]+)/?').firstMatch(href);
      if (m == null) continue;
      final slug = m.group(1)!;
      if (!seen.add(slug)) continue;

      final label = _squash(a.querySelector('.thumb_title')?.text).isEmpty
          ? _squash(a.attributes['title'])
          : _squash(a.querySelector('.thumb_title')?.text);
      final img = a.querySelector('img');
      final thumb = _absUrl(img?.attributes['src'] ?? '');

      out.add(FeedLink(
        slug: slug,
        label: label.isEmpty ? slug : label,
        thumb: thumb,
      ));
    }
    return out;
  }

  String _snippet(Document doc) {
    final body = doc.body?.outerHtml ?? '';
    return body.length > 500 ? '${body.substring(0, 500)}…' : body;
  }
}

/// 折叠空白。
///
/// 注意：站点用一个不可见的 U+200B（零宽空格）做占位，直接 trim 不掉，
/// 会导致 `'12 minutes ago'` 变成 `'\u200b12 minutes ago'`，比较时踩坑。
String _squash(String? s) {
  if (s == null) return '';
  return s
      .replaceAll('\u200b', '')
      .replaceAll('\u00a0', ' ')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
}

String _firstNonEmpty(List<String?> xs) {
  for (final x in xs) {
    final t = x?.trim() ?? '';
    if (t.isNotEmpty) return t;
  }
  return '';
}

String _absUrl(String u) {
  if (u.isEmpty) return '';
  if (u.startsWith('http')) return u;
  if (u.startsWith('//')) return 'https:$u';
  if (u.startsWith('/')) return '${Site.base}$u';
  return u;
}

int _int(String s) => int.tryParse(s.replaceAll(',', '').trim()) ?? 0;


