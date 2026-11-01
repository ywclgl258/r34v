import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:r34v/data/local/video_cache.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 本轮修复对应的测试：
/// 搜索排序（相关 vs 最新）、屏蔽过滤、音量持久化下限、自动缓存默认关。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('搜索排序', () {
    String urlOf(FeedKind kind, FeedSort sort, {String target = 'zelda'}) =>
        FeedRequest(kind: kind, target: target, sort: sort).url;

    test('相关排序：不传参数（站点默认是相关性）', () {
      expect(urlOf(FeedKind.search, FeedSort.relevance),
          isNot(contains('sort_by')));
    });

    test('搜索选「最新」必须显式传 post_date', () {
      // 这是本轮回修的核心 bug：搜索默认是相关性，省参数会导致
      // 「最新」芯片显示相关性结果
      expect(urlOf(FeedKind.search, FeedSort.newest),
          contains('sort_by=post_date'));
    });

    test('非搜索列表的「最新」不传参数（站点默认就是 post_date）', () {
      expect(urlOf(FeedKind.latest, FeedSort.newest, target: ''),
          isNot(contains('sort_by')));
    });

    test('其它排序照常传参', () {
      expect(urlOf(FeedKind.search, FeedSort.mostViewed),
          contains('sort_by=video_viewed'));
      expect(urlOf(FeedKind.search, FeedSort.random),
          contains('sort_by=pseudo_rand'));
    });
  });

  group('屏蔽过滤', () {
    VideoCard card(String title) => VideoCard(
          id: '1',
          slug: 'x',
          url: '',
          title: title,
          thumb: '',
          preview: '',
          duration: '',
          durationSeconds: 0,
          viewsText: '',
          ratingText: '',
          commentsText: '',
          addedText: '',
          isHd: false,
          hasSound: false,
        );

    test('标题包含被屏蔽词（全部词）才过滤', () {
      final blocked = ['lara-croft'];
      expect(cardPassesBlocklist(card('Lara Croft 3D'), blocked), isFalse);
      expect(cardPassesBlocklist(card('Lara In Trouble'), blocked), isTrue,
          reason: '只匹配到一个词不该过滤');
      expect(cardPassesBlocklist(card('Other Video'), blocked), isTrue);
    });

    test('空屏蔽列表全放行', () {
      expect(cardPassesBlocklist(card('Lara Croft'), const []), isTrue);
    });

    test('多屏蔽词各自匹配', () {
      final blocked = ['scat', 'guro-horror'];
      expect(cardPassesBlocklist(card('Scat Party'), blocked), isFalse);
      expect(cardPassesBlocklist(card('Guro Horror Show'), blocked), isFalse);
      expect(cardPassesBlocklist(card('Normal'), blocked), isTrue);
    });

    test('大小写不敏感', () {
      expect(cardPassesBlocklist(card('LARA CROFT'), ['lara-croft']), isFalse);
    });

    test('LibraryStore 增删查', () async {
      SharedPreferences.setMockInitialValues({});
      final store = LibraryStore(await SharedPreferences.getInstance());
      expect(store.loadBlockedTags(), isEmpty);
      await store.addBlockedTag('Lara Croft'); // 应归一化成 lara-croft
      expect(store.loadBlockedTags(), ['lara-croft']);
      expect(store.isTagBlocked('LARA-CROFT'), isTrue);
      await store.addBlockedTag('lara-croft'); // 重复不添加
      expect(store.loadBlockedTags().length, 1);
      await store.removeBlockedTag('lara-croft');
      expect(store.loadBlockedTags(), isEmpty);
    });
  });

  group('音量持久化策略', () {
    test('小于 10 的值落盘为 10（静音不该变成永久默认）', () {
      expect(LibraryStore.persistableVolume(0), 10);
      expect(LibraryStore.persistableVolume(5), 10);
      expect(LibraryStore.persistableVolume(9.9), 10);
      expect(LibraryStore.persistableVolume(10), 10);
      expect(LibraryStore.persistableVolume(65), 65);
      expect(LibraryStore.persistableVolume(100), 100);
    });

    test('自动缓存默认关闭', () async {
      SharedPreferences.setMockInitialValues({});
      final cache = VideoCache(await SharedPreferences.getInstance());
      expect(cache.autoCache, isFalse);
    });
  });
}
