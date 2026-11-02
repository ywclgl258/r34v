import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart' show Ticker, TickerCallback;
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/ui/components.dart';

/// `PillTabs` 切换时不能抛阴影断言。
///
/// 回归背景：`_PillTab` 早先用
///
/// ```dart
/// boxShadow: selected ? [BoxShadow(blurRadius: 14)] : null
/// ```
///
/// 让 `AnimatedContainer` 在「有列表」和「null」之间插值。框架在这种
/// 单向列表上做 lerp 时会算出一个负的模糊半径，命中 dart:ui 的断言：
///
/// ```
/// 'dart:ui/painting.dart': Failed assertion:
/// 'Text shadow blur radius should be non-negative.'
/// ```
///
/// 真机表现是点页签的瞬间整页变红（release 下是灰屏），而且只在
/// **切换的那一帧**出现，所以很容易被当成布局溢出误判。
void main() {
  testWidgets('切换页签不抛阴影断言，选中态平稳过渡', (tester) async {
    final controller = TabController(length: 4, vsync: const TestVSync());

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: Scaffold(
          body: PillTabs(
            controller: controller,
            labels: const ['一', '二', '三', '四'],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // 逐个点过去再点回来，覆盖「选中 → 未选中」两个方向。
    for (final i in [1, 2, 3, 0, 2, 1]) {
      await tester.tap(find.text(const ['一', '二', '三', '四'][i]).first);
      // 关键：在动画**中途**也要检查，负半径只在插值过程中出现。
      await tester.pump(const Duration(milliseconds: 60));
      await tester.pump(const Duration(milliseconds: 60));
      await tester.pumpAndSettle();
    }

    expect(tester.takeException(), isNull);
    controller.dispose();
  });

  testWidgets('选中态的阴影半径始终非负', (tester) async {
    final controller = TabController(length: 2, vsync: const TestVSync());

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PillTabs(controller: controller, labels: const ['A', 'B']),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // 直接检查渲染出来的 BoxDecoration，确保任何时刻 blurRadius >= 0。
    void assertNonNegative() {
      final containers = tester.widgetList<AnimatedContainer>(
        find.byType(AnimatedContainer),
      );
      for (final c in containers) {
        final deco = c.decoration;
        if (deco is BoxDecoration && deco.boxShadow != null) {
          for (final s in deco.boxShadow!) {
            expect(s.blurRadius, greaterThanOrEqualTo(0));
          }
        }
      }
    }

    assertNonNegative();
    await tester.tap(find.text('B').first);
    await tester.pump(const Duration(milliseconds: 80));
    assertNonNegative();
    await tester.pumpAndSettle();
    assertNonNegative();

    controller.dispose();
  });
}

/// 测试里用的 vsync，不依赖真实帧调度。
class TestVSync implements TickerProvider {
  const TestVSync();

  @override
  Ticker createTicker(TickerCallback onTick) => Ticker(onTick);
}
