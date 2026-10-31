import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;

import '../../core/constants.dart';
import '../../domain/ranked_entry.dart';

/// 排行榜解析：`/top-characters/`、`/top-model/`。
///
/// 两个页面结构一样（`cs-row` 列表），差别只在链接段名：
/// 角色指向 `/tags/<slug>/`，作者指向 `/models/<slug>/`。
class RankingParser {
  const RankingParser();

  /// 解析榜单行。页面没有榜单结构时返回空列表。
  ///
  /// 站点有两个**完全不同的**榜单格式，都要支持：
  /// - 角色榜（`/top-characters/<work>/`）：`li.cs-row` 行式，带播放量/收藏/视频数
  /// - 作者榜（`/top-model/`）：`a.item` 卡片式，带头像和好评率
  ///
  /// 作者榜还有两个坑：
  /// 1. 不带查询参数时页面**渲染不出作者**（实测 0 条），必须加 `?sort=az`；
  /// 2. canonical 指向 `/models/`，说明它复用作者索引页的模板。
  List<RankedEntry> parse(String html) {
    if (html.trim().isEmpty) return const [];

    final rows = _parseCharacterRows(html);
    if (rows.isNotEmpty) return rows;

    return _parseAuthorCards(html);
  }

  /// 角色榜：`li.cs-row`。
  List<RankedEntry> _parseCharacterRows(String html) {
    final doc = html_parser.parse(html);
    final rows = doc.querySelectorAll('li.cs-row');
    if (rows.isEmpty) return const [];

    final out = <RankedEntry>[];
    String? topViewsRaw;

    for (final row in rows) {
      final entry = _parseRow(row, out.length + 1);
      if (entry == null) continue;

      topViewsRaw ??= (row.querySelector('.cs-row__v b')?.text ?? '').trim();
      final ratio = _ratio(
        (row.querySelector('.cs-row__v b')?.text ?? '').trim(),
        topViewsRaw,
      );

      out.add(RankedEntry(
        rank: entry.rank,
        slug: entry.slug,
        name: entry.name,
        fullName: entry.fullName,
        thumb: entry.thumb,
        viewsText: entry.viewsText,
        favouritesText: entry.favouritesText,
        videosText: entry.videosText,
        viewsRatio: ratio,
        href: entry.href,
      ));
    }

    return out;
  }

  /// 作者榜：从 `Top Artists` 区块里取 `a.item`。
  ///
  /// 实测结论（踩了几轮才弄清）：
  /// - 站点**没有**独立的作者排行正文区。`/top-model/` 页面渲染的就是
  ///   侧栏那个 `Top Artists` 区块，普通列表页的侧栏里也有一个（同样 10 个）。
  /// - 所以不能靠「页面里有没有 a.item」判断，那个索引页/列表页都有。
  ///   唯一可靠的定位方式是**先找 `Top Artists` 标题，再读它后面的 aside_wrap**。
  /// - 同一页面里这个区块可能出现两次（主体侧栏 + sidebar_pages），
  ///   名次都从 1 开始，必须按 slug 和名次双重去重。
  List<RankedEntry> _parseAuthorCards(String html) {
    final doc = html_parser.parse(html);

    // 定位 "Top Artists" 标题后面的那个 aside_wrap
    Element? wrap;
    for (final h2 in doc.querySelectorAll('h2.title')) {
      final t = _squash(h2.text).toLowerCase();
      if (t != 'top artists') continue;
      // 标题的父级是 .headline，再往上是 .box，aside_wrap 在 .box 里
      final box = h2.parent?.parent;
      wrap ??= box?.querySelector('.aside_wrap');
      if (wrap != null) break;
    }
    if (wrap == null) return const [];

    final out = <RankedEntry>[];
    final seenSlug = <String>{};
    final seenRank = <int>{};

    for (final a in wrap.querySelectorAll('a.item[href*="/models/"]')) {
      final href = a.attributes['href'] ?? '';
      final slug = _slugFromHref(href);
      if (slug == null) continue;
      if (!seenSlug.add(slug)) continue;

      final rankEl = a.querySelector('.position');
      if (rankEl == null) continue;
      final rankText = _squash(rankEl.text);
      final rank = int.tryParse(rankText) ??
          int.tryParse(RegExp(r'\d+').firstMatch(rankText)?.group(0) ?? '');
      if (rank == null) continue;
      if (!seenRank.add(rank)) continue;

      final img = a.querySelector('.image img');
      final rawThumb = img?.attributes['src'] ?? '';
      final nameEl = a.querySelector('.name');
      final name = _squash(
        (nameEl?.text ?? '').replaceFirst(RegExp(r'\d+%'), '').trim(),
      );
      if (name.isEmpty) continue;

      final ratingText = _squash(a.querySelector('.count span')?.text ?? '');

      out.add(RankedEntry(
        rank: rank,
        slug: slug,
        name: name,
        fullName: name,
        thumb: rawThumb.isEmpty
            ? ''
            : Site.rewrite(
                rawThumb.startsWith('http') ? rawThumb : '${Site.base}$rawThumb',
              ),
        viewsText: ratingText.isEmpty ? '' : '好评 $ratingText',
        favouritesText: '',
        videosText: '',
        viewsRatio: 1,
        href: href,
      ));
    }

    out.sort((a, b) => a.rank.compareTo(b.rank));
    return out;
  }

