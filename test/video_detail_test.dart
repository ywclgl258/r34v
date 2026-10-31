import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/domain/video_detail.dart';

StreamVariant _v(String label, {bool hd = false}) => StreamVariant(
      label: label,
      url: 'https://rule34video.com/get_file/x/$label.mp4/?v-acctoken=abc',
      isHd: hd,
    );

VideoDetail _detail(List<StreamVariant> streams) => VideoDetail(
      id: '4703101',
      title: 'Moe Love 2',
      duration: '24:03',
      durationSeconds: 1443,
      viewsText: '20K',
      addedText: '11 hours ago',
      parentSlug: 'moe-love-2-by-doll-house',
      authorSlug: '335916',
      authorName: 'AbstractAlex',
      avatarUrl: 'https://x/a.webp',
      tags: const ['blowjob'],
      categories: const ['3d'],
      models: const ['doll-house'],
      description: 'desc',
      poster: 'https://x/p.jpg',
      streams: streams,
      timelineTemplate: 'https://x/{time}.jpg',
      timelineIntervalSeconds: 140,
      related: const [],
    );

void main() {
  group('清晰度选择', () {
    test('有 720p 时优先 720p（体积与画质平衡）', () {
      final d = _detail([_v('1080p', hd: true), _v('720p', hd: true), _v('480p'), _v('360p')]);
      expect(d.preferredStream?.label, '720p');
    });

    test('没有 720p 时取不超过 1080p 的最高档', () {
      final d = _detail([_v('1080p', hd: true), _v('480p'), _v('360p')]);
      expect(d.preferredStream?.label, '1080p');
    });

    test('只有 360p 也能播', () {
      final d = _detail([_v('360p')]);
      expect(d.preferredStream?.label, '360p');
    });

    test('没有直链时返回 null（调用方必须处理）', () {
      expect(_detail(const []).preferredStream, isNull);
    });

    test('高度解析：不受档位顺序影响', () {
      expect(_v('1080p').height, 1080);
      expect(_v('720p').height, 720);
      expect(_v('480p').height, 480);
      expect(_v('360p').height, 360);
      expect(_v('无标记').height, 0);
    });

    test('文件名提取用于日志与下载命名', () {
      expect(_v('720p').fileName, '720p.mp4');
      expect(
        const StreamVariant(
          label: '720p',
          url: 'https://rule34video.com/get_file/51/abc/4703000/4703101/4703101_720p.mp4/?v-acctoken=x',
          isHd: true,
        ).fileName,
        '4703101_720p.mp4',
      );
    });
  });

  group('直链过期恢复的路径拼装', () {
    test('详情页路径带 slug —— 回源换链要靠它', () {
      final d = _detail([_v('720p')]);
      expect(d.path, '/video/4703101/moe-love-2-by-doll-house/');
    });

    test('slug 为空时会拼出裸 id 路径（站点会 404，调用方应提前拦截）', () {
      final d = _detail([_v('720p')]);
      final noSlug = VideoDetail(
        id: d.id,
        title: d.title,
        duration: d.duration,
        durationSeconds: d.durationSeconds,
        viewsText: d.viewsText,
        addedText: d.addedText,
        parentSlug: '',
        authorSlug: d.authorSlug,
        authorName: d.authorName,
        avatarUrl: d.avatarUrl,
        tags: d.tags,
        categories: d.categories,
        models: d.models,
        description: d.description,
        poster: d.poster,
        streams: d.streams,
        timelineTemplate: d.timelineTemplate,
        timelineIntervalSeconds: d.timelineIntervalSeconds,
        related: d.related,
      );
      expect(noSlug.path, '/video/4703101//');
    });
  });

  group('copyWith', () {
    test('换 streams 不影响其他字段', () {
      final d = _detail([_v('720p')]);
      final d2 = d.copyWith(streams: [_v('480p')]);
      expect(d2.streams.single.label, '480p');
      expect(d2.title, d.title);
      expect(d2.tags, d.tags);
      expect(d2.durationSeconds, d.durationSeconds);
    });

    test('原对象不被改动', () {
      final d = _detail([_v('720p')]);
      d.copyWith(streams: [_v('360p')]);
      expect(d.streams.single.label, '720p');
    });
  });
}
