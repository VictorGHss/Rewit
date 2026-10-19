import 'dart:async';

import 'package:rewit_mobile/core/error/api_exception.dart';

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

/// Renova o access token com uma única chamada por vez.
///
/// O backend rotaciona o refresh token e trata o reuso como incidente. Requisições que recebem 401 ao mesmo
/// tempo, a verificação da sessão e o tratamento de sessão expirada precisam compartilhar a mesma renovação,
/// em vez de cada um enviar o mesmo refresh token.
class SessionRefresher {
  final Future<String> Function() _refreshAccessToken;
  Future<SessionRefreshResult>? _inFlight;

  SessionRefresher({required Future<String> Function() refreshAccessToken})
      : _refreshAccessToken = refreshAccessToken;

  Future<SessionRefreshResult> refresh() {
    return _inFlight ??= _run().whenComplete(() => _inFlight = null);
  }

  Future<SessionRefreshResult> _run() async {
    try {
      final accessToken = await _refreshAccessToken();
      if (accessToken.isEmpty) {
        return const SessionRefreshResult.rejected();
      }
      return SessionRefreshResult.refreshed(accessToken);
    } on ApiException catch (e) {
      // Falha do servidor ou limite de taxa não provam que o refresh token deixou de valer
      if (e.statusCode >= 500 || e.statusCode == 429) {
        return const SessionRefreshResult.unavailable();
      }
      return const SessionRefreshResult.rejected();
    } on NetworkException {
      return const SessionRefreshResult.unavailable();
    } catch (_) {
      return const SessionRefreshResult.unavailable();
    }
  }
}
