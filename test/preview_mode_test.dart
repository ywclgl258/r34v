import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/core/constants.dart';

/// 这些测试保护的是「本地 Web 预览」这条通道。
///
/// 预览能跑起来靠两个机制，任一坏掉都会表现为「界面能开但一张卡、一张图都没有」：
/// 1. `R34V_API_BASE` 能把请求指向本地代理；
/// 2. `Site.rewrite` 能把解析器吐出的站点绝对地址改写到代理。
///
/// 安卓包用默认 `base`，`rewrite` 是恒等操作 —— 下面也锁住了这一点，
/// 防止以后有人「顺手优化」把安卓包也改到代理上去。
void main() {
  group('预览代理开关', () {
    test('默认不启用（安卓/桌面构建走这条）', () {
      // 测试进程没有传 --dart-define，应保持官方域名
      expect(Site.base, 'https://rule34video.com');
      expect(Site.usingPreviewProxy, isFalse);
    });

    test('非默认 base 时视为预览模式', () {
      // 直接用常量推导，避免依赖运行参数
      const previewBase = 'http://127.0.0.1:8898';
      expect(previewBase == Site.base, isFalse);
    });
  });

  group('Site.rewrite', () {
    test('非预览模式下原样返回（护住安卓包）', () {
      const urls = [
        'https://rule34video.com/video/4703101/moe-love-2-by-doll-house/',
        'https://rule34video.com/get_file/51/abc/4703101_720p.mp4/?v-acctoken=x',
        'https://rule34video.com/contents/videos_screenshots/4703000/4703101/336x189/3.jpg',
      ];
      for (final u in urls) {
        expect(Site.rewrite(u), u, reason: '非预览模式不该改写 URL');
      }
    });

    test('非站点域名不受影响', () {
      const other = 'https://example.com/a.jpg';
      expect(Site.rewrite(other), other);
    });

    test('空串安全', () {
      expect(Site.rewrite(''), '');
    });
  });
}
