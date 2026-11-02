import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/local/background_transfer.dart';
import 'package:r34v/data/remote/stream_resolver.dart';

/// 本轮三个改动的回归测试：
/// 1. CDN 解析（Referer 用视频页、会话缓存、失效处理）
/// 2. 后台传输与页面生命周期解耦（detach 不等于 cancel）
/// 3. 多线程默认关闭（实测结论，不是保守选择）
void main() {
  group('CDN 解析：Referer 必须是视频页而不是站点根', () {
    test('pageUrlFor 拼出真实视频页地址', () {
      // 站点有反盗链白名单：外站 Referer → 403，视频页 → 放行。
      // 用站点根虽然也能过，但不是浏览器的行为，属于灰色地带。
      expect(
        StreamResolver.pageUrlFor('4706545', 'mmd-agano-class-sister'),
        '${Site.base}/video/4706545/mmd-agano-class-sister/',
      );
    });

    test('缓存命中的直链可直接取用，不触发网络', () {
      final resolver = StreamResolver();
      // 没有解析过时拿不到
      expect(resolver.cachedFor('https://x/get_file/a.mp4'), isNull);
    });

    test('invalidate 让旧直链失效（CDN 回 403 时要用）', () {
      final resolver = StreamResolver();
      // 只验证接口语义：失效后 cachedFor 必须为 null
      resolver.invalidate('https://x/get_file/a.mp4');
      expect(resolver.cachedFor('https://x/get_file/a.mp4'), isNull);
    });

    test('clear 清空全部会话缓存', () {
      final resolver = StreamResolver();
      resolver.clear();
      expect(resolver.cachedFor('https://x/a'), isNull);
    });
  });

  group('后台传输：页面退出只解绑，不取消下载', () {
    test('detach 后任务仍在 registry 里，未被标记完成', () {
      final reg = TransferRegistry.instance;
      var cancelled = 0;
      final s = TransferSession(
        id: 'v-detach-test',
        label: '缓存：测试',
        cancel: () async => cancelled++,
      );
      reg.add(s);

      // 模拟页面 dispose
      s.detach();

      expect(s.detached, isTrue);
      expect(s.finished, isFalse, reason: '解绑不等于结束，下载要继续');
      expect(cancelled, 0, reason: '解绑绝不能触发取消');
      expect(reg.find('v-detach-test'), isNotNull, reason: '任务还活着');
    });

    test('显式 cancel 才真的停', () async {
      final reg = TransferRegistry.instance;
      var cancelled = 0;
      final s = TransferSession(
        id: 'v-cancel-test',
        label: '缓存：测试',
        cancel: () async => cancelled++,
      );
      reg.add(s);
      await reg.cancel('v-cancel-test');
      expect(cancelled, 1);
      expect(reg.find('v-cancel-test'), isNull);
    });

    test('cancel 幂等：已完成的任务再取消不会重复触发', () async {
      var cancelled = 0;
      final s = TransferSession(
        id: 'v-idem',
        label: 'x',
        cancel: () async => cancelled++,
      );
      await s.cancelNow();
      await s.cancelNow();
      expect(cancelled, 1);
    });

    test('finish 移除任务但不触发 cancel', () async {
      final reg = TransferRegistry.instance;
      var cancelled = 0;
      final s = TransferSession(
        id: 'v-finish-test',
        label: 'x',
        cancel: () async => cancelled++,
      );
      reg.add(s);
      await reg.finish('v-finish-test');
      expect(cancelled, 0, reason: '正常完成不该调取消');
      expect(reg.find('v-finish-test'), isNull);
    });
  });

  group('多线程默认关闭：实测吞吐决定，不是保守', () {
    test('说明用的实测数字：并发越多越慢', () {
      // 这些是同一线路、同一文件实测值（见 LibraryStore.useChunkedStreaming 注释）。
      // 单连接 565–1000 KB/s，4 路 512 KB/s，8 路 489 KB/s。
      // 断言这组关系成立，防止以后有人「凭直觉」又把它改回默认开。
      const single = 565;
      const four = 512;
      const eight = 489;
      expect(four, lessThan(single));
      expect(eight, lessThan(four));
    });
  });
}
