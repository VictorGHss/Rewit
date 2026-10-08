import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/shared/widgets/app_button.dart';
import 'package:rewit_mobile/shared/widgets/app_text_field.dart';

/// Tela de autenticação local com validação de credenciais e feedback RFC 7807.
class LoginScreen extends StatefulWidget {
  final AuthNotifier authNotifier;
  final VoidCallback? onLoginSuccess;

  const LoginScreen({
    super.key,
    required this.authNotifier,
    this.onLoginSuccess,
  });

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _emailController;
  late final TextEditingController _passwordController;
  bool _obscurePassword = true;

  @override
  void initState() {
    super.initState();
    _emailController = TextEditingController();
    _passwordController = TextEditingController();
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) {
      return;
    }

    final success = await widget.authNotifier.login(
      _emailController.text.trim(),
      _passwordController.text,
    );

    if (success && mounted) {
      widget.onLoginSuccess?.call();
    }
  }

  Future<void> _confirmAndReactivate() async {
    if (!_formKey.currentState!.validate()) {
      return;
    }

    final email = _emailController.text.trim();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Reativar Conta'),
        content: Text(
          'Deseja reativar a conta associada ao e-mail "$email"?\n\n'
          'Ao confirmar, sua conta voltará ao estado ativo e você será conectado ao aplicativo.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Reativar'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      final success = await widget.authNotifier.reactivate(
        email,
        _passwordController.text,
      );

      if (success && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Conta reativada com sucesso! Bem-vindo de volta.'),
            backgroundColor: Colors.green,
          ),
        );
        widget.onLoginSuccess?.call();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 32.0),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 400),
              child: Form(
                key: _formKey,
                child: ListenableBuilder(
                  listenable: widget.authNotifier,
                  builder: (context, _) {
                    final state = widget.authNotifier.state;
                    final isLoading = state is Authenticating;
                    final errorMessage = state is Unauthenticated ? state.errorMessage : null;

                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        // Cabeçalho de marca
                        Icon(
                          Icons.location_on,
                          size: 64,
                          color: theme.colorScheme.primary,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'Rewit',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.headlineMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                            color: theme.colorScheme.primary,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Avaliações reais do mundo físico',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.onSurface.withAlpha(180),
                          ),
                        ),
                        const SizedBox(height: 32),

                        // Banner de mensagem de status/sucesso ou erro (ProblemDetail RFC 7807)
                        if (errorMessage != null) ...[
                          Builder(
                            builder: (context) {
                              final isSuccess = state is Unauthenticated && state.errorCode == 'PASSWORD_CHANGED';
                              final bannerColor = isSuccess ? Colors.green : theme.colorScheme.error;
                              final bannerIcon = isSuccess ? Icons.check_circle_outline : Icons.error_outline;

                              return Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: bannerColor.withAlpha(25),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: bannerColor.withAlpha(100),
                                  ),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        Icon(
                                          bannerIcon,
                                          color: bannerColor,
                                          size: 20,
                                        ),
                                        const SizedBox(width: 8),
                                        Expanded(
                                          child: Text(
                                            errorMessage,
                                            style: TextStyle(
                                              color: isSuccess ? Colors.green.shade800 : bannerColor,
                                              fontSize: 13,
                                              fontWeight: FontWeight.w500,
                                            ),
                                          ),
                                        ),
                                      ],
                                    ),
                                    if (state is Unauthenticated && state.retryAfterSeconds != null) ...[
                                      const SizedBox(height: 6),
                                      Text(
                                        'Por favor, aguarde ${state.retryAfterSeconds} segundos antes de tentar novamente.',
                                        style: TextStyle(
                                          color: theme.colorScheme.error,
                                          fontSize: 12,
                                          fontWeight: FontWeight.w600,
                                        ),
                                      ),
                                    ],
                                    if (state is Unauthenticated && state.isInvalidCredentials) ...[
                                      const SizedBox(height: 8),
                                      Divider(height: 1, color: theme.colorScheme.error.withAlpha(60)),
                                      const SizedBox(height: 6),
                                      Row(
                                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                        children: [
                                          Flexible(
                                            child: Text(
                                              'Sua conta foi desativada?',
                                              style: TextStyle(
                                                fontSize: 12,
                                                color: theme.colorScheme.error,
                                              ),
                                            ),
                                          ),
                                          TextButton(
                                            onPressed: isLoading ? null : _confirmAndReactivate,
                                            style: TextButton.styleFrom(
                                              visualDensity: VisualDensity.compact,
                                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                                            ),
                                            child: const Text('Reativar Conta', style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold)),
                                          ),
                                        ],
                                      ),
                                    ],
                                  ],
                                ),
                              );
                            },
                          ),
                          const SizedBox(height: 20),
                        ],

                        // Campo E-mail
                        AppTextField(
                          controller: _emailController,
                          labelText: 'E-mail',
                          hintText: 'seu@email.com',
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: TextInputAction.next,
                          prefixIcon: const Icon(Icons.email_outlined),
                          enabled: !isLoading,
                          validator: (value) {
                            if (value == null || value.trim().isEmpty) {
                              return 'Informe seu e-mail';
                            }
                            if (!value.contains('@') || !value.contains('.')) {
                              return 'Informe um e-mail válido';
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 16),

                        // Campo Senha
                        AppTextField(
                          controller: _passwordController,
                          labelText: 'Senha',
                          hintText: 'Sua senha segura',
                          obscureText: _obscurePassword,
                          textInputAction: TextInputAction.done,
                          prefixIcon: const Icon(Icons.lock_outline),
                          suffixIcon: IconButton(
                            icon: Icon(
                              _obscurePassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                            ),
                            onPressed: () {
                              setState(() {
                                _obscurePassword = !_obscurePassword;
                              });
                            },
                          ),
                          enabled: !isLoading,
                          onFieldSubmitted: (_) => _submit(),
                          validator: (value) {
                            if (value == null || value.isEmpty) {
                              return 'Informe sua senha';
                            }
                            if (value.length < 8) {
                              return 'A senha deve ter no mínimo 8 caracteres';
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 24),

                        // Botão Entrar
                        AppButton(
                          label: 'Entrar',
                          isLoading: isLoading,
                          onPressed: isLoading ? null : _submit,
                        ),
                        const SizedBox(height: 12),

                        // Link para Reativar Conta Desativada
                        TextButton.icon(
                          onPressed: isLoading ? null : _confirmAndReactivate,
                          icon: const Icon(Icons.settings_backup_restore_rounded, size: 18),
                          label: const Text('Reativar conta desativada'),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
