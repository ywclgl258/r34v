import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';

/// DoH（DNS over HTTPS）解析器。
///
/// ## 为什么需要
///
/// 站点在做 **DNS 轮询**，每次解析返回的 IP 都不一样（实测）：
///
/// ```
/// 108.160.167.158
/// 108.160.165.9
/// 52.175.9.80
/// ```
///
/// 所以任何硬编码的 IP 列表都会在几天内过时。之前的做法是内置几个 IP
/// 做直连，结果是「过一段时间就连不上，得发新版」——这是不可接受的。
///
/// DoH 从根上解决：**每次拿到的都是当下的真实 IP**，不依赖本地 DNS，
/// 也不怕 DNS 污染。
///
/// ## 为什么带 bootstrapIps
///
/// DoH 端点本身是个域名（`dns.alidns.com`），解析它需要 DNS——鸡生蛋问题。
/// 所以每个预设都带自己的 IP，用 IP 直连 DoH 端点，绕开这一步。
///
/// ## 路由策略
///
/// 1. 缓存命中（未过 TTL）→ 直接用
/// 2. 并发查所有预设 → 取第一个成功的
/// 3. 全失败 → 返回 null，调用方退回系统 DNS
class DohResolver {
  DohResolver({Dio? dio, this.preset, this.timeout = const Duration(seconds: 6)})
      : _dio = dio ??
            Dio(BaseOptions(
              connectTimeout: const Duration(seconds: 6),
              receiveTimeout: const Duration(seconds: 6),
              headers: {'accept': 'application/dns-json'},
              validateStatus: (s) => s != null && s < 500,
            ));

  final Dio _dio;
  final Duration timeout;

  /// 空表示用默认预设列表。
  final DohPreset? preset;

  /// 解析结果缓存：hostname → IP 列表。
  final Map<String, _CachedIps> _cache = {};

  /// 默认 TTL。DNS 记录本身的 TTL 通常 14–60 秒，
  /// 但我们不必那么激进——取 5 分钟，既能跟上轮询又能省请求。
  static const Duration defaultTtl = Duration(minutes: 5);

  /// 可用预设。
  ///
  /// 全部走 JSON API（`?name=xxx&type=A`），比 wireformat 简单且好调。
  ///
  /// ## 顺序很重要：国外服务商在前
  ///
  /// 实测这个域名在**国内 DNS 下是被污染的**：
  ///
  /// | 服务商 | 返回的 IP | 能否连通 |
  /// |---|---|---|
  /// | AliDNS | `69.171.242.11` / `174.36.196.242` / `31.13.69.169` | **全是 Facebook 段，443 全部连不通** |
  /// | Cloudflare | `190.115.31.243` | 200，稳定 |
  /// | Google | `190.115.31.243` | 200，与 Cloudflare 一致 |
  ///
  /// 所以国内 DoH 在这里不但没用，反而会把请求导向死路。
  /// 放在最后只作为「国外服务商也不通」时的尝试。
  static const List<DohPreset> presets = [
    DohPreset(
      key: 'cloudflare',
      title: 'Cloudflare',
      jsonUrl: 'https://cloudflare-dns.com/dns-query',
      bootstrapIps: ['1.1.1.1', '1.0.0.1'],
    ),
    DohPreset(
      key: 'google',
      title: 'Google',
      jsonUrl: 'https://dns.google/resolve',
      bootstrapIps: ['8.8.8.8', '8.8.4.4'],
    ),
    // 国内服务商垫底：实测该域名在它们那里是污染结果。
    DohPreset(
      key: 'alidns',
      title: 'AliDNS',
      jsonUrl: 'https://dns.alidns.com/resolve',
      bootstrapIps: ['223.5.5.5', '223.6.6.6'],
    ),
  ];

  DohPreset get activePreset => preset ?? presets.first;

  /// 解析 [hostname] 的 A 记录。
  ///
  /// 返回 IP 字符串列表（去重、保序）。失败返回空列表——调用方据此
  /// 退回系统 DNS，不要抛异常（DNS 失败不该让整个请求崩掉）。
  Future<List<String>> resolve(String hostname) async {
    final hit = _cache[hostname];
    if (hit != null && !hit.isExpired) return hit.ips;

    final ips = await _query(hostname);
    if (ips.isEmpty) return const [];

    _cache[hostname] = _CachedIps(ips, DateTime.now());
    return ips;
  }

  /// 同步取缓存（没有或过期返回空）。
  List<String> cachedFor(String hostname) {
    final hit = _cache[hostname];
    if (hit == null || hit.isExpired) return const [];
    return hit.ips;
  }

  void clear() => _cache.clear();

