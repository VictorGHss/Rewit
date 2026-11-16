import 'dart:async';

import 'package:rewit_mobile/core/storage/token_storage.dart';

/// Erro lançado quando uma tentativa de autenticação obsoleta tenta gravar
/// tokens após ter sido substituída por tentativa mais nova, logout ou `ACCOUNT_DISABLED`.
class StaleAuthenticationAttemptException implements Exception {
  final int attemptId;
  final String message;

  const StaleAuthenticationAttemptException({
    required this.attemptId,
    this.message =
        'Tentativa de autenticação obsoleta recusada antes ou durante a persistência.',
  });

  @override
  String toString() =>
      'StaleAuthenticationAttemptException(attemptId: $attemptId): $message';
}

/// Coordena geração de sessão, identidade das tentativas de autenticação e
/// serializa todas as mutações de [TokenStorage].
///
/// Garante que:
/// - tentativas antigas de login/cadastro/reativação não estabeleçam nem
///   sobrescrevam uma sessão mais recente (prevalece a intenção de autenticação mais recente);
/// - refresh de uma geração obsoleta não sobrescreva tokens de sessão mais nova;
/// - limpezas de sessão tardias não apaguem tokens recém-persistidos por outra sessão;
/// - gravações, compensações e limpezas ocorram estritamente em fila FIFO.
class SessionTokenCoordinator {
  SessionTokenCoordinator({required TokenStorage storage})
      : _storage = storage;

  final TokenStorage _storage;
  Future<void> _queue = Future<void>.value();
  int _sessionGeneration = 0;
  int _authenticationAttemptSequence = 0;
  int? _activeAttemptId;

  /// Geração atual conhecida para propriedade dos tokens persistidos.
  int get currentGeneration => _sessionGeneration;

  /// Sequência monotonicamente crescente das tentativas de autenticação já iniciadas.
  int get currentAuthenticationAttemptSequence => _authenticationAttemptSequence;

  /// Identidade da tentativa de autenticação atualmente proprietária, ou `null`.
  int? get activeAuthenticationAttemptId => _activeAttemptId;

  /// Reserva uma identidade exclusiva para uma nova tentativa de autenticação
  /// (login, cadastro, fluxo social ou reativação), invalidando tentativas
  /// anteriores sem alterar [_sessionGeneration] da eventual sessão vigente.
  int beginAuthenticationAttempt() {
    final attemptId = ++_authenticationAttemptSequence;
    _activeAttemptId = attemptId;
    return attemptId;
  }

  /// Libera a propriedade da tentativa apenas se [attemptId] ainda for a ativa.
  void abandonAuthenticationAttempt(int attemptId) {
    if (_activeAttemptId == attemptId) {
      _activeAttemptId = null;
    }
  }

  /// Informa se [attemptId] continua sendo a tentativa de autenticação vigente.
  bool isAuthenticationAttemptCurrent(int attemptId) =>
      _activeAttemptId == attemptId;

  /// Invalida imediatamente a geração de sessão corrente e qualquer tentativa
  /// de autenticação pendente, retornando o identificador para limpeza em fila.
  int invalidateSession() {
    _activeAttemptId = null;
    return ++_sessionGeneration;
  }

  /// Persiste tokens de uma nova sessão autenticada somente se [expectedAttemptId]
  /// (ou a tentativa reservada na chamada) ainda for a proprietária ativa ao
  /// entrar na fila FIFO e ao concluir a escrita em [TokenStorage].
  ///
  /// Se outra tentativa, logout ou `ACCOUNT_DISABLED` invalidar a propriedade
  /// durante uma escrita suspensa em `await`, executa compensação local
  /// (`clearTokens`) ainda dentro do slot FIFO antes da tentativa seguinte e
  /// lança [StaleAuthenticationAttemptException].
  Future<int> saveNewSessionTokens({
    required String accessToken,
    required String refreshToken,
    int? expectedAttemptId,
  }) {
    final resolvedAttemptId = expectedAttemptId ?? beginAuthenticationAttempt();
    return _enqueue(() async {
      if (_activeAttemptId != resolvedAttemptId) {
        throw StaleAuthenticationAttemptException(attemptId: resolvedAttemptId);
      }

      final assignedGeneration = ++_sessionGeneration;
      try {
        await _storage.saveTokens(
          accessToken: accessToken,
          refreshToken: refreshToken,
        );
      } catch (_) {
        if (_activeAttemptId == resolvedAttemptId) {
          _activeAttemptId = null;
          rethrow;
        }
        try {
          await _storage.clearTokens();
        } catch (_) {}
        throw StaleAuthenticationAttemptException(attemptId: resolvedAttemptId);
      }

      if (_activeAttemptId != resolvedAttemptId ||
          _sessionGeneration != assignedGeneration) {
        try {
          await _storage.clearTokens();
        } finally {
          if (_sessionGeneration == assignedGeneration) {
            ++_sessionGeneration;
          }
        }
        throw StaleAuthenticationAttemptException(attemptId: resolvedAttemptId);
      }

      _activeAttemptId = null;
      return assignedGeneration;
    });
  }

  /// Persiste tokens renovados apenas se [expectedGeneration] ainda corresponder
  /// à geração ativa no momento em que a escrita executa na fila serializada.
  ///
  /// Retorna `true` se os tokens foram gravados e `false` se a geração já foi
  /// invalidada por logout, encerramento de conta, `ACCOUNT_DISABLED` ou nova sessão.
  Future<bool> saveRefreshedTokens({
    required String accessToken,
    required String refreshToken,
    required int expectedGeneration,
  }) {
    return _enqueue(() async {
      if (expectedGeneration != _sessionGeneration) {
        return false;
      }
      await _storage.saveTokens(
        accessToken: accessToken,
        refreshToken: refreshToken,
      );
      return true;
    });
  }

  /// Limpa os tokens locais apenas se [expectedGeneration] ainda for a geração
  /// vigente quando a tarefa alcançar a cabeça da fila.
  Future<void> clearTokensForGeneration(int expectedGeneration) {
    return _enqueue(() async {
      if (expectedGeneration != _sessionGeneration) {
        return;
      }
      await _storage.clearTokens();
    });
  }

  /// Invalida a geração atual imediatamente e enfileira a limpeza dos tokens
  /// para essa geração.
  Future<void> invalidateAndClear() {
    final targetGeneration = invalidateSession();
    return clearTokensForGeneration(targetGeneration);
  }

  Future<T> _enqueue<T>(Future<T> Function() action) {
    final completer = Completer<T>();
    _queue = _queue.catchError((_) {}).then((_) async {
      try {
        final result = await action();
        if (!completer.isCompleted) {
          completer.complete(result);
        }
      } catch (error, stackTrace) {
        if (!completer.isCompleted) {
          completer.completeError(error, stackTrace);
        }
      }
    });
    return completer.future;
  }
}
