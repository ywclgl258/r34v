import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:r34v/data/parsing/detail_parser.dart';
import 'package:r34v/data/parsing/listing_parser.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/data/remote/site_client.dart';
import 'package:r34v/domain/feed_link.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:r34v/domain/video_detail.dart';
import 'package:r34v/features/feed/feed_page.dart';
import 'package:r34v/features/feed/video_card_tile.dart';
import 'package:r34v/features/library/library_page.dart';
import 'package:r34v/features/search/search_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

String fixture(String n) => File('test/fixtures/$n').readAsStringSync();

/// 假站点：真实解析器 + 冻结 HTML，只替换网络。
class FakeApi extends Rule34Api {
  FakeApi({this.failWith});

  final HttpException? failWith;
  int searchCalls = 0;
  String? lastSearchTarget;

  @override
  Future<FeedPage> fetchFeed(FeedRequest request) async {
    if (failWith != null) throw failWith!;
    const parser = ListingParser();
    if (request.kind == FeedKind.search) {
      searchCalls++;
      lastSearchTarget = request.target;
      return parser.parse(fixture('search-zelda-p2.html'), request);
    }
    final html = request.page <= 1 ? fixture('latest-p1.html') : fixture('latest-p2.html');
    return parser.parse(html, request);
  }

  @override
  Future<VideoDetail> fetchDetail(String p) async {
    if (failWith != null) throw failWith!;
    final d = const DetailParser().parse(fixture('video-4703101.html'));
    if (d == null) throw StateError('fixture 解析失败');
    return d;
  }

  @override
  Future<List<FeedLink>> fetchCategories() async => const [];
}

