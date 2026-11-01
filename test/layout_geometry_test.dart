import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/parsing/detail_parser.dart';
import 'package:r34v/data/parsing/listing_parser.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/domain/feed_link.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:r34v/domain/video_detail.dart';
import 'package:r34v/features/player/player_page.dart';
import 'package:r34v/features/feed/feed_page.dart';
import 'package:r34v/features/feed/video_card_tile.dart';
import 'package:r34v/features/feed/video_grid.dart';
import 'package:r34v/ui/net_image.dart';
import 'package:r34v/ui/theme.dart';
import 'package:r34v/ui/tokens.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 界面几何的回归测试。
///
/// 这三个断言对应驾驶员报的三个现象，全都不是「感觉」问题，
/// 而是能被矩形坐标证伪的具体缺陷：
///
/// 1. 详情页封面被标题栏压掉 48dp —— 「上边视频部分显示不完全」
/// 2. 网格 `childAspectRatio` 与内容高度不匹配 —— 「缩略图之间间隙有点大」
/// 3. 每行 3 个、卡宽仅 115dp —— 「改为 2 个视频一排」
String fixture(String n) => File('test/fixtures/$n').readAsStringSync();

/// 1×1 不透明 PNG，测试里顶替所有网络图。
final Uint8List _png = Uint8List.fromList([
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, //
  0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
  0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
  0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
  0x54, 0x08, 0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00,
  0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xDD, 0x8D,
  0xB0, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
  0x44, 0xAE, 0x42, 0x60, 0x82,
]);

class _FakeApi extends Rule34Api {
  @override
  Future<VideoDetail> fetchDetail(String p) async =>
      const DetailParser().parse(fixture('video-4703101.html'))!;

  @override
  Future<FeedPage> fetchFeed(FeedRequest request) async =>
      const ListingParser().parse(fixture('latest-p1.html'), request);

  @override
  Future<List<FeedLink>> fetchCategories() async => const [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() => NetImage.imageOverride = (url) => MemoryImage(_png));
  tearDownAll(() => NetImage.imageOverride = null);

