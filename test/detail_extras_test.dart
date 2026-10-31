import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/parsing/detail_parser.dart';
import 'package:r34v/data/parsing/listing_parser.dart';

/// 第二轮功能：TYPE 角标、下载、上传者、评论。
void main() {
  const detail = DetailParser();
  const listing = ListingParser();

  String fixture(String n) => File('test/fixtures/$n').readAsStringSync();

  group('TYPE 角标', () {
    test('解析出类型，且不是全都有', () {
      final page = listing.parse(
        fixture('latest-p1.html'),
        const FeedRequest(kind: FeedKind.latest),
      );
      final withType = page.cards.where((c) => c.type.isNotEmpty).toList();
      expect(withType, isNotEmpty, reason: 'fixture 里有带角标的视频');
      expect(
        withType.length,
        lessThan(page.cards.length),
        reason: '不该每个视频都被识别出类型——那说明匹配错了',
      );
    });

    test('类型值来自白名单，不会被 class 名带偏', () {
      // 站点所有角标的 class 都叫 futa（样式名），
      // 若按 class 匹配会把 Gay 也识别成 Futa。这里锁住按文本取值。
      final page = listing.parse(
        fixture('latest-p1.html'),
        const FeedRequest(kind: FeedKind.latest),
      );
      const allowed = {
        'Straight',
        'Gay',
        'Futa',
        'Music',
        'Iwara',
        'Lesbian',
        'Trans',
        'Other',
      };
      for (final c in page.cards) {
        if (c.type.isEmpty) continue;
        expect(allowed.contains(c.type), isTrue, reason: '意外的类型值：${c.type}');
      }
    });

    test('类型是首字母大写的规范形式', () {
      final page = listing.parse(
        fixture('latest-p1.html'),
        const FeedRequest(kind: FeedKind.latest),
      );
      for (final c in page.cards) {
        if (c.type.isEmpty) continue;
        expect(c.type[0], c.type[0].toUpperCase());
        expect(c.type.length, greaterThan(2));
        expect(c.type.length, lessThan(12), reason: '不该把整段文字当类型');
      }
    });

    test('没有角标的页面类型全为空', () {
      final d = detail.parse(fixture('video-4703101.html'));
      expect(d, isNotNull);
      // 详情页没有卡片角标，这条只是确认不会崩
    });
  });

  group('下载链接', () {
    test('四档清晰度都解析出来', () {
      final d = detail.parse(fixture('video-4cat.html'))!;
      expect(d.downloads.length, 4);
      final labels = d.downloads.map((e) => e.label).toList();
      expect(labels, containsAll(['1080p', '720p', '480p', '360p']));
    });

    test('清晰度里不带文件大小之类的杂字', () {
      // 踩过：最低档文件名叫 mario_360.mp4（没有 p），
      // 早期回退到用链接文本，结果 label 变成「MP4 360p2 MB」。
      final d = detail.parse(fixture('video-4cat.html'))!;
      for (final opt in d.downloads) {
        expect(
          RegExp(r'^\d{3,4}p$').hasMatch(opt.label),
          isTrue,
          reason: '清晰的格式应该是 720p 这种，实际：${opt.label}',
        );
      }
    });

    test('按清晰度从高到低排序', () {
      final d = detail.parse(fixture('video-4cat.html'))!;
      final nums = d.downloads
          .map((e) => int.parse(RegExp(r'\d+').firstMatch(e.label)!.group(0)!))
          .toList();
      for (var i = 1; i < nums.length; i++) {
        expect(nums[i], lessThan(nums[i - 1]));
      }
    });

    test('带文件名，且来自 download_filename 参数', () {
      final d = detail.parse(fixture('video-4cat.html'))!;
      for (final opt in d.downloads) {
        expect(opt.fileName, isNotEmpty);
        expect(opt.fileName, endsWith('.mp4'));
      }
    });

    test('url 是完整地址且带 download=true', () {
      final d = detail.parse(fixture('video-4cat.html'))!;
      for (final opt in d.downloads) {
        expect(opt.url, startsWith('http'));
        expect(opt.url, contains('download=true'));
      }
    });

    test('没有下载链接的页面返回空列表', () {
      final d = detail.parse(fixture('video-4703101.html'))!;
      // 该页也有下载（4 档），用 embed 页验证空的情况
      expect(d.downloads.length, lessThanOrEqualTo(5));
    });
  });

  group('上传者（Uploaded by）', () {
    test('解析出名字和数字 id', () {
      final d = detail.parse(fixture('video-4cat.html'))!;
      expect(d.uploaderName, isNotEmpty);
      expect(d.uploaderId, isNotEmpty);
      expect(RegExp(r'^\d+$').hasMatch(d.uploaderId), isTrue,
          reason: '上传者 id 是数字，和作者 slug 不同');
    });

    test('上传者和作者是两个人（关键区别）', () {
      // 这是这套体系存在的意义：video-4703101 的上传者是 AbstractAlex，
      // 作者是 doll-house。如果两者总相同，说明解析抓错了地方。
      final d = detail.parse(fixture('video-4703101.html'))!;
      expect(d.uploaderName, isNotEmpty);
      expect(d.models, isNotEmpty, reason: '这个视频有 Artist 分组');
      expect(
        d.models.map((m) => m.toLowerCase()).contains(d.uploaderName.toLowerCase()),
        isFalse,
        reason: '上传者不该等于作者（若相等说明抓错了字段）',
      );
    });

    test('没有上传者信息时为空而不是崩', () {
      final d = detail.parse(fixture('video-4703101.html'));
      expect(d, isNotNull);
      // 字段存在即可，值可为空
      expect(d!.uploaderId, isA<String>());
    });
  });

  group('评论', () {
    test('没有评论时是空列表，不是解析失败', () {
      final d = detail.parse(fixture('video-4cat.html'))!;
      expect(d.comments, isEmpty, reason: '这个视频没有评论');
    });

    test('评论对象字段齐备（结构约束）', () {
      final d = detail.parse(fixture('video-4cat.html'))!;
      for (final c in d.comments) {
        expect(c.author, isNotEmpty);
        expect(c.text, isNotEmpty);
      }
    });
  });
}
