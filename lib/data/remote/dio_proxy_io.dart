import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';

import 'doh_connector.dart';
import 'host_router.dart' as hr;

/// 原生端：显式设置代理，并让站点域名的请求真正走 IP 直连。
///
/// ## 这里修过一个会直接导致「播放不了」的 bug
///
/// 早先的实现只做了两件事：把 `Host` 头补回原域名，再塞一个
/// `X-R34V-Hint-Ip` 头。但**没有任何代码去读那个提示头**，请求本身
/// 仍然走系统 DNS。在系统 DNS 被污染的网络（实测返回
/// `202.160.128.40`，TCP 完全不通）下，结果是：
///
/// - 详情页文字能出来（那条路走 `SiteClientImpl`，有真正的 IP 直连）；
/// - **视频流连不上**，报 `Connection refused`。
///
/// `StreamFetcher` 的 Dio 和播放器都吃这个坑。
///
/// ## 现在的做法
///
/// 装 `connectionFactory`，只接管**站点域名**的连接：TCP 连 DoH 解析出的
/// IP，TLS 仍对域名握手（[hr.connectViaIp]）——这正是 `SiteClientImpl`
/// 里已实测可用的那条路。
///
/// 非站点域名（CDN、DoH 端点等）**不接管**：`connectionFactory` 一旦装上
/// 就会对所有请求生效，而那些域名必须走正常 TLS，自己实现容易出错。
/// 所以这里用一个包装标记：工厂内判断 host，非站点域名时按「域名连 TCP +
/// 用框架的 TLS」路径返回，行为与默认一致。
void applyProxyImpl(
  Dio dio,
  String? proxy, {
  DnsOverHttpsConnector? connector,
}) {
  final p = (proxy ?? '').trim();
  final hasProxy = p.isNotEmpty;

  // 代理优先；只有没代理且需要 IP 直连时才接管连接工厂。
  if (hasProxy) {
    dio.httpClientAdapter = IOHttpClientAdapter(
      createHttpClient: () {
        final c = HttpClient();
        c.findProxy = (_) => 'PROXY $p';
        c.connectionTimeout = const Duration(seconds: 20);
        return c;
      },
    );
    return;
  }

  if (connector == null) return;

  dio.httpClientAdapter = IOHttpClientAdapter(
    createHttpClient: () {
      final c = HttpClient();
      c.connectionTimeout = const Duration(seconds: 20);
      // 明确声明直连，避免 HttpClient 自动套用系统代理。
      c.findProxy = (_) => 'DIRECT';

      c.connectionFactory = (uri, proxyHost, proxyPort) async {
        // 站点域名才做 IP 直连。
        if (hr.isSiteHost(uri.host)) {
          String? ip;
          try {
            ip = await connector.resolveSiteIp();
          } catch (_) {
            ip = null;
          }
          if (ip != null) {
            try {
              final secure = await hr.connectViaIp(
                ip,
                uri.port == 0 ? 443 : uri.port,
                host: uri.host,
                connectTimeout: const Duration(seconds: 20),
              );
              return ConnectionTask.fromSocket<SecureSocket>(
                  Future.value(secure), () {});
            } catch (_) {
              // 落到下面的内置 IP 兜底
            }
          }
          // DoH 依赖 cloudflare-dns.com / dns.google，这些域名本身也要解析；
          // 系统 DNS 被污染时 DoH 会失败。用内置的已知可用 IP 兜底。
          for (final e in hr.kBuiltinEndpoints) {
            try {
              final secure = await hr.connectViaIp(
                e.ip,
                uri.port == 0 ? 443 : uri.port,
                host: uri.host,
                connectTimeout: const Duration(seconds: 10),
              );
              return ConnectionTask.fromSocket<SecureSocket>(
                  Future.value(secure), () {});
            } catch (_) {
              // 试下一个
            }
          }
        }

        // 其它情况（含 DoH 解析失败）：按域名建连，交给框架完成 TLS。
        return _connectDefault(uri);
      };
      return c;
    },
  );
}

/// 按域名建立连接，返回**已完成 TLS**（https）或明文（http）的 socket。
///
/// 注意：`connectionFactory` 返回的 socket 会被 HttpClient 直接使用，
/// **不会再做一次 TLS**。所以 https 必须在这里自己完成握手。
Future<ConnectionTask<Socket>> _connectDefault(Uri uri) async {
  final port = uri.port == 0 ? (uri.scheme == 'https' ? 443 : 80) : uri.port;
  final tcp = await Socket.connect(
    uri.host,
    port,
    timeout: const Duration(seconds: 20),
  );
  if (uri.scheme != 'https') {
    return ConnectionTask.fromSocket<Socket>(Future.value(tcp), () {});
  }
  final tls = await SecureSocket.secure(
    tcp,
    host: uri.host,
    supportedProtocols: const ['http/1.1'],
  );
  return ConnectionTask.fromSocket<SecureSocket>(Future.value(tls), () {});
}
