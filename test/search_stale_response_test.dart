import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/core/constants.dart';
import 'package:r34v/data/remote/rule34_api.dart';
import 'package:r34v/domain/video_card.dart';
import 'package:r34v/features/search/search_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _OutOfOrderApi extends Rule34Api {
  final requests = <String, Completer<FeedPage>>{};

  @override
  Future<FeedPage> fetchFeed(FeedRequest request) {
    return requests.putIfAbsent(request.target, Completer<FeedPage>.new).future;
  }
}

VideoCard _card(String id, String title) => VideoCard(
  id: id,
  url: 'https://example.com/video/$id/example/',
  slug: 'example',
  title: title,
  thumb: '',
  preview: '',
  duration: '01:00',
  durationSeconds: 60,
  viewsText: '1',
  ratingText: '',
  commentsText: '',
  addedText: '',
  isHd: false,
  hasSound: true,
);

FeedPage _page(FeedRequest request, List<VideoCard> cards) => FeedPage(
  request: request,
  cards: cards,
  pager: const Pager(current: 1, total: 1),
  heading: '',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('较慢的旧搜索响应不会覆盖新搜索', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final api = _OutOfOrderApi();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          rule34ApiProvider.overrideWithValue(api),
          sharedPrefsProvider.overrideWithValue(prefs),
        ],
        child: const MaterialApp(home: SearchPage()),
      ),
    );
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'old');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pump();
    await tester.enterText(find.byType(TextField), 'new');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pump();

    final newRequest = api.requests['new']!;
    newRequest.complete(
      _page(
        FeedRequest(
          kind: FeedKind.search,
          target: 'new',
          sort: FeedSort.relevance,
        ),
        [_card('2', 'new result')],
      ),
    );
    await tester.pump();
    expect(find.text('new result'), findsOneWidget);

    api.requests['old']!.complete(
      _page(
        FeedRequest(
          kind: FeedKind.search,
          target: 'old',
          sort: FeedSort.relevance,
        ),
        [_card('1', 'old result')],
      ),
    );
    await tester.pump();

    expect(find.text('new result'), findsOneWidget);
    expect(find.text('old result'), findsNothing);
  });
}
