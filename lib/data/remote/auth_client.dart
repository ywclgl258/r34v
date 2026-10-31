
import 'package:dio/dio.dart';

import '../../core/constants.dart';
import 'dio_proxy.dart';
import '../local/auth_store.dart';

/// 登录/登出。
///
/// 站点的登录是传统表单流程（KVS 架构的常见实现）：
///
/// 1. `GET /login/` —— 拿到 `remember_me_csrf_token`（64 位 hex）和 `PHPSESSID`；
/// 2. `POST /login/` —— 表单字段 `username` / `pass` / `remember_me` /
///    `action=login` / `remember_me_csrf_token`；
/// 3. 成功后服务端 Set-Cookie 更新 `PHPSESSID`，凭它访问会员内容。
///
/// **关键约束：token 与会话绑定。** 第 1 步拿到的 token 只能配第 1 步那个
/// `PHPSESSID` 使用。所以登录必须用一个「自己记 cookie」的客户端走完两步，
/// 不能分两次独立请求——分开做会一直报 token 无效。
///
/// 已实测：错误密码时服务端返回
/// `Invalid Username or Password. Username and Password are case-sensitive.`，
/// 说明这条链路没被 DDoS-Guard 拦，可以正常提交。
class AuthClient {
  AuthClient({String? proxy}) {
    _dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 20),
      receiveTimeout: const Duration(seconds: 30),
      followRedirects: true,
      maxRedirects: 5,
      // 4xx 也当正常响应处理，好读服务端的错误文案
      validateStatus: (s) => s != null && s < 500,
      headers: {
        'User-Agent': Site.userAgent,
        'Accept':
            'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
        'Origin': Site.base,
        'Referer': '${Site.base}/login/',
      },
    ));

    // 自己维护 cookie，不依赖额外包：Dio 默认不管 cookie，
    // 而登录流程强依赖 cookie 连续性。
    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) {
        if (_cookies.isNotEmpty) {
          options.headers['Cookie'] = cookieHeader;
        }
        handler.next(options);
      },
      onResponse: (response, handler) {
        final raw = response.headers['set-cookie'];
        if (raw != null) {
          for (final line in raw) {
            _absorb(line);
          }
        }
        handler.next(response);
      },
    ));

    applyProxyToDio(_dio, proxy);
  }

  late final Dio _dio;

  final Map<String, String> _cookies = {};

  String get cookieHeader =>
      _cookies.entries.map((e) => '${e.key}=${e.value}').join('; ');

  void _absorb(String setCookieLine) {
    final first = setCookieLine.split(';').first;
    final eq = first.indexOf('=');
    if (eq <= 0) return;
    final k = first.substring(0, eq).trim();
    final v = first.substring(eq + 1).trim();
    if (k.isEmpty) return;
    _cookies[k] = v;
  }

  /// 登录。成功返回会话，失败抛 [AuthException]。
  Future<AuthSession> login({
    required String username,
    required String password,
    bool remember = true,
  }) async {
    final u = username.trim();
    if (u.isEmpty || password.isEmpty) {
      throw const AuthException('用户名和密码都要填');
    }

    // 第 1 步：取 token（这一步会种下 PHPSESSID）
    final token = await _fetchCsrfToken();
    if (!_cookies.containsKey('PHPSESSID')) {
      throw const AuthException('拿不到会话 cookie，站点可能限制了这个网络');
    }

    // 第 2 步：带同一个 session 提交
    final form = <String, String>{
      'username': u,
      'pass': password,
      'action': 'login',
      'email_link': '${Site.base}/email/',
      'redirect_to': '/',
      'remember_me_csrf_token': token,
      if (remember) 'remember_me': '1',
    };

    late Response<String> res;
    try {
      res = await _dio.post<String>(
        '${Site.base}/login/',
        data: form,
        options: Options(
          contentType: Headers.formUrlEncodedContentType,
          responseType: ResponseType.plain,
        ),
      );
    } on DioException catch (e) {
      throw AuthException(_describeNetworkError(e));
    }

    final body = res.data ?? '';

    final errMsg = _extractError(body);
    if (errMsg != null) throw AuthException(errMsg);

    // 成功判定：表单消失或出现登出入口
    final stillHasForm = body.contains('id="login_username"');
    final hasLogout =
        body.contains('action=logout') || body.contains('href="/logout/"');
    if (stillHasForm && !hasLogout) {
      throw const AuthException('登录没成功，服务端也没给原因（可能账号要邮箱验证）');
    }

    if (!_cookies.containsKey('PHPSESSID')) {
      throw const AuthException('登录后没拿到会话 cookie');
    }

    return AuthSession(
      username: u,
      cookies: Map.of(_cookies),
      savedAt: DateTime.now(),
    );
  }

  Future<String> _fetchCsrfToken() async {
    late Response<String> res;
    try {
      res = await _dio.get<String>(
        '${Site.base}/login/',
        options: Options(responseType: ResponseType.plain),
      );
    } on DioException catch (e) {
      throw AuthException(_describeNetworkError(e));
    }

    if (res.statusCode == 403 || res.statusCode == 503) {
      throw AuthException(
        '站点的人机验证拦住了登录（HTTP ）。换个网络或等几分钟',
      );
    }

    final body = res.data ?? '';
    final m = RegExp(
      r'name="remember_me_csrf_token"\s+value="([^"]+)"',
    ).firstMatch(body);
    if (m == null) {
      if (body.toLowerCase().contains('ddos') ||
          body.contains('Checking your browser')) {
        throw const AuthException('被站点的浏览器检查拦住，稍后再试');
      }
      throw const AuthException('登录页结构变了：找不到 CSRF token');
    }
    return m.group(1)!;
  }

  /// 用该会话访问一个会员专属页面，判断是否还有效。
  Future<bool> validate(AuthSession session) async {
    try {
      final res = await _dio.get<String>(
        '${Site.base}/for-you/',
        options: Options(
          responseType: ResponseType.plain,
          headers: {
            'Cookie': session.cookieHeader,
            'Referer': '${Site.base}/',
          },
        ),
      );
      final body = res.data ?? '';
      if (res.statusCode != 200) return false;
      // 会员页里不该再出现登录表单
      return !body.contains('id="login_username"');
    } catch (_) {
      return false;
    }
  }

  /// 无登录态访问 /for-you/，看站点要求什么。
  Future<bool> probeForYouRequiresLogin() async {
    try {
      final res = await _dio.get<String>(
        '${Site.base}/for-you/',
        options: Options(responseType: ResponseType.plain),
      );
      final body = res.data ?? '';
      return body.contains('members only') || body.contains('id="login_username"');
    } catch (_) {
      return true;
    }
  }

  Future<void> logout(AuthSession session) async {
    try {
      await _dio.post<String>(
        '${Site.base}/logout/',
        options: Options(
          responseType: ResponseType.plain,
          headers: {'Cookie': session.cookieHeader},
        ),
      );
    } catch (_) {
      // 登出失败不影响本地清理
    }
  }

  String? _extractError(String body) {
    // 站点的错误容器实测是 <div class="generic-error">…</div>。
    // 实测还发现：不带 CSRF token 提交也会得到同样的错误信息，
    // 说明服务端目前并不强制校验它。我们仍然带上 token——万一站点以后
    // 启用了校验，带 token 的请求照常工作，不带就会突然失败。
    for (final pattern in [
      r'class="generic-error"[^>]*>([\s\S]{0,200}?)</div>',
      r'class="[^"]*error[^"]*"[^>]*>([\s\S]{0,200}?)<',
      r'class="[^"]*alert[^"]*"[^>]*>([\s\S]{0,200}?)<',
    ]) {
      final m = RegExp(pattern, caseSensitive: false).firstMatch(body);
      if (m != null) {
        final t = m.group(1)!.replaceAll(RegExp(r'<[^>]+>'), '').trim();
        if (t.isNotEmpty && t.length < 200) return t;
      }
    }
    if (body.contains('Invalid Username or Password')) {
      return '用户名或密码不对（区分大小写）';
    }
    return null;
  }

  String _describeNetworkError(DioException e) {
    return switch (e.type) {
      DioExceptionType.connectionTimeout ||
      DioExceptionType.receiveTimeout ||
      DioExceptionType.sendTimeout =>
        '连不上站点（超时）。检查网络或代理',
      DioExceptionType.connectionError => '网络不可达，可能是站点被墙或代理没生效',
      _ => '请求失败：${e.message}',
    };
  }
}

class AuthException implements Exception {
  const AuthException(this.message);

  final String message;

  @override
  String toString() => message;
}
