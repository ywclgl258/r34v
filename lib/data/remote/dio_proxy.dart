import 'package:dio/dio.dart';

import 'dio_proxy_io.dart' if (dart.library.js_interop) 'dio_proxy_web.dart';

/// 给 Dio 套上显式代理。Web 端是空操作（浏览器自己管）。
void applyProxyToDio(Dio dio, String? proxy) => applyProxyImpl(dio, proxy);
