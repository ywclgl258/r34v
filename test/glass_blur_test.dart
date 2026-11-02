import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/features/shell/root_shell.dart';
import 'package:r34v/ui/glass.dart';

void main() {
  tearDown(() => glassBlurEnabled.value = true);

  testWidgets('新底栏始终不创建 BackdropFilter', (tester) async {
    glassBlurEnabled.value = true;
    await tester.pumpWidget(
      const MaterialApp(
        home: RootShell(location: '/feed/latest', child: SizedBox()),
      ),
    );
    expect(find.byType(BackdropFilter), findsNothing);

    glassBlurEnabled.value = false;
    await tester.pump();
    expect(find.byType(BackdropFilter), findsNothing);
  });
}
