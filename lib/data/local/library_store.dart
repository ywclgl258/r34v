import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// 一条观看记录。
class WatchEntry {
  const WatchEntry({
    required this.videoId,
    required this.title,
    required this.thumb,
    required this.path,
    required this.positionSeconds,
    required this.durationSeconds,
    required this.updatedAt,
    this.streamLabel = '',
  });

  final String videoId;
  final String title;
  final String thumb;

  /// 详情页路径（含 slug），续播时要靠它回源。
  final String path;
  final int positionSeconds;
  final int durationSeconds;
  final DateTime updatedAt;

  /// 上次使用的清晰度，下次进入默认选它。
  final String streamLabel;

  /// 进度百分比 0..1。
  double get progress {
    if (durationSeconds <= 0) return 0;
    return (positionSeconds / durationSeconds).clamp(0.0, 1.0);
  }

  /// 是否算「看完了」（最后 20 秒内视为看完，不再提示续播）。
  bool get isFinished => durationSeconds > 0 && positionSeconds >= durationSeconds - 20;

  /// 是否值得显示在「继续观看」（看了至少 15 秒且没看完）。
  bool get isResumable => positionSeconds >= 15 && !isFinished;

  WatchEntry copyWith({
    int? positionSeconds,
    int? durationSeconds,
    DateTime? updatedAt,
    String? streamLabel,
    String? title,
    String? thumb,
  }) =>
      WatchEntry(
        videoId: videoId,
        title: title ?? this.title,
        thumb: thumb ?? this.thumb,
        path: path,
        positionSeconds: positionSeconds ?? this.positionSeconds,
        durationSeconds: durationSeconds ?? this.durationSeconds,
        updatedAt: updatedAt ?? this.updatedAt,
        streamLabel: streamLabel ?? this.streamLabel,
      );

  Map<String, dynamic> toJson() => {
        'id': videoId,
        'title': title,
        'thumb': thumb,
        'path': path,
        'pos': positionSeconds,
        'dur': durationSeconds,
        'at': updatedAt.millisecondsSinceEpoch,
        'q': streamLabel,
      };

  static WatchEntry fromJson(Map<String, dynamic> j) => WatchEntry(
        videoId: j['id'] as String? ?? '',
        title: j['title'] as String? ?? '',
        thumb: j['thumb'] as String? ?? '',
        path: j['path'] as String? ?? '',
        positionSeconds: (j['pos'] as num?)?.toInt() ?? 0,
        durationSeconds: (j['dur'] as num?)?.toInt() ?? 0,
        updatedAt: DateTime.fromMillisecondsSinceEpoch((j['at'] as num?)?.toInt() ?? 0),
        streamLabel: j['q'] as String? ?? '',
      );

  @override
  String toString() => 'WatchEntry($videoId, $positionSeconds/$durationSeconds)';
}

/// 收藏的种类。
///
/// 分开存是因为三种东西点进去看的东西不一样：视频是去播放，
/// 作者是去看他的作品列表，标签是去看这个标签下的所有视频。
/// 混在一个列表里只能做成「一堆链接」，没法按类型筛选、也没法给不同图标。
enum FavoriteKind {
  video('视频'),
  character('角色'),
  model('作者'),
  tag('标签');

  const FavoriteKind(this.label);

  final String label;

  static FavoriteKind fromName(String? name) {
    for (final k in values) {
      if (k.name == name) return k;
    }
    return FavoriteKind.video;
  }
}

/// 一条收藏。
///
/// 视频、作者、标签共用一个结构：`target` 存 slug（视频存 id），
/// `path` 存点进去要跳的 App 内路由。
class FavoriteEntry {
  const FavoriteEntry({
    required this.kind,
    required this.target,
    required this.title,
    required this.path,
    required this.addedAt,
    this.thumb = '',
    this.subtitle = '',
  });

  final FavoriteKind kind;

  /// 视频是 videoId，作者/标签是对应的 slug。
  final String target;
  final String title;

  /// App 内路由，如 `/video/123/slug/` 或 `/list/model/derpixon`。
  final String path;
  final DateTime addedAt;

  /// 视频封面；作者/标签没有就空。
  final String thumb;

  /// 副标题：视频显示时长，作者/标签可以留空。
  final String subtitle;

  /// 同类型内唯一。跨类型可以有同名（比如某标签和某作者同名）。
  String get key => '${kind.name}:$target';

  Map<String, dynamic> toJson() => {
        'k': kind.name,
        't': target,
        'title': title,
        'path': path,
        'at': addedAt.millisecondsSinceEpoch,
        'thumb': thumb,
        'sub': subtitle,
      };

