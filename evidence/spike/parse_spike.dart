// 解析层可行性验证：用冻结的真实 HTML fixture 证明「列表 / 详情 / 分页 / 标签」都能稳定抽出字段。
// 这不进最终工程，只用来在动手写 App 前把风险清零。
import 'dart:convert';
import 'dart:io';

import 'package:html/parser.dart' as html_parser;
import 'package:html/dom.dart';

class VideoCard {
  final String id;
  final String url;
  final String title;
  final String thumb;
  final String preview;
  final String duration;
  final String views;
  final String rating;
  final String comments;
  final bool hd;

  const VideoCard({
    required this.id,
    required this.url,
    required this.title,
    required this.thumb,
    required this.preview,
    required this.duration,
    required this.views,
    required this.rating,
    required this.comments,
    required this.hd,
  });

  @override
  String toString() =>
      '#$id $duration views=$views rating=$rating comments=$comments hd=$hd\n    $title\n    thumb=${thumb.split('/').skip(5).join('/')}';
}

class Pager {
  final int current;
  final int total;
  const Pager(this.current, this.total);
  @override
  String toString() => 'page $current / $total';
}

class VideoDetail {
  final String id;
  final String title;
  final String duration;
  final String views;
  final String addedAgo;
  final String author;
  final List<String> tags;
  final List<String> models;
  final List<String> categories;
  final Map<String, String> streams; // 清晰度 -> 直链
  final String poster;
  final String timelineTemplate;
  final int timelineInterval;
  final String description;
  final List<VideoCard> related;

  const VideoDetail({
    required this.id,
    required this.title,
    required this.duration,
    required this.views,
    required this.addedAgo,
    required this.author,
    required this.tags,
    required this.models,
    required this.categories,
    required this.streams,
    required this.poster,
    required this.timelineTemplate,
    required this.timelineInterval,
    required this.description,
    required this.related,
  });
}

String _text(Element? e) => (e?.text ?? '').replaceAll(RegExp(r'\s+'), ' ').trim();

List<VideoCard> parseListing(String html) {
  final doc = html_parser.parse(html);
  final cards = <VideoCard>[];
  for (final item in doc.querySelectorAll('div.item.thumb')) {
    final id = item.attributes['data-video-card-id'];
    if (id == null) continue;
    final a = item.querySelector('a.th');
    final href = a?.attributes['href'] ?? '';
    final img = item.querySelector('img.thumb');
    final wrap = item.querySelector('.wrap_image');
    cards.add(VideoCard(
      id: id,
      url: href,
      title: _firstNonEmpty([img?.attributes['alt'], _text(item.querySelector('.thumb_title')), a?.attributes['title']]),
      // 懒加载时 src 是 data: 占位，真链在 data-rd-src
      thumb: _firstNonEmpty([img?.attributes['data-rd-src'], img?.attributes['src']]).startsWith('data:')
          ? (img?.attributes['data-rd-jpg'] ?? '')
          : _firstNonEmpty([img?.attributes['data-rd-src'], img?.attributes['src']]),
      preview: wrap?.attributes['data-preview'] ?? '',
      duration: _text(item.querySelector('.time')),
      views: _text(item.querySelector('.video-views-count')),
      rating: _text(item.querySelector('.video-card-meta__rating')),
      comments: _text(item.querySelector('.video-comments-count')),
      hd: item.querySelector('.quality') != null,
    ));
  }
  return cards;
}

String _firstNonEmpty(List<String?> xs) {
  for (final x in xs) {
    if (x != null && x.trim().isNotEmpty) return x.trim();
  }
  return '';
}

Pager? parsePager(String html) {
  final doc = html_parser.parse(html);
  // <button class="page-of" title="Go to page">Page 2 of 14,632</button>
  for (final b in doc.querySelectorAll('button.page-of, .pagination a, nav a')) {
    final t = _text(b);
    final m = RegExp(r'Page\s+([\d,]+)\s+of\s+([\d,]+)').firstMatch(t);
    if (m != null) {
      return Pager(_int(m.group(1)!), _int(m.group(2)!));
    }
  }
  // 回退：从 /latest-updates/14632/ 这类链接取最大值
  var max = 0;
  for (final a in doc.querySelectorAll('nav.fs-pages a')) {
    final p = int.tryParse((a.attributes['data-page'] ?? '').replaceAll(',', ''));
    if (p != null && p > max) max = p;
  }
  return max > 0 ? Pager(0, max) : null;
}

int _int(String s) => int.tryParse(s.replaceAll(',', '')) ?? 0;

