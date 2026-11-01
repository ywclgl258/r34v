import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// 底部导航壳。
///
/// 底栏按界面原型的「悬浮胶囊」做：圆角 28、半透明毛玻璃、
/// 白色内侧高光描边，浮在内容上方而不是贴边条。
class RootShell extends StatelessWidget {
  const RootShell({super.key, required this.location, required this.child});

  final String location;
  final Widget child;

  static const _tabs = [
    ('/feed/latest', Icons.video_library_outlined, Icons.video_library, '最近'),
    ('/explore', Icons.explore_outlined, Icons.explore, '发现'),
    ('/library', Icons.bookmark_outline, Icons.bookmark, '片库'),
    ('/settings', Icons.settings_outlined, Icons.settings, '设置'),
  ];

  int get _index {
    for (var i = _tabs.length - 1; i >= 0; i--) {
      if (location.startsWith(_tabs[i].$1)) return i;
    }
    return 0;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      body: child,
      bottomNavigationBar: Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 14),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(28),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 22, sigmaY: 22),
            child: Container(
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0x16FFFFFF), Color(0x0AFFFFFF)],
                ),
                border: Border.all(color: const Color(0x1FFFFFFF)),
                borderRadius: BorderRadius.circular(28),
              ),
              child: NavigationBar(
                height: 62,
                backgroundColor: Colors.transparent,
                elevation: 0,
                indicatorColor: scheme.primary.withValues(alpha: 0.16),
                labelTextStyle: WidgetStateProperty.all(
                  const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w500),
                ),
                selectedIndex: _index,
                onDestinationSelected: (i) => context.go(_tabs[i].$1),
                destinations: [
                  for (final t in _tabs)
                    NavigationDestination(
                      icon: Icon(t.$2),
                      selectedIcon: Icon(t.$3),
                      label: t.$4,
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
