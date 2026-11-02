import '../../core/constants.dart';
import '../../domain/feed_link.dart';
import '../../domain/video_card.dart';
import '../../domain/video_detail.dart';
import '../parsing/detail_parser.dart';
import '../../domain/ranked_entry.dart';
import '../parsing/listing_parser.dart';
import '../parsing/ranking_parser.dart';
import '../parsing/parse_exception.dart';
import 'site_client.dart';

/// 站点数据入口：抓 HTML + 解析，出领域模型。
///
/// 这一层是 App 里唯一知道「站点长什么样」的地方。UI 只和它打交道，
/// 站点改版时只需要改解析器和这里，界面不用动。
class Rule34Api {
  Rule34Api({
    SiteClient? client,
    ListingParser? listingParser,
    DetailParser? detailParser,
    this.cookie,
  })  : _client = client ?? createSiteClient(),
        _listing = listingParser ?? const ListingParser(),
        _detail = detailParser ?? const DetailParser();

  final SiteClient _client;

  /// 登录后的会话 cookie。只有会员专属页面（for-you）需要它，
  /// 公开页面不带也没关系（带了反而少一层匿名缓存）。
  final String? cookie;
  final ListingParser _listing;
  final DetailParser _detail;

  SiteClient get client => _client;

  /// 抓一页列表。
  ///
  /// 会先跑结构哨兵：如果页面明显是列表页但一张卡都解析不出来，抛
  /// [ParseException] 而不是返回空列表——空列表会让 UI 说「这里没有视频」，
  /// 而实际是站点改版了，那种沉默的失败最难查。
  /// 只有会员专属页面才带 cookie。
  Map<String, String>? _headersFor(FeedRequest request) {
    if (cookie == null || cookie!.isEmpty) return null;
    if (request.kind != FeedKind.forYou) return null;
    return {'Cookie': cookie!};
  }

  Future<FeedPage> fetchFeed(FeedRequest request) async {
    final res = await _client.getHtml(
      request.url,
      headers: _headersFor(request),
    );
    if (!res.ok) {
      throw HttpException('列表页返回 ${res.status}', status: res.status, url: res.url);
    }
    if (res.looksLikeChallenge) {
      throw HttpException('站点要求人机验证', status: res.status, url: res.url, challenge: true);
    }
    _listing.assertStructure(res.body, request);
    return _listing.parse(res.body, request);
  }

  /// 抓详情页。
  ///
  /// [pathOrUrl] 接受完整 URL、`/video/<id>/<slug>/` 路径。
  /// **裸 `/video/<id>/` 会 404**（实测），所以调用方必须带上 slug；
  /// 若拿到 404，会给出明确提示。
  Future<VideoDetail> fetchDetail(String pathOrUrl) async {
    final url = pathOrUrl.startsWith('http') ? pathOrUrl : '${Site.base}$pathOrUrl';
    final res = await _client.getHtml(url, referer: Site.base);
    if (res.status == 404) {
      throw HttpException('视频页 404 —— 详情页 URL 必须带 slug（/video/<id>/<slug>/）', status: 404, url: url);
    }
    if (!res.ok) {
      throw HttpException('详情页返回 ${res.status}', status: res.status, url: res.url);
    }
    if (res.looksLikeChallenge) {
      throw HttpException('站点要求人机验证', status: res.status, url: res.url, challenge: true);
    }

    final detail = _detail.parse(res.body);
    if (detail == null) {
      throw ParseException(
        '详情页解析失败：找不到播放器配置（flashvars）',
        hint: '站点可能改了播放器；也可能是被重定向到了登录页或错误页',
        snippet: res.body.length > 400 ? res.body.substring(0, 400) : res.body,
      );
    }
    _detail.assertPlayable(detail);
    return detail;
  }

  /// 直链过期后重新换链。
  ///
  /// 站点的 `v-acctoken` 有有效期（实测未定，可能几小时）。播放中途 403 时，
  /// 重新抓一遍详情页就能拿到新 token。返回**同一清晰度**的新直链；
  /// 该档位已不存在（极少见）时返回默认档。
  Future<VideoDetail> refreshStreams(VideoDetail stale) async {
    final fresh = await fetchDetail(stale.path);
    return fresh;
  }

  /// 拉分类索引。
  final RankingParser _ranking = const RankingParser();

  /// 角色排行榜的作品分类（/top-characters/）。
  Future<List<CharacterWork>> fetchCharacterWorks() async {
    final res = await _client.getHtml('${Site.base}/top-characters/');
    if (!res.ok) {
      throw HttpException('角色榜返回 ${res.status}', status: res.status, url: res.url);
    }
    return _ranking.parseWorks(res.body);
  }

  /// 某个作品下的角色排行（/top-characters/{work}/）。
  Future<List<RankedEntry>> fetchCharacterRanking(String work) async {
    final res = await _client.getHtml(
      '${Site.base}/top-characters/${Uri.encodeComponent(work)}/',
    );
    if (!res.ok) {
      throw HttpException('角色榜返回 ${res.status}', status: res.status, url: res.url);
    }
    return _ranking.parse(res.body);
  }

  /// 作者排行榜（/top-model/）。
  ///
  /// 必须带 ?sort=az：不带参数时站点渲染不出作者（实测 0 条），
  /// 带了才有 20 条。这是站点的怪癖，不是我们的解析问题。
  Future<List<RankedEntry>> fetchArtistRanking() async {
    final res = await _client.getHtml('${Site.base}/top-model/?sort=az');
    if (!res.ok) {
      throw HttpException('作者榜返回 ${res.status}', status: res.status, url: res.url);
    }
    return _ranking.parse(res.body);
  }

  Future<List<FeedLink>> fetchCategories() => _fetchLinkIndex('/categories/', 'categories');

  /// 作者索引（/models/）。站点内部把作者叫 model。
  Future<List<FeedLink>> fetchModels() =>
      _fetchLinkIndex('/models/', 'models');

  /// 拉标签索引首页。
  Future<List<FeedLink>> fetchTags() => _fetchLinkIndex('/tags/', 'tags');

  Future<List<FeedLink>> _fetchLinkIndex(String path, String segment) async {
    final res = await _client.getHtml('${Site.base}$path');
    if (!res.ok) {
      throw HttpException('索引页返回 ${res.status}', status: res.status, url: res.url);
    }
    final parser = const ListingParser();
    return parser.extractLinks(res.body, segment: segment);
  }

  /// 搜索建议：站点没有公开的 suggest 接口（已探测：`/search-suggest/` 404、
  /// `get_block&block_id=search_suggest` 返回 0 字节），所以本地不做联想。
  /// 这里保留空实现，等以后有本地标签索引再填。
  Future<List<String>> suggest(String query) async => const [];
}