VideoDetail? parseDetail(String html) {
  final doc = html_parser.parse(html);
  final script = doc.querySelectorAll('script').map((e) => e.text).join('\n');
  final fv = _parseFlashvars(script);
  if (fv.isEmpty) return null;

  final title = _text(doc.querySelector('h1'));
  final info = doc.querySelectorAll('span.item_info').map(_text).toList();
  // 发布相对时间 / 播放量 / 时长
  String added = '', views = '', duration = '';
  for (final s in info) {
    if (s.contains('ago') || s.contains('yesterday')) {
      added = s;
    } else if (RegExp(r'^\d+K? \(').hasMatch(s) || RegExp(r'^[\d.,]+[KM]?$').hasMatch(s)) {
      views = s;
    } else if (RegExp(r'^\d+:\d{2}(:\d{2})?$').hasMatch(s)) {
      duration = s;
    }
  }

  final streams = <String, String>{};
  void put(String? url, String? label) {
    if (url == null || url.isEmpty || label == null || label.isEmpty) return;
    streams[label] = url;
  }

  put(fv['video_url'], fv['video_url_text']);
  put(fv['video_alt_url'], fv['video_alt_url_text']);
  put(fv['video_alt_url2'], fv['video_alt_url2_text']);
  put(fv['video_alt_url3'], fv['video_alt_url3_text']);

  // 标签/分类/模型只认视频自己的分组：div[data-suggest-type="tag|category|model"]。
  // 全页扫 a[href] 会把导航、页脚、相关推荐一起吞进来（实测 46 个标签 / 10 个无关分类）；
  // flashvars 的 video_tags 又会漏掉刚加的标签，所以以分组 DOM 为准。
  List<String> group(String type) {
    final root = doc.querySelector('div[data-suggest-type="$type"]');
    if (root == null) return const [];
    return root
        .querySelectorAll('a.item, a.tag_item')
        .map((a) => (a.attributes['href'] ?? ''))
        .map((h) => RegExp(r'/(?:tags|categories|models)/([^/"]+)/').firstMatch(h)?.group(1))
        .whereType<String>()
        .toSet()
        .toList();
  }

  final tags = group('tag');
  final cats = group('category');
  final models = group('model');

  var author = '';
  final am = RegExp(r'rule34video\.com/members/(\d+)/').firstMatch(html);
  if (am != null) author = am.group(1)!;

  final related = <VideoCard>[];
  final relRoot = doc.querySelector('#custom_list_videos_related_videos_items');
  if (relRoot != null) {
    related.addAll(parseListing('<div class="items">${relRoot.innerHtml}</div>'));
  }

  return VideoDetail(
    id: fv['video_id'] ?? '',
    title: title.isEmpty ? (fv['video_title'] ?? '') : title,
    duration: duration,
    views: views,
    addedAgo: added,
    author: author,
    tags: tags,
    models: models,
    categories: cats,
    streams: streams,
    poster: fv['preview_url'] ?? '',
    timelineTemplate: fv['timeline_screens_url'] ?? '',
    timelineInterval: int.tryParse(fv['timeline_screens_interval'] ?? '') ?? 0,
    description: _text(doc.querySelector('.vp-desc')) .isEmpty ? _text(doc.querySelector('.js-vp-desc')) : _text(doc.querySelector('.vp-desc')),
    related: related,
  );
}

Map<String, String> _parseFlashvars(String script) {
  final i = script.indexOf('flashvars');
  if (i < 0) return const {};
  final start = script.indexOf('{', i);
  if (start < 0) return const {};
  var depth = 0;
  var end = -1;
  for (var k = start; k < script.length; k++) {
    final c = script[k];
    if (c == '{') depth++;
    if (c == '}') {
      depth--;
      if (depth == 0) {
        end = k;
        break;
      }
    }
  }
  if (end < 0) return const {};
  final body = script.substring(start + 1, end);
  final out = <String, String>{};
  // key: 'value'  —— 值里可能出现转义单引号
  final re = RegExp(r"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*'((?:[^'\\]|\\.)*)'");
  for (final m in re.allMatches(body)) {
    out[m.group(1)!] = m.group(2)!;
  }
  return out;
}

Future<String> readFixture(String name) async {
  final candidates = [
    'evidence/fixtures/$name',
    '../evidence/fixtures/$name',
    '../../evidence/fixtures/$name',
  ];
  for (final c in candidates) {
    final f = File(c);
    if (await f.exists()) return f.readAsString();
  }
  throw FileSystemException('fixture not found: $name');
}