  RankedEntry? _parseRow(Element row, int fallbackRank) {
    final a = row.querySelector('a.cs-row__n');
    if (a == null) return null;

    final href = a.attributes['href'] ?? '';
    final slug = _slugFromHref(href);
    if (slug == null) return null;

    final name = _squash(a.text);
    if (name.isEmpty) return null;

    final rankText = _squash(row.querySelector('.cs-row__rk')?.text ?? '');
    // 头部那行是奖杯图标，文本可能为空，用序号兜底
    final rank = int.tryParse(RegExp(r'\d+').firstMatch(rankText)?.group(0) ?? '') ??
        fallbackRank;

    final img = row.querySelector('.cs-row__img img');
    final rawThumb = img?.attributes['src'] ?? '';

    // 收藏与视频数在 .cs-row__n2 里，形如 <em>Favourites</em>26K
    String fav = '';
    String vids = '';
    for (final n2 in row.querySelectorAll('.cs-row__n2')) {
      final label = _squash(n2.querySelector('em')?.text ?? '').toLowerCase();
      final value = _squash(n2.text.replaceFirst(RegExp(r'^[A-Za-z]+'), ''));
      if (label.contains('favourite')) {
        fav = value;
      } else if (label.contains('video')) {
        vids = value;
      }
    }

    return RankedEntry(
      rank: rank,
      slug: slug,
      name: name,
      fullName: _squash(row.querySelector('.cs-row__t')?.text ?? ''),
      thumb: rawThumb.isEmpty
          ? ''
          : Site.rewrite(
              rawThumb.startsWith('http') ? rawThumb : '${Site.base}$rawThumb',
            ),
      viewsText: _squash(row.querySelector('.cs-row__v b')?.text ?? ''),
      favouritesText: fav,
      videosText: vids,
      viewsRatio: 1,
      href: href,
    );
  }

  /// 解析 `/top-characters/` 上的作品分类。
  List<CharacterWork> parseWorks(String html) {
    if (html.trim().isEmpty) return const [];
    final doc = html_parser.parse(html);

    final out = <CharacterWork>[];
    final seen = <String>{};
    for (final a in doc.querySelectorAll('a[href^="/top-characters/"]')) {
      final href = a.attributes['href'] ?? '';
      final m = RegExp(r'^/top-characters/([^/?#]+)/$').firstMatch(href);
      if (m == null) continue;
      final slug = m.group(1)!;
      if (slug == 'top-characters') continue;

      final text = _squash(a.text);
      // 只要「作品名」那一行，跳过 "All 57 characters" 这类副标题
      if (text.isEmpty || RegExp(r'^All \d+', caseSensitive: false).hasMatch(text)) {
        continue;
      }
      if (!seen.add(slug)) continue;

      // 找同一块里的角色数
      final parent = a.parent;
      final countText = _squash(
        parent?.querySelector('.ma-count, small, .cs-work__count')?.text ?? '',
      );

      out.add(CharacterWork(slug: slug, name: text, countText: countText));
    }
    return out;
  }

  String? _slugFromHref(String href) {
    for (final seg in const ['tags', 'models', 'characters']) {
      final m = RegExp('/$seg/([^/?#]+)/?').firstMatch(href);
      if (m != null) return m.group(1);
    }
    return null;
  }

  /// `6.6M` / `1.2K` / `980` → 相对榜首的比例。
  double _ratio(String value, String? top) {
    final v = _toNumber(value);
    final t = _toNumber(top);
    if (v == null || t == null || t <= 0) return 1;
    return (v / t).clamp(0.0, 1.0);
  }

  double? _toNumber(String? raw) {
    if (raw == null) return null;
    final s = raw.trim().toUpperCase().replaceAll(',', '');
    if (s.isEmpty) return null;
    final m = RegExp(r'^([\d.]+)\s*([KMB]?)$').firstMatch(s);
    if (m == null) return null;
    final n = double.tryParse(m.group(1)!);
    if (n == null) return null;
    return switch (m.group(2)) {
      'K' => n * 1000,
      'M' => n * 1000000,
      'B' => n * 1000000000,
      _ => n,
    };
  }

  String _squash(String? s) =>
      (s ?? '').replaceAll(RegExp(r'\s+'), ' ').trim();
}
