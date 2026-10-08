import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';

/// Gerenciador reativo de estado de autenticação (ChangeNotifier).
class AuthNotifier extends ChangeNotifier {
  final AuthRepository _authRepository;
  AuthState _state = const AuthInitial();

  AuthNotifier({required AuthRepository authRepository})
      : _authRepository = authRepository;

  AuthState get state => _state;
  bool get isAuthenticated => _state is Authenticated;
  bool get isLoading => _state is Authenticating;

  /// Verifica se há sessão armazenada e valida identidade via /api/v1/auth/me.
  Future<void> checkAuthStatus() async {
    final hasSession = await _authRepository.hasStoredSession();
    if (!hasSession) {
      _state = const Unauthenticated();
      notifyListeners();
      return;
    }

    _state = const Authenticating(statusMessage: 'Verificando sessão...');
    notifyListeners();

    try {
      final user = await _authRepository.getMe();
      // Sessão válida com o token atual
      _state = Authenticated(
        user: user,
        tokens: const AuthTokens(accessToken: '', refreshToken: ''),
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (e.isUnauthorized) {
        // Tentar rotação do refresh token
        try {
          final refreshed = await _authRepository.refreshTokens();
          _state = refreshed;
          notifyListeners();
          return;
        } catch (_) {
          // Refresh também falhou
          await _authRepository.logout();
          _state = const Unauthenticated(
            errorMessage: 'Sessão expirada. Faça login novamente.',
            errorCode: 'SESSION_EXPIRED',
          );
          notifyListeners();
          return;
        }
      }

      _state = Unauthenticated(
        errorMessage: e.detail,
        errorCode: e.errorCode,
      );
      notifyListeners();
    } on NetworkException catch (e) {
      // Falha de rede: manter estado não autenticado com aviso
      _state = Unauthenticated(errorMessage: e.message);
      notifyListeners();
    } catch (_) {
      _state = const Unauthenticated(errorMessage: 'Não foi possível restaurar a sessão.');
      notifyListeners();
    }
  }

  /// Realiza login local com validação de credenciais.
  Future<bool> login(String email, String password) async {
    if (isLoading) return false;

    _state = const Authenticating(statusMessage: 'Entrando...');
    notifyListeners();

    try {
      final auth = await _authRepository.login(
        email: email,
        password: password,
      );
      _state = auth;
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _state = Unauthenticated(
        errorMessage: e.detail,
        errorCode: e.errorCode,
        retryAfterSeconds: e.retryAfterSeconds,
      );
      notifyListeners();
      return false;
    } on NetworkException catch (e) {
      _state = Unauthenticated(errorMessage: e.message);
      notifyListeners();
      return false;
    } catch (_) {
      _state = const Unauthenticated(
        errorMessage: 'Ocorreu um erro inesperado ao realizar login.',
      );
      notifyListeners();
      return false;
    }
  }

  /// Reativa uma conta desativada com as mesmas credenciais do login.
  Future<bool> reactivate(String email, String password) async {
    if (isLoading) return false;

    _state = const Authenticating(statusMessage: 'Reativando conta...');
    notifyListeners();

    try {
      final auth = await _authRepository.reactivate(
        email: email,
        password: password,
      );
      _state = auth;
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _state = Unauthenticated(
        errorMessage: e.detail,
        errorCode: e.errorCode,
        retryAfterSeconds: e.retryAfterSeconds,
      );
      notifyListeners();
      return false;
    } on NetworkException catch (e) {
      _state = Unauthenticated(errorMessage: e.message);
      notifyListeners();
      return false;
    } catch (_) {
      _state = const Unauthenticated(
        errorMessage: 'Ocorreu um erro inesperado ao reativar a conta.',
      );
      notifyListeners();
      return false;
    }
  }

  /// Desativa a própria conta do usuário autenticado atual via POST /api/v1/me/deactivate.
  Future<bool> deactivateAccount() async {
    if (isLoading) return false;

    final currentUser = _state is Authenticated ? (_state as Authenticated) : null;
    _state = const Authenticating(statusMessage: 'Desativando conta...');
    notifyListeners();

    try {
      await _authRepository.deactivateAccount();
      _state = const Unauthenticated();
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      if (currentUser != null) {
        _state = currentUser;
      } else {
        _state = Unauthenticated(
          errorMessage: e.detail,
          errorCode: e.errorCode,
          retryAfterSeconds: e.retryAfterSeconds,
        );
      }
      notifyListeners();
      rethrow;
    } on NetworkException catch (e) {
      if (currentUser != null) {
        _state = currentUser;
      } else {
        _state = Unauthenticated(errorMessage: e.message);
      }
      notifyListeners();
      rethrow;
    } catch (_) {
      if (currentUser != null) {
        _state = currentUser;
      } else {
        _state = const Unauthenticated(
          errorMessage: 'Ocorreu um erro inesperado ao desativar a conta.',
        );
      }
      notifyListeners();
      rethrow;
    }
  }

  /// Encerra a sessão atual local e remotamente.
  Future<void> logout() async {
    _state = const Authenticating(statusMessage: 'Encerrando sessão...');
    notifyListeners();

    await _authRepository.logout();
    _state = const Unauthenticated();
    notifyListeners();
  }

  /// Invocado quando o cliente HTTP detecta resposta 401 não recuperável.
  void handleSessionExpired() {
    _state = const Unauthenticated(
      errorMessage: 'Sua sessão expirou. Faça login novamente.',
      errorCode: 'SESSION_EXPIRED',
    );
    notifyListeners();
  }

  /// Atualiza os dados de perfil do usuário autenticado após edição bem-sucedida.
  void updateCurrentUser({
    String? handle,
    String? displayName,
    bool? isAnonymousDefault,
  }) {
    if (_state is Authenticated) {
      final current = _state as Authenticated;
      _state = Authenticated(
        user: current.user.copyWith(
          handle: handle,
          displayName: displayName,
          isAnonymousDefault: isAnonymousDefault,
        ),
        tokens: current.tokens,
      );
      notifyListeners();
    }
  }
}
