import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';

/// 设置页。首版只有播放偏好、历史控制与诊断信息。
class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final quality = ref.watch(preferredQualityProvider);
    final autoFs = ref.watch(autoFullscreenProvider);
    final store = ref.read(libraryStoreProvider);
    final history = ref.watch(historyProvider);
    final favorites = ref.watch(favoritesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        children: [
          const _SectionHeader('播放'),
          ListTile(
            leading: const Icon(Icons.high_quality),
            title: const Text('默认清晰度'),
            subtitle: Text(quality),
            trailing: DropdownButton<String>(
              value: quality,
              underline: const SizedBox.shrink(),
              items: [for (final q in const ['360p', '480p', '720p', '1080p']) DropdownMenuItem(value: q, child: Text(q))],
              onChanged: (v) async {
                if (v == null) return;
                await ref.read(preferredQualityProvider.notifier).set(v);
              },
            ),
          ),
          SwitchListTile(
            secondary: const Icon(Icons.fullscreen),
            title: const Text('打开视频时自动全屏横屏'),
            value: autoFs,
            onChanged: (v) async {
              await ref.read(autoFullscreenProvider.notifier).set(v);
            },
          ),
          const Divider(),
          const _SectionHeader('本地数据'),
          ListTile(
            leading: const Icon(Icons.history),
            title: const Text('观看记录'),
            subtitle: Text('${history.length} 条'),
            trailing: TextButton(
              onPressed: history.isEmpty
                  ? null
                  : () async {
                      final ok = await showDialog<bool>(
                        context: context,
                        builder: (context) => AlertDialog(
                          content: const Text('清空全部观看记录？'),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(context, false),
                              child: const Text('取消'),
                            ),
                            FilledButton(
                              onPressed: () => Navigator.pop(context, true),
                              child: const Text('清空'),
                            ),
                          ],
                        ),
                      );
                      if (ok != true) return;
                      await store.clearHistory();
                      ref.invalidate(historyProvider);
                    },
              child: const Text('清空'),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.bookmark),
            title: const Text('本地收藏'),
            subtitle: Text('${favorites.length} 条'),
          ),
          const Divider(),
          const _SectionHeader('缓存'),
          const _AutoCacheTile(),
          const _CacheUsageTile(),
          const Divider(),
          const _SectionHeader('诊断'),
          const _DiagnosticsTile(),
          const Divider(),
          const Padding(
            padding: EdgeInsets.all(16),
            child: Text(
              '这是一个第三方客户端，与 rule34video.com 无关联。\n'
              '应用不托管任何内容，所有视频均来自原站。\n'
              '仅读取公开页面，不做登录、上传、评论或评分。',
              style: TextStyle(fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }
}

class _DiagnosticsTile extends ConsumerWidget {
  const _DiagnosticsTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final api = ref.watch(rule34ApiProvider);
    return ExpansionTile(
      leading: const Icon(Icons.network_check),
      title: const Text('最近的网络请求'),
      subtitle: const Text('排查「加载不出来」时看这里'),
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: SelectableText(
            api.client.log.isEmpty ? '（还没有请求）' : api.client.log.join('\n'),
            style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
          ),
        ),
      ],
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        text,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: Theme.of(context).colorScheme.primary,
            ),
      ),
    );
  }
}



/// 「自动缓存」开关。
///
/// 默认开启：收藏的视频与看过的长视频自动存到本地，下次秒开。
/// 关掉后只有在播放页手动点「缓存到本地」才下载。
class _AutoCacheTile extends ConsumerWidget {
  const _AutoCacheTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(autoCacheProvider);
    return SwitchListTile(
      secondary: const Icon(Icons.save_alt),
      title: const Text('自动缓存'),
      subtitle: const Text('收藏的视频、看过的长视频自动存到本地，下次不用重新缓冲'),
      value: on,
      onChanged: (v) => ref.read(autoCacheProvider.notifier).set(v),
    );
  }
}

/// 缓存占用与预算。
class _CacheUsageTile extends ConsumerWidget {
  const _CacheUsageTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final index = ref.watch(cacheIndexProvider);
    final totalMb = index.fold<double>(0, (s, e) => s + e.megabytes);
    final budget = ref.read(videoCacheProvider).budgetMb;
    final usedFraction = budget > 0 ? (totalMb / budget).clamp(0.0, 1.0) : 0.0;

    return Column(
      children: [
        ListTile(
          leading: const Icon(Icons.sd_storage_outlined),
          title: const Text('已用缓存'),
          subtitle: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${totalMb.toStringAsFixed(0)} MB / ${(budget / 1024).toStringAsFixed(1)} GB'
                '  ·  ${index.length} 个视频',
              ),
              const SizedBox(height: 6),
              LinearProgressIndicator(value: usedFraction, minHeight: 3),
            ],
          ),
          trailing: TextButton(
            onPressed: index.isEmpty
                ? null
                : () async {
                    final ok = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        content: const Text('清空全部缓存？'),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('取消'),
                          ),
                          FilledButton(
                            onPressed: () => Navigator.pop(context, true),
                            child: const Text('清空'),
                          ),
                        ],
                      ),
                    );
                    if (ok != true) return;
                    await ref.read(videoCacheProvider).clearAll();
                    ref.invalidate(cacheIndexProvider);
                  },
            child: const Text('清空'),
          ),
        ),
        ListTile(
          leading: const Icon(Icons.straighten),
          title: const Text('缓存空间上限'),
          subtitle: const Text('超出后自动删除最旧的，不会撑爆存储'),
          trailing: DropdownButton<int>(
            value: budget,
            underline: const SizedBox.shrink(),
            items: [for (final mb in const [1024, 2048, 4096, 8192, 16384]) DropdownMenuItem(value: mb, child: Text('${mb ~/ 1024} GB'))],
            onChanged: (v) async {
              if (v == null) return;
              await ref.read(videoCacheProvider).setBudgetMb(v);
              await ref.read(videoCacheProvider).enforceBudget();
              ref.invalidate(cacheIndexProvider);
            },
          ),
        ),
      ],
    );
  }
}
