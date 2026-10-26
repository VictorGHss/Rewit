import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/shared/widgets/app_button.dart';

/// Botões "Continuar com Google" e "Continuar com Apple".
///
/// Um provedor sem configuração nesta build aparece desabilitado e com o motivo, em vez de um botão que não faz
/// nada; a sessão só existe depois que o backend valida o ID token do provedor.
class SocialSignInButtons extends StatelessWidget {
  final AuthNotifier authNotifier;
  final bool enabled;
  final VoidCallback? onSuccess;

  const SocialSignInButtons({
    super.key,
    required this.authNotifier,
    this.enabled = true,
    this.onSuccess,
  });

  static const Map<SocialAuthProvider, IconData> _icons = {
    SocialAuthProvider.google: Icons.g_mobiledata_rounded,
    SocialAuthProvider.apple: Icons.apple,
  };

  Future<void> _signIn(SocialAuthProvider provider) async {
    final success = await authNotifier.signInWithSocial(provider);
    if (success) onSuccess?.call();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final unavailable = SocialAuthProvider.values.where((p) => !authNotifier.isSocialProviderAvailable(p)).toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final provider in SocialAuthProvider.values) ...[
          AppButton(
            key: Key('social_sign_in_${provider.name}'),
            label: 'Continuar com ${provider.label}',
            icon: _icons[provider],
            isOutlined: true,
            onPressed: enabled && authNotifier.isSocialProviderAvailable(provider) ? () => _signIn(provider) : null,
          ),
          const SizedBox(height: 10),
        ],
        if (unavailable.isNotEmpty)
          Text(
            'Login com ${unavailable.map((p) => p.label).join(' e ')} ainda não está disponível nesta versão do app.',
            key: const Key('social_sign_in_unavailable_hint'),
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurface.withAlpha(160)),
          ),
      ],
    );
  }
}
