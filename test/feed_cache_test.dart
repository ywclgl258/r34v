import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/feed_cache.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:shared_preferences/shared_preferences.dart';

VideoCard _card(String id, {String views = '1K'}) => VideoCard(
  id: id,
  url: 'https://example.test/video/$id/slug/',
  slug: 'slug',
  title: 'title $id',
  thumb: 'https://example.test/t$id.jpg',
  preview: '',
  duration: '1:00',
  durationSeconds: 60,
  viewsText: views,
  ratingText: '90%',
  commentsText: '0',
  addedText: '1 hour ago',
  isHd: true,
  hasSound: false,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  group('列表缓存', () {
    test('没写过时读不到', () {
      final cache = FeedCache(prefs);
      expect(cache.read('latest:newest'), isNull);
    });

    test('写入后能原样读回（含全部字段）', () async {
      final cache = FeedCache(prefs);
      final cards = [_card('1'), _card('2')];
      await cache.write('latest:newest', cards);

      final got = cache.read('latest:newest');
      expect(got, isNotNull);
      expect(got!.cards.length, 2);
      expect(got.cards.first.id, '1');
      expect(got.cards.first.title, 'title 1');
      expect(got.cards[1].durationSeconds, 60);
      // 新鲜写入不应标记为 stale
      expect(got.isStale, isFalse);
    });

    test('不同 key 互不干扰', () async {
      final cache = FeedCache(prefs);
      await cache.write('latest:newest', [_card('1')]);
      await cache.write('tag:big:newest', [_card('9')]);

      expect(cache.read('latest:newest')!.cards.single.id, '1');
      expect(cache.read('tag:big:newest')!.cards.single.id, '9');
    });

    test('超过 TTL 的内容仍可读，但标记为 stale', () async {
      final cache = FeedCache(prefs);
      // 直接塞一条 2 小时前的记录
      final old = DateTime.now()
          .subtract(const Duration(hours: 2))
          .millisecondsSinceEpoch;
      await prefs.setString(
        'feed_cache_v1:old',
        jsonEncode({'at': old, 'cards': [_card('1').toJson()]}),
      );
      await prefs.setString('feed_cache_v1_index', jsonEncode({'old': old}));

      final got = cache.read('old');
      expect(got, isNotNull, reason: '过期不等于不可用，仍要能展示');
      expect(got!.isStale, isTrue);
    });

    test('损坏的缓存当作没有，不抛异常', () async {
      await prefs.setString('feed_cache_v1:bad', '{不是合法 json');
      final cache = FeedCache(prefs);
      expect(cache.read('bad'), isNull);
    });

    test('空列表不产生有效缓存', () async {
      final cache = FeedCache(prefs);
      await cache.write('empty', const []);
      expect(cache.read('empty'), isNull);
    });

    test('并发写入不会互相覆盖索引', () async {
      final cache = FeedCache(prefs);
      // 同时写 5 个不同的列表：早期实现里 read-modify-write 索引会丢 key
      await Future.wait([
        for (var i = 0; i < 5; i++)
          cache.write('list$i', [_card('$i')]),
      ]);
      for (var i = 0; i < 5; i++) {
        expect(cache.read('list$i'), isNotNull, reason: 'list$i 应仍在');
      }
    });

    test('超过上限时淘汰最旧的', () async {
      final cache = FeedCache(prefs);
      final total = FeedCache.maxEntries + 4;
      for (var i = 0; i < total; i++) {
        await cache.write('k$i', [_card('$i')]);
      }
      // 最早的几个应被淘汰
      expect(cache.read('k0'), isNull);
      // 最新的必须还在
      expect(cache.read('k${total - 1}'), isNotNull);
    });

    test('clear 清空全部', () async {
      final cache = FeedCache(prefs);
      await cache.write('a', [_card('1')]);
      await cache.write('b', [_card('2')]);
      await cache.clear();
      expect(cache.read('a'), isNull);
      expect(cache.read('b'), isNull);
    });
  });

  group('VideoCard 序列化', () {
    test('toJson/fromJson 往返保持所有字段', () {
      final c = _card('42', views: '19.5K');
      final back = VideoCard.fromJson(c.toJson());
      expect(back.id, c.id);
      expect(back.url, c.url);
      expect(back.slug, c.slug);
      expect(back.title, c.title);
      expect(back.thumb, c.thumb);
      expect(back.duration, c.duration);
      expect(back.durationSeconds, c.durationSeconds);
      expect(back.viewsText, c.viewsText);
      expect(back.ratingText, c.ratingText);
      expect(back.addedText, c.addedText);
      expect(back.isHd, c.isHd);
      expect(back.hasSound, c.hasSound);
      expect(back.views, 19500); // 反向解析仍然可用
    });

    test('缺字段时给安全默认值，不抛异常', () {
      final back = VideoCard.fromJson({'i': '1'});
      expect(back.id, '1');
      expect(back.title, '');
      expect(back.isHd, isFalse);
    });
  });
}