Widget wrap(Widget child, {Rule34Api? api, SharedPreferences? prefs}) {
  return ProviderScope(
    overrides: [
      if (api != null) rule34ApiProvider.overrideWithValue(api),
      if (prefs != null) sharedPrefsProvider.overrideWithValue(prefs),
    ],
    child: MaterialApp(home: child),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    // 不连网络图
    VideoCardTile.imageProviderOverride = (url) => const AssetImage('assets/icon.png');
  });

  tearDownAll(() {
    VideoCardTile.imageProviderOverride = null;
  });

  group('信息流列数', () {
    // 这是用户直接反馈的问题：手机上一排只有 2 个太浪费 → 改 3 列；
    // 后来驾驶员又要求回到 2 列（3 列时封面裁得太狠、标题挤成小字）。
    // 当前契约：手机 2 列，平板起加列。见 layout_geometry_test.dart。
    test('常见手机宽度排 2 列', () {
      for (final w in [360.0, 390.0, 412.0, 430.0]) {
        expect(feedColumnsFor(w), 2, reason: '宽度 $w 应该排 2 列');
      }
    });

    test('平板与桌面加列', () {
      expect(feedColumnsFor(600), 3);
      expect(feedColumnsFor(840), 4);
      expect(feedColumnsFor(1100), 5);
      expect(feedColumnsFor(1400), 6);
    });

    test('超窄屏仍是 2 列，不会掉成一列', () {
      expect(feedColumnsFor(280), 2);
    });
  });

  group('首页搜索入口', () {
    testWidgets('首页标题栏直接有搜索框，不用先去发现页', (tester) async {
      await tester.pumpWidget(wrap(
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
        api: FakeApi(),
        prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('搜索视频、标签、作者'), findsOneWidget);
      expect(find.widgetWithIcon(InkWell, Icons.search), findsOneWidget);
    });

    testWidgets('非首页列表不带搜索框（标题要放别的内容）', (tester) async {
      await tester.pumpWidget(wrap(
        const FeedPageTab(
          kind: FeedKind.tag,
          target: 'sex',
          showBackButton: false,
        ),
        api: FakeApi(),
        prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('搜索视频、标签、作者'), findsNothing);
      expect(find.textContaining('标签'), findsWidgets);
    });
  });

  group('搜索页', () {
    testWidgets('输入并提交后出结果', (tester) async {
      final api = FakeApi();
      await tester.pumpWidget(wrap(const SearchPage(), api: api, prefs: prefs));
      await tester.pump();

      await tester.enterText(find.byType(TextField), 'zelda');
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(api.searchCalls, greaterThanOrEqualTo(1));
      expect(api.lastSearchTarget, 'zelda');
      expect(find.byType(VideoCardTile), findsWidgets);
    });

    testWidgets('未搜索时显示引导文案', (tester) async {
      await tester.pumpWidget(wrap(const SearchPage(), api: FakeApi(), prefs: prefs));
      await tester.pump();

      expect(find.textContaining('输入关键词开始搜索'), findsOneWidget);
    });

    testWidgets('搜过的词进历史，清空后消失', (tester) async {
      await tester.pumpWidget(wrap(const SearchPage(), api: FakeApi(), prefs: prefs));
      await tester.pump();

      await tester.enterText(find.byType(TextField), 'kirby');
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      // 直接查存储，避开 UI 时序
      expect(LibraryStore(prefs).loadSearchHistory(), contains('kirby'));
    });

    testWidgets('带初始词进来会直接搜', (tester) async {
      final api = FakeApi();
      await tester.pumpWidget(
        wrap(const SearchPage(initialQuery: 'zelda'), api: api, prefs: prefs),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(api.lastSearchTarget, 'zelda');
    });

    testWidgets('搜索失败有提示与重试', (tester) async {
      await tester.pumpWidget(wrap(
        const SearchPage(initialQuery: 'x'),
        api: FakeApi(failWith: HttpException('请求超时（20s）')),
        prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('超时'), findsWidgets);
      expect(find.text('重试'), findsOneWidget);
    });
  });

  group('片库分三类', () {
    testWidgets('四个分页：观看记录 / 视频 / 作者 / 标签', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
        child: const MaterialApp(home: LibraryPage()),
      ));
      await tester.pump();

      // 页签名从「继续观看」改成「观看记录」：现在这一页列的是**全部**
      // 历史（含已看完的），不只是可续播的那些，用「继续观看」会名不副实。
      expect(find.textContaining('观看记录'), findsOneWidget);
      expect(find.textContaining('视频'), findsWidgets);
      expect(find.textContaining('作者'), findsWidgets);
      expect(find.textContaining('标签'), findsWidgets);
    });

    testWidgets('收藏的分类各自显示，不混在一起', (tester) async {
      final store = LibraryStore(prefs);
      await store.toggleFavorite(FavoriteEntry(
        kind: FavoriteKind.model,
        target: 'derpixon',
        title: 'Derpixon',
        path: '/list/model/derpixon',
        addedAt: DateTime.now(),
      ));
      await store.toggleFavorite(FavoriteEntry(
        kind: FavoriteKind.tag,
        target: 'big-breasts',
        title: 'Big Breasts',
        path: '/list/tag/big-breasts',
        addedAt: DateTime.now(),
      ));

      await tester.pumpWidget(ProviderScope(
        overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
        child: const MaterialApp(home: LibraryPage()),
      ));
      await tester.pump();

      // 计数出现在 tab 上
      expect(find.text('作者 1'), findsOneWidget);
      expect(find.text('标签 1'), findsOneWidget);
      expect(find.text('视频 0'), findsOneWidget);
    });

    testWidgets('切到作者页能看到收藏的作者 chip', (tester) async {
      final store = LibraryStore(prefs);
      await store.toggleFavorite(FavoriteEntry(
        kind: FavoriteKind.model,
        target: 'derpixon',
        title: 'Derpixon',
        path: '/list/model/derpixon',
        addedAt: DateTime.now(),
      ));

      await tester.pumpWidget(ProviderScope(
        overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
        child: const MaterialApp(home: LibraryPage()),
      ));
      await tester.pump();

      await tester.tap(find.text('作者 1'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('Derpixon'), findsWidgets);
      expect(find.textContaining('点作者看他的全部作品'), findsOneWidget);
    });

    testWidgets('作者页为空时给引导', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
        child: const MaterialApp(home: LibraryPage()),
      ));
      await tester.pump();

      await tester.tap(find.text('作者 0'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.textContaining('长按作者名'), findsOneWidget);
    });
  });
}
