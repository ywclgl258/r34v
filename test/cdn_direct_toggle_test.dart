import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 「CDN 直链」开关的默认值与持久化。
///
/// 这个开关默认必须是**开**：它决定播放器是直接连 CDN，还是每个 Range
/// 请求都重走一次 302。实测同一文件 20MB：
/// 走 302 是 887 KB/s / 23.6s，直链是 3.48 MB/s / 6.0s。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('默认开启', () async {
    final prefs = await SharedPreferences.getInstance();
    final store = LibraryStore(prefs);
    expect(
      store.cdnDirect,
      isTrue,
      reason: '默认必须开：关掉等于放弃 4 倍缓冲速度',
    );
  });

  test('关掉后能读回 false', () async {
    final prefs = await SharedPreferences.getInstance();
    final store = LibraryStore(prefs);
    await store.setCdnDirect(false);
    expect(store.cdnDirect, isFalse);
  });

  test('重新打开能读回 true', () async {
    final prefs = await SharedPreferences.getInstance();
    final store = LibraryStore(prefs);
    await store.setCdnDirect(false);
    await store.setCdnDirect(true);
    expect(store.cdnDirect, isTrue);
  });

  test('写入的 key 与读取一致（防止改名导致开关失效）', () async {
    final prefs = await SharedPreferences.getInstance();
    final store = LibraryStore(prefs);
    await store.setCdnDirect(false);
    // 直接看底层 key，确保 set/get 用的是同一个
    expect(prefs.getBool('settings_v1_cdn_direct'), isFalse);
  });

  test('与多线程开关互不影响（两个独立设置）', () async {
    final prefs = await SharedPreferences.getInstance();
    final store = LibraryStore(prefs);
    // 多线程默认关，CDN 直链默认开
    expect(store.useChunkedStreaming, isFalse);
    expect(store.cdnDirect, isTrue);

    await store.setCdnDirect(false);
    expect(store.useChunkedStreaming, isFalse, reason: '不该被连带改掉');
    expect(store.cdnDirect, isFalse);
  });
}
