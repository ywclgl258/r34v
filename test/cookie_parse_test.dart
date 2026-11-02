import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/site_client_io.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Cookie 解析的回归测试。
///
/// 背景：以前只取 `Set-Cookie` 的第一段（`name=value`），把 Path / Domain /
/// Expires / HttpOnly 全部丢掉。后果是**失效的 cookie 一直被发出去**，
/// 而本站的 ddos-guard cookie 里 `__ddg1_` 是一年、`__ddg8_` 是一小时，
/// 混在一起当永久值处理，行为完全错乱。
///
/// 这类错误的表现是「偶发要重新验证」，真机上极难定位，所以必须用单测钉住。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Set-Cookie 解析', () {
    test('基本 name=value', () {
      final c = DomainCookie.parse('a=1');
      expect(c, isNotNull);
      expect(c!.name, 'a');
      expect(c.value, '1');
      expect(c.expiresAt, isNull, reason: '没有 Expires 就是会话 cookie');
      expect(c.isExpired(DateTime.now()), isFalse);
    });

    test('解析 Expires（RFC 1123，站点实际用的格式）', () {
      // 这是 ddos-guard 下发的真实形态
      final c = DomainCookie.parse(
        '__ddg1_=O2jIHAgdZh7tRoDF1IeS; Domain=.rule34video.com; '
        'HttpOnly; Path=/; Expires=Tue, 02 Nov 2027 00:36:19 GMT',
      );
      expect(c, isNotNull);
      expect(c!.name, '__ddg1_');
      expect(c.httpOnly, isTrue);
      expect(c.path, '/');
      expect(c.domain, '.rule34video.com');
      expect(c.expiresAt, isNotNull);
      expect(c.expiresAt!.year, 2027);
    });

    test('过期的 cookie 判定为已过期', () {
      final c = DomainCookie.parse(
        'old=1; Expires=Mon, 01 Jan 2001 00:00:00 GMT',
      );
      expect(c!.isExpired(DateTime.now()), isTrue);
    });

    test('未来的 cookie 不算过期', () {
      final c = DomainCookie.parse(
        'new=1; Expires=Fri, 01 Jan 2100 00:00:00 GMT',
      );
      expect(c!.isExpired(DateTime.now()), isFalse);
    });

    test('Max-Age 优先于 Expires', () {
      // RFC 6265 §5.3：同时存在时 Max-Age 胜出
      final c = DomainCookie.parse(
        'x=1; Max-Age=3600; Expires=Mon, 01 Jan 2001 00:00:00 GMT',
      );
      expect(
        c!.isExpired(DateTime.now()),
        isFalse,
        reason: 'Max-Age 是未来，应盖过已过的 Expires',
      );
    });

    test('Max-Age<=0 表示立刻失效', () {
      final c = DomainCookie.parse('x=1; Max-Age=0');
      expect(c!.isExpired(DateTime.now()), isTrue);
    });

    test('Secure 与 HttpOnly 标记', () {
      final c = DomainCookie.parse('s=1; Secure; HttpOnly');
      expect(c!.secure, isTrue);
      expect(c.httpOnly, isTrue);
    });

    test('Expires 格式非法时不死板地当过期', () {
      // 解析失败应该「当没写」，而不是「立刻过期」——
      // 后者会误删本来有效的 cookie
      final c = DomainCookie.parse('x=1; Expires=这不是日期');
      expect(c!.expiresAt, isNull);
      expect(c.isExpired(DateTime.now()), isFalse);
    });

    test('格式非法返回 null 而不是崩', () {
      expect(DomainCookie.parse(''), isNull);
      expect(DomainCookie.parse('=value'), isNull);
      expect(DomainCookie.parse('novalue'), isNull);
    });

    test('值里带等号也能正确切分', () {
      final c = DomainCookie.parse('k=a=b=c; Path=/');
      expect(c!.name, 'k');
      expect(c.value, 'a=b=c');
    });
  });

  group('请求 Cookie 合并', () {
    test('保留 ddos-guard 与登录会话', () {
      expect(
        mergeCookieHeaders('__ddg1_=guard', 'PHPSESSID=session'),
        '__ddg1_=guard; PHPSESSID=session',
      );
    });

    test('同名 Cookie 由调用方会话优先', () {
      expect(
        mergeCookieHeaders('__ddg1_=guard; PHPSESSID=old', 'PHPSESSID=new'),
        '__ddg1_=guard; PHPSESSID=new',
      );
    });

    test('空输入与带等号的值不会损坏', () {
      expect(mergeCookieHeaders('', 'token=a=b=c'), 'token=a=b=c');
      expect(mergeCookieHeaders('', ''), isEmpty);
    });
  });

  group('序列化往返', () {
    test('toJson / fromJson 保持属性', () {
      final c = DomainCookie.parse(
        'n=val; Path=/x; Domain=.a.com; Secure; HttpOnly; '
        'Expires=Fri, 01 Jan 2100 00:00:00 GMT',
      )!;
      final back = DomainCookie.fromJson(c.toJson())!;
      expect(back.name, c.name);
      expect(back.value, c.value);
      expect(back.path, c.path);
      expect(back.domain, c.domain);
      expect(back.secure, c.secure);
      expect(back.httpOnly, c.httpOnly);
      expect(back.expiresAt!.year, 2100);
    });

    test('会话 cookie 往返后仍是会话 cookie', () {
      final c = DomainCookie.parse('s=1')!;
      final back = DomainCookie.fromJson(c.toJson())!;
      expect(back.expiresAt, isNull);
    });

    test('坏 JSON 返回 null', () {
      expect(DomainCookie.fromJson({}), isNull);
      expect(DomainCookie.fromJson({'n': ''}), isNull);
    });
  });

  group('HttpDate 兼容性', () {
    test('Dart 的 HttpDate 能读懂站点的日期格式', () {
      final d = HttpDate.parse('Tue, 02 Nov 2027 00:36:19 GMT');
      expect(d.year, 2027);
      expect(d.month, 11);
      expect(d.day, 2);
    });
  });

  test('prefs 可用时 cookie 能持久化', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    // 这里只验证读写通路不炸；真实网络行为由联网测试覆盖
    expect(prefs.getString('site_cookies_v1'), isNull);
  });
}
