import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/features/player/playback_guards.dart';

/// 「手势加速直接播放不了」与「线路被限流」两条根因的回归测试。
///
/// 两个都是能在纯逻辑层钉住的具体缺陷，不需要真机也不需要网络。
void main() {
  group('token 过期判定：不能把错误文本里的任意数字当成 HTTP 码', () {
    // 这一组是「线路被限流」的直接原因：误判 → 回源抓详情页 → 打站点。
    test('真正的 HTTP 401/403/410 才算过期', () {
      expect(looksLikeTokenExpiry('HttpException: http 403'), isTrue);
      expect(looksLikeTokenExpiry('HTTP/1.1 401 Unauthorized'), isTrue);
      expect(looksLikeTokenExpiry('Server returned 410'), isTrue);
      expect(looksLikeTokenExpiry('status: 403'), isTrue);
      expect(looksLikeTokenExpiry('code=403'), isTrue);
    });

    test('关键字也认', () {
      expect(looksLikeTokenExpiry('Forbidden'), isTrue);
      expect(looksLikeTokenExpiry('UnAuthorized'), isTrue);
      expect(looksLikeTokenExpiry('Gone'), isTrue);
    });

    test('普通错误文本里的 403 子串不算（曾导致反复回源）', () {
      // 这些是播放器真实会给出的错误形态：数字是位置/字节数，不是状态码
      expect(
        looksLikeTokenExpiry('Source error: position 403000 out of range'),
        isFalse,
        reason: '403000 里的 403 不是 HTTP 码，误判会回源抓详情页',
      );
      expect(
        looksLikeTokenExpiry('read failed at offset 1403'),
        isFalse,
      );
      expect(
        looksLikeTokenExpiry('buffer 41024 bytes dropped'),
        isFalse,
      );
    });

    test('常见网络错误不触发回源', () {
      expect(looksLikeTokenExpiry('Connection reset by peer'), isFalse);
      expect(looksLikeTokenExpiry('Timeout after 20s'), isFalse);
      expect(looksLikeTokenExpiry('TLS handshake failed'), isFalse);
    });
  });

  group('倍速下发去重：逐帧回调不能每帧都打一遍播放器', () {
    test('同一档位重复下发只有第一次生效', () {
      final gate = RateApplyGate(initial: 1.0);
      // 模拟一次横向拖动：位移连续增加，但档位只在少数几帧变化
      final rates = <double>[
        1.0, 1.0, 1.25, 1.25, 1.25, 1.5, 1.5, 2.0, 2.0, 2.0, 2.0, 2.0,
      ];
      final applied = <double>[];
      for (final r in rates) {
        if (gate.shouldApply(r)) applied.add(r);
      }
      // 1.0 与初始一致，不该下发；之后每档各一次
      expect(applied, [1.25, 1.5, 2.0]);
    });

    test('回弹到原档位也算一次变化', () {
      final gate = RateApplyGate(initial: 1.0);
      expect(gate.shouldApply(2.0), isTrue);
      expect(gate.shouldApply(1.0), isTrue, reason: '回弹必须真的下发，否则速度不会恢复');
      expect(gate.shouldApply(1.0), isFalse);
    });

    test('初始值就等于要下发的值时不下发', () {
      final gate = RateApplyGate(initial: 1.5);
      expect(gate.shouldApply(1.5), isFalse,
          reason: 'initState 已经下发过，重复一次没有意义');
      expect(gate.shouldApply(2.0), isTrue);
    });
  });
}
