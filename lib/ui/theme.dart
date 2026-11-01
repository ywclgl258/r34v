import 'package:flutter/material.dart';

import 'tokens.dart';

/// 全局主题。
///
/// 只保留深色一套：这是一个看视频的应用，深色是主场景，
/// 浅色会把封面和播放器的对比压没。
///
/// 结构上按「画布 → 卡片 → 浮层」三级明度，加发丝描边；
/// 强调色只有站点红一种，用在按钮、选中态和进度上。
ThemeData buildAppTheme() {
  const cjk = 'NotoSansSC';

  final scheme = ColorScheme.fromSeed(
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

  final text = TextTheme(
    displaySmall: const TextStyle(
      fontFamily: cjk,
      fontSize: 30,
      height: 1.15,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.6,
      color: R34Color.ink,
    ),
    headlineMedium: const TextStyle(
      fontFamily: cjk,
      fontSize: 24,
      height: 1.2,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.4,
      color: R34Color.ink,
    ),
    titleLarge: const TextStyle(
      fontFamily: cjk,
      fontSize: 20,
      height: 1.25,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.3,
      color: R34Color.ink,
    ),
    titleMedium: const TextStyle(
      fontFamily: cjk,
      fontSize: 17,
      height: 1.3,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.2,
      color: R34Color.ink,
    ),
    titleSmall: const TextStyle(
      fontFamily: cjk,
      fontSize: 15,
      height: 1.35,
      fontWeight: FontWeight.w600,
      color: R34Color.ink,
    ),
    bodyLarge: const TextStyle(
      fontFamily: cjk,
      fontSize: 16,
      height: 1.55,
      fontWeight: FontWeight.w400,
      color: R34Color.ink,
    ),
    bodyMedium: const TextStyle(
      fontFamily: cjk,
      fontSize: 14.5,
      height: 1.5,
      fontWeight: FontWeight.w400,
      color: R34Color.inkSecondary,
    ),
    bodySmall: const TextStyle(
      fontFamily: cjk,
      fontSize: 13,
      height: 1.45,
      fontWeight: FontWeight.w400,
      color: R34Color.inkSecondary,
    ),
    labelLarge: const TextStyle(
      fontFamily: cjk,
      fontSize: 13.5,
      height: 1.3,
      fontWeight: FontWeight.w500,
      color: R34Color.ink,
    ),
    labelMedium: const TextStyle(
      fontFamily: cjk,
      fontSize: 12,
      height: 1.3,
      fontWeight: FontWeight.w500,
      color: R34Color.inkMuted,
    ),
    labelSmall: const TextStyle(
      fontFamily: cjk,
      fontSize: 11,
      height: 1.3,
      fontWeight: FontWeight.w500,
      color: R34Color.inkMuted,
    ),
  );

  const hairlineBorder = BorderSide(color: R34Color.hairline);
  const pill = StadiumBorder();

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: scheme,
    fontFamily: cjk,
    fontFamilyFallback: const [cjk],
    // 画布交给 MaterialApp 底下的环境光去铺，Scaffold 本身透明，
    // 这样玻璃层和半透明才有东西可透。
    scaffoldBackgroundColor: Colors.transparent,
    canvasColor: R34Color.canvas,
    textTheme: text,
    primaryTextTheme: text,
    splashFactory: InkSparkle.splashFactory,
    // 进详情/播放这类二级页用淡入前推，别用 Android 默认的左右整屏推。
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
        fontSize: 20,
        height: 1.25,
        fontWeight: FontWeight.w600,
        letterSpacing: -0.3,
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
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.lg)),
        side: hairlineBorder,
      ),
    ),
    dividerTheme: const DividerThemeData(
      color: R34Color.hairlineSoft,
      thickness: 1,
      space: 1,
    ),
    chipTheme: const ChipThemeData(
      backgroundColor: R34Color.surfaceRaised,
      selectedColor: R34Color.accentSoft,
      disabledColor: R34Color.surface,
      side: hairlineBorder,
      shape: pill,
      showCheckmark: false,
      padding: EdgeInsets.symmetric(horizontal: 10, vertical: 4),
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
        color: R34Color.ink,
      ),
      iconTheme: IconThemeData(color: R34Color.inkMuted, size: 16),
      deleteIconColor: R34Color.inkMuted,
      avatarBoxConstraints: BoxConstraints(minWidth: 20, minHeight: 20),
      deleteIconBoxConstraints: BoxConstraints(minWidth: 20, minHeight: 20),
    ),
    inputDecorationTheme: const InputDecorationTheme(
      filled: true,
      fillColor: R34Color.surface,
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
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        borderSide: BorderSide(color: R34Color.hairline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        borderSide: BorderSide(color: R34Color.hairline),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        borderSide: BorderSide(color: R34Color.accent, width: 1.4),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        borderSide: BorderSide(color: R34Color.danger),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        borderSide: BorderSide(color: R34Color.danger, width: 1.4),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: R34Color.accent,
        foregroundColor: R34Color.onAccent,
        disabledBackgroundColor: R34Color.surfaceHigh,
        disabledForegroundColor: R34Color.inkFaint,
        minimumSize: const Size(0, 46),
        padding: const EdgeInsets.symmetric(horizontal: 22),
        shape: pill,
        textStyle: const TextStyle(
          fontFamily: cjk,
          fontSize: 14.5,
          fontWeight: FontWeight.w600,
        ),
        elevation: 0,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: R34Color.ink,
        minimumSize: const Size(0, 40),
        padding: const EdgeInsets.symmetric(horizontal: 16),
        side: hairlineBorder,
        shape: pill,
        textStyle: const TextStyle(
          fontFamily: cjk,
          fontSize: 13.5,
          fontWeight: FontWeight.w500,
        ),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: R34Color.accent,
        textStyle: const TextStyle(
          fontFamily: cjk,
          fontSize: 13.5,
          fontWeight: FontWeight.w500,
        ),
        shape: pill,
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
        fontSize: 15,
        height: 1.35,
        fontWeight: FontWeight.w500,
        color: R34Color.ink,
      ),
      subtitleTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 12.5,
        height: 1.4,
        color: R34Color.inkMuted,
      ),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? R34Color.onAccent
            : R34Color.inkMuted,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? R34Color.accent
            : R34Color.surfaceHigh,
      ),
      trackOutlineColor: const WidgetStatePropertyAll(R34Color.hairline),
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
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        side: hairlineBorder,
      ),
      insetPadding: EdgeInsets.fromLTRB(16, 0, 16, 16),
    ),
    dialogTheme: const DialogThemeData(
      backgroundColor: R34Color.surfaceRaised,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.xl)),
        side: hairlineBorder,
      ),
      titleTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 17,
        fontWeight: FontWeight.w600,
        color: R34Color.ink,
      ),
      contentTextStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 14.5,
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
        borderRadius: BorderRadius.vertical(top: Radius.circular(R34Radius.xl)),
      ),
      showDragHandle: true,
      dragHandleColor: R34Color.hairline,
    ),
    popupMenuTheme: const PopupMenuThemeData(
      color: R34Color.surfaceRaised,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(R34Radius.md)),
        side: hairlineBorder,
      ),
      textStyle: TextStyle(
        fontFamily: cjk,
        fontSize: 14,
        color: R34Color.ink,
      ),
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(
        color: R34Color.surfaceHigh,
        borderRadius: BorderRadius.circular(R34Radius.sm),
        border: Border.all(color: R34Color.hairline),
      ),
      textStyle: const TextStyle(
        fontFamily: cjk,
        fontSize: 12,
        color: R34Color.ink,
      ),
    ),
    iconTheme: const IconThemeData(color: R34Color.inkSecondary, size: 22),
  );
}
