/// 站点 IP 路由：内置可用 IP，一个首选、其余备用兜底。
///
/// ## 为什么需要
///
/// `rule34video.com` 的解析结果**随 DNS 服务器变化**（实测）：
///
/// | Resolver | 解析到的 IP |
/// |---|---|
/// | 本地 DNS | `128.121.243.76` |
/// | Google 8.8.8.8 | `108.160.162.109` |
/// | Cloudflare 1.1.1.1 | `4.78.139.54` |
///
/// 三个 IP 实测**全部可用**，都是 `Server: ddos-guard` 前置、都不返回挑战页。
///
/// 但要注意：**靠 IP 选优提速的收益很小**——详情页最优 1.311s vs
/// 最差 1.566s，差距接近网络抖动本身。所以这个模块的价值不在"更快"，
/// 而在**容错**：
///
/// - 某个 IP 超时/被拦时可立刻换下一个，而不是直接失败；
/// - 本地 DNS 被污染时仍有可用出口；
/// - 少一次 DNS 解析往返。
///
/// ## 为什么保留系统 DNS
///
/// 硬编码 IP **一定会过期**（站点换机房、CDN 调度调整）。所以：
/// 内置列表只是**候选**，探测全失败就退回系统 DNS 正常解析。
/// 绝不把内置 IP 当成唯一出路。
library;

/// 一个候选端点。
class HostEndpoint {
  const HostEndpoint({
    required this.ip,
    this.label = '',
    this.weight = 0,
  });

  final String ip;

  /// 给日志/诊断用的来源说明。
  final String label;

  /// 静态优先级，数字小的优先。实测差异不明显，所以只用来定初始顺序。
  final int weight;

  @override
  String toString() => '$ip${label.isEmpty ? '' : '($label)'}';
}

/// 内置候选表。
///
/// 顺序按实测「详情页最优耗时」排过：
/// `108.160.162.109` 1.311s < `128.121.243.76` 1.382s < `4.78.139.54` 1.566s。
///
/// **注意这只是初始顺序**。真实顺序由 [HostRouter] 的探测结果决定，
/// 因为网络路径千差万别，我们这边快不代表用户那边也快。
const List<HostEndpoint> kBuiltinEndpoints = [
  HostEndpoint(ip: '108.160.162.109', label: 'dns.google', weight: 0),
  HostEndpoint(ip: '128.121.243.76', label: 'dns.local', weight: 1),
  HostEndpoint(ip: '4.78.139.54', label: 'dns.cloudflare', weight: 2),
];

/// 站点 IP 路由。
///
/// 语义是「首选一个，其余兜底」：
/// - [preferred] 是当前首选 IP，请求先打它；
/// - 失败时 [nextAfter] 依次给出候选，最后一个之后回退系统 DNS（返回 null）。
///
/// 刻意做成**无状态查询 + 外部记账**的形式：不在这里自己发请求，
/// 也不持有 HttpClient。谁发请求谁负责报告成败，路由只负责排序。
class HostRouter {
  HostRouter({
    List<HostEndpoint>? endpoints,
    this.enabled = true,
  })  : _endpoints = [...(endpoints ?? kBuiltinEndpoints)]
          ..sort((a, b) => a.weight.compareTo(b.weight));

  final List<HostEndpoint> _endpoints;

  /// 关掉时 [preferred] 恒为 null，调用方走系统 DNS。
  bool enabled;

  /// 上一次可用性探测的最优 IP（由 [recordSuccess] 更新）。
  String? _preferred;

  /// 连续失败次数，按 IP 记。用来把坏掉的 IP 降到后面。
  final Map<String, int> _failures = {};

  /// 候选列表（拷贝，供诊断展示）。
  List<HostEndpoint> get endpoints => List.unmodifiable(_endpoints);

  bool get isEmpty => _endpoints.isEmpty;

  /// 当前首选 IP；没有可用候选时返回 null（调用方走系统 DNS）。
  String? get preferred {
    if (!enabled || _endpoints.isEmpty) return null;

    // 1) 探测成功过、且没连续失败两次 → 用它
    final p = _preferred;
    if (p != null && (_failures[p] ?? 0) < 2) return p;

    // 2) 否则取静态顺序里「第一个没被彻底放弃的」。
    //
    // 刻意**不用**失败次数动态排序：`nextAfter` 走的是静态顺序，
    // 两者不一致时，遍历候选会跳过或重复某个 IP。曾经就是这么错的——
    // 首选失败一次后被降级，`nextAfter` 从静态表里取到的下一个
    // 恰好也是它自己刚跳过的位置，于是某个 IP 一次都没被试过。
    for (final e in _endpoints) {
      if ((_failures[e.ip] ?? 0) < 2) return e.ip;
    }
    // 全都失败两次了，还是从静态顺序第一个开始（总比直接放弃好）
    return _endpoints.first.ip;
  }

  /// 在 [ip] 失败之后，下一个该试的 IP；全都试过返回 null（→ 系统 DNS）。
  ///
  /// 严格按静态顺序往后走，保证一次请求里每个候选最多试一次。
  String? nextAfter(String ip) {
    if (!enabled) return null;
    final idx = _endpoints.indexWhere((e) => e.ip == ip);
    if (idx < 0) return _endpoints.firstOrNull?.ip;
    if (idx + 1 >= _endpoints.length) return null;
    return _endpoints[idx + 1].ip;
  }

  /// 报告某 IP 成功。连续成功会让它成为首选。
  void recordSuccess(String ip) {
    _failures[ip] = 0;
    _preferred = ip;
  }

  /// 报告某 IP 失败。
  ///
  /// 只累加计数；[preferred] 会在连续失败两次后自动换人。
  void recordFailure(String ip) {
    _failures[ip] = (_failures[ip] ?? 0) + 1;
  }

  /// 尝试次数上限：候选数 + 1（最后一次是系统 DNS）。
  int get maxAttempts => _endpoints.length + 1;

  void reset() {
    _preferred = null;
    _failures.clear();
  }

  /// 拼出「用这个 IP 但保留原 Host/SNI」的地址。
  ///
  /// 只是把主机名换成 IP，路径与查询串原样保留。
  /// **不能**直接拿 `https://<ip>/path` 就完事——那样 Host 头会变成 IP，
  /// 站点会当成未知虚拟主机。调用方必须同时把 Host 头设回原域名。
  static Uri withIp(Uri original, String ip) =>
      original.replace(host: ip);
}

extension _FirstOrNull<T> on List<T> {
  T? get firstOrNull => isEmpty ? null : first;
}

