import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/ui/net_image.dart';

/// `ImageDiskCache` 的落盘行为。
///
/// 为什么值得单独测：封面缓存是「二进 App 立刻出图」的前提，而它
/// 出问题时的表现非常隐蔽——不抛异常、日志没有、只是图片又变慢了，
/// 很难靠肉眼发现回归。
void main() {
  late Directory tmp;

  setUp(() async {
    tmp = await Directory.systemTemp.createTemp('r34v_imgcache_');
  });

  tearDown(() async {
    if (await tmp.exists()) await tmp.delete(recursive: true);
  });

  Uint8List bytes(int n, [int fill = 7]) =>
      Uint8List.fromList(List.filled(n, fill));

  test('写入后能读回相同字节', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    await cache.write('https://a.test/1.jpg', bytes(100));

    final got = await cache.read('https://a.test/1.jpg');
    expect(got, isNotNull);
    expect(got!.length, 100);
    expect(got.first, 7);
  });

  test('没写过的 URL 读到 null', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    expect(await cache.read('https://a.test/none.jpg'), isNull);
  });

  test('不同 URL 互不覆盖', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    await cache.write('https://a.test/1.jpg', bytes(10, 1));
    await cache.write('https://a.test/2.jpg', bytes(20, 2));

    expect((await cache.read('https://a.test/1.jpg'))!.first, 1);
    expect((await cache.read('https://a.test/2.jpg'))!.first, 2);
  });

  test('超长 URL 也能落盘（文件名用哈希，不受路径长度限制）', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    // 真实场景里缩略图 URL 会带很长的 acctoken
    final long = 'https://a.test/img.jpg?token=${'x' * 800}';
    await cache.write(long, bytes(50));
    expect(await cache.read(long), isNotNull);
  });

  test('URL 里含特殊字符不会导致路径错误', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    const weird = 'https://a.test/a b/c?d=1&e=2#frag';
    await cache.write(weird, bytes(8));
    expect(await cache.read(weird), isNotNull);
  });

  test('超过 maxBytes 时淘汰最旧的', () async {
    final dir = Directory('${tmp.path}/covers');
    // 上限 1000 字节，写 5 个 300 字节的
    final cache = ImageDiskCache(dir, maxBytes: 1000);

    // 显式拉开 mtime：文件系统的时间戳精度不保证能区分 20ms 的间隔，
    // 靠 sleep 排序会让这个测试时好时坏（实测在 tmpfs 上就会塌缩成同一时刻）。
    // 这里直接设定时间，测的是「按 mtime 淘汰」这个逻辑本身。
    final base = DateTime(2026, 1, 1, 12);
    for (var i = 0; i < 5; i++) {
      final url = 'https://a.test/$i.jpg';
      await cache.write(url, bytes(300, i));
      await cache.fileForUrl(url).setLastModified(base.add(Duration(minutes: i)));
    }

    final beforeTrim = await cache.sizeBytes();
    expect(beforeTrim, 1500);

    await cache.trimIfNeeded();

    final afterTrim = await cache.sizeBytes();
    expect(afterTrim, lessThanOrEqualTo(1000), reason: '应压到上限内');
    expect(afterTrim, lessThan(beforeTrim));
    // 最新写入的必须还在（mtime 最大）
    expect(
      await cache.read('https://a.test/4.jpg'),
      isNotNull,
      reason: '淘汰应删最旧的，而不是最新的',
    );
    // 最旧的应被删掉
    expect(await cache.read('https://a.test/0.jpg'), isNull);
  });

  test('未超上限时不删任何东西', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'), maxBytes: 10000);
    await cache.write('https://a.test/1.jpg', bytes(100));
    await cache.trimIfNeeded();
    expect(await cache.read('https://a.test/1.jpg'), isNotNull);
  });

  test('clear 清空磁盘', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    await cache.write('https://a.test/1.jpg', bytes(10));
    await cache.clear();
    expect(await cache.read('https://a.test/1.jpg'), isNull);
  });

  test('空字节不写入（避免留下 0 字节文件）', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    await cache.write('https://a.test/empty.jpg', Uint8List(0));
    // 写是写了，但读的时候会判空返回 null，不会把空图当有效缓存
    expect(await cache.read('https://a.test/empty.jpg'), isNull);
  });

  test('sizeBytes 统计实际占用', () async {
    final cache = ImageDiskCache(Directory('${tmp.path}/covers'));
    expect(await cache.sizeBytes(), 0);
    await cache.write('https://a.test/1.jpg', bytes(120));
    await cache.write('https://a.test/2.jpg', bytes(80));
    expect(await cache.sizeBytes(), 200);
  });
}
