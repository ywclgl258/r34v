import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';

import 'doh_connector.dart';
import 'host_router.dart';

/// 站点直连 IP 的候选池：**自动发现、自动排序、跨启动复用**。
///
/// ## 要解决的两个现实问题
///
/// **1. 内置 IP 会过期。** 站点在做 DNS 轮询，硬编码的
/// `190.115.31.243` 只是「写这行代码那天」的结果。它迟早会失效，
/// 而失效后用户只能等发新版——不可接受。
///
/// **2. IP 可达性有地区差异。** 同一域名在不同运营商/国家可能解析到
/// 不同 IP，可达性也完全不同。所以「哪几个 IP 能用」必须在本机现场测，
/// 不能靠一个全局写死的清单。
///
/// ## 策略：先用手上的，后台换新的
///
/// 冷启动时网络最宝贵，不能等一轮 DoH + 探测才开始加载列表。所以：
///
/// ```
/// 启动 → 立刻用上次存盘的池（可能为空）→ 后台探测 → 更新池并落盘
/// ```
///
/// 也就是 stale-while-revalidate 的同一套思路。用户几乎无感，
/// 而池子会随每次使用逐渐变准。
///
/// ## 什么时候强制刷新
///
/// - 用户在设置里改了自定义 IP
/// - VPN 开关变化（网络环境本质上变了，旧结果不可信）
/// - 连续失败达到阈值（说明池子里的 IP 都废了）
class SiteIpPool {
  SiteIpPool({
    required this.connector,
    required this._prefs,
    this.probeTimeout = const Duration(seconds: 4),
  }) {
    // 构造时同步载入上次结果：让第一批请求就能用上，不用等探测。
    loadFromDisk();
  }

  final DnsOverHttpsConnector connector;

  final SharedPreferences _prefs;
  final Duration probeTimeout;

  static const String _kIps = 'site_ip_pool_v1';
  static const String _kAt = 'site_ip_pool_v1_at';

  /// 池子最多留几个。太多会让每次请求的重试链变长。
  static const int maxPoolSize = 4;

  /// 存盘的池子超过这个时间就在**下次启动**重新探测。
  ///
  /// 取 6 小时：站点轮询周期不是分钟级的，太频繁探测只是白耗流量；
  /// 但也不能太久，否则 IP 失效后用户要等一整天。
  static const Duration maxAge = Duration(hours: 6);

  List<String> _ips = const [];
  DateTime? _updatedAt;
  bool _loading = false;

  /// 当前可用的候选（已按探测延迟排序，快的在前）。
  ///
  /// 可能为空——那表示「还没探到」或「上次存的也过期了」，
  /// 调用方应继续尝试内置候选和系统 DNS。
  List<String> get ips => List.unmodifiable(_ips);

  /// 池子最后一次更新时间（null 表示从没成功过）。
  DateTime? get updatedAt => _updatedAt;

  /// 池子是否已经陈旧（需要后台刷新）。
  bool get isStale {
    final at = _updatedAt;
    if (at == null) return true;
    return DateTime.now().difference(at) > maxAge;
  }

  // ---------- 读写存盘 ----------

  /// 同步载入上次的池子。**在启动路径上调用，必须很快**。
  void loadFromDisk() {
    try {
      final raw = _prefs.getString(_kIps);
      final at = _prefs.getInt(_kAt);
      if (raw == null || raw.isEmpty) return;
      final list = (jsonDecode(raw) as List<dynamic>)
          .whereType<String>()
          .where(_looksLikeIpv4)
          .take(maxPoolSize)
          .toList();
      if (list.isEmpty) return;
      _ips = list;
      _updatedAt = at == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(at);
    } catch (_) {
      // 格式坏了就当没有，不值得为一份缓存崩掉启动
    }
  }

  Future<void> _saveToDisk(List<String> ips) async {
    try {
      await _prefs.setString(_kIps, jsonEncode(ips));
      await _prefs.setInt(_kAt, DateTime.now().millisecondsSinceEpoch);
    } catch (_) {}
  }

  // ---------- 刷新 ----------

  /// 后台刷新池子（并发保护：同时只有一轮在跑）。
  ///
  /// 返回刷新后的候选。失败时保留旧值——**旧的能用总比空着好**。
  Future<List<String>> refresh({List<String> extra = const []}) async {
    if (_loading) return _ips;
    _loading = true;
    try {
      // 1) 自定义 IP 永远优先，且不参与探测淘汰：它是用户自己填的，
      //    即使这一轮探不通也不该被自动删掉（可能只是临时抖动）。
      final candidates = <String>[];
      for (final ip in extra) {
        if (_looksLikeIpv4(ip) && !candidates.contains(ip)) {
          candidates.add(ip);
        }
      }

      // 2) 候选来源要**尽量全**，因为站点在做 DNS 轮询，不同服务商/地区
      //    视角返回的 A 记录可能不同，可达性也各不相同。三路并起来：
      //
      //    a. 多 DoH 服务商的并集（并发问，最可靠）
      //    b. 系统 DNS 的结果（可能被污染，但也可能是有效的本地就近 IP，
      //       实测在部分网络下系统 DNS 反而给的是可用地址）
      //    c. 内置兜底
      final resolved = <String>[];
      try {
        resolved.addAll(await connector.resolveAllCandidates());
      } catch (_) {}

      try {
        final systemDns = await InternetAddress.lookup(
          kSiteHost,
          type: InternetAddressType.IPv4,
        ).timeout(const Duration(seconds: 4));
        for (final a in systemDns) {
          // 过滤明显的污染结果：私有地址、回环、0.0.0.0
          if (_isUsablePublicIpv4(a.address)) resolved.add(a.address);
        }
      } catch (_) {}

      for (final ip in resolved) {
        if (!candidates.contains(ip)) candidates.add(ip);
      }

      // 3) 内置兜底。
      for (final e in kBuiltinEndpoints) {
        if (!candidates.contains(e.ip)) candidates.add(e.ip);
      }

      // 4) 并发做**完整 TLS 探测**，按延迟排序。只保留真正能服务的。
      //
      //    注意候选可能不少（多服务商 + 系统 DNS + 内置），但探测是并发的，
      //    最坏只等一个 probeTimeout，不会线性变慢。
      final probed = await Future.wait(
        candidates.map((ip) async {
          // 用户手填的 IP 不参与淘汰：他自己填的，即使这轮探不通也保留
          final fromUser = extra.contains(ip);
          final sw = Stopwatch()..start();
          final ok = await _probe(ip);
          return (ip: ip, ok: ok || fromUser, ms: sw.elapsedMilliseconds);
        }),
      );

      final alive = probed.where((p) => p.ok).toList()
        ..sort((a, b) => a.ms.compareTo(b.ms));

      if (alive.isEmpty) {
        // 一个都不通：什么都不改，保留旧池子。
        // 常见于「网络刚断还没恢复」，此时清空只会让恢复更慢。
        return _ips;
      }

      final fresh = alive.map((p) => p.ip).take(maxPoolSize).toList();
      _ips = fresh;
      _updatedAt = DateTime.now();
      await _saveToDisk(fresh);
      return fresh;
    } finally {
      _loading = false;
    }
  }

