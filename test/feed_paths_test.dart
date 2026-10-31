import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/core/constants.dart';

/// 列表请求的路径拼接。
///
/// 这块出过一次真 bug：角色列表拼成 `/characters/<slug>/`，
/// 而站点**没有**这个路径（返回 404）。角色在站内就是 tag，
/// 必须落到 `/tags/<slug>/`。用测试把这条锁住。
///
/// 另一个约定：站点第 1 页**不带**页号后缀（`/tags/x/` 而不是 `/tags/x/1/`）。
void main() {
  String pathOf(FeedKind kind, {String target = 'x', int page = 1}) =>
      FeedRequest(kind: kind, target: target, page: page).path;

  group('角色走 tags 路径（关键修复）', () {
    test('角色列表指向 /tags/，不是 /characters/', () {
      final p = pathOf(FeedKind.character, target: 'aether-genshin-impact');
      expect(p, '/tags/aether-genshin-impact/');
      expect(p.contains('/characters/'), isFalse,
          reason: '站点的 /characters/ 是 404');
    });

    test('翻页同样走 tags', () {
      expect(pathOf(FeedKind.character, target: 'zelda', page: 3), '/tags/zelda/3/');
    });
  });

  group('第 1 页不带页号', () {
    test('所有列表第 1 页都没有 /1/', () {
      for (final kind in FeedKind.values) {
        final p = pathOf(kind, target: 'probe');
        expect(p.contains('/1/'), isFalse, reason: '${kind.name}: $p');
      }
    });

    test('第 2 页开始带页号', () {
      expect(pathOf(FeedKind.latest, page: 2), '/latest-updates/2/');
      expect(pathOf(FeedKind.tag, target: 'sex', page: 5), '/tags/sex/5/');
    });
  });

  group('其它列表路径', () {
    test('标签', () {
      expect(pathOf(FeedKind.tag, target: 'sex'), '/tags/sex/');
    });

    test('分类', () {
      expect(pathOf(FeedKind.category, target: '3d'), '/categories/3d/');
    });

    test('作者', () {
      expect(pathOf(FeedKind.model, target: 'derpixon'), '/models/derpixon/');
    });

    test('搜索', () {
      expect(pathOf(FeedKind.search, target: 'zelda'), '/search/zelda/');
    });

    test('榜单', () {
      expect(pathOf(FeedKind.latest), '/latest-updates/');
      expect(pathOf(FeedKind.topRated), '/top-rated/');
      expect(pathOf(FeedKind.mostPopular), '/most-popular/');
    });

    test('for-you 不分页', () {
      expect(pathOf(FeedKind.forYou, page: 5), '/for-you/');
    });

    test('排行榜是固定页', () {
      expect(pathOf(FeedKind.topCharacters), '/top-characters/');
      expect(pathOf(FeedKind.topArtists), '/top-model/');
    });
  });

  group('防回归', () {
    test('没有任何 kind 会拼出不存在的 /characters/ 路径', () {
      for (final kind in FeedKind.values) {
        final p = pathOf(kind, target: 'probe');
        expect(p.contains('/characters/'), isFalse,
            reason: '${kind.name} 拼出了不存在的路径: $p');
      }
    });

    test('所有路径都以 / 开头', () {
      for (final kind in FeedKind.values) {
        expect(pathOf(kind).startsWith('/'), isTrue);
      }
    });

    test('带目标的路径会把 slug 编进去', () {
      expect(pathOf(FeedKind.tag, target: 'big breasts'), contains('big%20breasts'));
    });
  });
}
