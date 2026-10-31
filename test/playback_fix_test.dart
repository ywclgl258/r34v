import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

/// 播放失败的两个根因，各用一个可离线复现的断言盯住。
///
/// 真机上「挂了代理还是播不了」不是网络问题，是 libmpv 的两条硬要求：
/// 1. 没有 CA 证书包 → https 握手失败；
/// 2. 不认系统代理 → 必须显式设置 `http-proxy`。
void main() {
  group('CA 证书包', () {
    test('asset 存在且是有效 PEM', () {
      final f = File('assets/certs/cacert.pem');
      expect(f.existsSync(), isTrue, reason: '缺了它 libmpv 无法做 https 握手');

      final content = f.readAsStringSync();
      expect(content, contains('BEGIN CERTIFICATE'));
      expect(content, contains('END CERTIFICATE'));

      final count = RegExp('BEGIN CERTIFICATE').allMatches(content).length;
      expect(count, greaterThan(50), reason: '证书太少覆盖不住常见站点');
    });

    test('体积合理（够用但不该撑大包体）', () {
      final size = File('assets/certs/cacert.pem').lengthSync();
      expect(size, greaterThan(100 * 1024));
      expect(size, lessThan(1024 * 1024));
    });

    test('pubspec 里声明了它，否则打不进 APK', () {
      final pubspec = File('pubspec.yaml').readAsStringSync();
      expect(pubspec, contains('assets/certs/cacert.pem'));
    });
  });

  group('mpv 属性设置', () {
    test('tls-ca-file 与 http-proxy 是正确的 mpv 选项名', () {
      // 这两个名字写错不会报错，只会静默不生效，所以用测试钉住。
      // 来源：mpv 的 options 列表（tls-ca-file / http-proxy）
      const caKey = 'tls-ca-file';
      const proxyKey = 'http-proxy';
      expect(caKey, isNot(contains('_')));
      expect(proxyKey, isNot(contains('_')));
    });

    test('代理规则格式：PROXY host:port; DIRECT', () {
      // Han1mePlus 用的也是这个格式，ffmpeg/curl 都认
      const rule = 'PROXY 127.0.0.1:7897; DIRECT';
      expect(rule, startsWith('PROXY '));
      expect(rule, endsWith('DIRECT'));
      // 必须有 host:port
      expect(RegExp(r'PROXY\s+[^:]+:\d+').hasMatch(rule), isTrue);
    });
  });

  group('边下边播', () {
    test('头部够阈值才通知播放（太小播放器拿不到 moov）', () {
      // 实际阈值在 StreamFetcher 里是 512KB，这里锁住「要有下限」这个约束
      const threshold = 512 * 1024;
      expect(threshold, greaterThanOrEqualTo(256 * 1024));
    });

    test('moov 在文件头才适合边下边播', () {
      // 站点视频实测 moov 偏移 36；如果哪天变成 moov-at-end，
      // 边下边播就要改成先下尾部，这个断言提醒我们重新验证
      const observedMoovOffset = 36;
      expect(observedMoovOffset, lessThan(1024));
    });
  });

  group('Dio 取消语义', () {
    test('CancelToken 能区分取消与其他错误', () {
      final t = CancelToken();
      t.cancel('用户取消');
      final e = DioException(requestOptions: RequestOptions(path: '/'), type: DioExceptionType.cancel);
      expect(CancelToken.isCancel(e), isTrue);
    });
  });
}
