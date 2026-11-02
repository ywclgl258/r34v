import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/stream_resolver.dart';

/// **联网**验证：CDN 直链解析 + 远端区间取用（断点续传的基础）。
///
/// ```
/// flutter test test/stream_resume_live_test.dart --dart-define=R34V_LIVE=1
/// ```
///
/// 断点续传依赖三件事，这里逐一验证：
/// 1. 能解析出 CDN 直链（302 → Location）
/// 2. 该直链支持 `Range: bytes=N-` 形式的**远端起取**
/// 3. 同一链接可重复使用（不然续传时链接已失效）
void main() {
  const flag = String.fromEnvironment('R34V_LIVE');
  final live = flag == '1' || flag == 'true';

  test('实测：解析 CDN 直链后，可从任意偏移续取', () async {
    if (!live) {
      markTestSkipped('需要联网：加 --dart-define=R34V_LIVE=1');
      return;
    }

    const page =
        'https://rule34video.com/video/4706545/mmd-agano-class-sister-hibikase-kancolle/';

    // **不能写死 token**：页面里的 `v-acctoken` 是有时效的，写死的链
    // 过一段时间就解析不出 302 了（实测跑第二次就失败）。
    // 这里先抓一次页面，从 flashvars 里取当下的 `video_url`。
    final client0 = HttpClient();
    String? original;
    try {
      final req = await client0.getUrl(Uri.parse(page));
      req.headers.set(
        'User-Agent',
        'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 '
            '(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
      );
      final res = await req.close();
      final html = await res.transform(const Utf8Decoder(allowMalformed: true)).join();
      final m = RegExp(r"video_url:\s*'([^']+)'").firstMatch(html);
      original = m?.group(1);
    } finally {
      client0.close(force: true);
    }
    expect(original, isNotNull, reason: '页面里应能抓到 video_url');

    final resolver = StreamResolver();
    final cdn = await resolver.resolve(original!, pageUrl: page);
    expect(cdn, isNotNull, reason: '应能解析出 CDN 直链');

    // 用 Dart 自带 HttpClient 取两个区间，验证 Range 生效
    final client = HttpClient();
    try {
      Future<(int code, int size, String? range)> fetch(int start, int end) async {
        final req = await client.getUrl(Uri.parse(cdn!));
        req.headers.set('Range', 'bytes=$start-$end');
        req.headers.set('Referer', page);
        final res = await req.close();
        var got = 0;
        await for (final chunk in res) {
          got += chunk.length;
        }
        return (res.statusCode, got, res.headers.value('content-range'));
      }

      // 起点 0：模拟首次下载。同时用 Content-Range 拿到真实总长。
      final head = await fetch(0, 65535);
      expect(head.$1, 206, reason: '应支持 Range，实际 ${head.$1}');
      expect(head.$2, 65536);

      // **远端偏移要按真实大小算**。
      // 曾经写死 80MB，结果这个视频只有几十 MB，服务端回 416
      // （Range Not Satisfiable）——那不是不支持续传，是我们请求了
      // 超出文件末尾的区间。所以先解析出总长，取中段。
      final total = int.tryParse(
            RegExp(r'/(\d+)$').firstMatch(head.$3 ?? '')?.group(1) ?? '',
          ) ??
          0;
      expect(total, greaterThan(0), reason: 'Content-Range 应带总长，实际 ${head.$3}');

      final offset = total ~/ 2;
      final tail = await fetch(offset, offset + 65535);
      expect(tail.$1, 206, reason: '中段区间要能取，实际 ${tail.$1}');
      expect(tail.$2, 65536);
      expect(
        tail.$3,
        startsWith('bytes $offset-'),
        reason: 'Content-Range 应确认从 $offset 开始，实际 ${tail.$3}',
      );

      // 同一链接再用一次：续传时链接不能已经作废
      final again = await fetch(0, 1023);
      expect(again.$1, 206, reason: '同一 CDN 链必须可复用，实际 ${again.$1}');
      // ignore: avoid_print
      print('视频总长: $total 字节，续传偏移: $offset');
    } finally {
      client.close(force: true);
    }
  }, timeout: const Timeout(Duration(seconds: 90)));
}

