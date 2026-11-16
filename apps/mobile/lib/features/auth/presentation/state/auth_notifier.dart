import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/session_expiration_details.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/storage/session_token_coordinator.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';

/// Erro sanificado da última tentativa de autenticação quando a sessão
/// autenticada anterior foi preservada sem transição para [Unauthenticated].
class AuthenticationAttemptError {
  final String message;
  final String? code;
  final int? retryAfterSeconds;
  final Map<String, String>? fieldErrors;
  final SocialAuthProvider? socialProvider;

  const AuthenticationAttemptError({
    required this.message,
    this.code,
    this.retryAfterSeconds,
    this.fieldErrors,
    this.socialProvider,
  });
}

class _PreviousSessionSnapshot {
  final Authenticated session;
  final int? persistedGeneration;

  const _PreviousSessionSnapshot({
    required this.session,
    required this.persistedGeneration,
  });
}

/// Gerenciador reativo de estado de autenticação (ChangeNotifier).
class AuthNotifier extends ChangeNotifier {
  /// Código do estado após o usuário cancelar a tela do provedor (aviso neutro, não é erro).
  static const String socialSignInCancelledCode = 'SOCIAL_SIGN_IN_CANCELLED';

  /// Mensagem neutra apresentada quando a sessão autenticada é encerrada por `ACCOUNT_DISABLED`.
  static const String accountDisabledMessage =
      'Sua conta não está disponível para acesso no momento. '
      'Entre em contato com o suporte se acreditar que isso é um engano.';

  final AuthRepository _authRepository;
  final SessionRefresher? _sessionRefresher;
  final SessionTokenCoordinator? _tokenCoordinator;
  final Future<void> Function()? _clearLocalSession;
  final SocialSignInService? _socialSignInService;
  AuthState _state = const AuthInitial();
  bool _isHandlingExpiry = false;
  int _sessionEpoch = 0;
  int _authAttemptSequence = 0;
  int? _activeUiAttemptId;
  _PreviousSessionSnapshot? _previousSessionSnapshot;
  AuthenticationAttemptError? _lastAttemptError;
  int _sessionCheckSequence = 0;
  Future<SessionRefreshOutcome>? _latestSessionCheck;
  Completer<SessionRefreshOutcome>? _activeSessionCheckCompleter;
  Future<void>? _inFlightDisabledExpiry;

  /// [sessionRefresher] permite confirmar, antes de encerrar a sessão, se o refresh token ainda vale;
  /// [tokenCoordinator] coordena gerações e fila serializada de gravação/limpeza de tokens;
  /// [clearLocalSession] apaga os tokens guardados quando a sessão acaba de fato. Sem eles, um 401
  /// encerra a sessão na hora, como antes. Sem [socialSignInService], o login social fica indisponível.
  AuthNotifier({
    required AuthRepository authRepository,
    SessionRefresher? sessionRefresher,
    SessionTokenCoordinator? tokenCoordinator,
    Future<void> Function()? clearLocalSession,
    SocialSignInService? socialSignInService,
  })  : _authRepository = authRepository,
        _sessionRefresher = sessionRefresher,
        _tokenCoordinator = tokenCoordinator,
        _clearLocalSession = clearLocalSession,
        _socialSignInService = socialSignInService;

  SessionTokenCoordinator? get _effectiveTokenCoordinator {
    final explicit = _tokenCoordinator;
    if (explicit != null) return explicit;
    final repo = _authRepository;
    return repo is AttemptScopedAuthRepository ? repo.tokenCoordinator : null;
  }

  bool get _isInAccountDisabledState {
    final current = _state;
    return current is Unauthenticated &&
        current.errorCode == 'ACCOUNT_DISABLED' &&
        current.socialProvider == null;
  }

  bool _isUiAttemptValid(int uiAttemptId, {int? expectedEpoch}) =>
      _activeUiAttemptId == uiAttemptId &&
      (expectedEpoch == null || _sessionEpoch == expectedEpoch);

