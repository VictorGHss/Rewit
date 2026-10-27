import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/validation/registration_validators.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/shared/widgets/app_button.dart';
import 'package:rewit_mobile/shared/widgets/app_text_field.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

/// Cadastro local (POST /api/v1/auth/register). Em caso de sucesso a sessão emitida pelo cadastro já autentica o
/// usuário e a tela volta à raiz, que exibe a Home.
class RegisterScreen extends StatefulWidget {
  final AuthNotifier authNotifier;

  const RegisterScreen({super.key, required this.authNotifier});

  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  // Códigos de negócio do backend associados a um campo do formulário
  static const Map<String, String> _fieldByErrorCode = {
    'EMAIL_ALREADY_EXISTS': 'email',
    'INVALID_EMAIL': 'email',
    'HANDLE_ALREADY_EXISTS': 'handle',
    'INVALID_HANDLE': 'handle',
    'INVALID_PASSWORD_POLICY': 'password',
    'INVALID_DISPLAY_NAME': 'displayName',
  };

  final _formKey = GlobalKey<FormState>();
  final _displayNameController = TextEditingController();
  final _handleController = TextEditingController();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmationController = TextEditingController();
  bool _obscurePassword = true;

  /// Erros do backend por campo; cada um some quando o campo correspondente é editado.
  Map<String, String> _serverErrors = const {};

  @override
  void initState() {
    super.initState();
    _clearServerErrorOnEdit(_displayNameController, 'displayName');
    _clearServerErrorOnEdit(_handleController, 'handle');
    _clearServerErrorOnEdit(_emailController, 'email');
    _clearServerErrorOnEdit(_passwordController, 'password');
  }

  void _clearServerErrorOnEdit(TextEditingController controller, String field) {
    var previous = controller.text;
    controller.addListener(() {
      if (controller.text == previous) return;
      previous = controller.text;
      if (_serverErrors.containsKey(field)) {
        setState(() => _serverErrors = Map.of(_serverErrors)..remove(field));
      }
    });
  }

  @override
  void dispose() {
    _displayNameController.dispose();
    _handleController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    _confirmationController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() => _serverErrors = const {});
    if (!_formKey.currentState!.validate()) return;

    final success = await widget.authNotifier.register(
      displayName: _displayNameController.text.trim(),
      handle: RegistrationValidators.normalizeHandle(_handleController.text),
      email: _emailController.text.trim(),
      password: _passwordController.text,
    );
    if (!mounted) return;

    if (success) {
      // A raiz já reage ao estado autenticado: basta sair do cadastro
      Navigator.of(context).popUntil((route) => route.isFirst);
      return;
    }

    final state = widget.authNotifier.state;
    if (state is Unauthenticated) {
      final errors = <String, String>{...?state.fieldErrors};
      final field = _fieldByErrorCode[state.errorCode];
      if (field != null && state.errorMessage != null) errors[field] = state.errorMessage!;
      if (errors.isNotEmpty) {
        setState(() => _serverErrors = errors);
        _formKey.currentState!.validate();
      }
    }
  }

  // A tela de login limpa a mensagem do cadastro ao voltar
  void _backToLogin() => Navigator.of(context).maybePop();

  String? _withServerError(String field, String? localError) => localError ?? _serverErrors[field];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(title: const Text('Criar conta')),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 24.0),
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
                        Text(
                          'Avaliações reais do mundo físico',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.onSurface.withAlpha(180),
                          ),
                        ),
                        const SizedBox(height: 24),
                        if (errorMessage != null) ...[
                          ErrorBanner(
                            message: errorMessage,
                            retryAfterSeconds: state is Unauthenticated ? state.retryAfterSeconds : null,
                          ),
                          const SizedBox(height: 20),
                        ],
                        AppTextField(
                          key: const Key('register_display_name'),
                          controller: _displayNameController,
                          labelText: 'Nome de exibição',
                          hintText: 'Como você quer aparecer',
                          textInputAction: TextInputAction.next,
                          prefixIcon: const Icon(Icons.badge_outlined),
                          enabled: !isLoading,
                          validator: (value) =>
                              _withServerError('displayName', RegistrationValidators.displayName(value)),
                        ),
                        const SizedBox(height: 16),
                        AppTextField(
                          key: const Key('register_handle'),
                          controller: _handleController,
                          labelText: 'Nome de usuário',
                          hintText: '@seu_usuario',
                          textInputAction: TextInputAction.next,
                          prefixIcon: const Icon(Icons.alternate_email),
                          enabled: !isLoading,
                          validator: (value) => _withServerError('handle', RegistrationValidators.handle(value)),
                        ),
                        const SizedBox(height: 16),
                        AppTextField(
                          key: const Key('register_email'),
                          controller: _emailController,
                          labelText: 'E-mail',
                          hintText: 'seu@email.com',
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: TextInputAction.next,
                          prefixIcon: const Icon(Icons.email_outlined),
                          enabled: !isLoading,
                          validator: (value) => _withServerError('email', RegistrationValidators.email(value)),
                        ),
                        const SizedBox(height: 16),
                        AppTextField(
                          key: const Key('register_password'),
                          controller: _passwordController,
                          labelText: 'Senha',
                          hintText: 'Mínimo de 8 caracteres',
                          obscureText: _obscurePassword,
                          textInputAction: TextInputAction.next,
                          prefixIcon: const Icon(Icons.lock_outline),
                          suffixIcon: IconButton(
                            icon: Icon(_obscurePassword ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                            onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                          ),
                          enabled: !isLoading,
                          validator: (value) => _withServerError('password', RegistrationValidators.password(value)),
                        ),
                        const SizedBox(height: 16),
                        AppTextField(
                          key: const Key('register_password_confirmation'),
                          controller: _confirmationController,
                          labelText: 'Confirmar senha',
                          obscureText: _obscurePassword,
                          textInputAction: TextInputAction.done,
                          prefixIcon: const Icon(Icons.lock_outline),
                          enabled: !isLoading,
                          onFieldSubmitted: (_) => _submit(),
                          validator: (value) =>
                              RegistrationValidators.passwordConfirmation(value, _passwordController.text),
                        ),
                        const SizedBox(height: 24),
                        AppButton(
                          key: const Key('register_submit'),
                          label: 'Criar conta',
                          isLoading: isLoading,
                          onPressed: isLoading ? null : _submit,
                        ),
                        const SizedBox(height: 12),
                        TextButton(
                          key: const Key('register_back_to_login'),
                          onPressed: isLoading ? null : _backToLogin,
                          child: const Text('Já tem conta? Entrar'),
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
