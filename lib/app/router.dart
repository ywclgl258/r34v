import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../core/constants.dart';
import '../domain/video_card.dart';
import '../features/explore/explore_page.dart';
import '../features/feed/feed_page.dart';
import '../features/library/library_page.dart';
import '../features/player/player_page.dart';
import '../features/auth/login_page.dart';
import '../features/search/search_page.dart';
import '../features/settings/settings_page.dart';
import '../features/shell/root_shell.dart';
import 'providers.dart';

/// 路由表。
///
/// 详情页走 `/video/:id/:slug`，因为站点自己的 URL 就是这个形状，
/// 直接把站内路径复用到 App 里，分享/深链将来不用另做映射。
final routerProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/feed/latest',
    observers: [ref.watch(routeObserverProvider)],
    routes: [
      ShellRoute(
        builder: (context, state, child) => RootShell(location: state.uri.path, child: child),
        routes: [
          GoRoute(
            path: '/feed/:kind',
            builder: (context, state) {
              final kind = state.pathParameters['kind'] ?? 'latest';
              final feedKind = _kindFromName(kind);
              return FeedPageTab(kind: feedKind, sortName: state.uri.queryParameters['sort']);
            },
          ),
          GoRoute(
            path: '/explore',
            builder: (context, state) => const ExplorePage(),
          ),
          GoRoute(
            path: '/library',
            builder: (context, state) => const LibraryPage(),
          ),
          GoRoute(
            path: '/settings',
            builder: (context, state) => const SettingsPage(),
          ),
        ],
      ),
      // 详情与播放是**同一页**。
      //
      // 曾经拆成 `/video/:id/:slug`（详情）和 `/play/:id/:slug`（播放）两页，
      // 结果是从列表点进详情、再点播放按钮进播放页，返回要按两次——
      // 而且两页展示的视频信息几乎重复（清晰度、标签、角色、作者、
      // 上传者、下载、评论、简介、相关推荐）。
      //
      // 现在统一进 [PlayerPage]：视频在上、信息面板在下，点播放按钮就地起播，
      // 返回一次就回到列表。保留两个 path 是为了不让已有深链/历史失效。
      GoRoute(
        path: '/video/:id/:slug',
        builder: (context, state) => PlayerPage(
          videoId: state.pathParameters['id'] ?? '',
          slug: state.pathParameters['slug'] ?? '',
        ),
      ),
      GoRoute(
        path: '/play/:id/:slug',
        builder: (context, state) => PlayerPage(
          videoId: state.pathParameters['id'] ?? '',
          slug: state.pathParameters['slug'] ?? '',
        ),
      ),
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginPage(),
      ),
      // 搜索走独立页（有自己的输入框、历史、空态），不套底部导航
      GoRoute(
        path: '/search',
        builder: (context, state) =>
            SearchPage(initialQuery: state.uri.queryParameters['q'] ?? ''),
      ),
      // 列表类页（标签/分类/作者/搜索词）
      GoRoute(
        path: '/list/:kind/:target',
        builder: (context, state) {
          final kind = _kindFromName(state.pathParameters['kind'] ?? 'tag');
          final target = Uri.decodeComponent(state.pathParameters['target'] ?? '');
          // `/list/search/<词>` 也导向独立搜索页，保持行为一致
          if (kind == FeedKind.search) {
            return SearchPage(initialQuery: target);
          }
          return FeedPageTab(
            kind: kind,
            target: target,
            sortName: state.uri.queryParameters['sort'],
          );
        },
      ),
    ],
  );
});

FeedKind _kindFromName(String name) {
  for (final k in FeedKind.values) {
    if (k.name == name) return k;
  }
  return FeedKind.latest;
}

/// 各列表页的排序选项（详情页与列表页共用）。
const sortsForUi = FeedSort.values;

/// 把详情页路径拼出来给 UI 用。
String detailLocation(VideoCard card) => '/video/${card.id}/${card.slug}';

String playerLocation(String id, String slug) => '/play/$id/$slug';


