import 'dart:io';

/// 网络环境感知：**当前是否有 VPN/代理在接管流量**。
///
/// ## 为什么这个判断至关重要
///
/// 「IP 直连」和「VPN」是**互相破坏**的两套机制，这一点非常反直觉：
///
/// - VPN（Clash/sing-box 这类 `tun` 模式）的工作原理是**按域名分流**：
///   它看到你连 `rule34video.com`，才按规则决定走哪个节点。
/// - 我们做 IP 直连时，把「连哪个域名」这个信息丢掉了——只给系统一个
///   裸 IP。VPN 拿到一个 IP，匹配不到任何域名规则，只能按 IP-CIDR 兜底，
///   结果往往是 **DIRECT（直连）**：既没用上节点，又因为本地 DNS 被污染
///   连到了错的 IP。
///
/// 所以**开着 VPN 时走 IP 直连 = 自废武功**。表现就是用户说的
/// 「直连能通，开了梯子反而连不上」。
///
/// 正确策略：检测到 VPN 就**不要**做 IP 直连，老实按域名请求，
/// 让 VPN 自己分流；只有没 VPN 时才启用 DoH + IP 直连。
///
/// ## 怎么检测
///
/// Android 上 VPN 会创建 `tun0` / `tun1` / `ppp0` 这类网络接口。
/// 通过 `NetworkInterface.list()` 看有没有这类接口，就是最直接的识别方式
/// （不需要额外权限，比读 `ConnectivityManager` 简单且跨平台）。
abstract final class NetworkEnv {
  /// VPN 常创建的接口名前缀。不同实现用的名字不太一样：
  /// - Clash / sing-box / v2ray：`tun0`、`tun1`
  /// - 系统自带 VPN、部分企业 VPN：`ppp0`
  /// - WireGuard：`wg0`
  /// - OpenVPN：`tun`、`tap`
  static const List<String> _vpnPrefixes = ['tun', 'tap', 'ppp', 'wg'];

  /// 缓存结果，避免每次请求都枚举网卡（那是个系统调用，有开销）。
  static bool? _cached;
  static DateTime _cachedAt = DateTime.fromMillisecondsSinceEpoch(0);

  /// 缓存有效期。VPN 可能随时开关，但也不需要每次请求都查——5 秒足够
  /// 让用户在切换 VPN 后感受到变化，又不至于拖慢请求。
  static const Duration _ttl = Duration(seconds: 5);

  /// 当前是否有 VPN 接口处于启用状态。
  ///
  /// 失败的读取一律当作「没有 VPN」——这是保守方向：宁可多做一次
  /// IP 直连尝试（最多浪费一个超时），也不要因为误判把 VPN 用户的
  /// 正常链路关掉。
  static Future<bool> hasVpn() async {
    final now = DateTime.now();
    final cached = _cached;
    if (cached != null && now.difference(_cachedAt) < _ttl) return cached;

    var found = false;
    try {
      final interfaces = await NetworkInterface.list(
        includeLoopback: false,
        type: InternetAddressType.IPv4,
      );
      for (final nic in interfaces) {
        // 没有任何地址的接口不算「在用」
        if (nic.addresses.isEmpty) continue;
        final name = nic.name.toLowerCase();
        if (_vpnPrefixes.any(name.startsWith)) {
          found = true;
          break;
        }
      }
    } catch (_) {
      found = false;
    }

    _cached = found;
    _cachedAt = now;
    return found;
  }

  /// 强制丢弃缓存（用户在设置里改了网络相关选项时调用）。
  static void invalidate() {
    _cached = null;
    _cachedAt = DateTime.fromMillisecondsSinceEpoch(0);
  }
}