  /// 探测某个 IP 是否**真的可用**（不只是 TCP 能连）。
  ///
  /// ## 为什么不能只做 TCP 握手
  ///
  /// 先前这里只 `Socket.connect(ip, 443)` 就当作可用，这是不够的：
  ///
  /// - 中间设备可以回一个 SYN-ACK 再把连接断掉（防火墙、运营商劫持页）
  /// - 这个 IP 可能确实是活的，但跑的是别的服务
  /// - 污染 IP 也有可能完成 TCP 握手（实测 `202.160.128.40` 就是 TCP 就
  ///   不通，但换个网络环境未必如此）
  ///
  /// 只测 TCP 的后果很直接：池子里存进一堆「能连但打不开」的 IP，
  /// 用户看到的就是「一直转圈」。
  ///
  /// 所以这里做**完整 TLS 握手 + 证书校验**：`SecureSocket.secure` 的
  /// `host` 参数既是 SNI 也是证书校验目标，能通过就说明这个 IP 确实在
  /// 为 `rule34video.com` 提供服务，而且证书链合法。
  ///
  /// 成本：比纯 TCP 多 1–2 个往返。但这是后台任务，不阻塞首屏。
  Future<bool> _probe(String ip) async {
    Socket? tcp;
    SecureSocket? tls;
    try {
      tcp = await Socket.connect(ip, 443, timeout: probeTimeout);
      tls = await SecureSocket.secure(
        tcp,
        host: kSiteHost,
        // 站点只协商到 HTTP/1.1
        supportedProtocols: const ['http/1.1'],
      ).timeout(probeTimeout);
      return true;
    } catch (_) {
      // 握手失败 / 证书不对 / 超时：这个 IP 不可用
      return false;
    } finally {
      // 必须显式关闭，否则半开连接会占着文件描述符
      try {
        tls?.destroy();
      } catch (_) {}
      try {
        tcp?.destroy();
      } catch (_) {}
    }
  }

  /// 报告某个 IP 失败，把它从池子里挪到末尾。
  ///
  /// 不直接删除：一次失败可能是抖动，立刻删掉会让池子快速枯竭；
  /// 挪到末尾既降低了它的优先级，又保留了「下次再试」的机会。
  void demote(String ip) {
    if (!_ips.contains(ip)) return;
    final next = [..._ips]..remove(ip);
    next.add(ip);
    _ips = next;
    unawaited(_saveToDisk(_ips));
  }

  /// 整个池子作废（VPN 开关、用户改设置、网络切换时调用）。
  void reset() {
    _ips = const [];
    _updatedAt = null;
    try {
      _prefs.remove(_kIps);
      _prefs.remove(_kAt);
    } catch (_) {}
  }

  static bool _looksLikeIpv4(String s) {
    final parts = s.split('.');
    if (parts.length != 4) return false;
    for (final p in parts) {
      final n = int.tryParse(p);
      if (n == null || n < 0 || n > 255) return false;
    }
    return true;
  }

  /// 过滤掉不可能承载站点的地址：私有段、回环、保留段。
  ///
  /// 系统 DNS 被污染时，返回的可能是 `127.0.0.1`、`0.0.0.0`、内网段这类
  /// 明显错误的结果。把它们放进候选池只会浪费一次探测，
  /// 而且 127.x 在本机探测「成功」的概率很高，会造成假阳性。
  static bool _isUsablePublicIpv4(String s) {
    if (!_looksLikeIpv4(s)) return false;
    final p = s.split('.').map(int.parse).toList();
    final a = p[0];
    final b = p[1];
    // 0.0.0.0/8、127/8、10/8、172.16/12、192.168/16、169.254/16
    if (a == 0 || a == 127 || a == 10) return false;
    if (a == 172 && b >= 16 && b <= 31) return false;
    if (a == 192 && b == 168) return false;
    if (a == 169 && b == 254) return false;
    // 100.64/10（CGNAT）、224+/4（组播）、255.255.255.255
    if (a == 100 && b >= 64 && b <= 127) return false;
    if (a >= 224) return false;
    return true;
  }
}
