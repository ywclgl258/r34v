import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// 登录凭证。
///
/// 站点用 `PHPSESSID` 维持会话，登录成功后服务端下发这个 cookie。
/// 我们不需要存用户名密码——只存会话 cookie，这样即使数据库被看到，
/// 也拿不到明文密码。
class AuthSession {
  const AuthSession({
    required this.username,
    required this.cookies,
    required this.savedAt,
  });

  /// 登录的用户名，只用于界面显示。
  final String username;

  /// 需要的 cookie 键值对（至少含 `PHPSESSID`）。
  final Map<String, String> cookies;

  final DateTime savedAt;

  /// 会话是否可能还有效。站点 PHP session 通常几周，
  /// 这里按 30 天保守判断——真过期了请求会拿到登录页，届时再提示重登。
  bool get maybeValid =>
      DateTime.now().difference(savedAt).inDays < 30;

  String get cookieHeader =>
      cookies.entries.map((e) => '${e.key}=${e.value}').join('; ');

  Map<String, dynamic> toJson() => {
        'u': username,
        'c': cookies,
        'at': savedAt.millisecondsSinceEpoch,
      };

  static AuthSession? fromJson(Map<String, dynamic> j) {
    final c = j['c'];
    final u = j['u'] as String?;
    if (c is! Map || u == null || u.isEmpty) return null;
    return AuthSession(
      username: u,
      cookies: c.map((k, v) => MapEntry('$k', '$v')),
      savedAt:
          DateTime.fromMillisecondsSinceEpoch((j['at'] as num?)?.toInt() ?? 0),
    );
  }
}

/// 登录状态存储。只落本地，不上传任何地方。
class AuthStore {
  AuthStore(this._prefs);

  final SharedPreferences _prefs;

  static const _kSession = 'auth_session_v1';

  AuthSession? load() {
    final raw = _prefs.getString(_kSession);
    if (raw == null || raw.isEmpty) return null;
    try {
      final j = jsonDecode(raw);
      if (j is! Map<String, dynamic>) return null;
      return AuthSession.fromJson(j);
    } catch (_) {
      return null;
    }
  }

  Future<void> save(AuthSession s) =>
      _prefs.setString(_kSession, jsonEncode(s.toJson()));

  Future<void> clear() => _prefs.remove(_kSession);

  bool get isLoggedIn => load() != null;
}
