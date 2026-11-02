import 'dart:async';
import 'dart:io';

/// 一个站点 IP 端点。
class HostEndpoint {
  const HostEndpoint({
    required this.ip,
    this.label = '',
  });

  final String ip;

  /// 给日志/诊断用的来源说明。
  final String label;

  @override
  String toString() => '$ip($label)';
}

/// 站点直连 IP 的选择器。
///
/// ## 先说结论：这条路在当前网络下**不可靠**
///
/// 实测数据（本机，绕开代理直连）：
///
/// | 检查点 | 结果 |
/// |---|---|
/// | TCP 连 `190.115.31.243:443` | 8/8 成功 |
/// | TLS 握手 | 6/8 成功 |
/// | **完整拿到响应正文** | **1/8 成功** |
///
/// 也就是说连接能建立、握手能完成，但**服务端在传正文阶段极不稳定**
/// （大量 `No stream event` 超时）。同机走代理时是 **10/10 成功**，
/// 稳定在 0.3–1.5 秒。
///
/// 另一个 IP `202.160.128.40`（系统 DNS 当时解析到的）TCP 就是 **0/8**，
/// 完全不通。
///
/// 所以这里的定位是：**直连是一次机会，不是默认路径**。有代理就优先用
/// 代理；只有没配代理时才尝试直连，且失败要能快速退回。
///
/// ## 为什么不能「把 URL 的 host 换成 IP」
///
/// 这是之前踩过的坑，必须记下来。那样做在 TLS 层**必然失败**：
///
/// | 方式 | 结果 |
/// |---|---|
/// | 代理 + 域名 | ✅ 200 |
/// | 代理 + IP 当 host | ❌ `HandshakeException` |
/// | 直连 + IP 当 host | ❌ `HandshakeException` |
///
/// 原因是 URI 的 host 是 IP 时，Dart 拿 **IP 做 SNI**，而服务器证书是
/// `/CN=rule34video.com`，校验不过。补 `Host` 头救不了——`Host` 是
/// HTTP 层的事，管不到 TLS。
///
/// 真要让 TCP 连 IP、TLS 对域名，只有 `SecureSocket.secure(tcp, host: 域名)`
/// 这一条路。它确实能通（实测拿到 170707 字节、200 OK），但因为上面说的
/// 服务端不稳定，整体成功率仍然很低。

/// 内置候选。**只保留实测能连通的**。
///
/// 曾经有 `108.160.162.109` 和 `128.121.243.76`，实测 TCP **0/6 全不通**，
/// 留着只会让每次请求先白等一个超时。
const List<HostEndpoint> kBuiltinEndpoints = [
  HostEndpoint(ip: '190.115.31.243', label: 'DoH'),
];

/// 站点主机名。
const String kSiteHost = 'rule34video.com';

/// 判断 host 是否是站点自身（含 www）。
bool isSiteHost(String host) =>
    host == kSiteHost || host == 'www.$kSiteHost';

/// 用 IP 连 TCP，但让 TLS 对域名完成握手。
///
/// 这是唯一能在不改变 URI host 的前提下用指定 IP 建连的方式：
///
/// ```dart
/// final tcp  = await Socket.connect(ip, 443);
/// final tls  = await SecureSocket.secure(tcp, host: 'rule34video.com');
/// ```
///
/// `host` 参数同时决定 **SNI** 和 **证书校验目标**，所以证书能过。
///
/// 返回已完成 TLS 的 socket，可直接用于 `ConnectionTask.fromSocket`。
Future<SecureSocket> connectViaIp(
  String ip,
  int port, {
  required String host,
  Duration connectTimeout = const Duration(seconds: 8),
  Duration tlsTimeout = const Duration(seconds: 12),
}) async {
  final tcp = await Socket.connect(ip, port, timeout: connectTimeout);
  try {
    return await SecureSocket.secure(
      tcp,
      host: host,
      // 站点只支持 HTTP/1.1（实测 ALPN 只协商出 1.1）
      supportedProtocols: const ['http/1.1'],
    ).timeout(tlsTimeout);
  } catch (_) {
    // TLS 失败必须关掉底层 TCP，否则留下半开连接
    try {
      tcp.destroy();
    } catch (_) {}
    rethrow;
  }
}

/// 探测某个 IP 是否能连通（只建 TCP，不握手，快）。
Future<bool> probeTcp(
  String ip, {
  int port = 443,
  Duration timeout = const Duration(seconds: 4),
}) async {
  try {
    final s = await Socket.connect(ip, port, timeout: timeout);
    s.destroy();
    return true;
  } catch (_) {
    return false;
  }
}
