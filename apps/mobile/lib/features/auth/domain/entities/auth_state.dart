import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';

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

  /// Erros por campo devolvidos pela validação do backend (RFC 7807 `fieldErrors`), quando houver.
  final Map<String, String>? fieldErrors;

  /// Provedor social da tentativa que gerou este estado, quando houver.
  final SocialAuthProvider? socialProvider;

  const Unauthenticated({
    this.errorMessage,
    this.errorCode,
    this.retryAfterSeconds,
    this.fieldErrors,
    this.socialProvider,
  });

  bool get isRateLimited => retryAfterSeconds != null && retryAfterSeconds! > 0;
  bool get isInvalidCredentials => errorCode == 'INVALID_CREDENTIALS';

  /// Login social recusado por conta não ativa: o app pode oferecer a reativação como ação explícita do usuário
  /// (o backend só reativa conta desativada pelo próprio usuário e responde igual para suspensa ou excluída).
  bool get canOfferSocialReactivation => errorCode == 'ACCOUNT_DISABLED' && socialProvider != null;

  @override
  String toString() => 'Unauthenticated(error: $errorMessage, code: $errorCode)';
}
