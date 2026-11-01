import 'package:flutter/material.dart';

import '../../ui/tokens.dart';

/// 视频网格的列数与单元高度。
///
/// 抽成独立文件的理由：列表页、搜索页各写一份比例和间距，已经漂移过一次
/// （列表 `mainAxisSpacing: grid`、搜索 `grid + 2`）。间距/列数这类东西
/// 一旦有两个真源，改一处另一处就变成「看起来不对但说不清哪里不对」。

/// 视频网格的间距。
///
/// 比通用 [R34Space.grid]（10）再收 2dp：两列后卡片本身变宽，
/// 同样 10dp 的缝在视觉上会显得比三列时更松。横向纵向用同一个值，
/// 免得又出现「横向紧、纵向松」这种说不清的不齐。
const double videoGridGap = 8.0;

/// 按屏宽决定每行几个视频。
///
/// 手机固定两列：这是驾驶员的明确要求，也是这类影音应用的常态——
/// 三列时卡宽只有 ~115dp，封面被裁得看不出内容，标题也只能挤两行小字。
/// 两列后卡宽 ~175dp，封面完整、标题可读，一屏仍能看到 4–6 个。
///
/// 更宽的屏幕往上加列，但守住「卡宽不低于 ~155dp」这条底线。
int feedColumnsFor(double width) {
  if (width >= 1400) return 6;
  if (width >= 1100) return 5;
  if (width >= 840) return 4;
  if (width >= 560) return 3;
  return 2;
}

/// 卡片标题的行高（整像素）。
///
/// 为什么向上取整：文字排版的实际行高由底层引擎按整像素分配，
/// `13 × 1.3 = 16.9` 会被落到 17。按小数算出来的预留比真实占用少 0.2，
/// 两行就少 0.4——表现是每张卡底部出现 `A RenderFlex overflowed by 0.4 pixels`。
/// 网格的高度和标题块的 SizedBox 必须用同一个取整结果。
double videoTileLineHeight(BuildContext context, {bool compact = false}) {
  final scaler = MediaQuery.textScalerOf(context);
  final raw = scaler.scale(compact ? 12.0 : 13.0) * (compact ? 1.34 : 1.3);
  return raw.ceilToDouble();
}

/// 卡片文字块高度：标题固定两行 + 一行元信息。
///
/// 网格的 `mainAxisExtent` 和卡片自身排版都用它，两边各写一份数字迟早对不上；
/// 对不上的表现就是卡片底部多一截空白，看起来像「缩略图之间间隙很大」。
///
/// 按 [TextScaler] 算，系统放大字体时预留跟着长，不会挤压标题。
double videoTileTextBlockHeight(BuildContext context, {bool compact = false}) {
  final scaler = MediaQuery.textScalerOf(context);
  final titleLine = videoTileLineHeight(context, compact: compact);
  final metaLine = (scaler.scale(10.5) * 1.2).ceilToDouble();
  // 上内边距 8 + 标题两行 + 标题到元信息 4 + 元信息一行 + 下内边距 9
  return 8 + titleLine * 2 + 4 + metaLine + 9;
}

/// 一张卡在给定宽度下的完整高度：封面 16:9 + 文字块。
double videoTileExtentFor(
  BuildContext context,
  double tileWidth, {
  bool compact = false,
}) {
  final cover = tileWidth * 9 / 16;
  return cover + videoTileTextBlockHeight(context, compact: compact);
}

/// 网格单元高度（按当前屏宽与列数推算）。
double videoGridExtentFor(BuildContext context, double viewportWidth, int cols) {
  final tileWidth =
      (viewportWidth - R34Space.page * 2 - videoGridGap * (cols - 1)) / cols;
  return videoTileExtentFor(context, tileWidth);
}

/// 列表页 / 搜索页共用的网格配置。
///
/// 用 `mainAxisExtent` 而不是 `childAspectRatio`：后者是宽高比，
/// 卡宽一变高度就跟着乱变，而文字块高度其实是常数——用一个比例去凑，
/// 必然有一边浪费：要么封面被拉高（画面被裁），要么卡片底部多一截空白。
/// 给死高度后，卡高恰好等于内容，间隙只剩 [videoGridGap]。
SliverGridDelegate videoGridDelegateFor(BuildContext context) {
  final width = MediaQuery.sizeOf(context).width;
  final cols = feedColumnsFor(width);
  return SliverGridDelegateWithFixedCrossAxisCount(
    crossAxisCount: cols,
    mainAxisExtent: videoGridExtentFor(context, width, cols),
    crossAxisSpacing: videoGridGap,
    mainAxisSpacing: videoGridGap,
  );
}
