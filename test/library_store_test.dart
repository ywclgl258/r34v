import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

WatchEntry _entry({
  String id = '1',
  int pos = 100,
  int dur = 600,
  String title = 'T',
  String path = '/video/1/slug/',
  DateTime? at,
}) =>
    WatchEntry(
      videoId: id,
      title: title,
      thumb: 'https://x/y.jpg',
      path: path,
      positionSeconds: pos,
      durationSeconds: dur,
      updatedAt: at ?? DateTime(2026, 10, 31, 12),
      streamLabel: '720p',
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;
  late LibraryStore store;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    store = LibraryStore(prefs);
  });

  group('观看记录', () {
    test('初始为空', () {
      expect(store.loadHistory(), isEmpty);
    });

    test('写入后能读回，字段完整', () async {
      await store.saveWatch(_entry(id: '42', pos: 300, dur: 600));
      final h = store.loadHistory();
      expect(h.length, 1);
      expect(h.first.videoId, '42');
      expect(h.first.positionSeconds, 300);
      expect(h.first.durationSeconds, 600);
      expect(h.first.streamLabel, '720p');
    });

    test('同一个视频重复写只保留一条（更新而不是追加）', () async {
      await store.saveWatch(_entry(id: '42', pos: 100));
      await store.saveWatch(_entry(id: '42', pos: 200));
      final h = store.loadHistory();
      expect(h.length, 1);
      expect(h.first.positionSeconds, 200);
    });

    test('按更新时间倒序', () async {
      await store.saveWatch(_entry(id: 'a', at: DateTime(2026, 1, 1)));
      await store.saveWatch(_entry(id: 'b', at: DateTime(2026, 5, 1)));
      await store.saveWatch(_entry(id: 'c', at: DateTime(2026, 3, 1)));
      expect(store.loadHistory().map((e) => e.videoId).toList(), ['b', 'c', 'a']);
    });

    test('超过上限时丢掉最旧的', () async {
      for (var i = 0; i < LibraryStore.historyCap + 20; i++) {
        await store.saveWatch(_entry(id: '$i', at: DateTime(2026, 1, 1).add(Duration(minutes: i))));
      }
      expect(store.loadHistory().length, LibraryStore.historyCap);
      // 最新的那条一定在
      expect(store.loadHistory().first.videoId, '${LibraryStore.historyCap + 19}');
    });

    test('删除单条', () async {
      await store.saveWatch(_entry(id: 'a'));
      await store.saveWatch(_entry(id: 'b'));
      await store.removeWatch('a');
      expect(store.loadHistory().map((e) => e.videoId).toList(), ['b']);
    });

    test('清空', () async {
      await store.saveWatch(_entry(id: 'a'));
      await store.clearHistory();
      expect(store.loadHistory(), isEmpty);
    });

    test('存储内容被写坏时返回空而不是崩溃', () async {
      SharedPreferences.setMockInitialValues({'watch_history_v1': '{不是JSON['});
      final p = await SharedPreferences.getInstance();
      expect(LibraryStore(p).loadHistory(), isEmpty);
    });
  });

  group('进度判定', () {
    test('看了不到 15 秒不算可续播', () {
      expect(_entry(pos: 10, dur: 600).isResumable, isFalse);
    });

    test('看了 15 秒以上且没看完 → 可续播', () {
      expect(_entry(pos: 15, dur: 600).isResumable, isTrue);
      expect(_entry(pos: 300, dur: 600).isResumable, isTrue);
    });

    test('最后 20 秒内视为看完', () {
      expect(_entry(pos: 585, dur: 600).isFinished, isTrue);
      expect(_entry(pos: 585, dur: 600).isResumable, isFalse);
    });

    test('时长为 0 时不做判定', () {
      expect(_entry(pos: 100, dur: 0).isFinished, isFalse);
      expect(_entry(pos: 100, dur: 0).progress, 0);
    });

    test('进度百分比夹在 0..1', () {
      expect(_entry(pos: 300, dur: 600).progress, closeTo(0.5, 0.001));
      expect(_entry(pos: 9999, dur: 600).progress, 1.0);
    });
  });

  group('收藏 · 视频', () {
    FavoriteEntry video(String id, {DateTime? at}) => FavoriteEntry(
          kind: FavoriteKind.video,
          target: id,
          title: 'T$id',
          thumb: 'https://x/$id.jpg',
          path: '/video/$id/slug/',
          subtitle: '12:34',
          addedAt: at ?? DateTime(2026, 10, 31),
        );

    test('切换：第一次收藏，第二次取消', () async {
      expect(store.isFavorite(FavoriteKind.video, 'a'), isFalse);
      expect(await store.toggleFavorite(video('a')), isTrue);
      expect(store.isFavorite(FavoriteKind.video, 'a'), isTrue);
      expect(await store.toggleFavorite(video('a')), isFalse);
      expect(store.isFavorite(FavoriteKind.video, 'a'), isFalse);
    });

    test('多条收藏按时间倒序', () async {
      await store.toggleFavorite(video('a', at: DateTime(2026, 1, 1)));
      await store.toggleFavorite(video('b', at: DateTime(2026, 6, 1)));
      expect(store.favoritesOf(FavoriteKind.video).map((e) => e.target).toList(), ['b', 'a']);
    });

    test('副标题（时长）能存下来', () async {
      await store.toggleFavorite(video('a'));
      expect(store.favoritesOf(FavoriteKind.video).first.subtitle, '12:34');
    });
  });

  group('收藏 · 作者与标签', () {
    FavoriteEntry model(String slug) => FavoriteEntry(
          kind: FavoriteKind.model,
          target: slug,
          title: 'M $slug',
          path: '/list/model/$slug',
          addedAt: DateTime(2026, 10, 31),
        );

    FavoriteEntry tag(String slug) => FavoriteEntry(
          kind: FavoriteKind.tag,
          target: slug,
          title: 'T $slug',
          path: '/list/tag/$slug',
          addedAt: DateTime(2026, 10, 31),
        );

    test('三种类型互不干扰', () async {
      await store.toggleFavorite(model('derpixon'));
      await store.toggleFavorite(tag('big-breasts'));

      expect(store.favoritesOf(FavoriteKind.model).map((e) => e.target), ['derpixon']);
      expect(store.favoritesOf(FavoriteKind.tag).map((e) => e.target), ['big-breasts']);
      expect(store.favoritesOf(FavoriteKind.video), isEmpty);
    });

    test('同名的作者和标签可以各自存在', () async {
      // key 由 kind + target 组成，跨类型不会互相顶掉
      await store.toggleFavorite(model('same-name'));
      await store.toggleFavorite(tag('same-name'));

      expect(store.isFavorite(FavoriteKind.model, 'same-name'), isTrue);
      expect(store.isFavorite(FavoriteKind.tag, 'same-name'), isTrue);
      expect(store.loadFavorites().length, 2);
    });

    test('取消其中一种不影响另一种', () async {
      await store.toggleFavorite(model('x'));
      await store.toggleFavorite(tag('x'));
      await store.toggleFavorite(model('x'));

      expect(store.isFavorite(FavoriteKind.model, 'x'), isFalse);
      expect(store.isFavorite(FavoriteKind.tag, 'x'), isTrue);
    });

    test('清空收藏后三个列表都空', () async {
      await store.toggleFavorite(model('a'));
      await store.toggleFavorite(tag('b'));
      await store.clearFavorites();

      expect(store.loadFavorites(), isEmpty);
      expect(store.favoritesOf(FavoriteKind.model), isEmpty);
      expect(store.favoritesOf(FavoriteKind.tag), isEmpty);
    });

    test('旧格式（没有 kind 字段）退化成视频，不丢数据', () async {
      SharedPreferences.setMockInitialValues({
        'favorites_v1': '[{"id":"4703101","title":"Old","thumb":"","path":"/video/4703101/x/","at":1}]',
      });
      final p = await SharedPreferences.getInstance();
      final s = LibraryStore(p);
      final list = s.loadFavorites();
      expect(list.length, 1);
      expect(list.first.kind, FavoriteKind.video);
      expect(list.first.target, '4703101');
    });
  });

  group('搜索历史', () {
    test('初始为空', () {
      expect(store.loadSearchHistory(), isEmpty);
    });

    test('新词在最前，重复词去重后提前', () async {
      await store.addSearchHistory('zelda');
      await store.addSearchHistory('kirby');
      await store.addSearchHistory('zelda');
      expect(store.loadSearchHistory(), ['zelda', 'kirby']);
    });

    test('空白词不记', () async {
      await store.addSearchHistory('   ');
      await store.addSearchHistory('');
      expect(store.loadSearchHistory(), isEmpty);
    });

    test('最多 20 条', () async {
      for (var i = 0; i < 25; i++) {
        await store.addSearchHistory('q$i');
      }
      final h = store.loadSearchHistory();
      expect(h.length, 20);
      expect(h.first, 'q24');
    });

    test('清空', () async {
      await store.addSearchHistory('a');
      await store.clearSearchHistory();
      expect(store.loadSearchHistory(), isEmpty);
    });
  });

  group('设置', () {
    test('默认值', () {
      expect(store.preferredQuality, '720p');
      expect(store.autoFullscreen, isTrue);
      expect(store.playbackSpeed, 1.0);
    });

    test('写入后读回', () async {
      await store.setPreferredQuality('1080p');
      await store.setAutoFullscreen(false);
      await store.setPlaybackSpeed(1.5);
      expect(store.preferredQuality, '1080p');
      expect(store.autoFullscreen, isFalse);
      expect(store.playbackSpeed, 1.5);
    });
  });
}
