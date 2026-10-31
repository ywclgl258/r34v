import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';

/// 原生端：显式设置代理。
///
/// Dart 的 HttpClient 默认会读系统代理，但某些机型/系统版本上不可靠，
/// 用户既然手动填了，就明确设一遍。
void applyProxyImpl(Dio dio, String? proxy) {
  final p = (proxy ?? '').trim();
  if (p.isEmpty) return;
  dio.httpClientAdapter = IOHttpClientAdapter(
    createHttpClient: () {
      final c = HttpClient();
      c.findProxy = (_) => 'PROXY $p';
      c.connectionTimeout = const Duration(seconds: 20);
      return c;
    },
  );
}
