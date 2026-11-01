import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../data/remote/auth_client.dart';
import '../../ui/tokens.dart';

/// 登录页。
///
/// 站点用账号密码登录，成功后靠 PHPSESSID 维持会话。
/// 我们只存这个会话 cookie，不存密码。
///
/// 登录后能用的功能：For You 推荐、点赞、站内收藏、评论。
class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  bool _busy = false;
  bool _obscure = true;
  String? _error;

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(authProvider.notifier).login(_user.text, _pass.text);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on AuthException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _busy = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '登录失败：$e';
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('登录')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
          R34Space.page + 4,
          R34Space.x6,
          R34Space.page + 4,
          R34Space.x8,
        ),
        children: [
          Container(
            width: 52,
            height: 52,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: R34Color.accentSoft,
              borderRadius: BorderRadius.circular(R34Radius.md),
              border: Border.all(
                color: R34Color.accent.withValues(alpha: 0.45),
              ),
            ),
            child: const Text(
              'R34',
              style: TextStyle(
                fontFamily: 'NotoSansSC',
                fontSize: 15,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.5,
                color: R34Color.accentBright,
              ),
            ),
          ),
          const SizedBox(height: R34Space.x5),
          Text(
            '登录站点账号',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: R34Space.x2),
          Text(
            '登录后可以用「为你推荐」、点赞、站内收藏和评论。\n'
            '账号密码只发给 rule34video.com，本应用不保存密码，只保存登录后的会话。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: R34Color.inkMuted,
                  height: 1.6,
                ),
          ),
          const SizedBox(height: R34Space.x6),
          TextField(
            controller: _user,
            autocorrect: false,
            enableSuggestions: false,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: '用户名或邮箱',
              prefixIcon: Icon(Icons.person_outline_rounded),
            ),
          ),
          const SizedBox(height: R34Space.x3),
          TextField(
            controller: _pass,
            obscureText: _obscure,
            autocorrect: false,
            enableSuggestions: false,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
            decoration: InputDecoration(
              labelText: '密码',
              prefixIcon: const Icon(Icons.lock_outline_rounded),
              suffixIcon: IconButton(
                icon: Icon(
                  _obscure
                      ? Icons.visibility_off_outlined
                      : Icons.visibility_outlined,
                ),
                onPressed: () => setState(() => _obscure = !_obscure),
              ),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: R34Space.x3),
            Container(
              padding: const EdgeInsets.all(R34Space.x3),
              decoration: BoxDecoration(
                color: R34Color.danger.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(R34Radius.sm),
                border: Border.all(
                  color: R34Color.danger.withValues(alpha: 0.35),
                ),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(
                    Icons.error_outline_rounded,
                    size: 18,
                    color: R34Color.danger,
                  ),
                  const SizedBox(width: R34Space.x2),
                  Expanded(
                    child: Text(
                      _error!,
                      style: const TextStyle(
                        fontFamily: 'NotoSansSC',
                        color: R34Color.danger,
                        fontSize: 12.5,
                        height: 1.5,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: R34Space.x5),
          FilledButton(
            onPressed: _busy ? null : _submit,
            child: _busy
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('登录'),
          ),
          const SizedBox(height: R34Space.x4),
          Text(
            '没有账号？这个应用不提供注册入口，请到网站注册后再回来登录。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: R34Color.inkFaint,
                ),
          ),
        ],
      ),
    );
  }
}
