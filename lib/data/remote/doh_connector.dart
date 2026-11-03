import 'dart:async';
import 'dart:io';

import 'doh_resolver.dart';
import 'host_router.dart';

/// DNS over HTTPS 的落地方式：**解析出 IP，交给上层的「换 IP + 补 Host 头」路径**。
///
/// ## 为什么不是「在 socket 层拦截」
///
/// 最初的设计是用 `HttpClient.connectionFactory` —— 那个口子在建立 TCP
/// 之前被调用，理论上能直接把域名换成 IP，而 HTTP 层仍看到原域名。
/// 实测这条路**走不通**，有两个硬障碍：
///
/// 1. `connectionFactory` 返回后 **HttpClient 不再做 TLS**。默认实现自己
///    调 `SecureSocket.startConnect`，而工厂路径拿到什么就当成已就绪的
///    socket 用。直接返回 `Socket.startConnect(ip, port)` 得到的是明文
///    TCP，握手数据对不上，服务端立刻关连接，报
///    `Connection closed before full header was received`。
/// 2. 想在工厂里自己做 TLS 也不行：`SecureSocket.startConnect` 会返回
///    `ConnectionTask<SecureSocket>`，而 `ConnectionTask` 是 **final class**，
///    没法自己实现一个把 `SecureSocket` 包成 `ConnectionTask<Socket>` 的适配器。
///
/// 所以退回到「换 IP + 补 Host 头」这条路：把 URI 的 host 换成 IP，
/// 同时把 `Host` 头设回原域名。这条路之前已经验证可用（HTTP/1.1 的
/// Host 头决定虚拟主机，SNI 在 DNS 名与 Host 一致时也能对上）。
///
/// ## 这个类负责什么
///
/// 只负责「问 DoH 要一个当下可用的 IP」。`SiteClientImpl` 拿到后照旧拼
/// URL 补 Host 头，其它逻辑一行不用改。
class DnsOverHttpsConnector {
  DnsOverHttpsConnector({
    required this.resolver,
    this.enabled = true,
  });

  final DohResolver resolver;

  /// 关掉时完全走系统 DNS（设置里的开关）。
  bool enabled;

  /// 最近一次解析用的 IP，供诊断面板展示。
  String? lastResolvedIp;

  /// 解析失败次数，便于判断 DoH 是否整体不可用。
  int failureCount = 0;

  /// 上次验证过的 IP，用来跳过重复探测。
  String? _lastGood;

  /// 最近的候选池（按探测耗时排序），给请求层逐个重试用。
  ///
  /// 单个 IP 的成败**和地区强相关**：同一个域名在国内连
  /// `190.115.31.243` 很稳，换个国家/运营商可能完全不通。所以不能只挑
  /// 「当前最快的那个」就锁死——要把整池按质量排好序交给上层，
  /// 这条不通就试下一条。
  List<String> _lastPool = const [];

  /// 上次探测得到的候选（已按可用性 + 延迟排序，且只含能连上的）。
  ///
  /// 为空表示还没探测过或全部不通。
  List<String> get lastPool => List.unmodifiable(_lastPool);

  /// 单个 IP 的探测超时。
  ///
  /// 取 4 秒而不是 2 秒：实测这个 CDN 的 TCP 握手在 1–3 秒之间波动，
  /// 2 秒会把还能用的 IP 误判成不可用，于是「明明有可用 IP 却全被过滤掉」，
  /// 请求退回系统 DNS——而系统 DNS 拿到的是污染结果，反而更慢更糟。
  ///
  /// 候选是并发探测的，所以放宽超时不会成倍拖慢，最坏只多等这一次。
  static const Duration probeTimeout = Duration(seconds: 4);

  /// 给站点域名解析出一个**当下可连**的 IP。失败返回 null（走系统 DNS）。
  ///
  /// 站点在做 DNS 轮询，返回的 IP 质量参差不齐——实测同一域名，DoH 先后
  /// 给出 `202.160.128.238`（可用）、`108.160.169.174`（超时）、以及国内
  /// 服务商给出的 Facebook IP 段（完全不通）。随便取第一个会让整个请求
  /// 超时，所以这里必须验证。
  Future<String?> resolveSiteIp() async {
    if (!enabled) return null;
    return resolve(kSiteHost);
  }

  /// 解析任意 host（主要给测试与诊断用）。
  Future<String?> resolve(String host) async {
    final pool = await resolvePool(host);
    return pool.isEmpty ? null : pool.first;
  }

  /// 解析出**整池**可用 IP，按「先快后慢」排序。
  ///
  /// 与 [resolveSiteIp] 的区别：那个只给一个，这个给全部可用的。
  /// 请求层需要后者——某个 IP 在特定地区/运营商下不通时，
  /// 要能立刻试下一个，而不是把整次请求判死。
  Future<List<String>> resolvePool(String host) async {
    if (!enabled) return const [];
    if (!isSiteHost(host)) return const [];

    try {
      final ips = await resolver.resolve(host);
      if (ips.isEmpty) {
        failureCount++;
        return const [];
      }

      // 上次探测的池子仍然有效（候选没变）就直接复用，省一次全量探测。
      final remembered = _lastGood;
      if (remembered != null && ips.contains(remembered) && _lastPool.isNotEmpty) {
        lastResolvedIp = remembered;
        return _lastPool;
      }

      // 并发探测全部候选，按耗时排序。
      // 串行最坏是 N × probeTimeout，用户感受到的就是「打开很慢」；
      // 并发之后最坏只等一个 probeTimeout。
      final probed = await Future.wait(
        ips.map((ip) async {
          final sw = Stopwatch()..start();
          final ok = await _probe(ip);
          return (ip: ip, ok: ok, ms: sw.elapsedMilliseconds);
        }),
      );

      final alive = probed.where((p) => p.ok).toList()
        ..sort((a, b) => a.ms.compareTo(b.ms));

      if (alive.isEmpty) {
        failureCount++;
        _lastPool = const [];
        return const [];
      }

      final pool = alive.map((p) => p.ip).toList();
      _lastPool = pool;
      _lastGood = pool.first;
      lastResolvedIp = pool.first;
      return pool;
    } catch (_) {
      failureCount++;
      return const [];
    }
  }

  /// 解析出**所有可用 IP**（多服务商并集 + 实测筛选），用于候选池。
  ///
  /// 与 [resolvePool] 的区别：
  /// - [resolvePool] 只问「能快速拿到的那个结果」，适合请求路径；
  /// - 这个函数用 [DohResolver.resolveAll] **并发问全部服务商**并把结果
  ///   并起来，得到尽量多的候选，再由 [SiteIpPool] 做完整 TLS 实测筛选。
  ///
  /// 「候选多」这件事很重要：站点做 DNS 轮询，不同服务商/地区返回的
  /// A 记录可能不同，只取一个就锁死，等于放弃「换条路」的机会。
  Future<List<String>> resolveAllCandidates() async {
    if (!enabled) return const [];
    try {
      return await resolver.resolveAll(kSiteHost);
    } catch (_) {
      return const [];
    }
  }

  /// 探测某个 IP 的 443 端口是否可连（短超时，不建 TLS）。
  Future<bool> _probe(String ip) async {
    try {
      final s = await Socket.connect(ip, 443, timeout: probeTimeout);
      s.destroy();
      return true;
    } catch (_) {
      return false;
    }
  }

  /// 忘掉已验证的 IP（网络变化或用户切换设置时调用）。
  void reset() {
    _lastGood = null;
    _lastPool = const [];
    lastResolvedIp = null;
    failureCount = 0;
    resolver.clear();
  }
}

