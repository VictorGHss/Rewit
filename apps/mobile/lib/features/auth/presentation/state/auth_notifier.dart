import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';

/// Gerenciador reativo de estado de autenticação (ChangeNotifier).
class AuthNotifier extends ChangeNotifier {
  /// Código do estado após o usuário cancelar a tela do provedor (aviso neutro, não é erro).
  static const String socialSignInCancelledCode = 'SOCIAL_SIGN_IN_CANCELLED';

  final AuthRepository _authRepository;
  final SessionRefresher? _sessionRefresher;
  final Future<void> Function()? _clearLocalSession;
  final SocialSignInService? _socialSignInService;
  AuthState _state = const AuthInitial();
  bool _isHandlingExpiry = false;
  int _sessionCheckSequence = 0;
  Future<SessionRefreshOutcome>? _latestSessionCheck;

  /// [sessionRefresher] permite confirmar, antes de encerrar a sessão, se o refresh token ainda vale;
  /// [clearLocalSession] apaga os tokens guardados quando a sessão acaba de fato. Sem eles, um 401
  /// encerra a sessão na hora, como antes. Sem [socialSignInService], o login social fica indisponível.
  AuthNotifier({
    required AuthRepository authRepository,
    SessionRefresher? sessionRefresher,
    Future<void> Function()? clearLocalSession,
    SocialSignInService? socialSignInService,
  })  : _authRepository = authRepository,
        _sessionRefresher = sessionRefresher,
        _clearLocalSession = clearLocalSession,
        _socialSignInService = socialSignInService;

  AuthState get state => _state;
  bool get isAuthenticated => _state is Authenticated;
  bool get isLoading => _state is Authenticating;

  /// Provedor social configurado nesta build e suportado nesta plataforma.
  bool isSocialProviderAvailable(SocialAuthProvider provider) =>
      _socialSignInService?.isAvailable(provider) ?? false;

  /// Remove a mensagem exibida (ex.: ao alternar entre login e cadastro), sem mexer numa sessão ou operação ativa.
  void clearMessage() {
    final current = _state;
    if (current is Unauthenticated && current.errorMessage != null) {
      _state = const Unauthenticated();
      notifyListeners();
    }
  }

