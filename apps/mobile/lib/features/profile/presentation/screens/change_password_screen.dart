import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/shared/widgets/app_button.dart';

/// Tela dedicada para alteração de senha da conta local autenticada (C5.11).
class ChangePasswordScreen extends StatefulWidget {
  final AuthNotifier authNotifier;
  final VoidCallback? onPasswordChanged;

  const ChangePasswordScreen({
    super.key,
    required this.authNotifier,
    this.onPasswordChanged,
  });

  @override
  State<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends State<ChangePasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _currentPasswordController = TextEditingController();
  final _newPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();

  bool _obscureCurrentPassword = true;
  bool _obscureNewPassword = true;
  bool _obscureConfirmPassword = true;
  bool _isSubmitting = false;
  bool _hasNavigatedAway = false;

  String? _errorMessage;
  int? _retryAfterSeconds;

  @override
  void initState() {
    super.initState();
    widget.authNotifier.addListener(_onAuthStateChanged);
    if (widget.authNotifier.state is Unauthenticated &&
        (widget.authNotifier.state as Unauthenticated).errorCode != 'PASSWORD_CHANGED') {
      WidgetsBinding.instance.addPostFrameCallback((_) => _returnToAuthFlow());
    }
  }

  @override
  void didUpdateWidget(ChangePasswordScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.authNotifier != widget.authNotifier) {
      oldWidget.authNotifier.removeListener(_onAuthStateChanged);
      widget.authNotifier.addListener(_onAuthStateChanged);
    }
  }

  @override
  void dispose() {
    widget.authNotifier.removeListener(_onAuthStateChanged);
    _currentPasswordController.dispose();
    _newPasswordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  void _onAuthStateChanged() {
    if (!mounted || _hasNavigatedAway) return;
    final state = widget.authNotifier.state;
    if (state is Unauthenticated && state.errorCode != 'PASSWORD_CHANGED') {
      _returnToAuthFlow();
    }
  }

  void _returnToAuthFlow() {
    if (!mounted || _hasNavigatedAway) return;
    _hasNavigatedAway = true;

    final navigator = Navigator.of(context);
    if (navigator.canPop()) {
      navigator.popUntil((route) => route.isFirst);
    } else {
      try {
        navigator.pushReplacementNamed(AppRouter.root);
      } catch (_) {
        // Fallback seguro caso rotas nomeadas não estejam configuradas em testes unitários
      }
    }
  }

  Future<void> _submit() async {
    if (_isSubmitting || _hasNavigatedAway) return;

    setState(() {
      _errorMessage = null;
      _retryAfterSeconds = null;
    });

    if (!_formKey.currentState!.validate()) {
      return;
    }

    setState(() {
      _isSubmitting = true;
    });

    try {
      final success = await widget.authNotifier.changePassword(
        currentPassword: _currentPasswordController.text,
        newPassword: _newPasswordController.text,
      );

      if (success && mounted && !_hasNavigatedAway) {
        setState(() {
          _isSubmitting = false;
        });
        _currentPasswordController.clear();
        _newPasswordController.clear();
        _confirmPasswordController.clear();

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Senha alterada com sucesso! Entre novamente com sua nova senha.'),
            backgroundColor: Colors.green,
            duration: Duration(seconds: 4),
          ),
        );

        widget.onPasswordChanged?.call();

        _returnToAuthFlow();
      }
    } on ApiException catch (e) {
      if (e.isUnauthorized && e.errorCode != 'INVALID_CREDENTIALS') {
        _returnToAuthFlow();
        return;
      }
      if (mounted && !_hasNavigatedAway) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = e.detail;
          _retryAfterSeconds = e.retryAfterSeconds;
        });
      }
    } on NetworkException catch (e) {
      if (mounted && !_hasNavigatedAway) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = e.message;
        });
      }
    } catch (_) {
      if (mounted && !_hasNavigatedAway) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = 'Ocorreu um erro inesperado ao alterar sua senha.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.authNotifier.state is Unauthenticated &&
        (widget.authNotifier.state as Unauthenticated).errorCode != 'PASSWORD_CHANGED') {
      return const Scaffold(
        body: SizedBox.shrink(),
      );
    }

    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Alterar Senha'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Banner de Erro RFC 7807 / Rede
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

              // Card Informativo de Segurança
              Card(
                elevation: 0,
                color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                  side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(14.0),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.info_outline, size: 20, color: theme.colorScheme.primary),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          'Ao alterar sua senha, todas as sessões ativas deste aplicativo e de outros '
                          'dispositivos serão encerradas automaticamente por segurança. '
                          'Você precisará entrar novamente com a nova senha.',
                          style: TextStyle(
                            fontSize: 12.5,
                            color: theme.colorScheme.onSurface.withAlpha(180),
                            height: 1.4,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 20),

              // Campo: Senha atual
              TextFormField(
                key: const Key('current_password_field'),
                controller: _currentPasswordController,
                obscureText: _obscureCurrentPassword,
                enabled: !_isSubmitting,
                decoration: InputDecoration(
                  labelText: 'Senha atual',
                  prefixIcon: const Icon(Icons.lock_outline, size: 20),
                  suffixIcon: IconButton(
                    key: const Key('toggle_current_password_visibility'),
                    icon: Icon(
                      _obscureCurrentPassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                      size: 20,
                    ),
                    onPressed: () {
                      setState(() {
                        _obscureCurrentPassword = !_obscureCurrentPassword;
                      });
                    },
                  ),
                  border: const OutlineInputBorder(),
                ),
                validator: (val) {
                  if (val == null || val.isEmpty) {
                    return 'Informe sua senha atual.';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // Campo: Nova senha
              TextFormField(
                key: const Key('new_password_field'),
                controller: _newPasswordController,
                obscureText: _obscureNewPassword,
                enabled: !_isSubmitting,
                decoration: InputDecoration(
                  labelText: 'Nova senha',
                  helperText: 'Entre 8 e 128 caracteres',
                  prefixIcon: const Icon(Icons.lock_reset_outlined, size: 20),
                  suffixIcon: IconButton(
                    key: const Key('toggle_new_password_visibility'),
                    icon: Icon(
                      _obscureNewPassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                      size: 20,
                    ),
                    onPressed: () {
                      setState(() {
                        _obscureNewPassword = !_obscureNewPassword;
                      });
                    },
                  ),
                  border: const OutlineInputBorder(),
                ),
                validator: (val) {
                  if (val == null || val.isEmpty) {
                    return 'Informe a nova senha.';
                  }
                  if (val.length < 8) {
                    return 'A nova senha deve ter no mínimo 8 caracteres.';
                  }
                  if (val.length > 128) {
                    return 'A nova senha deve ter no máximo 128 caracteres.';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // Campo: Confirmar nova senha
              TextFormField(
                key: const Key('confirm_new_password_field'),
                controller: _confirmPasswordController,
                obscureText: _obscureConfirmPassword,
                enabled: !_isSubmitting,
                decoration: InputDecoration(
                  labelText: 'Confirmar nova senha',
                  prefixIcon: const Icon(Icons.check_circle_outline, size: 20),
                  suffixIcon: IconButton(
                    key: const Key('toggle_confirm_password_visibility'),
                    icon: Icon(
                      _obscureConfirmPassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                      size: 20,
                    ),
                    onPressed: () {
                      setState(() {
                        _obscureConfirmPassword = !_obscureConfirmPassword;
                      });
                    },
                  ),
                  border: const OutlineInputBorder(),
                ),
                validator: (val) {
                  if (val == null || val.isEmpty) {
                    return 'Confirme a nova senha.';
                  }
                  if (val != _newPasswordController.text) {
                    return 'A confirmação de senha não confere.';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 24),

              // Botão de Submissão
              AppButton(
                key: const Key('change_password_button'),
                label: 'Alterar senha',
                isLoading: _isSubmitting,
                icon: Icons.vpn_key_outlined,
                onPressed: _isSubmitting ? null : _submit,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
