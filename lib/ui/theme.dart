import 'package:flutter/material.dart';

import 'tokens.dart';

/// 单一深色主题。白色用于主操作，红色只表达品牌、选中与播放进度。
ThemeData buildAppTheme() {
  const cjk = 'NotoSansSC';
  final scheme =
      ColorScheme.fromSeed(
        seedColor: R34Color.accent,
        brightness: Brightness.dark,
      ).copyWith(
        primary: R34Color.accent,
        onPrimary: R34Color.onAccent,
        primaryContainer: R34Color.accentSoft,
        onPrimaryContainer: R34Color.ink,
        secondary: R34Color.inkSecondary,
        onSecondary: R34Color.canvas,
        surface: R34Color.canvas,
        onSurface: R34Color.ink,
        surfaceContainerLowest: R34Color.canvas,
        surfaceContainerLow: R34Color.surface,
        surfaceContainer: R34Color.surface,
        surfaceContainerHigh: R34Color.surfaceRaised,
        surfaceContainerHighest: R34Color.surfaceHigh,
        onSurfaceVariant: R34Color.inkSecondary,
        outline: R34Color.inkMuted,
        outlineVariant: R34Color.hairline,
        error: R34Color.danger,
        onError: Colors.white,
      );

  const text = TextTheme(
    displaySmall: TextStyle(
      fontFamily: cjk,
      fontSize: 30,
      height: 1.15,
      fontWeight: FontWeight.w700,
      letterSpacing: -0.6,
      color: R34Color.ink,
    ),
    headlineMedium: TextStyle(
      fontFamily: cjk,
      fontSize: 27,
      height: 1.2,
      fontWeight: FontWeight.w800,
      letterSpacing: -0.5,
      color: R34Color.ink,
    ),
    titleLarge: TextStyle(
      fontFamily: cjk,
      fontSize: 20,
      height: 1.25,
      fontWeight: FontWeight.w700,
      letterSpacing: -0.3,
      color: R34Color.ink,
    ),
    titleMedium: TextStyle(
      fontFamily: cjk,
      fontSize: 17,
      height: 1.3,
      fontWeight: FontWeight.w700,
      color: R34Color.ink,
    ),
    titleSmall: TextStyle(
      fontFamily: cjk,
      fontSize: 15,
      height: 1.35,
      fontWeight: FontWeight.w600,
      color: R34Color.ink,
    ),
    bodyLarge: TextStyle(
      fontFamily: cjk,
      fontSize: 16,
      height: 1.55,
      color: R34Color.ink,
    ),
    bodyMedium: TextStyle(
      fontFamily: cjk,
      fontSize: 14,
      height: 1.5,
      color: R34Color.inkSecondary,
    ),
    bodySmall: TextStyle(
      fontFamily: cjk,
      fontSize: 12.5,
      height: 1.45,
      color: R34Color.inkSecondary,
    ),
    labelLarge: TextStyle(
      fontFamily: cjk,
      fontSize: 13.5,
      height: 1.3,
      fontWeight: FontWeight.w600,
      color: R34Color.ink,
    ),
    labelMedium: TextStyle(
      fontFamily: cjk,
      fontSize: 12,
      height: 1.3,
      fontWeight: FontWeight.w500,
      color: R34Color.inkMuted,
    ),
    labelSmall: TextStyle(
      fontFamily: cjk,
      fontSize: 10.5,
      height: 1.3,
      fontWeight: FontWeight.w500,
      color: R34Color.inkMuted,
    ),
  );

  const hairline = BorderSide(color: R34Color.hairlineSoft);
  const smallShape = RoundedRectangleBorder(
    borderRadius: BorderRadius.all(Radius.circular(R34Radius.sm)),
  );

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: scheme,
    fontFamily: cjk,
    fontFamilyFallback: const [cjk],
    scaffoldBackgroundColor: R34Color.canvas,
    canvasColor: R34Color.canvas,
    textTheme: text,
    primaryTextTheme: text,
    splashFactory: InkSparkle.splashFactory,
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
        TargetPlatform.windows: FadeForwardsPageTransitionsBuilder(),
      },
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: R34Color.canvas,
      surfaceTintColor: Colors.transparent,
      foregroundColor: R34Color.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleSpacing: R34Space.page,
      titleTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 27,
        height: 1.2,
        fontWeight: FontWeight.w800,
        letterSpacing: -0.5,
        color: R34Color.ink,
      ),
      iconTheme: IconThemeData(color: R34Color.ink, size: 22),
      actionsIconTheme: IconThemeData(color: R34Color.ink, size: 22),
    ),
    cardTheme: const CardThemeData(
      color: R34Color.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      shape: smallShape,
    ),
    dividerTheme: const DividerThemeData(
      color: R34Color.hairlineSoft,
      thickness: 1,
      space: 1,
    ),
    chipTheme: const ChipThemeData(
      backgroundColor: R34Color.surfaceRaised,
      selectedColor: R34Color.ink,
      disabledColor: R34Color.surface,
      side: BorderSide.none,
      shape: smallShape,
      showCheckmark: false,
      padding: EdgeInsets.symmetric(horizontal: 12, vertical: 5),
      labelStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 12.5,
        fontWeight: FontWeight.w500,
        color: R34Color.inkSecondary,
      ),
      secondaryLabelStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 12.5,
        fontWeight: FontWeight.w600,
        color: R34Color.canvas,
      ),
      iconTheme: IconThemeData(color: R34Color.inkMuted, size: 16),
      deleteIconColor: R34Color.inkMuted,
      avatarBoxConstraints: BoxConstraints(minWidth: 20, minHeight: 20),
      deleteIconBoxConstraints: BoxConstraints(minWidth: 20, minHeight: 20),
    ),
    inputDecorationTheme: const InputDecorationTheme(
      filled: true,
      fillColor: R34Color.surfaceRaised,
      isDense: true,
      contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      hintStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 14,
        color: R34Color.inkFaint,
      ),
      labelStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 14,
        color: R34Color.inkMuted,
      ),
      floatingLabelStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 12.5,
        color: R34Color.inkMuted,
      ),
      prefixIconColor: R34Color.inkMuted,
      suffixIconColor: R34Color.inkMuted,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.sm)),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.sm)),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.sm)),
        borderSide: BorderSide(color: R34Color.inkMuted),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.sm)),
        borderSide: BorderSide(color: R34Color.danger),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.sm)),
        borderSide: BorderSide(color: R34Color.danger),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: R34Color.ink,
        foregroundColor: R34Color.canvas,
        disabledBackgroundColor: R34Color.surfaceHigh,
        disabledForegroundColor: R34Color.inkFaint,
        minimumSize: const Size(0, 44),
        padding: const EdgeInsets.symmetric(horizontal: 22),
        shape: smallShape,
        textStyle: const TextStyle(
          fontFamily: cjk,
          fontSize: 14,
          fontWeight: FontWeight.w700,
        ),
        elevation: 0,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: R34Color.ink,
        minimumSize: const Size(0, 40),
        padding: const EdgeInsets.symmetric(horizontal: 16),
        side: hairline,
        shape: smallShape,
        textStyle: const TextStyle(
          fontFamily: cjk,
          fontSize: 13.5,
          fontWeight: FontWeight.w600,
        ),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: R34Color.inkSecondary,
        textStyle: const TextStyle(
          fontFamily: cjk,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
        shape: smallShape,
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        foregroundColor: R34Color.ink,
        highlightColor: R34Color.accentSoft,
      ),
    ),
    listTileTheme: const ListTileThemeData(
      iconColor: R34Color.inkMuted,
      textColor: R34Color.ink,
      contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      minVerticalPadding: 10,
      titleTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 14.5,
        height: 1.35,
        fontWeight: FontWeight.w500,
        color: R34Color.ink,
      ),
      subtitleTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 12,
        height: 1.45,
        color: R34Color.inkMuted,
      ),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (s) =>
            s.contains(WidgetState.selected) ? R34Color.ink : R34Color.inkMuted,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected)
            ? R34Color.accent
            : R34Color.surfaceHigh,
      ),
      trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
    ),
    sliderTheme: const SliderThemeData(
      activeTrackColor: R34Color.accent,
      inactiveTrackColor: R34Color.surfaceHigh,
      thumbColor: R34Color.accent,
      overlayColor: R34Color.accentSoft,
      trackHeight: 3,
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(
      color: R34Color.accent,
      linearTrackColor: R34Color.surfaceHigh,
      circularTrackColor: R34Color.surfaceHigh,
    ),
    snackBarTheme: const SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: R34Color.surfaceRaised,
      contentTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 13.5,
        color: R34Color.ink,
      ),
      elevation: 0,
      shape: smallShape,
      insetPadding: EdgeInsets.fromLTRB(20, 0, 20, 16),
    ),
    dialogTheme: const DialogThemeData(
      backgroundColor: R34Color.surfaceRaised,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.lg)),
      ),
      titleTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 18,
        fontWeight: FontWeight.w700,
        color: R34Color.ink,
      ),
      contentTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 14,
        height: 1.5,
        color: R34Color.inkSecondary,
      ),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      backgroundColor: R34Color.surface,
      modalBackgroundColor: R34Color.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(R34Radius.lg)),
      ),
      showDragHandle: true,
      dragHandleColor: R34Color.hairline,
    ),
    popupMenuTheme: const PopupMenuThemeData(
      color: R34Color.surfaceRaised,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: smallShape,
      textStyle: TextStyle(fontFamily: cjk, fontSize: 14, color: R34Color.ink),
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(
        color: R34Color.surfaceHigh,
        borderRadius: BorderRadius.circular(R34Radius.sm),
      ),
      textStyle: const TextStyle(
        fontFamily: cjk,
        fontSize: 12,
        color: R34Color.ink,
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 60,
      backgroundColor: R34Color.canvas,
      surfaceTintColor: Colors.transparent,
      indicatorColor: Colors.transparent,
      overlayColor: WidgetStatePropertyAll(Colors.transparent),
      iconTheme: WidgetStatePropertyAll(IconThemeData(size: 21)),
      labelTextStyle: WidgetStateProperty.resolveWith(
        (states) => TextStyle(
          fontFamily: cjk,
          fontSize: 10.5,
          height: 1.2,
          fontWeight: FontWeight.w500,
          color: states.contains(WidgetState.selected)
              ? R34Color.ink
              : R34Color.inkFaint,
        ),
      ),
    ),
    iconTheme: const IconThemeData(color: R34Color.inkSecondary, size: 22),
  );
}
