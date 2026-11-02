import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../core/player_bootstrap.dart';
import '../../data/local/apk_installer.dart';
import '../../data/remote/host_router.dart';
import '../../ui/components.dart';
import '../../ui/glass.dart';
import '../../ui/tokens.dart';
import 'update_controller.dart';

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
      appBar: r34AppBar(large: true, title: const Text('设置')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
          R34Space.page,
          R34Space.x1,
          R34Space.page,
          R34Space.x8,
        ),
        children: [
          _Group(
            title: '显示',
            children: const [_TranslateTagsTile(), _GlassBlurTile()],
          ),
          _Group(
            title: '播放',
            children: [
              const _PlayerEngineTile(),
              ListTile(
                leading: const Icon(Icons.high_quality_rounded, size: 21),
                title: const Text('默认清晰度'),
                subtitle: Text(quality),
                trailing: DropdownButton<String>(
                  value: quality,
                  underline: const SizedBox.shrink(),
                  items: [
                    for (final q in const ['360p', '480p', '720p', '1080p'])
                      DropdownMenuItem(value: q, child: Text(q)),
                  ],
                  onChanged: (v) async {
                    if (v == null) return;
                    await ref.read(preferredQualityProvider.notifier).set(v);
                  },
                ),
              ),
              SwitchListTile(
                secondary: const Icon(Icons.fullscreen_rounded, size: 21),
                title: const Text('打开视频时自动全屏横屏'),
                value: autoFs,
                onChanged: (v) async {
                  await ref.read(autoFullscreenProvider.notifier).set(v);
                },
              ),
            ],
          ),
          _Group(
            title: '本地数据',
            children: [
              ListTile(
                leading: const Icon(Icons.history_rounded, size: 21),
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
                leading: const Icon(Icons.bookmark_rounded, size: 21),
                title: const Text('本地收藏'),
                subtitle: Text('${favorites.length} 条'),
              ),
            ],
          ),
          _Group(
            title: '缓存',
            children: const [_AutoCacheTile(), _CacheUsageTile()],
          ),
          _Group(
            title: '网络',
            children: const [
              _DirectIpTile(),
              _CdnDirectTile(),
              _ProxyTile(),
            ],
          ),
          _Group(
            title: '关于',
            children: const [_UpdateTile()],
          ),
          _Group(
            title: '诊断',
            children: const [_DiagnosticsTile()],
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(
              R34Space.x1,
              R34Space.x8,
              R34Space.x1,
              0,
            ),
            child: Text(
              '这是一个第三方客户端，与 rule34video.com 无关联。\n'
              '应用不托管任何内容，所有视频均来自原站。\n'
              '仅读取公开页面，不做登录、上传、评论或评分。',
              style: const TextStyle(
                fontFamily: 'NotoSansSC',
                fontSize: 11.5,
                height: 1.7,
                color: R34Color.inkFaint,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 设置分组：小标题 + 一张内嵌列表的卡片，项目之间用发丝线分隔。
class _Group extends StatelessWidget {
  const _Group({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader(
          title: title,
          padding: const EdgeInsets.fromLTRB(
            R34Space.x1 + 2,
            R34Space.x5,
            R34Space.x1,
            R34Space.x2 + 2,
          ),
        ),
        SurfaceCard(
          padding: EdgeInsets.zero,
          radius: R34Radius.md,
          child: Column(
            children: [
              for (var i = 0; i < children.length; i++) ...[
                if (i > 0)
                  const Divider(
                    height: 1,
                    indent: R34Space.x4,
                    endIndent: R34Space.x4,
                  ),
                children[i],
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _DiagnosticsTile extends ConsumerWidget {
  const _DiagnosticsTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final api = ref.watch(rule34ApiProvider);
    return ExpansionTile(
      leading: const Icon(Icons.network_check_rounded, size: 21),
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

/// 「检查更新」。
///
/// 数据源是 GitHub Releases：CI 每次构建都会把签名过的 arm64 APK 传上去，
/// 所以这里能拿到和 CI 产物完全一致的包，不需要额外的更新服务器。
///
/// 状态机在 [UpdateController]，这里只负责展示和转发动作。
class _UpdateTile extends ConsumerWidget {
  const _UpdateTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final st = ref.watch(updateControllerProvider);
    final ctrl = ref.read(updateControllerProvider.notifier);
    final cur = st.currentVersion.isEmpty ? '…' : st.currentVersion;

    final (IconData icon, String subtitle, Color? color) = switch (st.phase) {
      UpdatePhase.checking => (
          Icons.sync_rounded,
          '正在检查…',
          R34Color.inkSecondary,
        ),
      UpdatePhase.upToDate => (
          Icons.check_circle_rounded,
          '已是最新版本',
          R34Color.accentBright,
        ),
      UpdatePhase.available => (
          Icons.system_update_rounded,
          '发现 ${st.info?.version}，点右侧下载',
          R34Color.accentBright,
        ),
      UpdatePhase.downloading => (
          Icons.downloading_rounded,
          st.progress < 0
              ? '正在下载…'
              : '正在下载 ${(st.progress * 100).toStringAsFixed(0)}%',
          R34Color.accentBright,
        ),
      UpdatePhase.readyToInstall => (
          Icons.install_mobile_rounded,
          '已下载完成，点右侧安装',
          R34Color.accentBright,
        ),
      UpdatePhase.failed => (
          Icons.error_outline_rounded,
          st.error.isEmpty ? '检查失败' : st.error,
          R34Color.inkSecondary,
        ),
      UpdatePhase.idle => (
          Icons.system_update_rounded,
          '当前 v$cur',
          null,
        ),
    };

    return ListTile(
      leading: Icon(icon, size: 21),
      title: const Text('检查更新'),
      subtitle: st.phase == UpdatePhase.downloading
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(subtitle, style: TextStyle(color: color)),
                const SizedBox(height: 6),
                ClipRRect(
                  borderRadius: BorderRadius.circular(2),
                  child: LinearProgressIndicator(
                    value: st.progress < 0 ? null : st.progress,
                    minHeight: 3,
                  ),
                ),
              ],
            )
          : Text(
              subtitle,
              style: color == null ? null : TextStyle(color: color),
            ),
      trailing: _updateAction(context, st, ctrl),
    );
  }

  Widget _updateAction(
    BuildContext context,
    UpdateState st,
    UpdateController ctrl,
  ) {
    switch (st.phase) {
      case UpdatePhase.checking:
        return const SizedBox(
          width: 20,
          height: 20,
          child: CircularProgressIndicator(strokeWidth: 2),
        );
      case UpdatePhase.downloading:
        return TextButton(
          onPressed: ctrl.cancelDownload,
          child: const Text('取消'),
        );
      case UpdatePhase.readyToInstall:
        return FilledButton(
          onPressed: () async {
            final err = await ctrl.install();
            if (err.isEmpty || !context.mounted) return;
            // 装不了最常见的原因是没开「安装未知应用」，给出直接入口
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text(err),
                action: SnackBarAction(
                  label: '去设置',
                  onPressed: () => ApkInstaller.openUnknownSourcesSettings(),
                ),
              ),
            );
          },
          child: const Text('安装'),
        );
      case UpdatePhase.available:
        return FilledButton(
          onPressed: ctrl.download,
          child: const Text('下载'),
        );
      case UpdatePhase.upToDate:
      case UpdatePhase.failed:
      case UpdatePhase.idle:
        return TextButton(
          onPressed: () => ctrl.check(),
          child: const Text('检查'),
        );
    }
  }
}

/// 「站点直连 IP」开关（默认开）。
///
/// `rule34video.com` 的 DNS 结果随 resolver 变化——实测本地 / 8.8.8.8 /
/// 1.1.1.1 给出三个不同 IP，且都可用。开启后先打内置首选 IP，
/// 失败依次换备用，全失败才退回系统 DNS。
///
/// **价值在容错而非提速**：实测各 IP 差异只有 5% 级别，接近网络抖动。
class _DirectIpTile extends ConsumerWidget {
  const _DirectIpTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(directIpProvider);
    return SwitchListTile(
      secondary: const Icon(Icons.dns_rounded, size: 21),
      title: const Text('站点直连 IP'),
      subtitle: Text(
        on
            ? '内置 ${kBuiltinEndpoints.length} 个 IP，首选失败自动换备用'
            : '走系统 DNS 解析，DNS 被污染时可能连不上',
      ),
      value: on,
      onChanged: (v) => ref.read(directIpProvider.notifier).set(v),
    );
  }
}

/// 「CDN 直链」开关（默认开）。
///
/// 站点对 `/get_file/...` 回 302 跳 CDN，播放器每发一个 Range 请求
/// （首播 / 每次 seek / 每次重连）都要重走一遍。开启后先解析出 CDN 地址，
/// 整个播放会话直接连 CDN。
///
/// 实测（同一文件 20MB）：走 302 是 887 KB/s，直链 3.48 MB/s，差 4 倍。
class _CdnDirectTile extends ConsumerWidget {
  const _CdnDirectTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(cdnDirectProvider);
    return SwitchListTile(
      secondary: const Icon(Icons.bolt_rounded, size: 21),
      title: const Text('CDN 直链'),
      subtitle: Text(
        on
            ? '播放前先解析出 CDN 地址，缓冲更快（实测快 4 倍）'
            : '直接连站点地址，每个请求都走一次 302 跳转',
      ),
      value: on,
      onChanged: (v) async {
        await ref.read(cdnDirectProvider.notifier).set(v);
        if (v) {
          // 重新打开时清掉旧缓存，避免用到关闭期间可能已失效的直链
          ref.read(streamResolverProvider).clear();
        }
      },
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
      secondary: const Icon(Icons.download_for_offline_outlined, size: 21),
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
          leading: const Icon(Icons.storage_rounded, size: 21),
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
          leading: const Icon(Icons.straighten_rounded, size: 21),
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

/// 播放器代理设置。
///
/// 为什么需要单独填：libmpv 用自己的网络栈，既不看 Android 的系统代理，
/// 也不读 HTTP_PROXY 环境变量。只用 VPN（透明路由）时通常不用管它，
/// 但如果是 HTTP 代理，必须在这里填，否则视频流会 Failed to open。
class _ProxyTile extends ConsumerStatefulWidget {
  const _ProxyTile();

  @override
  ConsumerState<_ProxyTile> createState() => _ProxyTileState();
}

class _ProxyTileState extends ConsumerState<_ProxyTile> {
  late final TextEditingController _c = TextEditingController(
    text: ref.read(libraryStoreProvider).playerProxy,
  );

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final v = _c.text.trim();
    await ref.read(libraryStoreProvider).setPlayerProxy(v);
    PlayerBootstrap.setProxy(v);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(v.isEmpty ? '已取消播放器代理' : '已设置播放器代理：$v'),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('播放器代理', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Text(
            '只影响视频流。网页数据走系统代理/VPN，不用填这里。\n'
            '填 host:port，例如 127.0.0.1:7897。留空表示直连。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.outline,
                ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _c,
                  decoration: const InputDecoration(
                    isDense: true,
                    border: OutlineInputBorder(),
                    hintText: '127.0.0.1:7897',
                  ),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton(onPressed: _save, child: const Text('保存')),
            ],
          ),
        ],
      ),
    );
  }
}

/// 标签中文显示开关。
class _TranslateTagsTile extends ConsumerWidget {
  const _TranslateTagsTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(translateTagsProvider);
    return SwitchListTile(
      secondary: const Icon(Icons.translate_rounded, size: 21),
      title: const Text('标签显示中文'),
      subtitle: const Text(
        '常用标签用内置词典（离线、零延迟），其余联网自动翻译并缓存',
      ),
      value: on,
      onChanged: (v) => ref.read(translateTagsProvider.notifier).set(v),
    );
  }
}

/// 玻璃模糊开关。
class _GlassBlurTile extends ConsumerWidget {
  const _GlassBlurTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ValueListenableBuilder<bool>(
      valueListenable: glassBlurEnabled,
      builder: (context, on, _) => SwitchListTile(
        secondary: const Icon(Icons.blur_on_rounded, size: 21),
        title: const Text('毛玻璃模糊'),
        subtitle: const Text('关掉后顶栏和底栏变成半透明色块，低端机滑动更顺'),
        value: on,
        onChanged: (v) async {
          glassBlurEnabled.value = v;
          await ref.read(libraryStoreProvider).setGlassBlur(v);
        },
      ),
    );
  }
}

/// 屏蔽标签管理。
class _BlockedTagsSection extends ConsumerStatefulWidget {
  const _BlockedTagsSection();

  @override
  ConsumerState<_BlockedTagsSection> createState() => _BlockedTagsSectionState();
}

class _BlockedTagsSectionState extends ConsumerState<_BlockedTagsSection> {
  final _input = TextEditingController();

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _add() async {
    final v = _input.text.trim();
    if (v.isEmpty) return;
    await ref.read(blockedTagsProvider.notifier).add(v);
    _input.clear();
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final blocked = ref.watch(blockedTagsProvider);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('屏蔽标签', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Text(
            '包含这些标签的视频不会出现在列表里，详情页也会拦截。\n'
            '列表卡片不带标签数据，按标题关键词匹配；详情层按真实标签拦截。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.outline,
                ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _input,
                  decoration: const InputDecoration(
                    isDense: true,
                    border: OutlineInputBorder(),
                    hintText: '如 scat、guro、loli',
                  ),
                  onSubmitted: (_) => _add(),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton(onPressed: _add, child: const Text('添加')),
            ],
          ),
          if (blocked.isNotEmpty) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final tag in blocked)
                  InputChip(
                    label: Text(tag),
                    onDeleted: () =>
                        ref.read(blockedTagsProvider.notifier).remove(tag),
                    deleteIcon: const Icon(Icons.close, size: 15),
                    deleteButtonTooltipMessage: '取消屏蔽',
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// 播放器引擎切换（对下一个播放生效）。
class _PlayerEngineTile extends ConsumerWidget {
  const _PlayerEngineTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final engine = ref.watch(libraryStoreProvider).playerEngine;
    return ListTile(
      leading: const Icon(Icons.movie_filter_outlined, size: 21),
      title: const Text('播放器引擎'),
      subtitle: const Text(
        'ExoPlayer：硬件解码、代理免配置（默认）\nlibmpv：软解万能，冷门编码兜底',
      ),
      trailing: DropdownButton<String>(
        value: engine,
        underline: const SizedBox.shrink(),
        items: const [
          DropdownMenuItem(value: 'exo', child: Text('ExoPlayer')),
          DropdownMenuItem(value: 'mpv', child: Text('libmpv')),
        ],
        onChanged: (v) async {
          if (v == null) return;
          await ref.read(libraryStoreProvider).setPlayerEngine(v);
          ref.invalidate(libraryStoreProvider);
          if (!context.mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('已切换，对下一个播放生效'),
              duration: Duration(seconds: 1),
            ),
          );
        },
      ),
    );
  }
}

