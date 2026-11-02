import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../ui/tokens.dart';

/// 贴底黑色导航：细顶边、无悬浮卡片、无磨砂和选中胶囊。
class RootShell extends StatelessWidget {
  const RootShell({super.key, required this.location, required this.child});

  final String location;
  final Widget child;

  static const _tabs = [
    ('/feed/latest', Icons.home_outlined, Icons.home_rounded, '最近'),
    ('/explore', Icons.explore_outlined, Icons.explore_rounded, '发现'),
    ('/library', Icons.bookmark_outline_rounded, Icons.bookmark_rounded, '片库'),
    ('/settings', Icons.settings_outlined, Icons.settings_rounded, '设置'),
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
      bottomNavigationBar: DecoratedBox(
        decoration: const BoxDecoration(
          color: R34Color.canvas,
          border: Border(top: BorderSide(color: R34Color.hairlineSoft)),
        ),
        child: NavigationBar(
          height: 60,
          selectedIndex: _index,
          onDestinationSelected: (i) => context.go(_tabs[i].$1),
          destinations: [
            for (final t in _tabs)
              NavigationDestination(
                icon: Icon(t.$2, color: R34Color.inkFaint),
                selectedIcon: Icon(t.$3, color: R34Color.ink),
                label: t.$4,
              ),
          ],
        ),
      ),
    );
  }
}
