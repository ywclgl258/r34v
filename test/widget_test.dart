import 'dart:io';
import 'dart:typed_data';

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
import 'package:r34v/features/detail/detail_page.dart';
import 'package:r34v/features/feed/feed_page.dart';
import 'package:r34v/features/feed/video_card_tile.dart';
import 'package:r34v/features/library/library_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

String fixture(String name) => File('test/fixtures/$name').readAsStringSync();

/// 1×1 透明 PNG。widget 测试里用它顶替所有网络图。
final Uint8List _transparentPng = Uint8List.fromList([
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, //
  0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
  0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
  0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
  0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
  0x42, 0x60, 0x82,
]);

/// 假站点：不发网络请求，直接把冻结的 HTML 交给真实解析器。
///
/// 这样 UI 测试跑的是**真实解析链路**，只有「网络」这一步被替换，
/// 比手搓假模型更能发现字段映射问题。
class FakeApi extends Rule34Api {
  FakeApi({this.failWith});

  /// 非 null 时所有请求都抛这个异常，用于测错误态。
  final HttpException? failWith;

  int feedCalls = 0;
  int detailCalls = 0;

  @override
  Future<FeedPage> fetchFeed(FeedRequest request) async {
    feedCalls++;
    if (failWith != null) throw failWith!;
    const parser = ListingParser();
    final html = switch (request.kind) {
      FeedKind.search => fixture('search-zelda-p2.html'),
      FeedKind.tag => fixture('tag-sex-p2.html'),
      FeedKind.model => fixture('model-derpixon.html'),
      _ => request.page <= 1 ? fixture('latest-p1.html') : fixture('latest-p2.html'),
    };
    return parser.parse(html, request);
  }

  @override
  Future<VideoDetail> fetchDetail(String pathOrUrl) async {
    detailCalls++;
    if (failWith != null) throw failWith!;
    const parser = DetailParser();
    final d = parser.parse(fixture('video-4703101.html'));
    if (d == null) throw StateError('fixture 解析失败');
    return d;
  }

  @override
  Future<List<FeedLink>> fetchCategories() async {
    if (failWith != null) throw failWith!;
    return const ListingParser().extractLinks(fixture('categories.html'), segment: 'categories');
  }
}


Widget _wrap(Widget child, {Rule34Api? api, SharedPreferences? prefs}) {
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

  // 测试里不发图片请求：换成一个 1x1 的透明图，避免真连图床导致超时
  setUpAll(() {
    VideoCardTile.imageProviderOverride = (url) => MemoryImage(_transparentPng);
  });

  tearDownAll(() {
    VideoCardTile.imageProviderOverride = null;
  });

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  group('列表页 UI', () {
    testWidgets('加载后渲染出卡片，标题与时长可见', (tester) async {
      await tester.pumpWidget(_wrap(
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
        api: FakeApi(), prefs: prefs,
      ));
      // 首帧是 loading
      expect(find.byType(CircularProgressIndicator), findsWidgets);

      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.byType(VideoCardTile), findsWidgets);
      final firstCard = tester.widgetList<VideoCardTile>(find.byType(VideoCardTile)).first.card;
      expect(find.textContaining(firstCard.duration), findsWidgets);
      // 标题可能被省略号截断，用 textContaining
      expect(find.textContaining(firstCard.title.substring(0, 6)), findsWidgets);
    });

    testWidgets('滚动到底触发加载第 2 页', (tester) async {
      final api = FakeApi();
      await tester.pumpWidget(_wrap(
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
        api: api, prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(api.feedCalls, 1);

      // 滚到底
      final scrollable = find.byType(CustomScrollView);
      await tester.fling(scrollable, const Offset(0, -20000), 5000);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(api.feedCalls, greaterThanOrEqualTo(2), reason: '滚动到底没有请求下一页');
    });

    testWidgets('网络失败时显示错误与重试，不白屏', (tester) async {
      await tester.pumpWidget(_wrap(
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
        api: FakeApi(failWith: HttpException('请求超时（20s）')), prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('超时'), findsWidgets);
      expect(find.text('重试'), findsWidgets);
      expect(find.byType(VideoCardTile), findsNothing);
    });

    testWidgets('人机验证有专属提示', (tester) async {
      await tester.pumpWidget(_wrap(
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
        api: FakeApi(failWith: HttpException('站点要求人机验证')), prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('人机验证'), findsWidgets);
    });
  });

  group('详情页 UI', () {
    testWidgets('渲染标题、四档清晰度、标签', (tester) async {
      await tester.pumpWidget(_wrap(
        const DetailPage(videoId: '4703101', slug: 'moe-love-2-by-doll-house'),
        api: FakeApi(), prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('Moe Love 2'), findsWidgets);
      // 四档清晰度 chip
      for (final q in ['360p', '480p', '720p', '1080p']) {
        expect(find.text(q), findsWidgets, reason: '缺少 $q 档位按钮');
      }
      // 标签（美化后首字母大写）
      expect(find.text('Big Breasts'), findsWidgets);
    });

    testWidgets('详情加载失败有提示', (tester) async {
      await tester.pumpWidget(_wrap(
        const DetailPage(videoId: '1', slug: 'x'),
        api: FakeApi(failWith: HttpException('详情页返回 500', status: 500)), prefs: prefs,
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('500'), findsWidgets);
    });
  });

  group('片库 UI', () {
    testWidgets('空状态有引导文案', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
        child: const MaterialApp(home: LibraryPage()),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('还没有看过的视频'), findsWidgets);
    });

    testWidgets('有历史时继续观看列出条目', (tester) async {
      final store = LibraryStore(prefs);
      await store.saveWatch(WatchEntry(
        videoId: '4703101',
        title: 'Moe Love 2',
        thumb: 'https://example.com/x.jpg',
        path: '/video/4703101/moe-love-2-by-doll-house/',
        positionSeconds: 300,
        durationSeconds: 1443,
        updatedAt: DateTime.now(),
      ));

      await tester.pumpWidget(ProviderScope(
        overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
        child: const MaterialApp(home: LibraryPage()),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('Moe Love 2'), findsWidgets);
      expect(find.textContaining('05:00'), findsWidgets);
      expect(find.textContaining('24:03'), findsWidgets);
    });
  });
}







