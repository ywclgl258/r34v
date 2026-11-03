import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/remote/doh_connector.dart';
import 'package:r34v/data/remote/doh_resolver.dart';
import 'package:r34v/data/remote/site_ip_pool.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// `SiteIpPool` 的存盘/复用逻辑。
///
/// 这里不测真实网络探测（那需要外网，且天然不稳定）。重点测**离线也能
/// 保证正确**的那部分：存盘格式、跨启动复用、失败时不破坏旧值、
/// 以及「池子陈旧」的判定。这几条出问题时的表现都极其隐蔽——
/// 只是「打开慢一点」或「偶尔连不上」，真机很难复现定位。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  group('存盘与复用', () {
    test('空 prefs 时池为空、且算陈旧', () {
      // 用一个不会真的去探测的桩 connector（探测在 refresh 里才发生）
      final pool = _FakePool(prefs);
      expect(pool.ips, isEmpty);
      expect(pool.updatedAt, isNull);
      expect(pool.isStale, isTrue, reason: '从没探测过应算陈旧');
    });

    test('能读回之前写下的 IP 列表', () async {
      final at = DateTime.now().millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({
        'site_ip_pool_v1': '["1.2.3.4","5.6.7.8"]',
        'site_ip_pool_v1_at': at,
      });
      final p = await SharedPreferences.getInstance();
      final pool = _FakePool(p);

      expect(pool.ips, ['1.2.3.4', '5.6.7.8']);
      expect(pool.updatedAt, isNotNull);
      expect(pool.isStale, isFalse, reason: '刚存的不该算陈旧');
    });

    test('超过 6 小时判为陈旧', () async {
      final old = DateTime.now()
          .subtract(const Duration(hours: 7))
          .millisecondsSinceEpoch;
      SharedPreferences.setMockInitialValues({
        'site_ip_pool_v1': '["1.2.3.4"]',
        'site_ip_pool_v1_at': old,
      });
      final p = await SharedPreferences.getInstance();
      expect(_FakePool(p).isStale, isTrue);
    });

    test('损坏的存盘内容当作没有，不抛异常', () async {
      SharedPreferences.setMockInitialValues({
        'site_ip_pool_v1': '{不是合法 json',
      });
      final p = await SharedPreferences.getInstance();
      final pool = _FakePool(p);
      expect(pool.ips, isEmpty);
    });

    test('存盘里的非法 IPv4 会被过滤掉', () async {
      SharedPreferences.setMockInitialValues({
        'site_ip_pool_v1': '["1.2.3.4","not-an-ip","999.1.1.1","5.6.7.8"]',
      });
      final p = await SharedPreferences.getInstance();
      expect(_FakePool(p).ips, ['1.2.3.4', '5.6.7.8']);
    });

    test('超过上限时只保留前几个', () async {
      final many = List.generate(10, (i) => '"10.0.0.$i"').join(',');
      SharedPreferences.setMockInitialValues({'site_ip_pool_v1': '[$many]'});
      final p = await SharedPreferences.getInstance();
      final pool = _FakePool(p);
      expect(pool.ips.length, SiteIpPool.maxPoolSize);
    });
  });

  group('demote', () {
    test('把失败的 IP 挪到末尾而不是删除', () async {
      SharedPreferences.setMockInitialValues({
        'site_ip_pool_v1': '["1.1.1.1","2.2.2.2","3.3.3.3"]',
        'site_ip_pool_v1_at': DateTime.now().millisecondsSinceEpoch,
      });
      final p = await SharedPreferences.getInstance();
      final pool = _FakePool(p);

      pool.demote('1.1.1.1');

      expect(pool.ips, ['2.2.2.2', '3.3.3.3', '1.1.1.1']);
      expect(pool.ips.length, 3, reason: '一次失败不该直接删掉');
    });

    test('demote 不存在的 IP 是无操作', () async {
      SharedPreferences.setMockInitialValues({
        'site_ip_pool_v1': '["1.1.1.1"]',
      });
      final p = await SharedPreferences.getInstance();
      final pool = _FakePool(p);
      pool.demote('9.9.9.9');
      expect(pool.ips, ['1.1.1.1']);
    });
  });

  group('reset', () {
    test('清空池并抹掉存盘', () async {
      SharedPreferences.setMockInitialValues({
        'site_ip_pool_v1': '["1.1.1.1"]',
        'site_ip_pool_v1_at': DateTime.now().millisecondsSinceEpoch,
      });
      final p = await SharedPreferences.getInstance();
      final pool = _FakePool(p);

      pool.reset();

      expect(pool.ips, isEmpty);
      expect(pool.updatedAt, isNull);
      expect(p.getString('site_ip_pool_v1'), isNull);
      expect(p.getInt('site_ip_pool_v1_at'), isNull);
    });
  });

  group('多服务商并集解析', () {
    test('resolveAll 会去重并合并各服务商结果', () async {
      // 三个预设各返回不同 IP，其中两条重复。
      // DoH 走网络，这里用桩 Dio 模拟响应。
      final calls = <String>[];
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://stub.test',
          responseType: ResponseType.json,
        ),
      );
      dio.httpClientAdapter = _StubAdapter((options) {
        calls.add(options.uri.toString());
        // 按 host 区分服务商，返回不同结果
        final host = options.uri.host;
        if (host.contains('cloudflare')) {
          return _dohJson(['1.1.1.1', '2.2.2.2']);
        }
        if (host.contains('google')) {
          return _dohJson(['2.2.2.2', '3.3.3.3']);
        }
        return _dohJson(['4.4.4.4']);
      });

      final resolver = DohResolver(dio: dio);
      final all = await resolver.resolveAll('rule34video.com');

      // 三个服务商都问过（并发）
      expect(calls.length, 3, reason: '应并发问全部服务商');
      // 并集去重
      expect(all, containsAll(['1.1.1.1', '2.2.2.2', '3.3.3.3', '4.4.4.4']));
      expect(all.where((e) => e == '2.2.2.2').length, 1, reason: '必须去重');
    });
  });
}

/// 造一份 DoH JSON 响应。
String _dohJson(List<String> ips) {
  final answers = ips
      .map((ip) => '{"type":1,"data":"$ip"}')
      .join(',');
  return '{"Status":0,"Answer":[$answers]}';
}

/// 极简 Dio 适配器：不走网络，直接返回造好的响应。
class _StubAdapter implements HttpClientAdapter {
  _StubAdapter(this.handler);

  final String Function(RequestOptions) handler;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final body = handler(options);
    return ResponseBody.fromString(
      body,
      200,
      headers: {
        Headers.contentTypeHeader: ['application/dns-json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

/// 不触发网络探测的 `SiteIpPool`。
///
/// 只验证存盘相关行为；`refresh()` 需要真实网络，不在单测里跑。
class _FakePool extends SiteIpPool {
  _FakePool(SharedPreferences prefs)
    : super(
        // 这些用例不会调 refresh，所以 connector 永远不会真的发请求。
        connector: DnsOverHttpsConnector(
          resolver: DohResolver(dio: Dio()),
          enabled: false,
        ),
        prefs: prefs,
      );
}
