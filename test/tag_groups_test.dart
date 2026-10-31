import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/parsing/detail_parser.dart';

/// 详情页四类标签的解析。
///
/// 站点把 Characters 和 Tags 都标成 `data-item-type="tag"`，只靠类型分不开，
/// 必须靠分组标题。之前没做这个区分，角色名被混进标签，详情页标签栏又长又乱。
void main() {
  const parser = DetailParser();

  String fixture(String n) => File('test/fixtures/$n').readAsStringSync();

  group('四类分开', () {
    test('Characters 不混进 tags', () {
      final d = parser.parse(fixture('video-4cat.html'));
      expect(d, isNotNull, reason: 'fixture 应能解析');

      // 这个页面有 Characters 组，里面的角色名不该出现在 tags 里
      final overlap = d!.characters.toSet().intersection(d.tags.toSet());
      expect(
        overlap,
        isEmpty,
        reason: '角色和标签重叠说明分组没分开：$overlap',
      );
    });

    test('三类都非空（fixture 本身有这三组）', () {
      final d = parser.parse(fixture('video-4cat.html'))!;
      expect(d.characters, isNotEmpty, reason: 'Characters 组应被解析出来');
      expect(d.categories, isNotEmpty, reason: 'Categories 组应被解析出来');
      expect(d.tags, isNotEmpty, reason: 'Tags 组应被解析出来');
    });

    test('characters 是角色不是别的（抽查已知值）', () {
      final d = parser.parse(fixture('video-4cat.html'))!;
      // 这个视频是 mario 搜索结果里的，角色应包含 mario 相关
      final joined = d.characters.join(',').toLowerCase();
      expect(joined, contains('mario'));
    });

    test('categories 里是分类而不是标签', () {
      final d = parser.parse(fixture('video-4cat.html'))!;
      // 分类通常是 3d / anime 这类宽泛词，数量很少
      expect(d.categories.length, lessThanOrEqualTo(6));
    });
  });

  group('老页面（无 Characters 组）', () {
    test('characters 为空，tags 仍正常', () {
      final d = parser.parse(fixture('video-4703101.html'));
      expect(d, isNotNull);
      expect(d!.characters, isEmpty, reason: '这个页面没有 Characters 组');
      expect(d.tags, isNotEmpty);
      expect(d.models, isNotEmpty);
    });

    test('标签数量是真实分组数，不是全页扫', () {
      final d = parser.parse(fixture('video-4703101.html'))!;
      // 全页扫 a[href*="/tags/"] 会得到 46 个，真实分组只有 21 个
      expect(d.tags.length, lessThan(30), reason: '应以分组为准，不能把导航/推荐区算进来');
      expect(d.tags.length, greaterThan(5));
    });
  });

  group('分组解析的鲁棒性', () {
    test('同一类里不出现重复', () {
      final d = parser.parse(fixture('video-4cat.html'))!;
      for (final list in [d.characters, d.categories, d.models, d.tags]) {
        expect(list.toSet().length, list.length, reason: '同类里不该有重复项');
      }
    });

    test('slug 不含路径残留', () {
      final d = parser.parse(fixture('video-4cat.html'))!;
      for (final s in [...d.characters, ...d.categories, ...d.tags]) {
        expect(s.contains('/'), isFalse, reason: 'slug 里不该有斜杠：$s');
        expect(s.contains('?'), isFalse, reason: 'slug 里不该有查询串：$s');
      }
    });

    test('空 HTML 返回 null 而不是抛异常', () {
      expect(parser.parse(''), isNull);
      expect(parser.parse('   '), isNull);
    });
  });
}
