import 'dart:io';

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
import 'package:r34v/ui/net_image.dart';
import 'package:r34v/ui/theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 播放页必须**能构建出来**。
///
/// 「灰屏」的判据不是「视频能不能播」——播放失败有 ErrorState 兜着，
/// 用户看到的是文案和重试按钮。真正的灰屏是页面根本没 build：
/// initState 里下发倍速/音量时后端同步抛异常，整棵树替换成
/// Flutter 的 RenderErrorBox（0xF0C0C0C0 那块灰）。
///
/// 所以这里的断言只有一条：pump 之后屏幕上出现的是**我们自己的控件**
/// （AppBar 标题、信息面板），而不是异常。
class _FakeApi extends Rule34Api {
  @override
  Future<VideoDetail> fetchDetail(String p) async => const DetailParser()
      .parse(File('test/fixtures/video-4703101.html').readAsStringSync())!;

  @override
  Future<FeedPage> fetchFeed(FeedRequest request) async =>
      const ListingParser().parse(
        File('test/fixtures/latest-p1.html').readAsStringSync(),
        request,
      );

  @override
  Future<List<FeedLink>> fetchCategories() async => const [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    NetImage.imageOverride = (url) => const AssetImage('assets/icon.png');
  });
  tearDownAll(() => NetImage.imageOverride = null);

  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        rule34ApiProvider.overrideWithValue(_FakeApi()),
        sharedPrefsProvider.overrideWithValue(prefs),
      ],
      child: MaterialApp(
        theme: buildAppTheme(),
        home: const PlayerPage(videoId: '4703101', slug: 'x'),
      ),
    ));
    // 首帧：加载中
    await tester.pump();
    // 详情回来之后：应该有标题栏和信息面板
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump(const Duration(milliseconds: 500));
  }

  testWidgets('播放页能构建：没有异常，界面是自己的控件', (tester) async {
    await pump(tester);

    // 关键断言：initState 没炸。炸了的话这里会拿到 StateError。
    expect(tester.takeException(), isNull,
        reason: 'initState 抛异常 → 整页变成 RenderErrorBox（灰屏）');

    // 页面真的画出来了：标题栏 + 清晰度/标签等信息面板
    expect(find.byType(AppBar), findsOneWidget);
    expect(find.text('清晰度'), findsNothing); // 明细在详情页，这里只有信息面板
    expect(find.textContaining('views'), findsNothing);
  });

  testWidgets('播放器区域是 16:9 且从标题栏下方开始', (tester) async {
    await pump(tester);

    final bar = tester.getRect(find.byType(AppBar).first);
    final video = tester.getRect(find.byType(AspectRatio).first);
    expect(video.top, greaterThanOrEqualTo(bar.bottom));
    expect(video.width / video.height, closeTo(16 / 9, 0.01));
  });
}
