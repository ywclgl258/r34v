/// 全局常量：站点地址、伪装 UA、路径模板。
///
/// 站点是纯 HTML 的 KVS 系站点，没有 JSON API（已实测：
/// `?mode=async&get_block` 返回 0 字节、`/api/json/` 404），所以所有数据都要解析 DOM。
library;

class Site {
  const Site._();

  /// 站点根。App 内所有请求都以此拼接。
  ///
  /// 可用 `--dart-define=R34V_API_BASE=...` 覆盖，**只用于本地 Web 预览**：
  /// 浏览器有同源策略，直接 fetch 站点会被拦；此时把请求指向
  /// `tool/preview_proxy.py` 起的本地转发。安卓包保持默认值，不走代理。
  static const String base = String.fromEnvironment(
    'R34V_API_BASE',
    defaultValue: 'https://rule34video.com',
  );

  /// 是否正在走本地预览代理（用于 UI 上给一句提示）。
  static bool get usingPreviewProxy => base != 'https://rule34video.com';

  /// 站点主机名（不含协议与路径）。
  ///
  /// 给 libmpv 的 `connect_to=` 参数用：那边要的是 `域名:端口` 形式。
  static const String hostForMpv = 'rule34video.com';

  /// 把站点绝对 URL 改写成本地代理地址（仅预览模式生效）。
  ///
  /// 解析器从 HTML 里拿到的是 `https://rule34video.com/...` 这样的绝对地址。
  /// Web 预览时若直接用，浏览器会直连站点，图片与视频会被同源策略拦掉，
  /// 表现为封面破图、视频播不了。这里统一改写，安卓包下是恒等操作。
  static String rewrite(String url) {
    if (!usingPreviewProxy) return url;
    const official = 'https://rule34video.com';
    if (url.startsWith(official)) return '$base${url.substring(official.length)}';
    return url;
  }

  /// 伪装成 Android Chrome。实测站点对 Dart 默认 UA 也放行，
  /// 但移动 UA 拿到的 DOM 与桌面版结构一致、体积更小（213KB vs 227KB），
  /// 且以后若要做「移动版更省流」的适配也留了余地。
  static const String userAgent =
      'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36';

  /// 列表页每页大约 23–24 条（实测），用于估算总条数。
  static const int approxPageSize = 24;
}

/// 列表页的排序方式。站点用 `?sort_by=` 查询参数，所有列表页通用。
enum FeedSort {
  relevance('', '相关'),
  newest('post_date', '最新'),
  mostViewed('video_viewed', '最多播放'),
  topRated('rating', '最高评分'),
  longest('duration', '最长'),
  random('pseudo_rand', '随机');

  const FeedSort(this.param, this.label);

  final String param;
  final String label;
}

/// 列表页的种类。每种对应一套路径规则（已逐一实测 200）。
enum FeedKind {
  latest('最新更新'),
  topRated('高分榜'),
  mostPopular('热门榜'),
  forYou('为你推荐'),
  topCharacters('角色排行'),
  topArtists('作者排行'),
  search('搜索'),
  tag('标签'),
  character('角色'),
  category('分类'),
  model('作者');

  const FeedKind(this.label);

  final String label;
}

/// 一条列表请求：种类 + 目标（搜索词/标签 slug）+ 排序 + 页码。
///
/// 页码从 1 开始；站点第 1 页不带 `/1/` 后缀。
class FeedRequest {
  const FeedRequest({
    required this.kind,
    this.target = '',
    this.sort = FeedSort.newest,
    this.page = 1,
  });

  final FeedKind kind;

  /// 搜索词、标签 slug、分类 slug 或作者 slug；[FeedKind.latest] 等榜单忽略它。
  final String target;
  final FeedSort sort;
  final int page;

  FeedRequest copyWith({FeedSort? sort, int? page}) => FeedRequest(
        kind: kind,
        target: target,
        sort: sort ?? this.sort,
        page: page ?? this.page,
      );

  /// 生成路径（不含域名与查询串）。
  ///
  /// 目标 slug 统一做 URL 编码：站点的 slug 虽然通常是 `big-breasts` 这种
  /// 安全形式，但也存在带空格/特殊字符的（如搜索词），不编码会拼出坏 URL。
  String get path {
    final p = page <= 1 ? '' : '$page/';
    final t = Uri.encodeComponent(target);
    switch (kind) {
      case FeedKind.latest:
        return '/latest-updates/$p';
      case FeedKind.topRated:
        return '/top-rated/$p';
      case FeedKind.mostPopular:
        return '/most-popular/$p';
      // for-you 没有分页，站点按成员画像一次给一批
      case FeedKind.forYou:
        return '/for-you/';
      case FeedKind.topCharacters:
        return '/top-characters/';
      case FeedKind.topArtists:
        return '/top-model/';
      case FeedKind.search:
        return '/search/$t/$p';
      case FeedKind.tag:
        return '/tags/$t/$p';
      case FeedKind.character:
        // 角色在站内就是 tag。实测 `/characters/` 和 `/characters/<slug>/`
        // 都是 404，排行榜里角色的链接也指向 `/tags/<slug>/`。
        // 所以这里必须落到 tags 路径，不能自己造一个 /characters/。
        return '/tags/$t/$p';
      case FeedKind.category:
        return '/categories/$t/$p';
      case FeedKind.model:
        return '/models/$t/$p';
    }
  }

  /// 完整 URL。默认排序（最新）不传 `sort_by`，让站点走自己的默认值——
  /// 少一个参数、少一份缓存区分，也让「第 1 页」和「第 n 页」的 URL 形态一致。
  String get url {
    // 非搜索列表的站点默认排序就是 post_date，省参数；
    // 搜索的默认排序是相关性，所以搜索里选「最新」必须显式传 post_date，
    // 否则「最新」芯片显示的其实是相关性的结果，芯片在撒谎。
    final emit = sort.param.isNotEmpty &&
        !(sort == FeedSort.newest && kind != FeedKind.search);
    final q = emit ? '?sort_by=${sort.param}' : '';
    return '${Site.base}$path$q';
  }

  /// 用于在 UI 上区分「同一个列表的不同排序」，也用作缓存键。
  String get key => '${kind.name}:$target:${sort.param}';

  /// 值相等。
  ///
  /// **必须实现**：这个对象是 Riverpod `family` 的键。如果按引用比较，
  /// 页面每次 build 都会生成新实例 → 每次都是新 provider → 状态被重置成
  /// loading → 列表永远转圈。这不是理论问题，是写这版时真踩到的坑。
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is FeedRequest &&
          other.kind == kind &&
          other.target == target &&
          other.sort == sort &&
          other.page == page;

  @override
  int get hashCode => Object.hash(kind, target, sort, page);

  @override
  String toString() => '$key:p$page';
}