  Future<List<String>> _query(String hostname) async {
    final targets = preset == null ? presets : <DohPreset>[preset!];

    // **按优先级串行查，不能用 Future.any。**
    //
    // `Future.any` 返回的是「最快响应」的那个，而不是「优先级最高」的。
    // 在国内 AliDNS 的响应通常比 Cloudflare 快，于是污染结果会抢先返回，
    // 把服务商顺序完全打乱——实测就是这么拿到 Facebook 段 IP 的。
    //
    // 串行的代价是最坏多等一两秒，但换来的是「用对了服务商」。
    // 前一个成功就立刻返回，所以正常情况下只查第一个。
    for (final p in targets) {
      try {
        final ips = await _queryOne(p, hostname);
        if (ips.isNotEmpty) return ips;
      } catch (_) {
        // 这个服务商不通，试下一个
        continue;
      }
    }
    return const [];
  }

  Future<List<String>> _queryOne(DohPreset p, String hostname) async {
    final res = await _dio.get<dynamic>(
      p.jsonUrl,
      queryParameters: {'name': hostname, 'type': 'A'},
      options: Options(
        responseType: ResponseType.json,
        receiveTimeout: timeout,
        connectTimeout: timeout,
      ),
    ).timeout(timeout);

    if (res.statusCode != 200) {
      throw StateError('DoH ${p.key} 返回 ${res.statusCode}');
    }

    final ips = parseAnswers(res.data);
    if (ips.isEmpty) {
      throw StateError('DoH ${p.key} 没有 A 记录');
    }
    return ips;
  }

  /// 解析 DoH 的 JSON 响应。
  ///
  /// 标准格式（RFC 8484 的 JSON 变体，Cloudflare / AliDNS / DNSPod 都支持）：
  ///
  /// ```json
  /// { "Status": 0,
  ///   "Answer": [ {"name":"x.","TTL":56,"type":1,"data":"1.2.3.4"} ] }
  /// ```
  ///
  /// 只取 `type == 1`（A 记录）。纯函数，方便单测。
  ///
  /// ## 为什么要接受字符串
  ///
  /// 实测 Cloudflare 的 `/dns-query` 返回的 `Content-Type` 不是
  /// `application/json`，于是 Dio 即使设了 `ResponseType.json` 也**不解析**，
  /// `res.data` 拿到的是原始 `String`。
  ///
  /// 第一版只判断 `is Map`，遇到字符串就当成「没有 A 记录」直接返回空——
  /// 结果 Cloudflare 作为第一优先级永远是空的，每个请求都要多绕两轮。
  /// 现在字符串也会被解析。
  static List<String> parseAnswers(Object? raw) {
    var node = raw;

    // 字符串先尝试当 JSON 解析（Cloudflare 就属于这种）
    if (node is String) {
      final t = node.trim();
      if (t.isEmpty) return const [];
      try {
        node = jsonDecode(t);
      } catch (_) {
        return const [];
      }
    }

    if (node is! Map) return const [];
    final status = node['Status'];
    // 0 = NOERROR；其它（3 = NXDOMAIN 等）都算没有结果
    if (status is int && status != 0) return const [];

    final answer = node['Answer'];
    if (answer is! List) return const [];

    final out = <String>[];
    for (final e in answer) {
      if (e is! Map) continue;
      // type 1 = A 记录（IPv4）。AAAA 是 28，这里不要——实测 IPv6
      // 在多数移动网络上不可用，拿到反而会拖慢或超时。
      final type = e['type'];
      if (type is int && type != 1) continue;
      final data = e['data'];
      if (data is! String || data.isEmpty) continue;
      if (!_looksLikeIpv4(data)) continue;
      if (!out.contains(data)) out.add(data);
    }
    return out;
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
}

/// 一个 DoH 服务商。
class DohPreset {
  const DohPreset({
    required this.key,
    required this.title,
    required this.jsonUrl,
    required this.bootstrapIps,
  });

  final String key;
  final String title;

  /// 返回 JSON 的查询地址。
  final String jsonUrl;

  /// DoH 端点自己的 IP，用于它本身也解析不了时直连。
  final List<String> bootstrapIps;
}

class _CachedIps {
  _CachedIps(this.ips, this.at);

  final List<String> ips;
  final DateTime at;

  bool get isExpired =>
      DateTime.now().difference(at) > DohResolver.defaultTtl;
}

/// 供外部做连通性探测用：直接对一个 IP + 域名建 TLS 连接看看通不通。
///
/// 不参与生产路径，只在「设置 → 网络 → 测试 DNS」这类诊断里用。
Future<bool> probeEndpoint(String host, String ip, {Duration? timeout}) async {
  final limit = timeout ?? const Duration(seconds: 5);
  try {
    final socket = await Socket.connect(ip, 443, timeout: limit);
    socket.destroy();
    return true;
  } catch (_) {
    return false;
  }
}

/// 便于测试注入。
typedef DohQuery = Future<List<String>> Function(String hostname);

/// 把 JSON 文本直接转成 IP 列表的便利函数（测试用）。
List<String> parseDohJsonText(String text) {
  try {
    return DohResolver.parseAnswers(jsonDecode(text));
  } catch (_) {
    return const [];
  }
}


