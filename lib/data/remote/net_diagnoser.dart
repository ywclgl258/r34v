import 'dart:async';
import 'dart:io';

import '../../core/constants.dart';
import 'doh_connector.dart';
import 'host_router.dart';

/// 一条连通性测试的结果。
class NetProbe {
  NetProbe({
    required this.label,
    required this.detail,
    this.ok = false,
    this.ms = 0,
    this.bytes = 0,
    this.error = '',
  });

  final String label;
  String detail;
  bool ok;
  int ms;
  int bytes;
  String error;

  bool get done => ok || error.isNotEmpty;

  String get summary {
    if (ok) return '✅ ${ms}ms  ${bytes}B';
    if (error.isEmpty) return '… 测试中';
    return '❌ $error';
  }
}

/// 现场诊断：把每条网络路径都实测一遍，把结果直接摆出来。
///
/// ## 为什么需要它
///
/// 用户报「连不上」时，光看请求日志无法区分：
///
/// - 是**系统 DNS 解析失败**（返回了污染 IP）
/// - 还是**TCP 连不上**（IP 不通）
/// - 还是**TLS 握手失败**（SNI / 证书问题）
/// - 还是**能连但服务端不回数据**（链路质量问题）
/// - 还是**手机上的代理根本没被 HttpClient 读到**
///
/// 这几种的修法完全不同。所以这里逐条实测，每条给独立结论。
class NetDiagnoser {
  NetDiagnoser({required this.connector});

  final DnsOverHttpsConnector connector;

  /// 逐条测。每完成一条就回调（UI 可以流式显示）。
  Future<List<NetProbe>> run(void Function(NetProbe) onUpdate) async {
    final probes = <NetProbe>[];

    Future<void> add(NetProbe p) async {
      probes.add(p);
      onUpdate(p);
    }

    // 1. 系统 DNS 解析到了什么
    final dnsProbe = NetProbe(label: '系统 DNS', detail: '解析 $kSiteHost');
    await add(dnsProbe);
    final sw0 = Stopwatch()..start();
    List<InternetAddress> sysAddrs = const [];
    try {
      sysAddrs = await InternetAddress.lookup(kSiteHost)
          .timeout(const Duration(seconds: 8));
      dnsProbe
        ..ok = sysAddrs.isNotEmpty
        ..ms = sw0.elapsedMilliseconds
        ..detail = sysAddrs.map((a) => a.address).join(', ');
    } catch (e) {
      dnsProbe
        ..ms = sw0.elapsedMilliseconds
        ..error = _short(e);
    }
    onUpdate(dnsProbe);

    // 2. DoH 解析到了什么
    final dohProbe = NetProbe(label: 'DoH 解析', detail: '查询 $kSiteHost');
    await add(dohProbe);
    final sw1 = Stopwatch()..start();
    List<String> dohIps = const [];
    try {
      dohIps = await connector.resolver
          .resolve(kSiteHost)
          .timeout(const Duration(seconds: 12));
      dohProbe
        ..ok = dohIps.isNotEmpty
        ..ms = sw1.elapsedMilliseconds
        ..detail = dohIps.isEmpty ? '没有 A 记录' : dohIps.join(', ');
    } catch (e) {
      dohProbe
        ..ms = sw1.elapsedMilliseconds
        ..error = _short(e);
    }
    onUpdate(dohProbe);

    // 3. 逐个 IP：TCP 通不通
    final candidates = <String>{
      ...dohIps,
      ...kBuiltinEndpoints.map((e) => e.ip),
      ...sysAddrs.map((a) => a.address).where((a) => !a.contains(':')),
    }.toList();

    for (final ip in candidates) {
      final p = NetProbe(label: 'TCP $ip', detail: '连 443');
      await add(p);
      final sw = Stopwatch()..start();
      try {
        final s = await Socket.connect(ip, 443,
            timeout: const Duration(seconds: 6));
        s.destroy();
        p
          ..ok = true
          ..ms = sw.elapsedMilliseconds;
      } catch (e) {
        p
          ..ms = sw.elapsedMilliseconds
          ..error = _short(e);
      }
      onUpdate(p);
    }

    // 4. 手机上的系统代理有没有被 HttpClient 读到
    //
    // 这一项很关键：Android 的代理配置不走环境变量，Dart 的 HttpClient
    // 在安卓上到底读不读系统代理、读的是哪个，行为并不直观。用户明确
    // 开了代理却仍超时，就要怀疑这一环。
    //
    // 判断方式：让 HttpClient 自己决定怎么连（不加任何 findProxy），
    // 看它连 example.com 能否成功。能成功说明"networking 通路正常"；
    // 但如果它同时又能连到 rule34video.com，那大概率是走了代理。
    final proxyProbe = NetProbe(label: '默认网络通路', detail: '不加 findProxy 直连 example.com');
    await add(proxyProbe);
    {
      final c = HttpClient();
      c.connectionTimeout = const Duration(seconds: 8);
      final sw = Stopwatch()..start();
      try {
        final req = await c.getUrl(Uri.parse('https://example.com/'));
        final res = await req.close().timeout(const Duration(seconds: 10));
        await res.drain<void>();
        proxyProbe
          ..ok = res.statusCode == 200
          ..ms = sw.elapsedMilliseconds
          ..detail = 'HTTP ${res.statusCode}（说明基础网络可用）';
      } catch (e) {
        proxyProbe
          ..ms = sw.elapsedMilliseconds
          ..error = _short(e);
      } finally {
        c.close(force: true);
      }
    }
    onUpdate(proxyProbe);

    // 5. 完整 HTTP 请求：TCP 连 IP + TLS 对域名
    for (final ip in candidates.where((e) => !e.contains(':')).take(3)) {
      final p = NetProbe(label: 'HTTP via $ip', detail: 'TCP→IP, TLS→域名');
      await add(p);
      final sw = Stopwatch()..start();
      final c = HttpClient();
      c.connectionTimeout = const Duration(seconds: 10);
      c.connectionFactory = (uri, ph, pp) async {
        final secure = await connectViaIp(
          ip,
          uri.port == 0 ? 443 : uri.port,
          host: uri.host,
          connectTimeout: const Duration(seconds: 10),
        );
        return ConnectionTask.fromSocket<SecureSocket>(
            Future.value(secure), () {});
      };
      try {
        final req = await c.getUrl(Uri.parse('https://$kSiteHost/'));
        req.headers.set(HttpHeaders.userAgentHeader, Site.userAgent);
        final res = await req.close().timeout(const Duration(seconds: 15));
        final n = await res
            .fold<int>(0, (a, b) => a + b.length)
            .timeout(const Duration(seconds: 15));
        p
          ..ok = res.statusCode == 200 && n > 10000
          ..ms = sw.elapsedMilliseconds
          ..bytes = n
          ..detail = 'HTTP ${res.statusCode}';
        if (!p.ok && p.error.isEmpty) p.error = '内容异常（${n}B）';
      } catch (e) {
        p
          ..ms = sw.elapsedMilliseconds
          ..error = _short(e);
      } finally {
        c.close(force: true);
      }
      onUpdate(p);
    }

    return probes;
  }

  static String _short(Object e) {
    var s = '$e';
    // 去掉冗长的异常类名前缀，只留关键信息
    s = s.replaceAll('SocketException: ', '');
    s = s.replaceAll('HandshakeException: ', 'TLS: ');
    s = s.replaceAll('TimeoutException after ', '超时 ');
    s = s.replaceAll('HttpException: ', '');
    return s.length > 70 ? '${s.substring(0, 70)}…' : s;
  }
}

