import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/data/local/library_store.dart';
import 'package:r34v/data/local/tag_dictionary.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 标签翻译词典与相关默认值。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('标签词典', () {
    test('有一定规模（太少起不到作用）', () {
      expect(tagDictionarySize, greaterThan(100));
    });

    test('常见标签有中文', () {
      expect(zhForTag('big-breasts'), '巨乳');
      expect(zhForTag('anal'), '肛交');
      expect(zhForTag('futanari'), '扶她');
      expect(zhForTag('pov'), '第一人称');
      expect(zhForTag('3d'), '三维');
    });

    test('未收录的返回空字符串（由界面显示原文）', () {
      expect(zhForTag('some-made-up-tag-xyz'), '');
      expect(hasTagTranslation('some-made-up-tag-xyz'), isFalse);
    });

    test('收录判断与查询一致', () {
      expect(hasTagTranslation('anal'), isTrue);
      expect(zhForTag('anal').isNotEmpty, hasTagTranslation('anal'));
    });

    test('译文里不残留连字符（说明不是原样返回）', () {
      for (final entry in kTagZh.entries) {
        expect(
          entry.value.contains('-'),
          isFalse,
          reason: '${entry.key} 的译文像是 slug 原文：${entry.value}',
        );
      }
    });

    test('译文都非空', () {
      for (final entry in kTagZh.entries) {
        expect(entry.value.trim(), isNotEmpty, reason: entry.key);
      }
    });

    test('键都是小写连字符形式（与站点 slug 一致）', () {
      for (final key in kTagZh.keys) {
        expect(key, key.toLowerCase(), reason: key);
        expect(key.contains(' '), isFalse, reason: key);
        expect(key.contains('_'), isFalse, reason: key);
      }
    });
  });

  group('默认设置', () {
    late SharedPreferences prefs;
    late LibraryStore store;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      prefs = await SharedPreferences.getInstance();
      store = LibraryStore(prefs);
    });

    test('标签翻译默认开启', () {
      expect(store.translateTags, isTrue);
    });

    test('可以关掉', () async {
      await store.setTranslateTags(false);
      expect(store.translateTags, isFalse);
    });

    test('自动全屏默认关闭（全屏要用户主动点）', () {
      expect(
        store.autoFullscreen,
        isFalse,
        reason: '进播放页就自动转横屏会打断「只想听声音」的场景',
      );
    });

    test('自动全屏可以手动打开', () async {
      await store.setAutoFullscreen(true);
      expect(store.autoFullscreen, isTrue);
    });
  });
}