  static FavoriteEntry fromJson(Map<String, dynamic> j) {
    // 兼容旧格式：早期版本用的是 `id` 且没有 `k`/`t` 字段。
    // 只认新字段会把老用户的收藏整条丢掉，所以这里做一次性迁移。
    final kind = FavoriteKind.fromName(j['k'] as String?);
    final target = (j['t'] as String?) ?? (j['id'] as String?) ?? '';
    return FavoriteEntry(
      kind: kind,
      target: target,
      title: j['title'] as String? ?? '',
      path: j['path'] as String? ?? '',
      addedAt: DateTime.fromMillisecondsSinceEpoch((j['at'] as num?)?.toInt() ?? 0),
      thumb: j['thumb'] as String? ?? '',
      subtitle: j['sub'] as String? ?? '',
    );
  }

  @override
  String toString() => 'FavoriteEntry(${kind.name}, $target, $title)';
}

/// 观看历史与本地收藏。
///
/// 首版用 `shared_preferences` 存 JSON：历史/收藏是几百条量级，
/// 全量读写一次几毫秒，够用。破千条再迁 sqlite。
class LibraryStore {
  LibraryStore(this._prefs);

  final SharedPreferences _prefs;

  static const _kHistory = 'watch_history_v1';
  static const _kFavorites = 'favorites_v1';
  static const _kSettings = 'settings_v1';

  /// 历史最多留 500 条，超出丢最旧的。
  static const int historyCap = 500;

  // ---------- 历史 ----------

  List<WatchEntry> loadHistory() {
    final raw = _prefs.getString(_kHistory);
    if (raw == null || raw.isEmpty) return const [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .whereType<Map<String, dynamic>>()
          .map(WatchEntry.fromJson)
          .where((e) => e.videoId.isNotEmpty)
          .toList()
        ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    } catch (_) {
      // 存储格式坏了就丢掉重建，不要因为一条脏记录让 App 起不来
      return const [];
    }
  }

  /// 写入/更新一条观看记录。
  Future<void> saveWatch(WatchEntry entry) async {
    final list = loadHistory().toList();
    list.removeWhere((e) => e.videoId == entry.videoId);
    list.insert(0, entry);
    if (list.length > historyCap) list.removeRange(historyCap, list.length);
    await _prefs.setString(_kHistory, jsonEncode(list.map((e) => e.toJson()).toList()));
  }

  Future<void> removeWatch(String videoId) async {
    final list = loadHistory().where((e) => e.videoId != videoId).toList();
    await _prefs.setString(_kHistory, jsonEncode(list.map((e) => e.toJson()).toList()));
  }

  Future<void> clearHistory() => _prefs.remove(_kHistory);

  /// 「继续观看」列表：看过一点但没看完，按时间倒序。
  List<WatchEntry> resumable() => loadHistory().where((e) => e.isResumable).toList();

  /// 查单个视频的进度，用于详情页显示「继续观看 12:30」。
  WatchEntry? findWatch(String videoId) {
    for (final e in loadHistory()) {
      if (e.videoId == videoId) return e;
    }
    return null;
  }

  // ---------- 收藏 ----------

  List<FavoriteEntry> loadFavorites() {
    final raw = _prefs.getString(_kFavorites);
    if (raw == null || raw.isEmpty) return const [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .whereType<Map<String, dynamic>>()
          .map(FavoriteEntry.fromJson)
          .where((e) => e.target.isNotEmpty)
          .toList()
        ..sort((a, b) => b.addedAt.compareTo(a.addedAt));
    } catch (_) {
      return const [];
    }
  }

  /// 按种类取。
  List<FavoriteEntry> favoritesOf(FavoriteKind kind) =>
      loadFavorites().where((e) => e.kind == kind).toList();

  bool isFavorite(FavoriteKind kind, String target) =>
      loadFavorites().any((e) => e.kind == kind && e.target == target);

  /// 切换收藏状态，返回切换后是否已收藏。
  Future<bool> toggleFavorite(FavoriteEntry entry) async {
    final list = loadFavorites().toList();
    final existed = list.any((e) => e.key == entry.key);
    if (existed) {
      list.removeWhere((e) => e.key == entry.key);
    } else {
      list.insert(0, entry);
    }
    await _prefs.setString(_kFavorites, jsonEncode(list.map((e) => e.toJson()).toList()));
    return !existed;
  }

  Future<void> clearFavorites() => _prefs.remove(_kFavorites);

  // ---------- 搜索历史 ----------

  static const _kSearchHistory = 'search_history_v1';

  /// 最近搜过的词，最新的在前，最多 20 条。
  List<String> loadSearchHistory() {
    final raw = _prefs.getStringList(_kSearchHistory);
    if (raw == null) return const [];
    return raw;
  }

  Future<void> addSearchHistory(String query) async {
    final q = query.trim();
    if (q.isEmpty) return;
    final list = loadSearchHistory().toList()
      ..remove(q)
      ..insert(0, q);
    if (list.length > 20) list.removeRange(20, list.length);
    await _prefs.setStringList(_kSearchHistory, list);
  }

