import 'package:media_kit/media_kit.dart';

/// Web 端：浏览器自己处理代理与证书，不需要额外设置。
Future<void> applyPlatformProps(
  Player player,
  Map<String, String> props,
) async {}

Future<String?> prepareCaBundle(List<int> bytes) async => null;