  bool _isAttemptBeforeCommitValid(
    int uiAttemptId,
    int? coordinatorAttemptId, {
    int? expectedEpoch,
  }) {
    if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: expectedEpoch)) {
      return false;
    }
    final coordinator = _effectiveTokenCoordinator;
    if (coordinatorAttemptId != null && coordinator != null) {
      return coordinator.isAuthenticationAttemptCurrent(coordinatorAttemptId);
    }
    return true;
  }

  void _abandonUiAttempt(int uiAttemptId, int? coordinatorAttemptId) {
    if (_activeUiAttemptId == uiAttemptId) {
      _activeUiAttemptId = null;
    }
    if (coordinatorAttemptId != null) {
      _effectiveTokenCoordinator?.abandonAuthenticationAttempt(
        coordinatorAttemptId,
      );
    }
  }

  void _capturePreviousSessionSnapshotIfNeeded() {
    final current = _state;
    if (current is Authenticated && _previousSessionSnapshot == null) {
      _previousSessionSnapshot = _PreviousSessionSnapshot(
        session: current,
        persistedGeneration: _effectiveTokenCoordinator?.currentGeneration,
      );
    }
  }

  void _discardPreviousSessionContext() {
    _previousSessionSnapshot = null;
    _lastAttemptError = null;
  }

  void _finishUiAttemptSuccess(Authenticated auth) {
    _activeUiAttemptId = null;
    _discardPreviousSessionContext();
    _state = auth;
    notifyListeners();
  }

  void _finishUiAttemptFailure({
    required int uiAttemptId,
    required int? coordinatorAttemptId,
    required AuthenticationAttemptError error,
  }) {
    _abandonUiAttempt(uiAttemptId, coordinatorAttemptId);

    final coordinator = _effectiveTokenCoordinator;
    final snapshot = _previousSessionSnapshot;
    final canRestorePreviousSession = snapshot != null &&
        (coordinator == null
            ? snapshot.persistedGeneration == null
            : snapshot.persistedGeneration == coordinator.currentGeneration);

    _previousSessionSnapshot = null;

    if (canRestorePreviousSession) {
      _lastAttemptError = error;
      _state = snapshot.session;
      notifyListeners();
      return;
    }

    _lastAttemptError = null;
    _state = Unauthenticated(
      errorMessage: error.message,
      errorCode: error.code,
      retryAfterSeconds: error.retryAfterSeconds,
      fieldErrors: error.fieldErrors,
      socialProvider: error.socialProvider,
    );
    notifyListeners();
  }

  void _invalidateSessionRefresher(int opEpoch) {
    _sessionRefresher?.invalidate(
      onStaleRefreshSettled: () async {
        if (_tokenCoordinator != null) {
          return;
        }
        if (_sessionEpoch == opEpoch) {
          await _clearLocalSession?.call();
        }
      },
    );
  }

  Future<bool> _abortIfSessionChangedOrDisabled(int epochAtStart) async {
    if (epochAtStart != _sessionEpoch ||
        _inFlightDisabledExpiry != null ||
        _isInAccountDisabledState) {
      await _inFlightDisabledExpiry;
      return true;
    }
    return false;
  }

  AuthState get state => _state;
  bool get isAuthenticated => _state is Authenticated;
  bool get isLoading => _state is Authenticating;

  /// Erro sanificado da última tentativa que falhou preservando a sessão anterior.
  AuthenticationAttemptError? get lastAttemptError => _lastAttemptError;

  /// Indica se existe snapshot de sessão anterior pendente de restauração ou descarte.
  @visibleForTesting
  bool get hasPendingPreviousSessionSnapshot => _previousSessionSnapshot != null;

  /// Provedor social configurado nesta build e suportado nesta plataforma.
  bool isSocialProviderAvailable(SocialAuthProvider provider) =>
      _socialSignInService?.isAvailable(provider) ?? false;

  /// Remove a mensagem exibida (ex.: ao alternar entre login e cadastro), sem mexer numa sessão ou operação ativa.
  void clearMessage() {
    var shouldNotify = false;
    if (_lastAttemptError != null) {
      _lastAttemptError = null;
      shouldNotify = true;
    }
    final current = _state;
    if (current is Unauthenticated && current.errorMessage != null) {
      _state = const Unauthenticated();
      shouldNotify = true;
    }
    if (shouldNotify) {
      notifyListeners();
    }
  }

  /// Cria a conta local e autentica com a sessão devolvida pelo cadastro, sem exigir um segundo login.
  Future<bool> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) {
    final repo = _authRepository;
    return _runCredentialAuthAttempt(
      statusMessage: 'Criando conta...',
      fallbackErrorMessage: 'Ocorreu um erro inesperado ao criar a conta.',
      execute: (attemptId) => repo is AttemptScopedAuthRepository
          ? repo.registerWithAttempt(
              displayName: displayName,
              handle: handle,
              email: email,
              password: password,
              authenticationAttemptId: attemptId,
            )
          : repo.register(
              displayName: displayName,
              handle: handle,
              email: email,
              password: password,
            ),
    );
  }

  /// Login social: nonce do Rewit, fluxo oficial do provedor com o SHA-256 do nonce e validação do ID token
  /// no backend, que emite a sessão. Cancelamento volta ao estado não autenticado com um aviso neutro. Conta
  /// desativada não é reativada aqui: o estado expõe [Unauthenticated.canOfferSocialReactivation].
  Future<bool> signInWithSocial(SocialAuthProvider provider) {
    final repo = _authRepository;
    return _runSocialFlow(
      provider,
      statusMessage: 'Entrando com ${provider.label}...',
      action: 'entrar',
      exchange: ({
        required provider,
        required idToken,
        required nonce,
        int? authenticationAttemptId,
      }) =>
          repo is AttemptScopedAuthRepository
              ? repo.signInWithSocialAttempt(
                  provider: provider,
                  idToken: idToken,
                  nonce: nonce,
                  authenticationAttemptId: authenticationAttemptId,
                )
              : repo.signInWithSocial(
                  provider: provider,
                  idToken: idToken,
                  nonce: nonce,
                ),
    );
  }

  /// Reativa a própria conta federada desativada: ação explícita do usuário (a tela pede confirmação antes),
  /// com uma autenticação nova no provedor e um nonce novo; nunca chamada pelo login.
  Future<bool> reactivateWithSocial(SocialAuthProvider provider) {
    final repo = _authRepository;
    return _runSocialFlow(
      provider,
      statusMessage: 'Reativando conta com ${provider.label}...',
      action: 'reativar a conta',
      exchange: ({
        required provider,
        required idToken,
        required nonce,
        int? authenticationAttemptId,
      }) =>
          repo is AttemptScopedAuthRepository
              ? repo.reactivateWithSocialAttempt(
                  provider: provider,
                  idToken: idToken,
                  nonce: nonce,
                  authenticationAttemptId: authenticationAttemptId,
                )
              : repo.reactivateWithSocial(
                  provider: provider,
                  idToken: idToken,
                  nonce: nonce,
                ),
    );
  }

  Future<bool> _runSocialFlow(
    SocialAuthProvider provider, {
    required String statusMessage,
    required String action,
    required Future<Authenticated> Function({
      required SocialAuthProvider provider,
      required String idToken,
      required String nonce,
      int? authenticationAttemptId,
    }) exchange,
  }) async {
    final coordinator = _effectiveTokenCoordinator;
    final service = _socialSignInService;
    if (service == null || !service.isAvailable(provider)) {
      final error = AuthenticationAttemptError(
        message: 'Login com ${provider.label} indisponível nesta versão do app.',
        code: 'SOCIAL_PROVIDER_UNAVAILABLE',
        socialProvider: provider,
      );
      if (_state is Authenticated) {
        _lastAttemptError = error;
      } else {
        _lastAttemptError = null;
        _state = Unauthenticated(
          errorMessage: error.message,
          errorCode: error.code,
        );
      }
      notifyListeners();
      return false;
    }

    final uiAttemptId = ++_authAttemptSequence;
    _activeUiAttemptId = uiAttemptId;
    final coordinatorAttemptId = coordinator?.beginAuthenticationAttempt();

    final disabledExpiry = _inFlightDisabledExpiry;
    if (disabledExpiry != null) {
      await disabledExpiry;
      if (!_isAttemptBeforeCommitValid(uiAttemptId, coordinatorAttemptId)) {
        return false;
      }
    }

    _lastAttemptError = null;
    _capturePreviousSessionSnapshotIfNeeded();
    final authEpoch = ++_sessionEpoch;
    _state = Authenticating(statusMessage: statusMessage);
    notifyListeners();

    try {
      // Nonce novo a cada tentativa, inclusive depois de cancelamento: o anterior expira sem uso no backend
      final nonce = await _authRepository.requestSocialNonce();
      if (!_isAttemptBeforeCommitValid(
        uiAttemptId,
        coordinatorAttemptId,
        expectedEpoch: authEpoch,
      )) {
        await _inFlightDisabledExpiry;
        return false;
      }

      final idToken = await service.obtainIdToken(
        provider,
        providerNonce: hashSocialNonce(nonce),
      );
      if (!_isAttemptBeforeCommitValid(
        uiAttemptId,
        coordinatorAttemptId,
        expectedEpoch: authEpoch,
      )) {
        await _inFlightDisabledExpiry;
        return false;
      }

      final auth = await exchange(
        provider: provider,
        idToken: idToken,
        nonce: nonce,
        authenticationAttemptId: coordinatorAttemptId,
      );
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptSuccess(auth);
      return true;
    } on StaleAuthenticationAttemptException {
      await _inFlightDisabledExpiry;
      return false;
    } on SocialSignInCancelledException {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(
          message: 'Login com ${provider.label} cancelado.',
          code: socialSignInCancelledCode,
          socialProvider: provider,
        ),
      );
      return false;
    } on SocialSignInFailureException catch (e) {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(
          message: e.message,
          code: 'SOCIAL_SIGN_IN_FAILED',
          socialProvider: provider,
        ),
      );
      return false;
    } on ApiException catch (e) {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(
          message: e.detail,
          code: e.errorCode,
          retryAfterSeconds: e.retryAfterSeconds,
          socialProvider: provider,
        ),
      );
      return false;
    } on NetworkException catch (e) {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(
          message: e.message,
          socialProvider: provider,
        ),
      );
      return false;
    } catch (_) {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(
          message: 'Ocorreu um erro inesperado ao $action com ${provider.label}.',
          socialProvider: provider,
        ),
      );
      return false;
    }
  }

  /// Valor entregue ao provedor: SHA-256 (hex minúsculo) do nonce do Rewit. O nonce em claro só vai ao Rewit.
  static String hashSocialNonce(String nonce) => sha256.convert(utf8.encode(nonce)).toString();

  /// Verifica se há sessão armazenada e valida identidade via /api/v1/auth/me.
  Future<void> checkAuthStatus() async {
    if (_inFlightDisabledExpiry != null) {
      await _inFlightDisabledExpiry;
      return;
    }
    if (_isInAccountDisabledState || _activeUiAttemptId != null) {
      return;
    }

    final epochAtEntry = _sessionEpoch;
    final hasSession = await _authRepository.hasStoredSession();
    if (_inFlightDisabledExpiry != null) {
      await _inFlightDisabledExpiry;
      return;
    }
    if (_isInAccountDisabledState ||
        _activeUiAttemptId != null ||
        epochAtEntry != _sessionEpoch) {
      return;
    }
    if (!hasSession) {
      _state = const Unauthenticated();
      notifyListeners();
      return;
    }

    _sessionEpoch++;
    final epochAtStart = _sessionEpoch;
    _state = const Authenticating(statusMessage: 'Verificando sessão...');
    notifyListeners();

    try {
      final user = await _authRepository.getMe();
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        return;
      }
      // Sessão válida com o token atual
      _state = Authenticated(
        user: user,
        tokens: const AuthTokens(accessToken: '', refreshToken: ''),
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        return;
      }
      if (e.isUnauthorized) {
        if (e.errorCode?.trim().toUpperCase() == 'ACCOUNT_DISABLED') {
          await _handleAccountDisabled();
          return;
        }
        // Tentar rotação do refresh token
        try {
          final refreshed = await _authRepository.refreshTokens();
          if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
            return;
          }
          _state = refreshed;
          notifyListeners();
          return;
        } catch (_) {
          if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
            return;
          }
          // Refresh também falhou
          await _authRepository.logout();
          if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
            return;
          }
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
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        return;
      }
      // Falha de rede: manter estado não autenticado com aviso
      _state = Unauthenticated(errorMessage: e.message);
      notifyListeners();
    } catch (_) {
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        return;
      }
      _state = const Unauthenticated(errorMessage: 'Não foi possível restaurar a sessão.');
      notifyListeners();
    }
  }

  /// Realiza login local com validação de credenciais.
  Future<bool> login(String email, String password) {
    final repo = _authRepository;
    return _runCredentialAuthAttempt(
      statusMessage: 'Entrando...',
      fallbackErrorMessage: 'Ocorreu um erro inesperado ao realizar login.',
      execute: (attemptId) => repo is AttemptScopedAuthRepository
          ? repo.loginWithAttempt(
              email: email,
              password: password,
              authenticationAttemptId: attemptId,
            )
          : repo.login(
              email: email,
              password: password,
            ),
    );
  }

  /// Reativa uma conta desativada com as mesmas credenciais do login.
  Future<bool> reactivate(String email, String password) {
    if (isLoading) return Future<bool>.value(false);
    final repo = _authRepository;
    return _runCredentialAuthAttempt(
      statusMessage: 'Reativando conta...',
      fallbackErrorMessage: 'Ocorreu um erro inesperado ao reativar a conta.',
      execute: (attemptId) => repo is AttemptScopedAuthRepository
          ? repo.reactivateWithAttempt(
              email: email,
              password: password,
              authenticationAttemptId: attemptId,
            )
          : repo.reactivate(
              email: email,
              password: password,
            ),
    );
  }

  Future<bool> _runCredentialAuthAttempt({
    required String statusMessage,
    required String fallbackErrorMessage,
    required Future<Authenticated> Function(int? attemptId) execute,
  }) async {
    final coordinator = _effectiveTokenCoordinator;
    final uiAttemptId = ++_authAttemptSequence;
    _activeUiAttemptId = uiAttemptId;
    final coordinatorAttemptId = coordinator?.beginAuthenticationAttempt();

    final disabledExpiry = _inFlightDisabledExpiry;
    if (disabledExpiry != null) {
      await disabledExpiry;
      if (!_isAttemptBeforeCommitValid(uiAttemptId, coordinatorAttemptId)) {
        return false;
      }
    }

    _lastAttemptError = null;
    _capturePreviousSessionSnapshotIfNeeded();
    final authEpoch = ++_sessionEpoch;
    _state = Authenticating(statusMessage: statusMessage);
    notifyListeners();

    try {
      final auth = await execute(coordinatorAttemptId);
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptSuccess(auth);
      return true;
    } on StaleAuthenticationAttemptException {
      await _inFlightDisabledExpiry;
      return false;
    } on ApiException catch (e) {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(
          message: e.detail,
          code: e.errorCode,
          retryAfterSeconds: e.retryAfterSeconds,
          fieldErrors: e.hasFieldErrors ? e.allFieldErrors : null,
        ),
      );
      return false;
    } on NetworkException catch (e) {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(message: e.message),
      );
      return false;
    } catch (_) {
      if (!_isUiAttemptValid(uiAttemptId, expectedEpoch: authEpoch) ||
          _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
        return false;
      }
      _finishUiAttemptFailure(
        uiAttemptId: uiAttemptId,
        coordinatorAttemptId: coordinatorAttemptId,
        error: AuthenticationAttemptError(message: fallbackErrorMessage),
      );
      return false;
    }
  }

  /// Desativa a própria conta do usuário autenticado atual via POST /api/v1/me/deactivate.
  Future<bool> deactivateAccount() async {
    if (isLoading) return false;
    await _inFlightDisabledExpiry;
    if (isLoading || _isInAccountDisabledState) return false;

    final currentUser = _state is Authenticated ? (_state as Authenticated) : null;
    final epochAtStart = _sessionEpoch;
    _state = const Authenticating(statusMessage: 'Desativando conta...');
    notifyListeners();

    try {
      await _authRepository.deactivateAccount();
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        return false;
      }
      _sessionEpoch++;
      final deactivatedEpoch = _sessionEpoch;
      _activeUiAttemptId = null;
      _discardPreviousSessionContext();
      _invalidateSessionRefresher(deactivatedEpoch);
      _state = const Unauthenticated();
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      if (e.isUnauthorized) {
        if (e.errorCode?.trim().toUpperCase() == 'ACCOUNT_DISABLED') {
          await _handleAccountDisabled();
        } else if (epochAtStart == _sessionEpoch && _inFlightDisabledExpiry == null) {
          _sessionEpoch++;
          final expiredEpoch = _sessionEpoch;
          _invalidateSessionRefresher(expiredEpoch);
          await _authRepository.logout();
          if (epochAtStart + 1 == _sessionEpoch && _inFlightDisabledExpiry == null) {
            _expireSession();
          }
        } else {
          await _inFlightDisabledExpiry;
        }
        rethrow;
      }
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        rethrow;
      }
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
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        rethrow;
      }
      if (currentUser != null) {
        _state = currentUser;
      } else {
        _state = Unauthenticated(errorMessage: e.message);
      }
      notifyListeners();
      rethrow;
    } catch (_) {
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        rethrow;
      }
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
    await _inFlightDisabledExpiry;
    if (isLoading || _isInAccountDisabledState) return false;

    final currentUser = _state is Authenticated ? (_state as Authenticated) : null;
    final epochAtStart = _sessionEpoch;
    _state = const Authenticating(statusMessage: 'Alterando senha...');
    notifyListeners();

    try {
      await _authRepository.changePassword(
        currentPassword: currentPassword,
        newPassword: newPassword,
      );
      if (await _abortIfSessionChangedOrDisabled(epochAtStart)) {
        return false;
      }

      _sessionEpoch++;
      final changedEpoch = _sessionEpoch;
      _activeUiAttemptId = null;
      _discardPreviousSessionContext();
      _invalidateSessionRefresher(changedEpoch);
      _state = const Unauthenticated(
        errorMessage: 'Senha alterada. Entre novamente com sua nova senha.',
        errorCode: 'PASSWORD_CHANGED',
      );
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      if (e.isUnauthorized && e.errorCode != 'INVALID_CREDENTIALS') {
        if (e.errorCode?.trim().toUpperCase() == 'ACCOUNT_DISABLED') {
          await _handleAccountDisabled();
        } else if (epochAtStart == _sessionEpoch && _inFlightDisabledExpiry == null) {
          _sessionEpoch++;
          final expiredEpoch = _sessionEpoch;
          _invalidateSessionRefresher(expiredEpoch);
          await _authRepository.logout();
          if (epochAtStart + 1 == _sessionEpoch && _inFlightDisabledExpiry == null) {
            _expireSession();
          }
        }
        rethrow;
      } else if (epochAtStart != _sessionEpoch || _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
      } else if (currentUser != null) {
        _state = currentUser;
        notifyListeners();
      } else {
        _state = Unauthenticated(
          errorMessage: e.detail,
          errorCode: e.errorCode,
          retryAfterSeconds: e.retryAfterSeconds,
        );
        notifyListeners();
      }
      rethrow;
    } on NetworkException catch (e) {
      if (epochAtStart != _sessionEpoch || _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
      } else if (currentUser != null) {
        _state = currentUser;
        notifyListeners();
      } else {
        _state = Unauthenticated(errorMessage: e.message);
        notifyListeners();
      }
      rethrow;
    } catch (_) {
      if (epochAtStart != _sessionEpoch || _inFlightDisabledExpiry != null) {
        await _inFlightDisabledExpiry;
      } else if (currentUser != null) {
        _state = currentUser;
        notifyListeners();
      } else {
        _state = const Unauthenticated(
          errorMessage: 'Ocorreu um erro inesperado ao alterar a senha.',
        );
        notifyListeners();
      }
      rethrow;
    }
  }

  /// Encerra a sessão atual local e remotamente.
  Future<void> logout() async {
    _sessionEpoch++;
    final logoutEpoch = _sessionEpoch;
    _activeUiAttemptId = null;
    _discardPreviousSessionContext();
    _effectiveTokenCoordinator?.invalidateSession();
    _invalidateSessionRefresher(logoutEpoch);
    _state = const Authenticating(statusMessage: 'Encerrando sessão...');
    notifyListeners();

    await _authRepository.logout();
    if (_sessionEpoch == logoutEpoch) {
      _state = const Unauthenticated();
      notifyListeners();
    }
  }

  /// Invocado quando o cliente HTTP recebe 401 numa requisição autenticada.
  ///
  /// Quando `details?.errorCode == 'ACCOUNT_DISABLED'`, encerra a sessão globalmente de imediato sem acionar
  /// [SessionRefresher.refresh()], invalida qualquer renovação em curso e preserva `ACCOUNT_DISABLED` com
  /// mensagem neutra e sem reativação social (`socialProvider == null`).
  ///
  /// Para `AUTHENTICATION_REQUIRED` ou 401 genérico com [SessionRefresher], a sessão só termina se o servidor
  /// recusar o refresh token. Ao terminar, os tokens locais são apagados e o estado vira [Unauthenticated] com
  /// `SESSION_EXPIRED`, que leva o app de volta à tela inicial.
  Future<void> handleSessionExpired([SessionExpirationDetails? details]) async {
    final normalizedCode = details?.errorCode?.trim().toUpperCase();
    if (normalizedCode == 'ACCOUNT_DISABLED') {
      return _handleAccountDisabled();
    }

    final refresher = _sessionRefresher;
    if (refresher == null) {
      final current = _state;
      if (current is Unauthenticated && current.errorCode == 'ACCOUNT_DISABLED') {
        return;
      }
      _sessionEpoch++;
      _expireSession();
      return;
    }
    if (_state is! Authenticated || _isHandlingExpiry || _inFlightDisabledExpiry != null) {
      return;
    }

    _isHandlingExpiry = true;
    final check = Completer<SessionRefreshOutcome>();
    _activeSessionCheckCompleter = check;
    _sessionCheckSequence++;
    _latestSessionCheck = check.future;
    final epochAtStart = _sessionEpoch;
    var outcome = SessionRefreshOutcome.unavailable;
    try {
      final result = await refresher.refresh();
      outcome = result.outcome;
      if (epochAtStart != _sessionEpoch ||
          _inFlightDisabledExpiry != null ||
          result.outcome != SessionRefreshOutcome.rejected ||
          _state is! Authenticated) {
        return;
      }
      final explicitCoordinator = _tokenCoordinator;
      final effectiveCoordinator = _effectiveTokenCoordinator;
      if (explicitCoordinator != null) {
        await explicitCoordinator.invalidateAndClear();
      } else if (_clearLocalSession != null) {
        effectiveCoordinator?.invalidateSession();
        await _clearLocalSession();
      } else if (effectiveCoordinator != null) {
        await effectiveCoordinator.invalidateAndClear();
      }
      if (epochAtStart != _sessionEpoch ||
          _inFlightDisabledExpiry != null ||
          _state is! Authenticated) {
        return;
      }
      _expireSession();
    } finally {
      if (identical(_activeSessionCheckCompleter, check)) {
        _isHandlingExpiry = false;
        _activeSessionCheckCompleter = null;
      }
      // Só conclui depois de o estado da sessão refletir o resultado
      if (!check.isCompleted) {
        check.complete(outcome);
      }
    }
  }

  Future<void> _handleAccountDisabled() {
    final inFlight = _inFlightDisabledExpiry;
    if (inFlight != null) return inFlight;

    if (_isInAccountDisabledState) {
      return Future<void>.value();
    }

    final completer = Completer<void>();
    _inFlightDisabledExpiry = completer.future;

    final check = Completer<SessionRefreshOutcome>();
    final previousCheck = _activeSessionCheckCompleter;
    _activeSessionCheckCompleter = check;

    _sessionEpoch++;
    final disabledEpoch = _sessionEpoch;
    _activeUiAttemptId = null;
    _discardPreviousSessionContext();
    _sessionCheckSequence++;
    _latestSessionCheck = check.future;
    _isHandlingExpiry = false;

    final explicitTokenCoordinator = _tokenCoordinator;
    final coordinatorToInvalidate = _effectiveTokenCoordinator;
    final invalidatedTokenGeneration =
        coordinatorToInvalidate?.invalidateSession();
    _invalidateSessionRefresher(disabledEpoch);

    () async {
      try {
        if (explicitTokenCoordinator != null &&
            invalidatedTokenGeneration != null) {
          await explicitTokenCoordinator
              .clearTokensForGeneration(invalidatedTokenGeneration);
        } else if (_clearLocalSession != null) {
          await _clearLocalSession();
        } else if (coordinatorToInvalidate != null &&
            invalidatedTokenGeneration != null) {
          await coordinatorToInvalidate
              .clearTokensForGeneration(invalidatedTokenGeneration);
        }
        if (_sessionEpoch == disabledEpoch) {
          _state = const Unauthenticated(
            errorMessage: accountDisabledMessage,
            errorCode: 'ACCOUNT_DISABLED',
          );
          notifyListeners();
        }
      } finally {
        if (identical(_inFlightDisabledExpiry, completer.future)) {
          _inFlightDisabledExpiry = null;
        }
        if (previousCheck != null && !previousCheck.isCompleted) {
          previousCheck.complete(SessionRefreshOutcome.rejected);
        }
        if (!check.isCompleted) {
          check.complete(SessionRefreshOutcome.rejected);
        }
        if (identical(_activeSessionCheckCompleter, check)) {
          _activeSessionCheckCompleter = null;
        }
        if (!completer.isCompleted) {
          completer.complete();
        }
      }
    }();

    return completer.future;
  }

  /// Quantas verificações de sessão [handleSessionExpired] já iniciou. Capturado antes de uma requisição, permite
  /// a [sessionCheckSince] associar o 401 dela à verificação que ele disparou.
  int get sessionCheckSequence => _sessionCheckSequence;

  /// Resultado da verificação de sessão iniciada depois de [sequence], ou da que ainda está em curso (um 401
  /// concorrente aproveita a mesma renovação ou resolução de conta desativada). Nulo quando nenhuma corresponde.
  Future<SessionRefreshOutcome>? sessionCheckSince(int sequence) {
    if (_sessionCheckSequence > sequence ||
        _isHandlingExpiry ||
        _inFlightDisabledExpiry != null) {
      return _latestSessionCheck;
    }
    return null;
  }

  void _expireSession() {
    _activeUiAttemptId = null;
    _discardPreviousSessionContext();
    _state = const Unauthenticated(
      errorMessage: 'Sua sessão expirou. Faça login novamente.',
      errorCode: 'SESSION_EXPIRED',
    );
    notifyListeners();
  }

  /// Atualiza os dados de perfil do usuário autenticado após edição bem-sucedida.
  ///
  /// [clearAvatarUrl] remove a foto da sessão (o perfil não tem mais avatar); sem ele, um [avatarUrl] nulo mantém
  /// a foto atual.
  void updateCurrentUser({
    String? handle,
    String? displayName,
    bool? isAnonymousDefault,
    String? avatarUrl,
    bool clearAvatarUrl = false,
  }) {
    if (_state is Authenticated) {
      final current = _state as Authenticated;
      _state = Authenticated(
        user: current.user.copyWith(
          handle: handle,
          displayName: displayName,
          isAnonymousDefault: isAnonymousDefault,
          avatarUrl: AuthUserDto.normalizeAvatarUrl(avatarUrl),
          clearAvatarUrl: clearAvatarUrl,
        ),
        tokens: current.tokens,
      );
      notifyListeners();
    }
  }
}
