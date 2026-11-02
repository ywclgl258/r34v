import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/doh_resolver.dart';

/// DoH 响应的解析回归测试。
///
/// ## 这里钉住的是真实踩过的坑
///
/// Cloudflare 的 `/dns-query` 返回的 `Content-Type` 不是 `application/json`，
/// 于是 Dio 即使设了 `ResponseType.json` 也**不会解析**，`res.data` 拿到的是
/// 原始字符串。
///
/// 第一版只判断 `is Map`，遇到字符串就当成「没有 A 记录」返回空 —— 结果
/// Cloudflare 作为第一优先级**永远是空的**，每个请求都要多绕两轮才拿到 IP。
/// 在用户手机上表现为：DoH 明明可用，直连却始终拿不到 IP。
void main() {
  group('parseAnswers 接受字符串', () {
    test('Cloudflare 实际返回的字符串（关键回归）', () {
      // 这是 Cloudflare 真实返回的原文
      const raw = '{"Status":0,"TC":false,"RD":true,"RA":true,"AD":false,'
          '"CD":false,"Question":[{"name":"rule34video.com","type":1}],'
          '"Answer":[{"name":"rule34video.com","type":1,"TTL":16,'
          '"data":"190.115.31.243"}]}';
      expect(DohResolver.parseAnswers(raw), ['190.115.31.243']);
    });

    test('已经是 Map 时照常工作（Google / AliDNS 那种）', () {
      expect(
        DohResolver.parseAnswers({
          'Status': 0,
          'Answer': [
            {'type': 1, 'data': '1.2.3.4'},
          ],
        }),
        ['1.2.3.4'],
      );
    });

    test('空字符串返回空', () {
      expect(DohResolver.parseAnswers(''), isEmpty);
      expect(DohResolver.parseAnswers('   '), isEmpty);
    });

    test('非法 JSON 字符串返回空，不抛异常', () {
      expect(DohResolver.parseAnswers('不是 JSON'), isEmpty);
      expect(DohResolver.parseAnswers('{损坏'), isEmpty);
    });

    test('JSON 字符串但内容不是对象', () {
      expect(DohResolver.parseAnswers('[1,2,3]'), isEmpty);
      expect(DohResolver.parseAnswers('"字符串"'), isEmpty);
    });
  });

  group('A 记录筛选', () {
    test('只取 type=1，忽略 AAAA', () {
      expect(
        DohResolver.parseAnswers({
          'Status': 0,
          'Answer': [
            {'type': 1, 'data': '1.2.3.4'},
            {'type': 28, 'data': '2001::1'},
            {'type': 5, 'data': 'cname.example'},
            {'type': 1, 'data': '5.6.7.8'},
          ],
        }),
        ['1.2.3.4', '5.6.7.8'],
      );
    });

    test('NXDOMAIN 返回空', () {
      expect(DohResolver.parseAnswers({'Status': 3}), isEmpty);
    });

    test('没有 Answer 字段返回空', () {
      expect(DohResolver.parseAnswers({'Status': 0}), isEmpty);
    });

    test('去重且保序', () {
      expect(
        DohResolver.parseAnswers({
          'Status': 0,
          'Answer': [
            {'type': 1, 'data': '2.2.2.2'},
            {'type': 1, 'data': '1.1.1.1'},
            {'type': 1, 'data': '2.2.2.2'},
          ],
        }),
        ['2.2.2.2', '1.1.1.1'],
      );
    });
  });

  group('服务商优先级', () {
    test('Cloudflare 排第一（它给出的结果最干净）', () {
      expect(DohResolver.presets.first.key, 'cloudflare');
    });

    test('AliDNS 垫底（对这个域名返回污染结果）', () {
      // 实测 AliDNS 曾返回 Facebook 的 IP 段，443 全部不通
      expect(DohResolver.presets.last.key, 'alidns');
    });

    test('至少有三个服务商可切换', () {
      expect(DohResolver.presets.length, greaterThanOrEqualTo(3));
    });
  });
}
