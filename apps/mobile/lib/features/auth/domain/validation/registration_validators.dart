/// Validações do cadastro local para feedback imediato, nos limites de `POST /api/v1/auth/register`.
///
/// O backend continua sendo a autoridade: ele repete as validações e responde por RFC 7807.
class RegistrationValidators {
  const RegistrationValidators._();

  static const int displayNameMinLength = 2;
  static const int displayNameMaxLength = 100;
  static const int handleMinLength = 3;
  static const int handleMaxLength = 30;
  static const int passwordMinLength = 8;
  static const int passwordMaxLength = 128;

  // Mesmo conjunto aceito pelo backend (^@?[a-zA-Z0-9_]{3,30}$), já normalizado para minúsculas
  static final RegExp _handlePattern = RegExp(r'^[a-z0-9_]+$');
  static final RegExp _emailPattern = RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$');

  /// Forma enviada ao backend: sem espaços laterais, sem `@` inicial e em minúsculas.
  static String normalizeHandle(String value) {
    var handle = value.trim();
    if (handle.startsWith('@')) handle = handle.substring(1);
    return handle.toLowerCase();
  }

  static String? displayName(String? value) {
    final name = value?.trim() ?? '';
    if (name.isEmpty) return 'Informe seu nome de exibição';
    final length = name.runes.length;
    if (length < displayNameMinLength || length > displayNameMaxLength) {
      return 'O nome deve ter entre $displayNameMinLength e $displayNameMaxLength caracteres';
    }
    return null;
  }

  static String? handle(String? value) {
    final handle = normalizeHandle(value ?? '');
    if (handle.isEmpty) return 'Informe um nome de usuário';
    if (handle.length < handleMinLength || handle.length > handleMaxLength) {
      return 'Use de $handleMinLength a $handleMaxLength caracteres';
    }
    if (!_handlePattern.hasMatch(handle)) {
      return 'Use apenas letras sem acento, números e sublinhado (_)';
    }
    return null;
  }

  static String? email(String? value) {
    final email = value?.trim() ?? '';
    if (email.isEmpty) return 'Informe seu e-mail';
    if (!_emailPattern.hasMatch(email)) return 'Informe um e-mail válido';
    return null;
  }

  static String? password(String? value) {
    final password = value ?? '';
    if (password.isEmpty) return 'Informe uma senha';
    if (password.length < passwordMinLength) {
      return 'A senha deve ter no mínimo $passwordMinLength caracteres';
    }
    if (password.length > passwordMaxLength) {
      return 'A senha deve ter no máximo $passwordMaxLength caracteres';
    }
    return null;
  }

  static String? passwordConfirmation(String? value, String password) {
    if (value == null || value.isEmpty) return 'Confirme a senha';
    if (value != password) return 'As senhas não coincidem';
    return null;
  }
}
