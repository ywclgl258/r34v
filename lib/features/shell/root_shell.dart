import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// 底部导航壳。
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
    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
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
    );
  }
}
