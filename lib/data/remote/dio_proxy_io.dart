import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';

import 'doh_connector.dart';
import 'host_router.dart';

/// 原生端：显式设置代理，并按需用 DoH 解析结果替换请求的 host。
///
/// Dart 的 HttpClient 默认会读系统代理，但某些机型/系统版本上不可靠，
/// 用户既然手动填了，就明确设一遍。
///
/// [connector] 不为空时，为站点自身的域名挂一个拦截器：先问 DoH 要一个
/// 当下可连的 IP，再把请求 URI 的 host 换成它、同时把 `Host` 头设回
/// 原域名。为什么要换 host 而不是在 socket 层拦截，见
/// [DnsOverHttpsConnector] 的注释。
void applyProxyImpl(Dio dio, String? proxy, {DnsOverHttpsConnector? connector}) {
  final p = (proxy ?? '').trim();
  if (p.isNotEmpty) {
    dio.httpClientAdapter = IOHttpClientAdapter(
      createHttpClient: () {
        final c = HttpClient();
        c.findProxy = (_) => 'PROXY $p';
        c.connectionTimeout = const Duration(seconds: 20);
        return c;
      },
    );
  }

  if (connector == null) return;

  dio.interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) async {
        final uri = options.uri;
        if (!isSiteHost(uri.host)) {
          handler.next(options);
          return;
        }
        final ip = await connector.resolveSiteIp();
        if (ip == null) {
          handler.next(options);
          return;
        }
        // 换 IP，但把 Host 头补回原域名——HTTP/1.1 靠它决定虚拟主机。
        options.headers['Host'] = uri.host;
        options.path = HostRouter.withIp(uri, ip).toString();
        handler.next(options);
      },
    ),
  );
}
