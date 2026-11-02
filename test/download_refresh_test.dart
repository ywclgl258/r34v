import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/data/local/video_downloader.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/domain/video_detail.dart';
import 'package:r34v/features/common/video_sections.dart';
import 'package:shared_preferences/shared_preferences.dart';

VideoDetail _detail(String prefix) => VideoDetail(
  id: 'video-1',
  title: '$prefix title',
  duration: '01:00',
  durationSeconds: 60,
  viewsText: '',
  addedText: '',
  parentSlug: 'example',
  authorSlug: '',
  authorName: '',
  avatarUrl: '',
  tags: const [],
  categories: const [],
  models: const [],
  description: '',
  poster: '',
  streams: const [],
  timelineTemplate: '',
  timelineIntervalSeconds: 0,
  related: const [],
  downloads: [
    DownloadOption(
      label: '720p',
      url: 'https://example.com/$prefix.mp4',
      fileName: '$prefix.mp4',
    ),
  ],
);

class _RefreshApi extends Rule34Api {
  _RefreshApi(this.fresh);

  final VideoDetail fresh;
  int refreshCount = 0;

  @override
  Future<VideoDetail> refreshStreams(VideoDetail stale) async {
    refreshCount++;
    return fresh;
  }
}

class _FakeDownloader extends VideoDownloader {
  String? url;
  String? suggestedFileName;

  @override
  Future<String> start({
    required String videoId,
    required String url,
    required String suggestedName,
    void Function(int received, int total)? onProgress,
    void Function(String path)? onDone,
    void Function(String error)? onError,
  }) async {
    this.url = url;
    suggestedFileName = suggestedName;
    onDone?.call('/downloads/fresh.mp4');
    return '/downloads/fresh.mp4';
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('点击下载时刷新链接，并使用最新文件名', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final stale = _detail('stale');
    final api = _RefreshApi(_detail('fresh'));
    final downloader = _FakeDownloader();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          rule34ApiProvider.overrideWithValue(api),
          videoDownloaderProvider.overrideWithValue(downloader),
          sharedPrefsProvider.overrideWithValue(prefs),
        ],
        child: MaterialApp(
          home: Scaffold(body: DownloadSection(video: stale)),
        ),
      ),
    );

    await tester.tap(find.widgetWithText(OutlinedButton, '720p'));
    await tester.pumpAndSettle();

    expect(api.refreshCount, 1);
    expect(downloader.url, 'https://example.com/fresh.mp4');
    expect(downloader.suggestedFileName, 'fresh.mp4');
  });
}
