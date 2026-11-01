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
import 'package:r34v/features/detail/detail_page.dart';
import 'package:r34v/features/feed/feed_page.dart';
import 'package:r34v/ui/net_image.dart';
import 'package:r34v/ui/theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 把真实界面渲染成 PNG 存进 evidence/preview/，供人工核对几何。
///
/// 这些不是「设计稿」，是测试环境里跑真实解析器 + 真实主题渲出来的像素。
/// 用固定种子图代替网络封面，所以每次都一样，可以对比改动前后。
///
///     flutter test test/render_evidence_test.dart
String fixture(String n) => File('test/fixtures/$n').readAsStringSync();

/// 生成一张带斜纹的测试封面，比纯色更容易看出裁切与拉伸。
Uint8List coverPng(int seed) {
  const w = 32, h = 18;
  final raw = <int>[];
  for (var y = 0; y < h; y++) {
    raw.add(0); // filter type
    for (var x = 0; x < w; x++) {
      final on = ((x + y + seed) % 8) < 3;
      final r = on ? 40 + (seed * 17) % 120 : 30;
      final g = on ? 90 + (seed * 29) % 100 : 34;
      final b = on ? 150 + (seed * 37) % 90 : 42;
      raw.addAll([r, g, b]);
    }
  }
  return _pngFrom(w, h, raw);
}

Uint8List _pngFrom(int w, int h, List<int> rgb) {
  // 手工拼一个最小 PNG：IHDR + IDAT(zlib stored) + IEND
  final out = <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
  final ihdr = <int>[
    ..._be32(w), ..._be32(h), 8, 2, 0, 0, 0,
  ];
  out.addAll(_chunk('IHDR', ihdr));

  final z = <int>[0x78, 0x01];
  // 每行按 stored block 输出；长度不固定，按 65535 切块
  var i = 0;
  while (i < rgb.length) {
    final take = (rgb.length - i).clamp(0, 65535);
    final last = i + take >= rgb.length ? 1 : 0;
    z.addAll([last, take & 0xFF, (take >> 8) & 0xFF, (~take) & 0xFF, ((~take) >> 8) & 0xFF]);
    z.addAll(rgb.sublist(i, i + take));
    i += take;
  }
  z.addAll(_adler32(rgb));
  out.addAll(_chunk('IDAT', z));
  out.addAll(_chunk('IEND', const []));
  return Uint8List.fromList(out);
}

/// 供其他文件复用：给每张卡一张不同的图。
Uint8List _be32(int v) => Uint8List.fromList([
      (v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF,
    ]);

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
  final len = _be32(data.length).toList();
  final crc = _be32(_crc32([...t, ...data])).toList();
  return [...len, ...t, ...data, ...crc];
}

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

  final cache = <String, Uint8List>{};
  var counter = 0;
  setUpAll(() {
    NetImage.imageOverride = (url) {
      return MemoryImage(cache.putIfAbsent(url, () => coverPng(counter++)));
    };
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
  });

  testWidgets('渲染详情页截图', (tester) async {
    await pumpAt(tester, const DetailPage(videoId: '4703101', slug: 'x'));
    await expectLater(
      find.byType(DetailPage),
      matchesGoldenFile('../evidence/preview/07-detail.png'),
    );
  });
}
