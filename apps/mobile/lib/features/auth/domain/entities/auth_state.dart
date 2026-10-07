import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';

/// Hierarquia tipada de estados de autenticação do usuário.
abstract class AuthState {
  const AuthState();
}

/// Estado inicial enquanto verifica tokens armazenados localmente.
class AuthInitial extends AuthState {
  const AuthInitial();
}

/// Usuário autenticado com sucesso e sessão ativa.
class Authenticated extends AuthState {
  final AuthUserDto user;
  final AuthTokens tokens;

  const Authenticated({
    required this.user,
    required this.tokens,
  });

  @override
  String toString() => 'Authenticated(user: ${user.handle})';
}

/// Operação de autenticação em andamento (login, refresh ou validação de sessão).
class Authenticating extends AuthState {
  final String? statusMessage;

  const Authenticating({this.statusMessage});
}

/// Usuário desautenticado (sem sessão ou após logout / sessão expirada).
class Unauthenticated extends AuthState {
  final String? errorMessage;
  final String? errorCode;
  final int? retryAfterSeconds;

  const Unauthenticated({
    this.errorMessage,
    this.errorCode,
    this.retryAfterSeconds,
  });

  bool get isRateLimited => retryAfterSeconds != null && retryAfterSeconds! > 0;
  bool get isInvalidCredentials => errorCode == 'INVALID_CREDENTIALS';

  @override
  String toString() => 'Unauthenticated(error: $errorMessage, code: $errorCode)';
}
