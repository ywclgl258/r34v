// 条件导入：原生平台用 dart:io 实现，浏览器用 fetch 实现。
// 这样 Web 预览能跑，而不是编译过了但一请求就崩。
export 'site_client_base.dart';
export 'site_client_io.dart' if (dart.library.js_interop) 'site_client_web.dart';
