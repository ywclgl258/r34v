import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/local/library_store.dart';
import '../data/local/video_cache.dart';
import '../data/remote/rule34_api.dart';

/// 在 main() 里被 override 成真实实例（需要 await 初始化）。
final sharedPrefsProvider = Provider<SharedPreferences>((ref) {
  throw UnimplementedError('sharedPrefsProvider 必须在 main() 里 override');
});

final libraryStoreProvider = Provider<LibraryStore>((ref) {
  return LibraryStore(ref.watch(sharedPrefsProvider));
});

final videoCacheProvider = Provider<VideoCache>((ref) {
  return VideoCache(ref.watch(sharedPrefsProvider));
});

/// 缓存索引。写操作后 invalidate 这个，片库和详情页会跟着刷新。
final cacheIndexProvider = Provider<List<CachedVideo>>((ref) {
  return ref.watch(videoCacheProvider).loadIndex();
});

/// 某个视频是否已缓存（且文件真的还在）。
final isCachedProvider = Provider.family<bool, String>((ref, videoId) {
  ref.watch(cacheIndexProvider); // 索引变了要重新算
  return ref.watch(videoCacheProvider).isCached(videoId);
});

/// 「自动缓存」开关。
class AutoCacheNotifier extends Notifier<bool> {
  @override
  bool build() => ref.watch(videoCacheProvider).autoCache;

  Future<void> set(bool v) async {
    await ref.read(videoCacheProvider).setAutoCache(v);
    state = v;
  }
}

final autoCacheProvider =
    NotifierProvider<AutoCacheNotifier, bool>(AutoCacheNotifier.new);

final rule34ApiProvider = Provider<Rule34Api>((ref) {
  final api = Rule34Api();
  ref.onDispose(api.client.dispose);
  return api;
});

/// 观看记录。
///
/// Riverpod 3 去掉了 `StateProvider`，这类「可变的简单状态」统一用
/// `NotifierProvider` 写：一个小 Notifier 加几个方法，边界反而更清楚。
class HistoryNotifier extends Notifier<List<WatchEntry>> {
  @override
  List<WatchEntry> build() => ref.watch(libraryStoreProvider).loadHistory();

  /// 从本地重新读一遍（写入后调用）。
  void reload() {
    state = ref.read(libraryStoreProvider).loadHistory();
  }

  Future<void> remove(String videoId) async {
    await ref.read(libraryStoreProvider).removeWatch(videoId);
    reload();
  }

  Future<void> clear() async {
    await ref.read(libraryStoreProvider).clearHistory();
    reload();
  }
}

final historyProvider = NotifierProvider<HistoryNotifier, List<WatchEntry>>(
  HistoryNotifier.new,
);

final resumableProvider = Provider<List<WatchEntry>>((ref) {
  return ref.watch(historyProvider).where((e) => e.isResumable).toList();
});

class FavoritesNotifier extends Notifier<List<FavoriteEntry>> {
  @override
  List<FavoriteEntry> build() => ref.watch(libraryStoreProvider).loadFavorites();

  void reload() {
    state = ref.read(libraryStoreProvider).loadFavorites();
  }

  Future<bool> toggle(FavoriteEntry entry) async {
    final now = await ref.read(libraryStoreProvider).toggleFavorite(entry);
    reload();
    return now;
  }

  /// 某一种类的收藏（视频 / 作者 / 标签）。
  List<FavoriteEntry> of(FavoriteKind kind) =>
      state.where((e) => e.kind == kind).toList();
}

final favoritesProvider = NotifierProvider<FavoritesNotifier, List<FavoriteEntry>>(
  FavoritesNotifier.new,
);

/// 某一类收藏。
final favoritesOfKindProvider =
    Provider.family<List<FavoriteEntry>, FavoriteKind>((ref, kind) {
  return ref.watch(favoritesProvider).where((e) => e.kind == kind).toList();
});

/// 某个视频的观看进度。
final watchProgressProvider = Provider.family<WatchEntry?, String>((ref, videoId) {
  for (final e in ref.watch(historyProvider)) {
    if (e.videoId == videoId) return e;
  }
  return null;
});

/// 是否已收藏某个对象（视频/作者/标签通用）。
final isFavoriteProvider = Provider.family<bool, FavoriteKey>((ref, key) {
  return ref
      .watch(favoritesProvider)
      .any((e) => e.kind == key.kind && e.target == key.target);
});

/// `isFavoriteProvider` 的键。做成值对象，family 才能正确复用。
class FavoriteKey {
  const FavoriteKey(this.kind, this.target);

  final FavoriteKind kind;
  final String target;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is FavoriteKey && other.kind == kind && other.target == target;

  @override
  int get hashCode => Object.hash(kind, target);
}

/// 视频收藏的便捷键。
FavoriteKey videoKey(String id) => FavoriteKey(FavoriteKind.video, id);

/// 搜索历史。
class SearchHistoryNotifier extends Notifier<List<String>> {
  @override
  List<String> build() => ref.watch(libraryStoreProvider).loadSearchHistory();

  Future<void> add(String query) async {
    await ref.read(libraryStoreProvider).addSearchHistory(query);
    state = ref.read(libraryStoreProvider).loadSearchHistory();
  }

  Future<void> clear() async {
    await ref.read(libraryStoreProvider).clearSearchHistory();
    state = const [];
  }
}

final searchHistoryProvider =
    NotifierProvider<SearchHistoryNotifier, List<String>>(SearchHistoryNotifier.new);

/// 设置项：默认清晰度。
class PreferredQualityNotifier extends Notifier<String> {
  @override
  String build() => ref.watch(libraryStoreProvider).preferredQuality;

  Future<void> set(String label) async {
    await ref.read(libraryStoreProvider).setPreferredQuality(label);
    state = label;
  }
}

final preferredQualityProvider =
    NotifierProvider<PreferredQualityNotifier, String>(PreferredQualityNotifier.new);

/// 设置项：打开视频是否自动全屏。
class AutoFullscreenNotifier extends Notifier<bool> {
  @override
  bool build() => ref.watch(libraryStoreProvider).autoFullscreen;

  Future<void> set(bool v) async {
    await ref.read(libraryStoreProvider).setAutoFullscreen(v);
    state = v;
  }
}

final autoFullscreenProvider =
    NotifierProvider<AutoFullscreenNotifier, bool>(AutoFullscreenNotifier.new);