void main() async {
  var fails = 0;
  void check(String label, bool ok, [String extra = '']) {
    stdout.writeln('${ok ? "  PASS" : "  FAIL"}  $label${extra.isEmpty ? "" : "  ($extra)"}');
    if (!ok) fails++;
  }

  stdout.writeln('== 列表解析 ==');
  for (final name in ['latest-p1.html', 'latest-p2.html', 'search-zelda-p2.html', 'tag-sex-p2.html', 'model-derpixon.html']) {
    final html = await readFixture(name);
    final cards = parseListing(html);
    final pager = parsePager(html);
    stdout.writeln('$name  cards=${cards.length}  pager=$pager');
    if (cards.isNotEmpty) stdout.writeln('   sample: ${cards.first}');
    check('$name 卡片数 >= 1', cards.length >= 1, '${cards.length}');
    check('$name 首卡字段完整', cards.isNotEmpty &&
        cards.first.id.isNotEmpty &&
        cards.first.title.isNotEmpty &&
        cards.first.thumb.isNotEmpty &&
        cards.first.duration.isNotEmpty &&
        !cards.first.thumb.startsWith('data:'));
    if (name != 'model-derpixon.html') {
      check('$name 分页器可读', pager != null && pager.total > 0, '$pager');
    }
  }

  stdout.writeln('\n== 分类索引解析 ==');
  {
    final html = await readFixture('categories.html');
    final doc = html_parser.parse(html);
    final items = doc.querySelectorAll('#list_categories_categories_list_items div.thumb.item');
    final withImg = items.where((e) => e.querySelector('img') != null).length;
    stdout.writeln('categories=${items.length} withImage=$withImg withoutImage=${items.length - withImg}');
    check('分类条数 >= 10', items.length >= 10, '${items.length}');
  }

  stdout.writeln('\n== 详情解析 ==');
  {
    final html = await readFixture('video-4703101.html');
    final d = parseDetail(html);
    check('详情可解析', d != null);
    if (d != null) {
      stdout.writeln('id=${d.id} title=${d.title}');
      stdout.writeln('duration=${d.duration} views=${d.views} added=${d.addedAgo} author=${d.author}');
      stdout.writeln('streams=${d.streams.keys.toList()}');
      for (final e in d.streams.entries) {
        final path = e.value.split('?').first;
        stdout.writeln('   ${e.key} -> ${path.split('/').where((s) => s.isNotEmpty).last}  token=${e.value.contains('v-acctoken')}');
      }
      stdout.writeln('tags(${d.tags.length})=${d.tags.take(8).toList()}');
      stdout.writeln('categories=${d.categories} models=${d.models}');
      stdout.writeln('poster=${d.poster}');
      stdout.writeln('timeline=${d.timelineTemplate} interval=${d.timelineInterval}');
      stdout.writeln('desc=${d.description.substring(0, d.description.length > 90 ? 90 : d.description.length)}...');
      stdout.writeln('related=${d.related.length}');

      check('id 非空', d.id.isNotEmpty, d.id);
      check('标题非空', d.title.isNotEmpty);
      check('时长形如 mm:ss', RegExp(r'^\d+:\d{2}(:\d{2})?$').hasMatch(d.duration), d.duration);
      check('清晰度 >= 4 档', d.streams.length >= 4, d.streams.keys.join(','));
      check('所有直链是 http 且带 token', d.streams.values.every((u) => u.startsWith('http') && u.contains('v-acctoken')));
      check('标签 15~30 个', d.tags.length >= 15 && d.tags.length <= 30, '${d.tags.length}');
      check('模型恰好 1 个', d.models.length == 1, d.models.toString());
      check('分类恰好 1 个且为 3d', d.categories.length == 1 && d.categories.first == '3d', d.categories.toString());
      check('封面是 http', d.poster.startsWith('http'));
      check('时间轴模板含 {time}', d.timelineTemplate.contains('{time}'));
      check('描述非空', d.description.isNotEmpty);
      check('相关推荐 >= 1', d.related.length >= 1, '${d.related.length}');
    }
  }

  stdout.writeln('\n== 坏 HTML 容错 ==');
  {
    check('空字符串不崩', parseListing('').isEmpty);
    check('垃圾 HTML 列表返回空', parseListing('<html><body><p>nope</p></body></html>').isEmpty);
    check('垃圾 HTML 详情返回 null', parseDetail('<html><script>var x=1;</script></html>') == null);
    check('被截断的 flashvars 不崩', parseDetail("<script>flashvars = { video_id: '1', video_ti") == null);
  }

  stdout.writeln('\n== 登录表单解析 ==');
  {
    final html = await readFixture('login.html');
    final doc = html_parser.parse(html);
    final form = doc.querySelector('form[action="/login/"]');
    check('找到登录表单', form != null);
    final fields = form?.querySelectorAll('input').map((e) => e.attributes['name']).whereType<String>().toList() ?? [];
    stdout.writeln('fields=$fields');
    check('含 username/pass/action', fields.contains('username') && fields.contains('pass') && fields.contains('action'));
    check('含 csrf 令牌', fields.any((f) => f.contains('csrf')));
  }

  stdout.writeln('\n${fails == 0 ? "全部通过 ✅" : "$fails 项失败 ❌"}');
  exit(fails == 0 ? 0 : 1);
}


