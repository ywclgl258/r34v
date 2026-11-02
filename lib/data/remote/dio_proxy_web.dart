import 'package:dio/dio.dart';

import 'doh_connector.dart';

/// Web 端：浏览器自己处理代理与 DNS，不用也不能插手。
void applyProxyImpl(
  Dio dio,
  String? proxy, {
  DnsOverHttpsConnector? connector,
}) {}
