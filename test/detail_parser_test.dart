import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/parsing/detail_parser.dart';
import 'package:r34v/data/parsing/parse_exception.dart';

String fixture(String name) {
  final f = File('test/fixtures/$name');
  if (!f.existsSync()) {
    fail('fixture 缺失: test/fixtures/$name');
  }
  return f.readAsStringSync();
}

void main() {
  const parser = DetailParser();

  group('详情页解析', () {
    late final detail = parser.parse(fixture('video-4703101.html'), expectedId: '4703101')!;

    test('基础字段', () {
      expect(detail.id, '4703101');
      expect(detail.title, 'Moe Love 2 | Doll House');
      expect(detail.duration, '24:03');
      expect(detail.durationSeconds, 24 * 60 + 3);
      expect(detail.viewsText, contains('19,505'));
      expect(detail.addedText, contains('hours ago'));
    });

    test('四档清晰度全部带 token，按高度降序', () {
      expect(detail.streams.map((s) => s.label).toList(), ['1080p', '720p', '480p', '360p']);
      for (final s in detail.streams) {
        expect(s.url, startsWith('https://'));
        expect(s.url, contains('v-acctoken'), reason: '直链必须带 token，否则会被拒');
        expect(s.height, greaterThan(0));
      }
      // 文件名要和档位对得上
      expect(detail.streams.firstWhere((s) => s.label == '720p').fileName, contains('720p'));
    });

    test('HD 标记正确', () {
      expect(detail.streams.firstWhere((s) => s.label == '1080p').isHd, isTrue);
      expect(detail.streams.firstWhere((s) => s.label == '720p').isHd, isTrue);
    });

    test('默认清晰度选 720p', () {
      expect(detail.preferredStream?.label, '720p');
    });

    test('标签只取本视频的 21 个，不吃导航和相关推荐', () {
      expect(detail.tags.length, 21, reason: '全页扫 a[href] 会得到 46 个，其中 25 个是别人的');
      expect(detail.tags, contains('big-breasts'));
      expect(detail.tags, contains('blowjob'));
      // 相关推荐区里的标签不该出现
      expect(detail.tags, isNot(contains('anabella-rosfield-final-fantasy')));
    });

    test('分类只取本视频的 1 个，不吃全站分类列表', () {
      expect(detail.categories, ['3d'], reason: '全页扫会吞进食指神探、哆啦A梦等 10 个无关分类');
    });

    test('模型只取本视频的 1 个', () {
      expect(detail.models, ['doll-house']);
    });

    test('封面与时间轴', () {
      expect(detail.poster, contains('/contents/videos_screenshots/'));
      expect(detail.poster, endsWith('preview.jpg'));
      expect(detail.timelineTemplate, contains('{time}'));
      expect(detail.timelineIntervalSeconds, 140);
    });

    test('简介保留分段且不含零宽空格', () {
      expect(detail.description, isNotEmpty);
      expect(detail.description, contains('Doll House'));
      expect(detail.description, isNot(contains('\u200b')));
    });

    test('相关推荐能复用列表解析器', () {
      expect(detail.related.length, 24);
      expect(detail.related.first.thumb, startsWith('https://'));
      expect(detail.related.first.title, isNotEmpty);
    });

    test('作者 id 取到', () {
      expect(detail.authorSlug, '335916');
    });

    test('详情页路径带 slug', () {
      expect(detail.path, '/video/4703101/moe-love-2-by-doll-house/');
    });
  });

  group('详情页容错', () {
    test('空 HTML 返回 null', () {
      expect(parser.parse(''), isNull);
    });

    test('没有 flashvars 的页面返回 null', () {
      expect(parser.parse('<html><body><h1>x</h1></body></html>'), isNull);
    });

    test('flashvars 被截断不崩', () {
      expect(
        parser.parse("<html><script>flashvars = { video_id: '1', video_ti"),
        isNull,
      );
    });

    test('id 不符时抛异常', () {
      expect(
        () => parser.parse(fixture('video-4703101.html'), expectedId: '999'),
        throwsA(isA<ParseException>()),
      );
    });

    test('无直链时 assertPlayable 抛异常', () {
      final d = parser.parse(fixture('video-4703101.html'))!;
      final broken = d.copyWith(streams: const []);
      expect(() => parser.assertPlayable(broken), throwsA(isA<ParseException>()));
    });
  });
}
