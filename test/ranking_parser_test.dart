import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/parsing/ranking_parser.dart';

/// 排行榜解析。
///
/// 站点有两个**完全不同的**榜单格式，用一份 fixture 测不出来：
/// - 角色榜 `li.cs-row`（行式，带播放量/收藏/视频数）
/// - 作者榜 `a.item`（卡片式，带头像和好评率）
void main() {
  const p = RankingParser();

  String fixture(String n) => File('test/fixtures/$n').readAsStringSync();

  group('角色榜（cs-row 行式）', () {
    test('解析出条目', () {
      final items = p.parse(fixture('top-characters-work.html'));
      expect(items, isNotEmpty, reason: 'genshin 角色榜应该有条目');
      expect(items.length, greaterThan(10));
    });

    test('名次从 1 递增且不重复', () {
      final items = p.parse(fixture('top-characters-work.html'));
      final ranks = items.map((e) => e.rank).toList();
      expect(ranks.first, 1);
      expect(ranks.toSet().length, ranks.length, reason: '名次不该重复');
      // 应该整体递增
      for (var i = 1; i < ranks.length; i++) {
        expect(ranks[i], greaterThan(ranks[i - 1]));
      }
    });

    test('播放量文本不是空，且榜首有比例条满格', () {
      final items = p.parse(fixture('top-characters-work.html'));
      expect(items.first.viewsText, isNotEmpty);
      expect(items.first.viewsRatio, closeTo(1.0, 0.01),
          reason: '榜首相对于自己应该是 1.0');
    });

    test('后面条目的比例不大于榜首', () {
      final items = p.parse(fixture('top-characters-work.html'));
      for (final e in items) {
        expect(e.viewsRatio, lessThanOrEqualTo(1.0));
        expect(e.viewsRatio, greaterThanOrEqualTo(0.0));
      }
    });

    test('角色链接指向 tags（站内角色就是 tag）', () {
      final items = p.parse(fixture('top-characters-work.html'));
      expect(items.first.isTag, isTrue);
      expect(items.first.slug, isNotEmpty);
      expect(items.first.slug.contains('/'), isFalse);
    });

    test('带收藏数与视频数', () {
      final items = p.parse(fixture('top-characters-work.html'));
      final withData = items.where(
        (e) => e.favouritesText.isNotEmpty || e.videosText.isNotEmpty,
      );
      expect(withData, isNotEmpty, reason: '至少部分条目该有收藏/视频数');
    });
  });

  group('作者榜（Top Artists 区块）', () {
    // 重要事实：站点没有独立的作者排行正文区。
    // /top-model/ 渲染的就是侧栏那个 Top Artists 区块，
    // 普通列表页侧栏里也有同样一块。所以解析器按标题定位，不按页面。
    test('解析出条目', () {
      final items = p.parse(fixture('top-model.html'));
      expect(items, isNotEmpty);
      expect(items.length, greaterThanOrEqualTo(5));
    });

    test('名次连续且不重复', () {
      final items = p.parse(fixture('top-model.html'));
      expect(items.first.rank, 1);
      final ranks = items.map((e) => e.rank).toList();
      expect(ranks.toSet().length, ranks.length, reason: '同一页面里区块出现两次，必须去重');
      for (var i = 1; i < ranks.length; i++) {
        expect(ranks[i], greaterThan(ranks[i - 1]));
      }
    });

    test('名字里不带好评率数字', () {
      final items = p.parse(fixture('top-model.html'));
      for (final e in items) {
        expect(
          RegExp(r'\d+%').hasMatch(e.name),
          isFalse,
          reason: '好评率应该被摘掉，实际名字：${e.name}',
        );
      }
    });

    test('好评率单独放在 viewsText 里', () {
      final items = p.parse(fixture('top-model.html'));
      expect(items.where((e) => e.viewsText.contains('%')), isNotEmpty);
    });

    test('作者链接指向 models', () {
      final items = p.parse(fixture('top-model.html'));
      expect(items.first.isTag, isFalse);
      expect(items.first.slug, isNotEmpty);
    });

    test('列表页也能解析出这块（侧栏里有 Top Artists）', () {
      // 这不是 bug：列表页侧栏确实渲染了 Top Artists。
      // 断言这一点，是为了防止以后改成「按页面路径判断」而误伤。
      final items = p.parse(fixture('latest-p1.html'));
      expect(items, isNotEmpty);
      expect(items.first.rank, 1);
    });

    test('榜单条目字段完整（名次/名字/slug 都不空）', () {
      final items = p.parse(fixture('top-model.html'));
      for (final e in items) {
        expect(e.rank, greaterThan(0));
        expect(e.name, isNotEmpty);
        expect(e.slug, isNotEmpty);
        expect(e.slug.contains('/'), isFalse);
      }
    });
  });

  group('角色榜的作品分类', () {
    test('解析出作品列表', () {
      final works = p.parseWorks(fixture('top-characters.html'));
      expect(works, isNotEmpty);
      expect(works.length, greaterThan(5));
    });

    test('slug 干净，不含路径', () {
      final works = p.parseWorks(fixture('top-characters.html'));
      for (final w in works) {
        expect(w.slug.contains('/'), isFalse);
        expect(w.name, isNotEmpty);
      }
    });

    test('不把「All N characters」当作品名', () {
      final works = p.parseWorks(fixture('top-characters.html'));
      for (final w in works) {
        expect(
          RegExp(r'^All \d+', caseSensitive: false).hasMatch(w.name),
          isFalse,
          reason: '副标题被误当成作品名：${w.name}',
        );
      }
    });

    test('作品不重复', () {
      final works = p.parseWorks(fixture('top-characters.html'));
      final slugs = works.map((w) => w.slug).toList();
      expect(slugs.toSet().length, slugs.length);
    });
  });

  group('容错', () {
    test('空 HTML 返回空列表而不是抛异常', () {
      expect(p.parse(''), isEmpty);
      expect(p.parse('   '), isEmpty);
      expect(p.parseWorks(''), isEmpty);
    });

    test('普通列表页不会误报成角色榜', () {
      // 列表页没有 cs-row 行式榜单，只会命中侧栏的 Top Artists。
      // 这条断言防止角色榜解析把普通卡片当成榜单。
      final items = p.parse(fixture('latest-p1.html'));
      expect(
        items.where((e) => e.viewsRatio < 1 || e.videosText.isNotEmpty),
        isEmpty,
        reason: '列表页不该解析出带播放量/视频数的角色榜条目',
      );
    });
  });
}