  /// Cria a conta local e autentica com a sessão devolvida pelo cadastro, sem exigir um segundo login.
  Future<bool> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) async {
    if (isLoading) return false;

    _state = const Authenticating(statusMessage: 'Criando conta...');
    notifyListeners();

    try {
      _state = await _authRepository.register(
        displayName: displayName,
        handle: handle,
        email: email,
        password: password,
      );
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _state = Unauthenticated(
        errorMessage: e.detail,
        errorCode: e.errorCode,
        retryAfterSeconds: e.retryAfterSeconds,
        fieldErrors: e.hasFieldErrors ? e.allFieldErrors : null,
      );
      notifyListeners();
      return false;
    } on NetworkException catch (e) {
      _state = Unauthenticated(errorMessage: e.message);
      notifyListeners();
      return false;
    } catch (_) {
      _state = const Unauthenticated(
        errorMessage: 'Ocorreu um erro inesperado ao criar a conta.',
      );
      notifyListeners();
      return false;
    }
  }

  /// Login social: nonce do Rewit, fluxo oficial do provedor com o SHA-256 do nonce e validação do ID token
  /// no backend, que emite a sessão. Cancelamento volta ao estado não autenticado com um aviso neutro.
  Future<bool> signInWithSocial(SocialAuthProvider provider) async {
    if (isLoading) return false;

    final service = _socialSignInService;
    if (service == null || !service.isAvailable(provider)) {
      _state = Unauthenticated(
        errorMessage: 'Login com ${provider.label} indisponível nesta versão do app.',
        errorCode: 'SOCIAL_PROVIDER_UNAVAILABLE',
      );
      notifyListeners();
      return false;
    }

    _state = Authenticating(statusMessage: 'Entrando com ${provider.label}...');
    notifyListeners();

    try {
      final nonce = await _authRepository.requestSocialNonce();
      final idToken = await service.obtainIdToken(provider, providerNonce: hashSocialNonce(nonce));
      _state = await _authRepository.signInWithSocial(provider: provider, idToken: idToken, nonce: nonce);
      notifyListeners();
      return true;
    } on SocialSignInCancelledException {
      _state = Unauthenticated(
        errorMessage: 'Login com ${provider.label} cancelado.',
        errorCode: socialSignInCancelledCode,
      );
      notifyListeners();
      return false;
    } on SocialSignInFailureException catch (e) {
      _state = Unauthenticated(errorMessage: e.message, errorCode: 'SOCIAL_SIGN_IN_FAILED');
      notifyListeners();
      return false;
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
      _state = Unauthenticated(
        errorMessage: 'Ocorreu um erro inesperado ao entrar com ${provider.label}.',
      );
      notifyListeners();
      return false;
    }
  }

  /// Valor entregue ao provedor: SHA-256 (hex minúsculo) do nonce do Rewit. O nonce em claro só vai ao Rewit.
  static String hashSocialNonce(String nonce) => sha256.convert(utf8.encode(nonce)).toString();

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

  /// Altera a senha da conta autenticada, revoga a sessão local e transiciona para Unauthenticated.
  Future<bool> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    if (isLoading) return false;

    final currentUser = _state is Authenticated ? (_state as Authenticated) : null;
    _state = const Authenticating(statusMessage: 'Alterando senha...');
    notifyListeners();

    try {
      await _authRepository.changePassword(
        currentPassword: currentPassword,
        newPassword: newPassword,
      );

      _state = const Unauthenticated(
        errorMessage: 'Senha alterada. Entre novamente com sua nova senha.',
        errorCode: 'PASSWORD_CHANGED',
      );
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      if (e.isUnauthorized && e.errorCode != 'INVALID_CREDENTIALS') {
        await _authRepository.logout();
        _state = const Unauthenticated(
          errorMessage: 'Sua sessão expirou. Faça login novamente.',
          errorCode: 'SESSION_EXPIRED',
        );
      } else if (currentUser != null) {
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
          errorMessage: 'Ocorreu um erro inesperado ao alterar a senha.',
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

  /// Invocado quando o cliente HTTP recebe 401 numa requisição autenticada.
  ///
  /// Com [SessionRefresher], a sessão só termina se o servidor recusar o refresh token: renovada (por exemplo,
  /// num upload, que não é repetido automaticamente) ou sem resposta do servidor, ela continua. Ao terminar, os
  /// tokens locais são apagados e o estado vira [Unauthenticated] com `SESSION_EXPIRED`, que leva o app de volta
  /// à tela inicial. A verificação de sessão e o login tratam o próprio 401, por isso só uma sessão ativa é
  /// avaliada aqui.
  Future<void> handleSessionExpired() async {
    final refresher = _sessionRefresher;
    if (refresher == null) {
      _expireSession();
      return;
    }
    if (_state is! Authenticated || _isHandlingExpiry) return;

    _isHandlingExpiry = true;
    final check = Completer<SessionRefreshOutcome>();
    _sessionCheckSequence++;
    _latestSessionCheck = check.future;
    var outcome = SessionRefreshOutcome.unavailable;
    try {
      final result = await refresher.refresh();
      outcome = result.outcome;
      if (result.outcome != SessionRefreshOutcome.rejected || _state is! Authenticated) return;
      await _clearLocalSession?.call();
      _expireSession();
    } finally {
      _isHandlingExpiry = false;
      // Só conclui depois de o estado da sessão refletir o resultado
      check.complete(outcome);
    }
  }

  /// Quantas verificações de sessão [handleSessionExpired] já iniciou. Capturado antes de uma requisição, permite
  /// a [sessionCheckSince] associar o 401 dela à verificação que ele disparou.
  int get sessionCheckSequence => _sessionCheckSequence;

  /// Resultado da verificação de sessão iniciada depois de [sequence], ou da que ainda está em curso (um 401
  /// concorrente aproveita a mesma renovação). Nulo quando nenhuma corresponde: sem [SessionRefresher], ou sem
  /// sessão ativa para avaliar.
  Future<SessionRefreshOutcome>? sessionCheckSince(int sequence) {
    if (_sessionCheckSequence > sequence || _isHandlingExpiry) return _latestSessionCheck;
    return null;
  }

  void _expireSession() {
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
