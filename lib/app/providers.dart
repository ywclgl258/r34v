import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/local/library_store.dart';
import '../data/local/video_downloader.dart';
import '../data/local/auth_store.dart';
import '../domain/video_card.dart';
import '../data/local/video_cache.dart';
import '../data/remote/auth_client.dart';
import '../data/remote/stream_fetcher.dart';
import '../data/remote/tag_translator.dart';
import '../data/remote/rule34_api.dart';

/// 在 main() 里被 override 成真实实例（需要 await 初始化）。
final sharedPrefsProvider = Provider<SharedPreferences>((ref) {
  throw UnimplementedError('sharedPrefsProvider 必须在 main() 里 override');
});

final libraryStoreProvider = Provider<LibraryStore>((ref) {
  return LibraryStore(ref.watch(sharedPrefsProvider));
});

/// 边下边播的流抓取器。全局单例，避免同一视频重复下载。
final streamFetcherProvider = Provider<StreamFetcher>((ref) {
  final proxy = ref.watch(libraryStoreProvider).playerProxy;
  return StreamFetcher(proxy: proxy);
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

/// 标签翻译服务（联网 + 缓存 + 词典兜底）。
final tagTranslatorProvider = Provider<TagTranslator>((ref) {
  return TagTranslator(
    prefs: ref.watch(sharedPrefsProvider),
    proxy: ref.watch(libraryStoreProvider).playerProxy,
  );
});

/// 标签是否显示中文译名。
///
/// 单独一个 Notifier 而不是每次 invalidate(libraryStoreProvider)：
/// 后者会连带重建 API 客户端、缓存器、下载器，改个显示开关不该动这些。
class TranslateTagsNotifier extends Notifier<bool> {
  @override
  bool build() => ref.watch(libraryStoreProvider).translateTags;

  Future<void> set(bool v) async {
    await ref.read(libraryStoreProvider).setTranslateTags(v);
    state = v;
  }
}

final translateTagsProvider =
    NotifierProvider<TranslateTagsNotifier, bool>(TranslateTagsNotifier.new);

/// 视频下载器（落到系统下载目录，和私有缓存分开）。
final videoDownloaderProvider = Provider<VideoDownloader>((ref) {
  final proxy = ref.watch(libraryStoreProvider).playerProxy;
  return VideoDownloader(proxy: proxy);
});

final authStoreProvider = Provider<AuthStore>((ref) {
  return AuthStore(ref.watch(sharedPrefsProvider));
});

/// 登录状态。null 表示未登录。
class AuthNotifier extends Notifier<AuthSession?> {
  @override
  AuthSession? build() => ref.watch(authStoreProvider).load();

  AuthClient _client() {
    final proxy = ref.read(libraryStoreProvider).playerProxy;
    return AuthClient(proxy: proxy);
  }

  /// 登录。失败抛 AuthException，由界面显示。
  Future<void> login(String username, String password) async {
    final s = await _client().login(username: username, password: password);
    await ref.read(authStoreProvider).save(s);
    state = s;
  }

  Future<void> logout() async {
    final s = state;
    if (s != null) await _client().logout(s);
    await ref.read(authStoreProvider).clear();
    state = null;
  }

  /// 检查会话是否还有效；失效就清掉。
  Future<bool> validate() async {
    final s = state;
    if (s == null) return false;
    final ok = await _client().validate(s);
    if (!ok) {
      await ref.read(authStoreProvider).clear();
      state = null;
    }
    return ok;
  }
}

final authProvider =
    NotifierProvider<AuthNotifier, AuthSession?>(AuthNotifier.new);

final rule34ApiProvider = Provider<Rule34Api>((ref) {
  // 登录态变化时重建 API，让它带上新的会话 cookie
  final session = ref.watch(authProvider);
  final api = Rule34Api(cookie: session?.cookieHeader);
  ref.onDispose(api.client.dispose);
  return api;
});

/// 屏蔽的标签列表。增删后 invalidate，列表/搜索会跟着过滤。
class BlockedTagsNotifier extends Notifier<List<String>> {
  @override
  List<String> build() => ref.watch(libraryStoreProvider).loadBlockedTags();

  Future<void> add(String slug) async {
    await ref.read(libraryStoreProvider).addBlockedTag(slug);
    state = ref.read(libraryStoreProvider).loadBlockedTags();
  }

  Future<void> remove(String slug) async {
    await ref.read(libraryStoreProvider).removeBlockedTag(slug);
    state = ref.read(libraryStoreProvider).loadBlockedTags();
  }
}

final blockedTagsProvider =
    NotifierProvider<BlockedTagsNotifier, List<String>>(BlockedTagsNotifier.new);

/// 视频卡片是否通过屏蔽过滤。
///
/// 列表卡片不带 tag 数据（站点列表页没给），只能按标题词匹配：
/// 被屏蔽 slug 拆成单词后，全部出现在标题里才算命中。
/// 详情/播放层另有真实 tag 的精确拦截。
bool cardPassesBlocklist(VideoCard card, List<String> blockedTags) {
  if (blockedTags.isEmpty) return true;
  final title = card.title.toLowerCase();
  for (final slug in blockedTags) {
    final words = slug.split('-').where((w) => w.isNotEmpty);
    if (words.isEmpty) continue;
    if (words.every(title.contains)) return false;
  }
  return true;
}

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
