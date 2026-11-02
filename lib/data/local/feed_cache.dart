import 'dart:async';
import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../../domain/video_card.dart';

/// 列表页的本地缓存：让首屏**立刻有内容**，再后台刷新。
///
/// ## 为什么需要（以及它解决什么）
///
/// 站点没有 JSON API，列表全靠解析 200KB+ 的 HTML，一轮下来 1–3 秒是常态。
/// 冷启动时用户盯着转圈，切页签回来还要再来一次（provider 被回收后又重新
/// 请求），体感就是「每次点进来都很慢」。
///
/// 这里把**最近一次成功的结果**按列表 key 存盘，下次进入时先同步吐出缓存
/// ——瞬间可见——再在后台拉新数据，拿到了再替换。也就是 stale-while-
/// revalidate：宁可能看到几秒前的内容，也不要白屏。
///
/// ## 设计取舍
///
/// - **只缓存第 1 页**。翻页结果缓存价值低（用户很少原样翻回去），
///   而且体积会失控；
/// - **带 TTL**，但过期不等于不可用：过期后仍然先展示（标为 stale），
///   后台刷新会覆盖它。只有「从未缓存过」才会真的白屏等待；
/// - **容量上限**：按 key 数量淘汰最旧的，避免列表种类多了撑爆 prefs。
/// - **写盘串行化**：多个列表同时返回时，避免 read-modify-write 互相覆盖。
class FeedCache {
  FeedCache(this._prefs);

  final SharedPreferences _prefs;

  static const String _kPrefix = 'feed_cache_v1:';
  static const String _kIndex = 'feed_cache_v1_index';

  /// 最多保留多少个列表的缓存。
  static const int maxEntries = 24;

  /// 超过这个时间就算「陈旧」，但仍然会先展示。
  ///
  /// 站点列表更新很快，但用户看到的几秒/几分钟前的数据并不影响使用；
  /// 真正重要的是**不要白屏**。所以 TTL 只用来决定要不要提示，
  /// 不用来决定「能不能用」。
  static const Duration ttl = Duration(minutes: 30);

  /// 写盘串行化：把并发调用排到同一条 Future 链上。
  Future<void> _writeChain = Future.value();

  String _keyFor(String cacheKey) => '$_kPrefix$cacheKey';

  /// 读缓存。没有就返回 null（调用方走正常加载流程）。
  ///
  /// [cacheKey] 由调用方生成，要能唯一标识「哪一页列表」，
  /// 例如 `latest:newest` / `tag:big-breasts:newest`。
  CachedFeed? read(String cacheKey) {
    try {
      final raw = _prefs.getString(_keyFor(cacheKey));
      if (raw == null || raw.isEmpty) return null;
      final json = jsonDecode(raw) as Map<String, dynamic>;
      final at = (json['at'] as num?)?.toInt() ?? 0;
      final list = (json['cards'] as List<dynamic>? ?? const [])
          .whereType<Map<String, dynamic>>()
          .map(VideoCard.fromJson)
          .where((c) => c.id.isNotEmpty)
          .toList();
      if (list.isEmpty) return null;
      final savedAt = DateTime.fromMillisecondsSinceEpoch(at);
      return CachedFeed(
        cards: list,
        savedAt: savedAt,
        isStale: DateTime.now().difference(savedAt) > ttl,
      );
    } catch (_) {
      // 格式变了就当没有，不要因为一条坏缓存让首屏崩掉
      return null;
    }
  }

  /// 写缓存（异步、串行、失败静默）。
  Future<void> write(String cacheKey, List<VideoCard> cards) {
    // 排到链尾，保证多个列表同时返回时不会互相覆盖索引。
    final next = _writeChain.then((_) => _writeImpl(cacheKey, cards));
    _writeChain = next.catchError((_) {});
    return _writeChain;
  }

  Future<void> _writeImpl(String cacheKey, List<VideoCard> cards) async {
    try {
      final payload = jsonEncode({
        'at': DateTime.now().millisecondsSinceEpoch,
        'cards': cards.map((c) => c.toJson()).toList(),
      });
      await _prefs.setString(_keyFor(cacheKey), payload);
      await _touchIndex(cacheKey);
    } catch (_) {
      // 写失败不影响使用，下次还有机会
    }
  }

  /// 维护「哪些 key 存在、最后一次写入是什么时候」，用于淘汰。
  Future<void> _touchIndex(String cacheKey) async {
    try {
      final raw = _prefs.getString(_kIndex);
      final map = <String, int>{};
      if (raw != null && raw.isNotEmpty) {
        final decoded = jsonDecode(raw);
        if (decoded is Map) {
          decoded.forEach((k, v) {
            if (k is String && v is num) map[k] = v.toInt();
          });
        }
      }
      map[cacheKey] = DateTime.now().millisecondsSinceEpoch;

      if (map.length > maxEntries) {
        final sorted = map.entries.toList()
          ..sort((a, b) => a.value.compareTo(b.value));
        final drop = sorted.take(map.length - maxEntries).toList();
        for (final e in drop) {
          map.remove(e.key);
          await _prefs.remove(_keyFor(e.key));
        }
      }
      await _prefs.setString(_kIndex, jsonEncode(map));
    } catch (_) {}
  }

  /// 清空全部列表缓存（设置页「清缓存」用）。
  Future<void> clear() async {
    try {
      final raw = _prefs.getString(_kIndex);
      if (raw != null && raw.isNotEmpty) {
        final decoded = jsonDecode(raw);
        if (decoded is Map) {
          for (final k in decoded.keys) {
            if (k is String) await _prefs.remove(_keyFor(k));
          }
        }
      }
      await _prefs.remove(_kIndex);
    } catch (_) {}
  }
}

/// 一条缓存读取结果。
class CachedFeed {
  const CachedFeed({
    required this.cards,
    required this.savedAt,
    required this.isStale,
  });

  final List<VideoCard> cards;
  final DateTime savedAt;

  /// 超过 [FeedCache.ttl]。仍然可用，只是 UI 可以提示「正在更新」。
  final bool isStale;

  Duration get age => DateTime.now().difference(savedAt);
}
