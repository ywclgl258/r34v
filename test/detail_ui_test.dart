import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/parsing/detail_parser.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:r34v/domain/video_detail.dart';
import 'package:r34v/features/player/player_page.dart';
import 'package:r34v/ui/net_image.dart';
import 'package:r34v/ui/theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 用冻结的真实页面渲染「详情 + 播放」单页，验证下载区 / 上传者 / 标签
/// 真的出现在界面上。
///
/// 这组测试原本针对独立的 DetailPage。详情与播放合并成一个页面后，
/// 断言内容不变、被断言的对象变了——这正好也验证了合并没有丢东西：
/// 原来详情页上有的信息，现在播放页的信息面板里都得还在。
class _FakeApi extends Rule34Api {
  _FakeApi(this.detail);

  final VideoDetail detail;

  @override
  Future<VideoDetail> fetchDetail(String pathOrUrl) async => detail;

  @override
  Future<FeedPage> fetchFeed(FeedRequest request) async => FeedPage(
        cards: const [],
        pager: const Pager(current: 1, total: 1),
        request: request,
        heading: '',
      );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;
  late VideoDetail detail;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    detail = const DetailParser()
        .parse(File('test/fixtures/video-4cat.html').readAsStringSync())!;
  });

  tearDownAll(() => NetImage.imageOverride = null);

  Future<void> pump(WidgetTester tester) async {
    // 必须给真实手机尺寸。默认测试画布是 800×600，16:9 视频吃掉 450dp 后
    // 信息面板只剩 ~94dp——ListView 只构建可见范围，下面的「清晰度/上传者/
    // 标签」压根不会出现在树里，断言会以「找不到」的形式误报。
    tester.view.physicalSize = const Size(1170, 2532); // 390×844 @3x
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.reset);

    // 不发网络图：离线环境里封面/头像是纯装饰，不影响这组断言。
    NetImage.imageOverride = (url) => const AssetImage('assets/icon.png');

    await tester.pumpWidget(ProviderScope(
      overrides: [
        rule34ApiProvider.overrideWithValue(_FakeApi(detail)),
        sharedPrefsProvider.overrideWithValue(prefs),
      ],
      child: MaterialApp(
        theme: buildAppTheme(),
        home: PlayerPage(videoId: detail.id, slug: 'mario'),
      ),
    ));
    await tester.pump();
    // 详情回来之后还要等信息面板布局完成
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump(const Duration(milliseconds: 500));
  }

  /// 信息面板是 ListView，屏幕外的内容不会被构建。
  /// 要断言「某个区块存在」就得先滚到它。
  ///
  /// 不用 `scrollUntilVisible`：目标不存在时它会直接抛 `StateError: No element`
  /// （finder 取 `.first` 时炸），把「这个区块没渲染出来」这条本该清楚的
  /// 断言失败，变成一个看不出所以然的异常。这里自己滚，滚到底还没找到
  /// 就交给后面的 expect 报出人话。
  Future<void> scrollToward(WidgetTester tester, String text) async {
    final scrollable = find.byType(Scrollable).last;
    for (var i = 0; i < 12; i++) {
      if (find.text(text).evaluate().isNotEmpty) return;
      await tester.drag(scrollable, const Offset(0, -260));
      await tester.pump();
    }
  }

  group('合并后的单页：原详情页的信息都在', () {
    testWidgets('下载区出现，四个清晰度按钮都在', (tester) async {
      await pump(tester);
      await scrollToward(tester, '下载');

      expect(find.text('下载'), findsOneWidget);
      for (final opt in detail.downloads) {
        expect(
          find.widgetWithText(OutlinedButton, opt.label),
          findsOneWidget,
          reason: '缺少 ${opt.label} 的下载按钮',
        );
      }
    });

    testWidgets('清晰度 chip 也在（原来只有详情页有）', (tester) async {
      await pump(tester);
      // 合并时把详情页的清晰度组搬进了信息面板，播放控件里的菜单之外
      // 还有一处好找的入口
      expect(find.text('清晰度'), findsOneWidget);
      for (final s in detail.streams) {
        expect(find.widgetWithText(ActionChip, s.label), findsWidgets,
            reason: '缺少 ${s.label} 档位');
      }
    });

    testWidgets('上传者一行出现，且显示的是上传者名字', (tester) async {
      await pump(tester);
      await scrollToward(tester, '上传者');

      expect(find.text('上传者'), findsOneWidget);
      expect(find.text(detail.uploaderName), findsWidgets);
    });

    testWidgets('上传者与作者分开显示', (tester) async {
      // 这个视频没有 Artist 分组，所以不应出现「作者」标题
      await pump(tester);
      await scrollToward(tester, '上传者');

      expect(find.text('上传者'), findsOneWidget);
      expect(find.text('作者'), findsNothing);
    });

    testWidgets('没有评论时不显示评论区', (tester) async {
      await pump(tester);
      expect(find.textContaining('评论'), findsNothing);
    });

    testWidgets('标签默认显示中文', (tester) async {
      await pump(tester);
      await scrollToward(tester, '标签');
      // fixture 的标签是 pov / large-butt / sound / vertical-video，
      // 其中 pov 词典收录为「第一人称」
      expect(find.text('第一人称'), findsWidgets);
    });

    testWidgets('关掉翻译开关后显示英文原文', (tester) async {
      // 直接写 prefs 模拟用户在设置里关掉
      SharedPreferences.setMockInitialValues({'settings_v1_translate_tags': false});
      prefs = await SharedPreferences.getInstance();
      await pump(tester);
      await scrollToward(tester, '标签');

      expect(find.text('第一人称'), findsNothing);
      expect(find.text('Pov'), findsWidgets);
    });
  });
}
