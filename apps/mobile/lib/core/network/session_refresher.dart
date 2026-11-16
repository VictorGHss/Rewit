import 'dart:async';

import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/storage/session_token_coordinator.dart';

/// Resultado de uma tentativa de renovar a sessão com o refresh token.
enum SessionRefreshOutcome {
  /// Novos tokens emitidos e salvos; [SessionRefreshResult.accessToken] traz o access token novo.
  refreshed,

  /// O servidor recusou o refresh token (expirado, revogado ou ausente): a sessão acabou de fato.
  rejected,

  /// Não foi possível consultar o servidor (rede, timeout, 5xx ou 429). A sessão não deve ser descartada.
  unavailable,
}

class SessionRefreshResult {
  final SessionRefreshOutcome outcome;
  final String? accessToken;

  const SessionRefreshResult._(this.outcome, [this.accessToken]);

  const SessionRefreshResult.refreshed(String accessToken)
      : this._(SessionRefreshOutcome.refreshed, accessToken);
  const SessionRefreshResult.rejected() : this._(SessionRefreshOutcome.rejected);
  const SessionRefreshResult.unavailable() : this._(SessionRefreshOutcome.unavailable);
}

/// Credenciais obtidas de um endpoint de refresh antes da persistência local.
class RefreshedSessionCredentials {
  final String accessToken;
  final String refreshToken;

  const RefreshedSessionCredentials({
    required this.accessToken,
    required this.refreshToken,
  });
}

/// Renova o access token com uma única chamada por vez.
///
/// O backend rotaciona o refresh token e trata o reuso como incidente. Requisições que recebem 401 ao mesmo
/// tempo, a verificação da sessão e o tratamento de sessão expirada precisam compartilhar a mesma renovação,
/// em vez de cada um enviar o mesmo refresh token.
class SessionRefresher {
  final Future<String> Function()? _refreshAccessToken;
  final Future<RefreshedSessionCredentials> Function()? _requestRefresh;
  final SessionTokenCoordinator? _tokenCoordinator;
  Future<SessionRefreshResult>? _inFlight;
  int _generation = 0;
  int? _activeGeneration;
  final Map<int, List<Future<void> Function()>> _staleCleanupCallbacks = {};

  SessionRefresher({
    Future<String> Function()? refreshAccessToken,
    Future<RefreshedSessionCredentials> Function()? requestRefresh,
    SessionTokenCoordinator? tokenCoordinator,
  })  : assert(
          refreshAccessToken != null ||
              (requestRefresh != null && tokenCoordinator != null),
          'Informe refreshAccessToken ou (requestRefresh + tokenCoordinator).',
        ),
        _refreshAccessToken = refreshAccessToken,
        _requestRefresh = requestRefresh,
        _tokenCoordinator = tokenCoordinator;

  /// Invalida qualquer renovação atualmente em curso ou anterior.
  ///
  /// No modo coordenado com [SessionTokenCoordinator], o chamador também
  /// invalida a geração do coordenador para impedir que uma resposta HTTP
  /// tardia persista tokens sobre uma sessão posterior ou já encerrada.
  /// Quando [onStaleRefreshSettled] é fornecido no modo legado, executa o
  /// callback ao término da chamada obsoleta em curso.
  void invalidate({Future<void> Function()? onStaleRefreshSettled}) {
    _generation++;
    final activeGen = _activeGeneration;
    _inFlight = null;
    _activeGeneration = null;
    if (activeGen != null && activeGen < _generation && onStaleRefreshSettled != null) {
      (_staleCleanupCallbacks[activeGen] ??= <Future<void> Function()>[])
          .add(onStaleRefreshSettled);
    }
  }

  Future<SessionRefreshResult> refresh() {
    final existing = _inFlight;
    if (existing != null) return existing;

    final callGeneration = ++_generation;
    final tokenGenerationAtStart = _tokenCoordinator?.currentGeneration;
    _activeGeneration = callGeneration;
    late final Future<SessionRefreshResult> wrapped;
    wrapped = _run(callGeneration, tokenGenerationAtStart).whenComplete(() {
      if (identical(_inFlight, wrapped)) {
        _inFlight = null;
      }
      if (_activeGeneration == callGeneration) {
        _activeGeneration = null;
      }
      _staleCleanupCallbacks.remove(callGeneration);
    });
    _inFlight = wrapped;
    return wrapped;
  }

  Future<SessionRefreshResult> _run(
    int callGeneration,
    int? tokenGenerationAtStart,
  ) async {
    try {
      final requestRefresh = _requestRefresh;
      final coordinator = _tokenCoordinator;
      if (requestRefresh != null && coordinator != null) {
        final credentials = await requestRefresh();
        if (callGeneration != _generation ||
            tokenGenerationAtStart != coordinator.currentGeneration) {
          await _runStaleCleanup(callGeneration);
          return const SessionRefreshResult.rejected();
        }
        if (credentials.accessToken.isEmpty ||
            credentials.refreshToken.isEmpty) {
          return const SessionRefreshResult.rejected();
        }
        final committed = await coordinator.saveRefreshedTokens(
          accessToken: credentials.accessToken,
          refreshToken: credentials.refreshToken,
          expectedGeneration: tokenGenerationAtStart!,
        );
        if (!committed || callGeneration != _generation) {
          await _runStaleCleanup(callGeneration);
          return const SessionRefreshResult.rejected();
        }
        return SessionRefreshResult.refreshed(credentials.accessToken);
      }

      final accessToken = await _refreshAccessToken!();
      if (callGeneration != _generation) {
        await _runStaleCleanup(callGeneration);
        return const SessionRefreshResult.rejected();
      }
      if (accessToken.isEmpty) {
        return const SessionRefreshResult.rejected();
      }
      return SessionRefreshResult.refreshed(accessToken);
    } on ApiException catch (e) {
      if (callGeneration != _generation) {
        await _runStaleCleanup(callGeneration);
        return const SessionRefreshResult.rejected();
      }
      // Falha do servidor ou limite de taxa não provam que o refresh token deixou de valer
      if (e.statusCode >= 500 || e.statusCode == 429) {
        return const SessionRefreshResult.unavailable();
      }
      return const SessionRefreshResult.rejected();
    } on NetworkException {
      if (callGeneration != _generation) {
        await _runStaleCleanup(callGeneration);
        return const SessionRefreshResult.rejected();
      }
      return const SessionRefreshResult.unavailable();
    } catch (_) {
      if (callGeneration != _generation) {
        await _runStaleCleanup(callGeneration);
        return const SessionRefreshResult.rejected();
      }
      return const SessionRefreshResult.unavailable();
    }
  }

  Future<void> _runStaleCleanup(int callGeneration) async {
    final callbacks = _staleCleanupCallbacks.remove(callGeneration);
    if (callbacks == null || callbacks.isEmpty) return;
    for (final callback in callbacks) {
      try {
        await callback();
      } catch (_) {
        // Melhor esforço para garantir limpeza sem quebrar o retorno rejected.
      }
    }
  }
}