  late SharedPreferences prefs;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  /// 390×844 逻辑像素，主流安卓机。
  Future<void> pumpAt(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        rule34ApiProvider.overrideWithValue(_FakeApi()),
        sharedPrefsProvider.overrideWithValue(prefs),
      ],
      child: MaterialApp(theme: buildAppTheme(), home: home),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
  }

  group('列数：手机固定两列', () {
    test('主流手机宽度都是 2 列', () {
      for (final w in [360.0, 390.0, 412.0, 430.0, 480.0]) {
        expect(feedColumnsFor(w), 2, reason: '宽度 $w 应该两列');
      }
    });

    test('窄屏也至少两列，不会退化成一列', () {
      expect(feedColumnsFor(280), 2);
      expect(feedColumnsFor(320), 2);
    });

    test('平板往上加列，但守住卡宽下限', () {
      // 820dp 平板按 3 列算，卡宽 ~260dp，仍然合理
      expect(feedColumnsFor(600), 3);
      expect(feedColumnsFor(840), 4);
      expect(feedColumnsFor(1100), 5);
      expect(feedColumnsFor(1400), 6);
    });

    test('每种宽度下卡宽都不低于 155dp', () {
      for (final w in [360.0, 390.0, 412.0, 430.0, 600.0, 840.0, 1100.0, 1400.0]) {
        final cols = feedColumnsFor(w);
        final tileW =
            (w - R34Space.page * 2 - videoGridGap * (cols - 1)) / cols;
        expect(tileW, greaterThanOrEqualTo(155),
            reason: '宽度 $w / $cols 列 → 卡宽 $tileW，太窄');
      }
    });
  });

  group('列表页：两列、间隙只有令牌值', () {
    testWidgets('一排两张，横竖间距都是 8dp', (tester) async {
      await pumpAt(
        tester,
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
      );

      final tiles = find.byType(VideoCardTile);
      expect(tiles.evaluate().length, greaterThanOrEqualTo(3));

      final r0 = tester.getRect(tiles.at(0));
      final r1 = tester.getRect(tiles.at(1));
      final r2 = tester.getRect(tiles.at(2));

      // 前两张同一行，第三张换行
      expect(r0.top, r1.top, reason: '第二张应该和第一张同一行');
      expect(r2.top, greaterThan(r0.top), reason: '第三张应该换到下一行');

      // 左右、上下间距都必须是同一个令牌值，不能有额外留白
      expect(r1.left - r0.right, closeTo(videoGridGap, 0.01));
      expect(r2.top - r0.bottom, closeTo(videoGridGap, 0.01));

      // 卡宽接近屏宽的一半
      expect(r0.width, closeTo((390 - 32 - videoGridGap) / 2, 0.5));
    });

    testWidgets('卡高恰好等于封面 + 文字，没有多余空白', (tester) async {
      await pumpAt(
        tester,
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
      );

      final r0 = tester.getRect(find.byType(VideoCardTile).at(0));
      final coverH = r0.width * 9 / 16;
      final textH = r0.height - coverH;

      // 文字区 = 上 8 + 标题两行(17×2) + 间距 4 + 元信息 13 + 下 9 = 68
      expect(textH, closeTo(68, 1.5),
          reason: '文字区 ${textH}dp 说明高度分配和内容不符（多了会变成底部空白）');
    });

    testWidgets('标题只有一行时卡片高度不变（同行底边齐平）', (tester) async {
      // 用真实卡片列表渲染两行，检查第 0、1 张底边对齐
      await pumpAt(
        tester,
        const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
      );
      final tiles = find.byType(VideoCardTile);
      final r0 = tester.getRect(tiles.at(0));
      final r1 = tester.getRect(tiles.at(1));
      expect(r0.bottom, r1.bottom);
    });
  });

  // 详情与播放合并成一页后，视频区不再靠 `extendBodyBehindAppBar` 顶到
  // 状态栏下面，而是规规矩矩待在 AppBar 之下的 Column 里。
  // 「被标题栏压掉」这个 bug 在新结构下不可能再发生——但断言留着，
  // 因为它防的是同一类错误：有人再把视频区塞进透明 AppBar 后面。
  group('合并页：视频区不被标题栏遮住', () {
    testWidgets('视频区顶边在标题栏底边之下', (tester) async {
      await pumpAt(tester, const PlayerPage(videoId: '4703101', slug: 'x'));

      final bar = tester.getRect(find.byType(AppBar).first);
      final video = tester.getRect(find.byType(AspectRatio).first);

      expect(video.top, greaterThanOrEqualTo(bar.bottom),
          reason: '视频区被标题栏压掉 ${bar.bottom - video.top}dp');
      // 也不该推太远：贴着栏底才对
      expect(video.top - bar.bottom, lessThan(4));
      // 仍是 16:9
      expect(video.height, closeTo(video.width * 9 / 16, 0.5));
    });

    testWidgets('有状态栏时同样不被遮', (tester) async {
      tester.view.padding = const FakeViewPadding(top: 72); // 24dp @ dpr3
      tester.view.viewPadding = const FakeViewPadding(top: 72);

      await pumpAt(tester, const PlayerPage(videoId: '4703101', slug: 'x'));

      final bar = tester.getRect(find.byType(AppBar).first);
      final video = tester.getRect(find.byType(AspectRatio).first);

      expect(video.top, greaterThanOrEqualTo(bar.bottom));
      expect(video.top - bar.bottom, lessThan(4),
          reason: '留白 ${video.top - bar.bottom}dp，疑似重复叠加了工具栏高度');
    });
  });

  group('播放页：引擎未就绪时不能炸 initState', () {
    testWidgets('打开播放页不应抛出异常（灰屏根因）', (tester) async {
      await pumpAt(tester, const PlayerPage(videoId: '4703101', slug: 'x'));
      expect(tester.takeException(), isNull);
    });
  });
}
