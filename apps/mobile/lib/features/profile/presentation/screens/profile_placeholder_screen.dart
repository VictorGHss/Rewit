import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';

/// Tela placeholder para Perfil do Usuário (próxima etapa).
class ProfilePlaceholderScreen extends StatelessWidget {
  const ProfilePlaceholderScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Perfil'),
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
              const SizedBox(height: 8),
              const Text(
                'Esta funcionalidade será entregue nas próximas etapas da evolução mobile.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.grey),
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
