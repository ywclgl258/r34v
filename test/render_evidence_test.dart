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
import 'package:r34v/ui/net_image.dart';
import 'package:r34v/ui/theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 把真实界面渲成 PNG 存进 evidence/preview/，供人工核对几何。
///
/// 跑真实解析器 + 真实主题，用带斜纹的种子图代替网络封面，所以每次一致，
/// 可以拿去对比改动前后。
///
///     flutter test test/render_evidence_test.dart --dart-define=R34V_SHOTS=true --update-goldens
///
/// **默认整组跳过，不进 CI。**
///
/// 起因：这文件早先用 `matchesGoldenFile` 无条件跑，CI（Ubuntu）直接红了——
/// 同一份代码在 Windows 与 Linux 上渲出的像素差 1.72% / 0.58%。字形光栅化、
/// 抗锯齿、字体 fallback 跨平台不可能逐像素一致，golden 的正当用途是
/// **同一平台内**的视觉回归，跨平台比对必然误报。
///
/// 而这里的产物本来就是「给人看的截图」，不是断言。所以用 `R34V_SHOTS`
/// 这个 dart-define 当闸：不传就整体 skip，退出码 0；要出图时显式传。
///
/// 为什么不用 `@Tags` + `dart_test.yaml` 的 exclude_tags：那份配置会与
/// 命令行的 `--exclude-tags` **叠加**而不是被覆盖，无论如何都跑不起来。
///
/// 为什么不用 `RenderRepaintBoundary.toImage`：在 widget test 的 FakeAsync
/// 时钟下它不会完成（`runAsync` 里也一样会卡在渲染管线），表现为测试挂死、
/// 无输出、不超时。golden matcher 内部对此做了处理，是这里唯一能用的路。
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

/// 生成一张带斜纹的测试封面。纯色看不出裁切/拉伸，斜纹能。
Uint8List stripedPng(int seed) {
  const w = 32, h = 18;
  final raw = <int>[];
  for (var y = 0; y < h; y++) {
    raw.add(0); // filter type: none
    for (var x = 0; x < w; x++) {
      final on = ((x + y + seed) % 8) < 3;
      raw.add(on ? 40 + (seed * 17) % 120 : 30);
      raw.add(on ? 90 + (seed * 29) % 100 : 34);
      raw.add(on ? 150 + (seed * 37) % 90 : 42);
    }
  }
  return _pngFrom(w, h, raw);
}

Uint8List _pngFrom(int w, int h, List<int> rgb) {
  final out = <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
  out.addAll(_chunk('IHDR', [..._be32(w), ..._be32(h), 8, 2, 0, 0, 0]));

  final z = <int>[0x78, 0x01]; // zlib, no compression
  var i = 0;
  while (i < rgb.length) {
    final take = (rgb.length - i).clamp(0, 65535);
    final last = i + take >= rgb.length ? 1 : 0;
    z.addAll([
      last,
      take & 0xFF, (take >> 8) & 0xFF,
      (~take) & 0xFF, ((~take) >> 8) & 0xFF,
    ]);
    z.addAll(rgb.sublist(i, i + take));
    i += take;
  }
  z.addAll(_adler32(rgb));
  out.addAll(_chunk('IDAT', z));
  out.addAll(_chunk('IEND', const []));
  return Uint8List.fromList(out);
}

List<int> _be32(int v) =>
    [(v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF];

int _crc32(List<int> data) {
  var c = 0xFFFFFFFF;
  for (final b in data) {
    c ^= b;
    for (var k = 0; k < 8; k++) {
      c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1);
    }
  }
  return c ^ 0xFFFFFFFF;
}

List<int> _adler32(List<int> data) {
  var a = 1, b = 0;
  for (final x in data) {
    a = (a + x) % 65521;
    b = (b + a) % 65521;
  }
  return [(b >> 8) & 0xFF, b & 0xFF, (a >> 8) & 0xFF, a & 0xFF];
}

List<int> _chunk(String type, List<int> data) {
  final t = type.codeUnits;
  return [
    ..._be32(data.length),
    ...t,
    ...data,
    ..._be32(_crc32([...t, ...data])),
  ];
}

/// 出图开关。CI 不传这个 define，于是整组 skip（退出码 0）。
const _enabled = bool.fromEnvironment('R34V_SHOTS');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final cache = <String, Uint8List>{};
  var counter = 0;

  setUpAll(() {
    NetImage.imageOverride =
        (url) => MemoryImage(cache.putIfAbsent(url, () => stripedPng(counter++)));
  });
  tearDownAll(() => NetImage.imageOverride = null);

  late SharedPreferences prefs;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    cache.clear();
    counter = 0;
  });

  Future<void> pumpAt(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(1170, 2532); // 390x844 @3x
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
    await tester.pump(const Duration(milliseconds: 800));
  }

  testWidgets('渲染列表页截图', (tester) async {
    await pumpAt(
      tester,
      const FeedPageTab(kind: FeedKind.latest, showBackButton: false),
    );
    await expectLater(
      find.byType(FeedPageTab),
      matchesGoldenFile('../evidence/preview/06-feed-2col.png'),
    );
  }, skip: !_enabled);

  testWidgets('渲染视频页截图（详情 + 播放合并）', (tester) async {
    await pumpAt(tester, const PlayerPage(videoId: '4703101', slug: 'x'));
    await expectLater(
      find.byType(PlayerPage),
      matchesGoldenFile('../evidence/preview/07-detail.png'),
    );
  }, skip: !_enabled);
}