  Future<void> clearSearchHistory() => _prefs.remove(_kSearchHistory);

  // ---------- 设置 ----------

  String get preferredQuality => _prefs.getString('${_kSettings}_quality') ?? '720p';

  Future<void> setPreferredQuality(String label) =>
      _prefs.setString('${_kSettings}_quality', label);

  /// 上次手动调的播放音量（0–100）。null 表示用户没调过，交给系统默认。
  double? get volume => _prefs.getDouble('${_kSettings}_volume');

  /// 播放器代理（host:port）。空表示直连。
  ///
  /// 只给 libmpv 用——它不认系统代理，必须显式设置。
  /// 播放器引擎：exo（ExoPlayer，默认）/ mpv（libmpv）。
  ///
  /// ExoPlayer 硬件解码、走系统代理；libmpv 软解万能但要自带 CA。
  /// 在播放页创建时读取——切换对下一个播放生效。
  String get playerEngine =>
      _prefs.getString('${_kSettings}_player_engine') ?? 'exo';

  Future<void> setPlayerEngine(String v) =>
      _prefs.setString('${_kSettings}_player_engine', v);

  String get playerProxy => _prefs.getString('${_kSettings}_player_proxy') ?? '';

  Future<void> setPlayerProxy(String v) => v.isEmpty
      ? _prefs.remove('${_kSettings}_player_proxy')
      : _prefs.setString('${_kSettings}_player_proxy', v);


  Future<void> setVolume(double v) => _prefs.setDouble('${_kSettings}_volume', v);

  // ---------- 屏蔽标签 ----------

  /// 被屏蔽的标签 slug 列表。包含这些标签的视频不再出现。
  /// 返回可变列表——调用方可能要 add/remove。
  List<String> loadBlockedTags() {
    final raw = _prefs.getString('${_kSettings}_blocked_tags');
    if (raw == null || raw.isEmpty) return [];
    try {
      final list = jsonDecode(raw);
      if (list is! List) return [];
      return list.map((e) => '$e').where((e) => e.isNotEmpty).toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> _saveBlockedTags(List<String> tags) =>
      _prefs.setString('${_kSettings}_blocked_tags', jsonEncode(tags));

  Future<void> addBlockedTag(String slug) async {
    final s = slug.trim().toLowerCase().replaceAll(' ', '-');
    if (s.isEmpty) return;
    final list = loadBlockedTags();
    if (list.contains(s)) return;
    list.add(s);
    await _saveBlockedTags(list);
  }

  Future<void> removeBlockedTag(String slug) async {
    final list = loadBlockedTags()..remove(slug);
    await _saveBlockedTags(list);
  }

  bool isTagBlocked(String slug) =>
      loadBlockedTags().contains(slug.toLowerCase());

  /// 值得落盘的音量：小于 10 的一律记 10。
  ///
  /// 用户把音量拖到 0 是「这次想安静」，不该让以后每次打开播放页都无声
  /// （真有过这种反馈：滑到 0 被永久记住，所有视频静音，以为播放器坏了）。
  static double persistableVolume(double v) => v < 10 ? 10 : v;

  /// 标签是否显示中文译名。
  ///
  /// 默认开：站点标签全是英文，中文用户看着累。
  /// 只翻词典里收录的常用词，未收录的显示原文。
  bool get translateTags =>
      _prefs.getBool('${_kSettings}_translate_tags') ?? true;

  Future<void> setTranslateTags(bool v) =>
      _prefs.setBool('${_kSettings}_translate_tags', v);

  /// 进入播放页是否自动横屏全屏。
  ///
  /// 默认 **false**：自动转屏在躺着看、只想听声音时会很烦，
  /// 全屏应该是用户主动点按钮才进。想自动的人可以在设置里打开。
  bool get autoFullscreen =>
      _prefs.getBool('${_kSettings}_auto_fullscreen') ?? false;

  Future<void> setAutoFullscreen(bool v) => _prefs.setBool('${_kSettings}_auto_fullscreen', v);

  /// 玻璃模糊开关。默认开启；关掉后所有玻璃退化成半透明色块，
  /// 省掉最贵的那层 BackdropFilter。
  bool get glassBlur => _prefs.getBool('${_kSettings}_glass_blur') ?? true;

  Future<void> setGlassBlur(bool v) => _prefs.setBool('${_kSettings}_glass_blur', v);

  double get playbackSpeed => _prefs.getDouble('${_kSettings}_speed') ?? 1.0;

  Future<void> setPlaybackSpeed(double v) => _prefs.setDouble('${_kSettings}_speed', v);

  /// 首页默认排序。
  String get defaultSort => _prefs.getString('${_kSettings}_sort') ?? 'post_date';

  Future<void> setDefaultSort(String v) => _prefs.setString('${_kSettings}_sort', v);
}

