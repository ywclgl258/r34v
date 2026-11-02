import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

/// 后台传输服务的配置与生命周期。
///
/// ## 解决的问题
///
/// 之前缓存和下载都绑在页面生命周期上：`PlayerPage.dispose()` 里直接
/// `_fetcher.cancel(...)`，退出页面 = 任务立刻中断。用户想要的是
/// 「点开始缓存 → 退出去看别的 / 切到后台 → 它自己下完」。
///
/// ## 做法
///
/// 用一个**前台服务**（Android Foreground Service + 常驻通知）承载传输：
///
/// - 服务活着 → 进程不会被系统回收，Dart 侧的下载 Future 继续跑；
/// - `WAKE_LOCK` → 息屏时不休眠；
/// - 通知栏显示进度 → 用户知道它在干活，也能一键停。
///
/// 注意：这里**不把下载逻辑搬进 isolate**。`flutter_foreground_task` 的
/// 任务回调跑在独立 isolate，要把 Dio、路径、鉴权全搬过去，收益却不大——
/// 我们只需要「进程别被杀」，前台服务就够了。真正的中断源是页面 dispose，
/// 那个在 [TransferSession] 里解耦。
class BackgroundTransfer {
  BackgroundTransfer._();

  /// 通知通道 / id。改名字要让用户看到新的通知分组。
  static const String _channelId = 'r34v_transfer';
  static const String _channelName = '后台传输';

  static bool _initialized = false;

  /// 是否可用。桌面 / Web 上直接返回 false，调用方据此走同步路径，
  /// 不要开服务——`flutter_foreground_task` 在那些平台没有意义。
  static bool get isSupported => !kIsWeb && Platform.isAndroid;

  /// 初始化。App 启动时调一次即可，重复调用无副作用。
  static void init() {
    if (_initialized || !isSupported) return;
    _initialized = true;
    FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: _channelId,
        channelName: _channelName,
        channelDescription: '缓存与下载在后台继续运行',
        // 低优先级：不发光、不响铃，进度类通知不该抢注意力
        channelImportance: NotificationChannelImportance.LOW,
        priority: NotificationPriority.LOW,
        onlyAlertOnce: true,
      ),
      iosNotificationOptions: const IOSNotificationOptions(),
      foregroundTaskOptions: ForegroundTaskOptions(
        // 不需要周期回调：Dart 侧的下载 Future 自己在跑，
        // 这里只要进程活着。
        eventAction: ForegroundTaskEventAction.nothing(),
        autoRunOnBoot: false,
        allowWakeLock: true,
        allowWifiLock: true,
      ),
    );
  }

  /// 开始一个传输任务：拉起前台服务并显示通知。
  ///
  /// [label] 会出现在通知标题里，例如「缓存：Moe Love 2」。
  static Future<void> begin(String label) async {
    if (!isSupported) return;
    init();
    try {
      // 已经跑着就只更新文案，重复 start 会重置通知
      if (await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.updateService(
          notificationTitle: label,
          notificationText: '准备中…',
        );
        return;
      }
      await FlutterForegroundTask.startService(
        serviceId: 3401,
        notificationTitle: label,
        notificationText: '准备中…',
        callback: _onStart,
      );
    } catch (_) {
      // 服务起不来不该阻断下载本身：退回「前台跑」的旧行为，
      // 只是退出页面会被中断。
    }
  }

  /// 更新通知里的进度文案。
  ///
  /// 调用频率要高吗？不要。通知更新是跨进程的，每帧调会卡。
  /// 调用方按 ~1s 节流。
  static Future<void> update(String label, String text) async {
    if (!isSupported) return;
    try {
      if (!await FlutterForegroundTask.isRunningService) return;
      await FlutterForegroundTask.updateService(
        notificationTitle: label,
        notificationText: text,
      );
    } catch (_) {}
  }

  /// 结束任务：停服务、撤通知。
  static Future<void> end() async {
    if (!isSupported) return;
    try {
      if (await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.stopService();
      }
    } catch (_) {}
  }
}

/// 前台服务的入口。**必须顶格函数**（不能是闭包/方法引用），插件要求。
@pragma('vm:entry-point')
void _onStart() {
  FlutterForegroundTask.setTaskHandler(_TransferTaskHandler());
}

/// 本 App 不在服务里做实际工作（下载跑在主 isolate），
/// 所以这里只需要保持存活、响应通知按钮。
class _TransferTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {}
}

/// 一个正在进行的传输任务，用来在**页面销毁后**继续持有取消句柄。
///
/// 关键设计：任务的生命周期不再等于页面的生命周期。
/// 页面 `dispose` 时调用 [detach]，只是解绑 UI 回调，下载继续；
/// 用户显式取消才调 [cancel]。
class TransferSession {
  TransferSession({
    required this.id,
    required this.label,
    required this.cancel,
  });

  final String id;
  final String label;

  /// 真正的取消动作（fetcher.cancel / CancelToken.cancel）。
  final Future<void> Function() cancel;

  bool _detached = false;
  bool _finished = false;

  /// 页面是否已经解绑。解绑后进度回调不再 setState。
  bool get detached => _detached;
  bool get finished => _finished;

  void detach() => _detached = true;

  void markFinished() => _finished = true;

  Future<void> cancelNow() async {
    if (_finished) return;
    _finished = true;
    try {
      await cancel();
    } catch (_) {}
  }
}

/// 全局任务表。页面销毁后任务还留在这里，供「后台任务」入口查看/取消。
///
/// 用进程级单例而不是 Riverpod provider：前台服务需要跨页面、跨路由
/// 拿到同一份状态，且生命周期比任何 ProviderScope 都长。
class TransferRegistry {
  TransferRegistry._();

  static final TransferRegistry instance = TransferRegistry._();

  final Map<String, TransferSession> _sessions = {};
  final _changes = StreamController<void>.broadcast();

  /// 任务变化通知（开始 / 结束 / 解绑）。
  Stream<void> get changes => _changes.stream;

  List<TransferSession> get active =>
      _sessions.values.where((s) => !s.finished).toList(growable: false);

  int get activeCount => active.length;

  void add(TransferSession session) {
    _sessions[session.id] = session;
    _changes.add(null);
  }

  TransferSession? find(String id) => _sessions[id];

  Future<void> finish(String id) async {
    final s = _sessions.remove(id);
    s?.markFinished();
    _changes.add(null);
    if (activeCount == 0) await BackgroundTransfer.end();
  }

  Future<void> cancel(String id) async {
    final s = _sessions.remove(id);
    if (s != null) await s.cancelNow();
    _changes.add(null);
    if (activeCount == 0) await BackgroundTransfer.end();
  }

  /// 取消全部（「设置 → 后台任务 → 全部停止」）。
  Future<void> cancelAll() async {
    final all = List.of(_sessions.values);
    _sessions.clear();
    for (final s in all) {
      await s.cancelNow();
    }
    _changes.add(null);
    await BackgroundTransfer.end();
  }
}
