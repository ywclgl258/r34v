import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:r34v/data/parsing/detail_parser.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:r34v/domain/video_detail.dart';
import 'package:r34v/features/player/player_page.dart';
import 'package:r34v/ui/net_image.dart';
import 'package:r34v/ui/theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 横向滑动调速的**接线**测试。
///
/// `swipe_rate_test.dart` 只证明「位移→倍速」的算得对；这里要证明
/// 手势真的接到了播放页上：滑一下、速度变了、松手回到原来的设置。
///
/// 这一层最容易在重构中悄悄断掉（比如手势被别的 GestureDetector 抢走），
/// 而纯函数测试完全照不出来。
class _FakeApi extends Rule34Api {
  _FakeApi(this.detail);

  final VideoDetail detail;

  @override
  Future<VideoDetail> fetchDetail(String p) async => detail;

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

  /// 抓当前显示的倍速（提示不可见时返回 null）。
  ///
  /// 必须在**松手前**采样：松手后提示只保留 500ms，且速度会回弹到原设置，
  /// 滑完才去看会把「功能没生效」和「已经复原」混为一谈。
  double? sampleRate(WidgetTester tester) {
    final texts = tester
        .widgetList<Text>(find.byType(Text))
        .map((t) => t.data)
        .where((s) => s != null && RegExp(r'^\d+(\.\d+)?x$').hasMatch(s))
        .toList();
    if (texts.isEmpty) return null;
    return double.parse(texts.first!.replaceAll('x', ''));
  }

  late SharedPreferences prefs;
  late VideoDetail detail;

  setUp(() async {
    // 关掉标签翻译：开着的话每个标签都会发一次联网翻译请求，
    // 测试环境里没有网络，请求会挂到超时才结束——测试框架随即报
    // "A Timer is still pending"。这跟本文件要验的手势毫无关系。
    SharedPreferences.setMockInitialValues({'settings_v1_translate_tags': false});
    prefs = await SharedPreferences.getInstance();
    detail = const DetailParser()
        .parse(File('test/fixtures/video-4cat.html').readAsStringSync())!;
    NetImage.imageOverride = (url) => const AssetImage('assets/icon.png');
  });

  tearDownAll(() => NetImage.imageOverride = null);

  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.reset);

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
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump(const Duration(milliseconds: 500));
  }

  /// 视频区**上半部分**的起手点。
  ///
  /// 不用 `rect.center`：控制栏占据视频区下半部分（进度条 + 两排按钮），
  /// 从中心起手经常落在按钮或进度条上，测的就不是"在画面上滑动"了。
  /// 控制栏背景已改成不拦指针，但按钮本身仍然该吃掉自己的点击——
  /// 测试要避开它们，才验证得到手势层。
  Offset videoTopArea(WidgetTester tester) {
    final r = tester.getRect(find.byType(AspectRatio).first);
    return Offset(r.center.dx, r.top + 40);
  }

  Future<void> dragAcross(WidgetTester tester, double dx) async {
    final start = videoTopArea(tester);
    final gesture = await tester.startGesture(start);
    // 分几步移动，模拟真实滑动（一次跳到底可能被当成 fling）
    const steps = 8;
    for (var i = 0; i < steps; i++) {
      await gesture.moveBy(Offset(dx / steps, 0));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await gesture.up();
    await tester.pump();
  }

  group('横向滑动调倍速', () {
    testWidgets('滑动过程中倍速变化，且提示可见', (tester) async {
      await pump(tester);

      final start = videoTopArea(tester);
      final gesture = await tester.startGesture(start);
      for (var i = 0; i < 8; i++) {
        await gesture.moveBy(const Offset(25, 0)); // 累计 200dp
        await tester.pump(const Duration(milliseconds: 16));
      }

      final rate = sampleRate(tester);
      expect(rate, isNotNull, reason: '滑动中应该显示倍速提示');
      expect(rate, greaterThan(1.0), reason: '右滑应当加速，实际 $rate');

      await gesture.up();
      await tester.pump();
      // 松手后有 500ms 回弹延时，必须让它跑完，否则测试结束时
      // 框架会报 "A Timer is still pending"（这本身也是泄漏的征兆）。
      await tester.pump(const Duration(milliseconds: 700));
    });

    testWidgets('左滑减速，提示出现且小于 1.0', (tester) async {
      await pump(tester);

      final start = videoTopArea(tester);
      final gesture = await tester.startGesture(start);
      for (var i = 0; i < 8; i++) {
        await gesture.moveBy(const Offset(-25, 0));
        await tester.pump(const Duration(milliseconds: 16));
      }

      final rate = sampleRate(tester);
      expect(rate, isNotNull);
      expect(rate, lessThan(1.0), reason: '左滑应当减速，实际 $rate');

      await gesture.up();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 700));
    });

    testWidgets('松手后提示消失，且不改掉持久化的默认倍速', (tester) async {
      // 用户本来设了 1.5x
      SharedPreferences.setMockInitialValues({'settings_v1_speed': 1.5, 'settings_v1_translate_tags': false});
      prefs = await SharedPreferences.getInstance();
      await pump(tester);

      await dragAcross(tester, 200);
      // 等提示消失（回弹延时 500ms）
      await tester.pump(const Duration(milliseconds: 700));

      expect(sampleRate(tester), isNull, reason: '松手后提示应当收起');
      final store = LibraryStore(prefs);
      expect(store.playbackSpeed, 1.5,
          reason: '临时调速不该改掉持久化的默认倍速');
    });

    testWidgets('竖向滑动不会改倍速（左右分区调亮度/音量）', (tester) async {
      await pump(tester);

      final start = videoTopArea(tester);
      final gesture = await tester.startGesture(start);
      for (var i = 0; i < 8; i++) {
        await gesture.moveBy(const Offset(0, -20));
        await tester.pump(const Duration(milliseconds: 16));
      }
      await gesture.up();
      await tester.pump();

      // 竖滑之后不应该出现倍速胶囊
      final rateTexts = tester
          .widgetList<Text>(find.textContaining('x'))
          .map((t) => t.data)
          .where((s) => s != null && RegExp(r'^\d+(\.\d+)?x$').hasMatch(s))
          .toList();
      expect(rateTexts, isEmpty, reason: '竖向滑动不该触发倍速提示');
    });

    testWidgets('轻微横向抖动不算调速（避免误触）', (tester) async {
      await pump(tester);

      await dragAcross(tester, 12); // 小于 kSwipeRateSlop

      final rateTexts = tester
          .widgetList<Text>(find.textContaining('x'))
          .map((t) => t.data)
          .where((s) => s != null && RegExp(r'^\d+(\.\d+)?x$').hasMatch(s))
          .toList();
      expect(rateTexts, isEmpty, reason: '12dp 的抖动不该改倍速');
    });
  });
}
