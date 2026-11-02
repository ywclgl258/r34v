import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/stream_resolver.dart';

/// **联网**验证 CDN 解析：确认 302 → CDN 直链这条路真的能走通。
///
/// 用 `--dart-define=R34V_LIVE=1` 才会跑，默认跳过——CI 里不该依赖外网。
///
/// ```
/// flutter test test/stream_resolver_live_test.dart --dart-define=R34V_LIVE=1
/// ```
void main() {
  // 用 String.fromEnvironment 判定：bool.fromEnvironment 在 flutter test
  // 的 --dart-define 下拿不到值（实测会恒为 false，导致永远跳过）。
  const flag = String.fromEnvironment('R34V_LIVE');
  final live = flag == '1' || flag == 'true';

  test('实测：解析出 CDN 直链，且比原始地址短且不同域', () async {
    if (!live) {
      markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
      return;
    }

    // 用真实视频页（这一条是实测时抓的，链上 token 过期不影响解析——
    // 解析失败时会返回 null，本测试会因此失败，正是我们要的信号）
    const page =
        'https://rule34video.com/video/4706545/mmd-agano-class-sister-hibikase-kancolle/';
    const original =
        'https://rule34video.com/get_file/58/c42d83b00b9fcb34e7b55475355afa8f/4706000/4706545/4706545_1080p.mp4/?v-acctoken=NDU0NHw0MjY2MjQ3fDB8MzUzMzc5ZjRkNjljNDJhNDdmZWZmZmQyNDI1NzM1ZDkd4bc32e2763665a5';

    final resolver = StreamResolver();
    final cdn = await resolver.resolve(original, pageUrl: page);

    expect(cdn, isNotNull, reason: '站点对 /get_file/ 应回 302，必须能解析出 Location');
    expect(cdn, isNot(original), reason: '解析结果应该是 CDN 地址');
    expect(
      cdn!.contains('boomio-cdn') || cdn.contains('cdn'),
      isTrue,
      reason: '实测跳转到 boomio-cdn，实际拿到：$cdn',
    );

    // 第二次必须命中会话缓存，不再发起网络请求
    final again = resolver.cachedFor(original);
    expect(again, cdn, reason: '解析结果要被缓存，seek 几十次只解析一次');
  }, timeout: const Timeout(Duration(seconds: 60)));
}
