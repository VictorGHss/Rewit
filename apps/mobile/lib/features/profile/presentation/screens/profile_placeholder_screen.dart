import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';

/// Tela para exibição de perfil no fluxo principal, integrando UserProfileScreen com suporte a fallback.
class ProfilePlaceholderScreen extends StatelessWidget {
  final AuthNotifier? authNotifier;
  final UserProfileRepository? userProfileRepository;

  const ProfilePlaceholderScreen({
    super.key,
    this.authNotifier,
    this.userProfileRepository,
  });

  @override
  Widget build(BuildContext context) {
    if (authNotifier != null) {
      return UserProfileScreen(
        userId: null,
        userProfileRepository: userProfileRepository,
        authNotifier: authNotifier!,
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Perfil de Usuário'),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.person_outline, size: 64, color: Colors.grey),
              const SizedBox(height: 16),
              Text(
                'Perfil de Usuário',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 24),
              OutlinedButton.icon(
                onPressed: () {
                  Navigator.of(context).pushNamed(AppRouter.accountSettings);
                },
                icon: const Icon(Icons.manage_accounts_outlined),
                label: const Text('Configurações da Conta'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
