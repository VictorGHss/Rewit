import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';

/// Tela de configurações e gerenciamento de conta do usuário autenticado.
class AccountSettingsScreen extends StatefulWidget {
  final AuthNotifier authNotifier;
  final VoidCallback? onDeactivated;

  const AccountSettingsScreen({
    super.key,
    required this.authNotifier,
    this.onDeactivated,
  });

  @override
  State<AccountSettingsScreen> createState() => _AccountSettingsScreenState();
}

class _AccountSettingsScreenState extends State<AccountSettingsScreen> {
  bool _isDeactivating = false;
  String? _errorMessage;
  int? _retryAfterSeconds;

  Future<void> _confirmAndDeactivate() async {
    if (_isDeactivating) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Desativar Conta'),
        content: const Text(
          'Sua conta será desativada e você será desconectado.\n\n'
          'Enquanto estiver desativada, você não poderá usar normalmente os recursos da conta.\n\n'
          'Para voltar, use o fluxo "Reativar conta" com seu e-mail e senha.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: ElevatedButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
              foregroundColor: Theme.of(context).colorScheme.onError,
            ),
            child: const Text('Desativar Conta'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      setState(() {
        _isDeactivating = true;
        _errorMessage = null;
        _retryAfterSeconds = null;
      });

      try {
        await widget.authNotifier.deactivateAccount();
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                'Sua conta foi desativada e a sessão encerrada. Para voltar, use o fluxo "Reativar conta".',
              ),
              backgroundColor: Colors.orange,
            ),
          );
          widget.onDeactivated?.call();
          if (Navigator.of(context).canPop()) {
            Navigator.of(context).pop();
          }
        }
      } on ApiException catch (e) {
        if (mounted) {
          setState(() {
            _isDeactivating = false;
            _errorMessage = e.detail;
            _retryAfterSeconds = e.retryAfterSeconds;
          });
        }
      } on NetworkException catch (e) {
        if (mounted) {
          setState(() {
            _isDeactivating = false;
            _errorMessage = e.message;
          });
        }
      } catch (_) {
        if (mounted) {
          setState(() {
            _isDeactivating = false;
            _errorMessage = 'Ocorreu um erro ao desativar sua conta. Tente novamente.';
          });
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final state = widget.authNotifier.state;
    final authenticatedUser = state is Authenticated ? state.user : null;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Configurações da Conta'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Banner de erro RFC 7807 caso ocorra falha na desativação
            if (_errorMessage != null) ...[
              Container(
                margin: const EdgeInsets.only(bottom: 16),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: theme.colorScheme.errorContainer.withAlpha(120),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: theme.colorScheme.error.withAlpha(80)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.error_outline, size: 20, color: theme.colorScheme.error),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _errorMessage!,
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w500,
                              color: theme.colorScheme.onErrorContainer,
                            ),
                          ),
                        ),
                      ],
                    ),
                    if (_retryAfterSeconds != null) ...[
                      const SizedBox(height: 6),
                      Text(
                        'Aguarde $_retryAfterSeconds segundos antes de tentar novamente.',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                          color: theme.colorScheme.error,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],

            // Seção: Informações da Conta
            if (authenticatedUser != null) ...[
              Card(
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                  side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Informações da Conta',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          CircleAvatar(
                            radius: 24,
                            backgroundColor: theme.colorScheme.primary.withAlpha(30),
                            child: Icon(Icons.person, color: theme.colorScheme.primary),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  authenticatedUser.displayName,
                                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                                ),
                                Text(
                                  '@${authenticatedUser.handle}',
                                  style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(150), fontSize: 13),
                                ),
                                Text(
                                  authenticatedUser.email,
                                  style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(130), fontSize: 12),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ],

            // Seção: Gerenciamento da Conta
            Card(
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
                side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
              ),
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.manage_accounts_outlined, color: theme.colorScheme.primary),
                        const SizedBox(width: 8),
                        Text(
                          'Gerenciamento da Conta',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Text(
                      'Desativação de Conta',
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Sua conta será desativada e você será desconectado. '
                      'Enquanto estiver desativada, você não poderá usar normalmente os recursos da conta. '
                      'Para voltar, use o fluxo "Reativar conta" com seu e-mail e senha.',
                      style: TextStyle(
                        fontSize: 13,
                        color: theme.colorScheme.onSurface.withAlpha(160),
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 16),

                    // Botão explícito para desativação
                    SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        onPressed: _isDeactivating ? null : _confirmAndDeactivate,
                        style: OutlinedButton.styleFrom(
                          foregroundColor: theme.colorScheme.error,
                          side: BorderSide(color: theme.colorScheme.error.withAlpha(140)),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8),
                          ),
                        ),
                        icon: _isDeactivating
                            ? SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: theme.colorScheme.error,
                                ),
                              )
                            : const Icon(Icons.pause_circle_outline, size: 20),
                        label: Text(
                          _isDeactivating ? 'Desativando conta...' : 'Desativar minha conta',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
