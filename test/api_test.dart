import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/parsing/listing_parser.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/data/remote/site_client.dart';
import 'package:r34v/domain/video_card.dart';

String fixture(String name) {
  final f = File('test/fixtures/$name');
  if (!f.existsSync()) fail('fixture 缺失: test/fixtures/$name');
  return f.readAsStringSync();
}

void main() {
  group('分类 / 标签索引解析', () {
    const parser = ListingParser();

    test('分类页抽出 36 条，其中 15 条有封面', () {
      final links = parser.extractLinks(fixture('categories.html'), segment: 'categories');
      expect(links.length, 36);
      expect(links.where((l) => l.hasThumb).length, 15,
          reason: '实测 36 条里 15 条有图、21 条是 no image');
      for (final l in links) {
        expect(l.slug, isNotEmpty);
        expect(l.label, isNotEmpty);
      }
      expect(links.map((l) => l.slug), contains('gankutsuou'),
          reason: '分类索引第 1 页按字母序，gankutsuou 是已知存在的一条；'
              '注意 3d 这类热门分类不在第 1 页，不能用来断言');
    });

    test('分类 slug 不重复', () {
      final links = parser.extractLinks(fixture('categories.html'), segment: 'categories');
      final slugs = links.map((l) => l.slug).toList();
      expect(slugs.toSet().length, slugs.length);
    });

    test('无图分类的 thumb 是空串而不是脏链', () {
      final links = parser.extractLinks(fixture('categories.html'), segment: 'categories');
      for (final l in links.where((l) => !l.hasThumb)) {
        expect(l.thumb, '');
      }
    });

    test('空 HTML 返回空列表', () {
      expect(parser.extractLinks('', segment: 'categories'), isEmpty);
    });
  });

  group('FeedRequest URL 生成', () {
    test('第 1 页不带页码也不带 sort_by', () {
      expect(
        const FeedRequest(kind: FeedKind.latest).url,
        'https://rule34video.com/latest-updates/',
      );
    });

    test('第 2 页带页码', () {
      expect(
        const FeedRequest(kind: FeedKind.latest, page: 2).url,
        'https://rule34video.com/latest-updates/2/',
      );
    });

    test('搜索词做 URL 编码', () {
      expect(
        const FeedRequest(kind: FeedKind.search, target: 'z e l d a').url,
        contains('z%20e%20l%20d%20a'),
      );
    });

    test('非默认排序追加 sort_by', () {
      expect(
        const FeedRequest(kind: FeedKind.tag, target: 'sex', sort: FeedSort.topRated, page: 2).url,
        'https://rule34video.com/tags/sex/2/?sort_by=rating',
      );
    });

    test('各榜单路径', () {
      expect(const FeedRequest(kind: FeedKind.topRated).path, '/top-rated/');
      expect(const FeedRequest(kind: FeedKind.mostPopular).path, '/most-popular/');
      expect(const FeedRequest(kind: FeedKind.model, target: 'derpixon').path, '/models/derpixon/');
      expect(const FeedRequest(kind: FeedKind.category, target: '3d').path, '/categories/3d/');
    });
  });

  group('VideoCard 数值解析', () {
    test('时长', () {
      expect(VideoCard.parseDuration('24:03'), 1443);
      expect(VideoCard.parseDuration('1:02:03'), 3723);
      expect(VideoCard.parseDuration('0:05'), 5);
      expect(VideoCard.parseDuration('abc'), 0);
      expect(VideoCard.parseDuration(''), 0);
    });

    test('播放量文本', () {
      expect(VideoCard.parseCount('20K'), 20000);
      expect(VideoCard.parseCount('1.3K'), 1300);
      expect(VideoCard.parseCount('2.4K'), 2400);
      expect(VideoCard.parseCount('3M'), 3000000);
      expect(VideoCard.parseCount('19,505'), 19505);
      expect(VideoCard.parseCount(''), 0);
    });
  });

  group('HttpResult 判定', () {
    test('token 过期的状态码', () {
      HttpResult r(int s) => HttpResult(status: s, body: '', url: '', elapsed: Duration.zero);
      expect(r(403).isTokenExpired, isTrue);
      expect(r(410).isTokenExpired, isTrue);
      expect(r(401).isTokenExpired, isTrue);
      expect(r(200).isTokenExpired, isFalse);
      expect(r(404).isTokenExpired, isFalse);
    });

    test('挑战页识别', () {
      HttpResult r(String b) => HttpResult(status: 200, body: b, url: '', elapsed: Duration.zero);
      expect(r('<html><body>Checking your browser…</body></html>').looksLikeChallenge, isTrue);
      expect(r('<html><script>__ddg8_=1</script></html>').looksLikeChallenge, isTrue);
      expect(r('x' * 50000).looksLikeChallenge, isFalse, reason: '正常页面 160KB+，不能误判');
    });
  });

  // 真实网络测试：默认跳过，CI 上用 --dart-define=R34V_LIVE=1 打开。
  // 目的不是「跑通就算完」，而是在真实站点上验证解析器的字段假设没过期。
  group('真实站点联通（可选）', () {
    const live = bool.fromEnvironment('R34V_LIVE');
    const skipReason = '需要网络；用 --dart-define=R34V_LIVE=1 打开';

    test('首页 + 第 2 页 + 详情 + 直链探活', () async {
      final api = Rule34Api();

      final p1 = await api.fetchFeed(const FeedRequest(kind: FeedKind.latest));
      expect(p1.cards, isNotEmpty, reason: '首页一张卡都没有，站点结构可能变了');
      expect(p1.pager.total, greaterThan(0));

      final p2 = await api.fetchFeed(const FeedRequest(kind: FeedKind.latest, page: 2));
      expect(p2.cards, isNotEmpty);
      final overlap = p1.cards.map((c) => c.id).toSet()
        ..retainAll(p2.cards.map((c) => c.id));
      expect(overlap, isEmpty, reason: '第 1、2 页重复，分页可能被忽略');

      final card = p1.cards.first;
      final detail = await api.fetchDetail(card.path);
      expect(detail.id, card.id);
      expect(detail.streams, isNotEmpty, reason: '详情页没有直链');
      expect(detail.title, isNotEmpty);

      final stream = detail.preferredStream!;
      expect(await api.client.isStreamAlive(stream.url), isTrue,
          reason: '${stream.label} 直链探测失败');
    }, skip: live ? false : skipReason, timeout: const Timeout(Duration(minutes: 3)));

    test('搜索页 + 标签页可用', () async {
      final api = Rule34Api();
      final s = await api.fetchFeed(const FeedRequest(kind: FeedKind.search, target: 'zelda'));
      expect(s.cards, isNotEmpty, reason: '搜索 zelda 无结果，可能是搜索路径变了');
      final t = await api.fetchFeed(const FeedRequest(kind: FeedKind.tag, target: 'sex'));
      expect(t.cards, isNotEmpty);
    }, skip: live ? false : skipReason, timeout: const Timeout(Duration(minutes: 2)));

    test('分类索引可用', () async {
      final api = Rule34Api();
      expect((await api.fetchCategories()).length, greaterThan(10));
    }, skip: live ? false : skipReason, timeout: const Timeout(Duration(minutes: 2)));

    test('裸 id（无 slug）确实 404 —— 给未来的自己看的回归锚点', () async {
      final client = createSiteClient();
      final res = await client.getHtml('${Site.base}/video/4703101/');
      expect(res.status, 404,
          reason: '站点行为变了：裸 id 居然能访问，slug 规则可以简化');
    }, skip: live ? false : skipReason, timeout: const Timeout(Duration(minutes: 1)));
  });
}

