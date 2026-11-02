import 'package:flutter/widgets.dart';

/// 播放器引擎抽象。播放页只依赖这个接口。
///
/// 为什么抽象：安卓上有两套成熟方案，各有一摊坑——
///
/// - **ExoPlayer**（默认）：硬件解码省电，走系统网络栈（代理/VPN 天然
///   生效），Google 维护。Han1mePlus 安卓端默认就是它。
/// - **libmpv**（兜底）：软解万能，冷门编码也能播；但要用自带 CA 证书、
///   手动配代理，费电发热。
///
/// 做成接口 + 设置切换，哪个出问题都有退路。引擎选择对**下一个**播放生效。
abstract class PlayerBackend {
  /// 一次性配置（libmpv 要设 CA/UA；ExoPlayer 无需配置）。
  Future<void> configure();

  /// 视频画面 widget，填满父组件并做信箱式适配。
  Widget videoWidget();

  /// 打开一个远端地址。网络类失败请抛异常或推 [errors]，
  /// 调用方靠 [duration]/[position] 流判断起播成功。
  ///
  /// [referer] 是**视频详情页地址**（不是站点根）。站点的反盗链白名单
  /// 认这个值——实测外站 Referer 直接 403，而视频页 / 站点根都能过。
  /// 用视频页是为了和浏览器发出的请求完全一致。
  Future<void> open(String url, {required bool play, String? referer});

  Future<void> pause();
  Future<void> resume();
  Future<void> seek(Duration position);
  Future<void> setRate(double rate);

  /// 0..100
  Future<void> setVolume(double volume);

  Future<void> dispose();

  // ---- 状态流：初始化前也可订阅，不能换成一次性的空流。 ----
  Stream<Duration> get position;
  Stream<Duration> get duration;
  Stream<double> get volume;
  Stream<String> get errors;
  Stream<bool> get completed;
  Stream<bool> get playing;

  // ---- 即时状态 ----
  Duration get currentPosition;
  Duration get currentDuration;
  bool get isPlaying;
  double get currentVolume;

  /// 视频宽高比（宽 / 高）。未知时返回 null。
  ///
  /// 全屏时靠它判断该转横屏还是竖屏：竖屏视频（宽高比 < 1）强行横屏会
  /// 左右留两条大黑边，实际画面只占中间一小条，非常难受。
  ///
  /// 在拿到元数据之前返回 null——调用方据此先不做朝向决定。
  double? get aspectRatio;
}
