import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:r34v/app/providers.dart';
import 'package:r34v/data/remote/update_service.dart';
import 'package:r34v/features/settings/update_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _CancelUpdateService extends UpdateService {
  static const info = UpdateInfo(
    version: '2.0.0',
    tag: 'v2.0.0',
    apkUrl: 'https://example.com/update.apk',
    sizeBytes: 100,
    notes: '',
  );

  @override
  Future<String> currentVersion() async => '1.0.0';

  @override
  Future<UpdateInfo?> checkLatest() async => info;

  @override
  Future<String> download(
    UpdateInfo info, {
    void Function(double progress)? onProgress,
    CancelToken? cancelToken,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 20));
    throw DioException(
      type: DioExceptionType.cancel,
      requestOptions: RequestOptions(),
      error: '用户取消',
    );
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('取消更新下载后恢复可下载状态，而不是失败', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final container = ProviderContainer(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        updateServiceProvider.overrideWithValue(_CancelUpdateService()),
      ],
    );
    addTearDown(container.dispose);

    final controller = container.read(updateControllerProvider.notifier);
    await controller.check();
    expect(
      container.read(updateControllerProvider).phase,
      UpdatePhase.available,
    );

    final pending = controller.download();
    controller.cancelDownload();
    await pending;

    final state = container.read(updateControllerProvider);
    expect(state.phase, UpdatePhase.available);
    expect(state.info?.version, '2.0.0');
    expect(state.progress, 0);
    expect(state.error, isEmpty);
  });
}
