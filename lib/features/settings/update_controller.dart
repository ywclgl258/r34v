import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/apk_installer.dart';
import '../../data/remote/update_service.dart';
import '../../app/providers.dart';

/// 版本检查 / 下载 / 安装的状态机。
///
/// 把「检查 → 下载 → 安装」三个阶段收在一个 Notifier 里，是因为它们共享
/// 同一份产物（下载好的 APK 路径），拆开会到处传路径、还要自己保证不重复下载。
enum UpdatePhase {
  /// 还没查过
  idle,

  /// 正在查 GitHub Releases
  checking,

  /// 查完了，且确认没有新版本
  upToDate,

  /// 有新版本可下
  available,

  /// 正在下载 APK
  downloading,

  /// 下载完了，等用户点安装
  readyToInstall,

  /// 下载或检查失败
  failed,
}

class UpdateState {
  const UpdateState({
    this.phase = UpdatePhase.idle,
    this.info,
    this.progress = 0,
    this.error = '',
    this.localPath = '',
    this.currentVersion = '',
  });

  final UpdatePhase phase;
  final UpdateInfo? info;

  /// 0..1；-1 表示总量未知。
  final double progress;
  final String error;

  /// 下载好的 APK 路径（readyToInstall 时有值）。
  final String localPath;

  /// 当前安装包的版本号。
  final String currentVersion;

  bool get hasUpdate =>
      phase == UpdatePhase.available ||
      phase == UpdatePhase.downloading ||
      phase == UpdatePhase.readyToInstall;

  UpdateState copyWith({
    UpdatePhase? phase,
    UpdateInfo? info,
    double? progress,
    String? error,
    String? localPath,
    String? currentVersion,
  }) => UpdateState(
    phase: phase ?? this.phase,
    info: info ?? this.info,
    progress: progress ?? this.progress,
    error: error ?? this.error,
    localPath: localPath ?? this.localPath,
    currentVersion: currentVersion ?? this.currentVersion,
  );
}

class UpdateController extends Notifier<UpdateState> {
  CancelToken? _cancelToken;

  @override
  UpdateState build() {
    // 进设置页时顺便把当前版本读出来，不用等用户点检查
    unawaited(_loadCurrentVersion());
    return const UpdateState();
  }

  Future<void> _loadCurrentVersion() async {
    final v = await ref.read(updateServiceProvider).currentVersion();
    if (v.isEmpty) return;
    state = state.copyWith(currentVersion: v);
  }

  /// 检查更新。[silent] 为 true 时（启动自动检查）失败不打扰用户。
  Future<void> check({bool silent = false}) async {
    if (state.phase == UpdatePhase.checking ||
        state.phase == UpdatePhase.downloading) {
      return;
    }
    state = state.copyWith(phase: UpdatePhase.checking, error: '');

    final svc = ref.read(updateServiceProvider);
    final current = state.currentVersion.isNotEmpty
        ? state.currentVersion
        : await svc.currentVersion();
    final UpdateInfo? latest;
    try {
      latest = await svc.checkLatest();
    } on UpdateException catch (e) {
      state = state.copyWith(
        phase: silent ? UpdatePhase.idle : UpdatePhase.failed,
        currentVersion: current,
        error: silent ? '' : e.userMessage,
      );
      return;
    }
    unawaited(
      ref.read(libraryStoreProvider).setLastUpdateCheckAt(DateTime.now()),
    );

    if (latest == null) {
      // 接口通了，但没有任何可用 release。这和"失败"是两件事。
      state = state.copyWith(
        phase: silent ? UpdatePhase.idle : UpdatePhase.failed,
        currentVersion: current,
        error: silent ? '' : '暂时没有可用版本',
      );
      return;
    }

    final newer = UpdateService.isNewer(latest.version, current);
    state = state.copyWith(
      phase: newer ? UpdatePhase.available : UpdatePhase.upToDate,
      info: newer ? latest : null,
      currentVersion: current,
      error: '',
    );
  }

  /// 下载已发现的新版本。
  Future<void> download() async {
    final info = state.info;
    if (info == null) return;
    if (state.phase == UpdatePhase.downloading) return;

    _cancelToken = CancelToken();
    state = state.copyWith(
      phase: UpdatePhase.downloading,
      progress: 0,
      error: '',
    );

    final String path;
    try {
      path = await ref
          .read(updateServiceProvider)
          .download(
            info,
            cancelToken: _cancelToken,
            onProgress: (v) {
              // 下载回调很密，但这里只改一个 double，Riverpod 自己会合并帧
              state = state.copyWith(progress: v);
            },
          );
    } on DioException catch (e) {
      if (CancelToken.isCancel(e)) {
        // cancelDownload 已恢复 available；取消是用户动作，不是失败。
        state = state.copyWith(
          phase: UpdatePhase.available,
          progress: 0,
          error: '',
        );
      } else {
        state = state.copyWith(
          phase: UpdatePhase.failed,
          error: '下载失败：${e.message}',
        );
      }
      return;
    } on UpdateException catch (e) {
      state = state.copyWith(phase: UpdatePhase.failed, error: e.userMessage);
      return;
    } catch (e) {
      state = state.copyWith(phase: UpdatePhase.failed, error: '下载失败：$e');
      return;
    }

    state = state.copyWith(
      phase: UpdatePhase.readyToInstall,
      localPath: path,
      progress: 1,
    );
  }

  /// 调起系统安装器。
  ///
  /// 返回失败原因（成功返回空串），调用方决定怎么提示。
  Future<String> install() async {
    final path = state.localPath;
    if (path.isEmpty) return '还没有下载好的安装包';
    final r = await ApkInstaller.install(path);
    return r.ok ? '' : r.reason;
  }

  void cancelDownload() {
    _cancelToken?.cancel();
    _cancelToken = null;
    state = state.copyWith(phase: UpdatePhase.available, progress: 0);
  }
}

final updateServiceProvider = Provider<UpdateService>((ref) => UpdateService());

final updateControllerProvider =
    NotifierProvider<UpdateController, UpdateState>(UpdateController.new);
