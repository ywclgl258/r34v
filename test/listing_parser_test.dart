import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/parsing/listing_parser.dart';
import 'package:r34v/data/parsing/parse_exception.dart';

String fixture(String name) {
  final f = File('test/fixtures/$name');
  if (!f.existsSync()) {
    fail('fixture 缺失: test/fixtures/$name —— 单测依赖真实页面快照，不要删');
  }
  return f.readAsStringSync();
}

void main() {
  const parser = ListingParser();

  group('列表页解析', () {
    test('latest-p1：23 张卡 + 分页器 1/14632', () {
      final page = parser.parse(
        fixture('latest-p1.html'),
        const FeedRequest(kind: FeedKind.latest),
      );

      expect(page.cards.length, 23);
      expect(page.pager.current, 1);
      expect(page.pager.total, 14632);

      final first = page.cards.first;
      expect(first.id, isNotEmpty);
      expect(first.url, startsWith('https://rule34video.com/video/'));
      expect(first.slug, isNotEmpty, reason: '详情页必须带 slug，裸 id 会 404');
      expect(first.title, isNotEmpty);
      expect(first.thumb, startsWith('https://'));
      expect(first.thumb, isNot(startsWith('data:')), reason: '懒加载占位图必须被解析掉');
      expect(first.duration, matches(r'^\d+:\d{2}(:\d{2})?$'));
      expect(first.durationSeconds, greaterThan(0));
      expect(first.viewsText, isNotEmpty);
      expect(first.preview, startsWith('https://'));
    });

    test('每张卡的必需字段都不为空', () {
      final page = parser.parse(
        fixture('latest-p1.html'),
        const FeedRequest(kind: FeedKind.latest),
      );
      for (final c in page.cards) {
        expect(c.id, isNotEmpty);
        expect(c.url, contains('/video/'));
        expect(c.title, isNotEmpty, reason: '卡片 ${c.id} 标题为空');
        expect(c.thumb, startsWith('https://'), reason: '卡片 ${c.id} 封面异常');
        expect(c.durationSeconds, greaterThan(0), reason: '卡片 ${c.id} 时长解析失败');
      }
    });

    test('id 不重复', () {
      final page = parser.parse(
        fixture('latest-p1.html'),
        const FeedRequest(kind: FeedKind.latest),
      );
      final ids = page.cards.map((c) => c.id).toList();
      expect(ids.toSet().length, ids.length);
    });

    test('latest-p2：第 2 页卡片与第 1 页不重叠', () {
      final p1 = parser.parse(
        fixture('latest-p1.html'),
        const FeedRequest(kind: FeedKind.latest),
      );
      final p2 = parser.parse(
        fixture('latest-p2.html'),
        const FeedRequest(kind: FeedKind.latest, page: 2),
      );
      expect(p2.pager.current, 2);
      final overlap = p1.cards.map((c) => c.id).toSet()
        ..retainAll(p2.cards.map((c) => c.id));
      expect(overlap, isEmpty, reason: '两页出现了重复视频，分页解析可能读错页');
    });

    test('search-zelda-p2：24 张卡 + 分页 2/106', () {
      final page = parser.parse(
        fixture('search-zelda-p2.html'),
        const FeedRequest(kind: FeedKind.search, target: 'zelda', page: 2),
      );
      expect(page.cards.length, 24);
      expect(page.pager.current, 2);
      expect(page.pager.total, 106);
    });

    test('tag-sex-p2：23 张卡 + 分页 2/1950', () {
      final page = parser.parse(
        fixture('tag-sex-p2.html'),
        const FeedRequest(kind: FeedKind.tag, target: 'sex', page: 2),
      );
      expect(page.cards.length, 23);
      expect(page.pager.current, 2);
      expect(page.pager.total, 1950);
    });

    test('model-derpixon：23 张卡 + 分页 1/4', () {
      final page = parser.parse(
        fixture('model-derpixon.html'),
        const FeedRequest(kind: FeedKind.model, target: 'derpixon'),
      );
      expect(page.cards.length, 23);
      expect(page.pager.total, 4);
    });

    test('空 HTML 返回空列表而不是崩溃', () {
      final page = parser.parse('', const FeedRequest(kind: FeedKind.latest));
      expect(page.cards, isEmpty);
      expect(page.pager.total, 0);
    });

    test('无关 HTML 返回空列表（搜索无结果的正常形态）', () {
      final page = parser.parse(
        '<html><body><p>No results</p></body></html>',
        const FeedRequest(kind: FeedKind.search, target: 'zzzz'),
      );
      expect(page.cards, isEmpty);
    });
  });

  group('结构哨兵', () {
    test('正常页不抛异常', () {
      expect(
        () => parser.assertStructure(
          fixture('latest-p1.html'),
          const FeedRequest(kind: FeedKind.latest),
        ),
        returnsNormally,
      );
    });

    test('容器在但卡片结构全废 → 抛带诊断的异常', () {
      const broken = '<html><body><div class="thumbs">'
          '<div class="item"><div class="somethingelse">x</div></div>'
          '<div class="item"><div class="somethingelse">y</div></div>'
          '</div></body></html>';
      expect(
        () => parser.assertStructure(broken, const FeedRequest(kind: FeedKind.latest)),
        throwsA(isA<ParseException>()),
      );
    });

    test('完全陌生的页面 → 抛带诊断的异常', () {
      expect(
        () => parser.assertStructure(
          '<html><body><div class="login-form">Please log in</div></body></html>',
          const FeedRequest(kind: FeedKind.latest),
        ),
        throwsA(isA<ParseException>()),
      );
    });
  });
}
