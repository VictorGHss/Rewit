import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';

/// Contrato de operações de autenticação e sessão.
abstract class AuthRepository {
  /// Realiza login local com e-mail e senha.
  Future<Authenticated> login({
    required String email,
    required String password,
  });

  /// Reativa uma conta previamente desativada usando credenciais locais.
  Future<Authenticated> reactivate({
    required String email,
    required String password,
  });

  /// Desativa a própria conta do usuário autenticado atual via POST /api/v1/me/deactivate.
  Future<void> deactivateAccount();

  /// Altera a senha da conta local autenticada via POST /api/v1/me/password.
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  });

  /// Renova a sessão usando o Refresh Token armazenado localmente.
  Future<Authenticated> refreshTokens();

  /// Obtém os dados do perfil do usuário autenticado atual via /api/v1/auth/me.
  Future<AuthUserDto> getMe();

  /// Encerra a sessão atual (revogação remota e limpeza local).
  Future<void> logout();

  /// Verifica se há tokens de sessão armazenados localmente.
  Future<bool> hasStoredSession();
}
