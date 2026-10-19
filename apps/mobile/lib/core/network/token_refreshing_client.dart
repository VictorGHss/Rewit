import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/network/session_refresher.dart';

/// Transporte que renova o access token expirado e repete a requisição uma vez.
///
/// O access token dura 15 minutos. Sem renovação, a primeira chamada autenticada depois disso recebia 401 e o
/// app encerrava a sessão, embora o refresh token ainda valesse. Aqui, um 401 com o código
/// [tokenRejectedCode] (emitido pelo backend quando o JWT é ausente, expirado ou inválido) aciona a renovação
/// e a mesma requisição é reenviada com o token novo.
///
/// Garantias:
/// - só age em requisições que já levavam `Authorization: Bearer`: quem decide se o token pode ir a um destino
///   continua sendo o [RewitHttpClient], e a repetição vai à mesma URL;
/// - 401 de negócio (credenciais inválidas, conta desativada) não renova nada;
/// - recusa do refresh token devolve o 401 original, e o app encerra a sessão;
/// - falha de rede na renovação vira erro de conexão, sem descartar uma sessão que pode continuar válida;
/// - só [http.Request] é repetida, porque o corpo fica em memória; upload multipart segue sem repetição.
class TokenRefreshingClient extends http.BaseClient {
  static const String tokenRejectedCode = 'AUTHENTICATION_REQUIRED';

  final http.Client _inner;
  final SessionRefresher _sessionRefresher;

  TokenRefreshingClient({
    required http.Client inner,
    required SessionRefresher sessionRefresher,
  })  : _inner = inner,
        _sessionRefresher = sessionRefresher;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    final authorization = request.headers['authorization'];
    if (request is! http.Request || authorization == null || !authorization.startsWith('Bearer ')) {
      return _inner.send(request);
    }

    // A cópia precisa ser feita antes do envio: depois dele a requisição original fica finalizada
    final replay = _copy(request);
    final response = await _inner.send(request);
    if (response.statusCode != 401) {
      return response;
    }

    final body = await response.stream.toBytes();
    final original = _rebuild(response, body);
    if (_problemCode(body) != tokenRejectedCode) {
      return original;
    }

    final result = await _sessionRefresher.refresh();
    switch (result.outcome) {
      case SessionRefreshOutcome.refreshed:
        replay.headers['authorization'] = 'Bearer ${result.accessToken}';
        return _inner.send(replay);
      case SessionRefreshOutcome.rejected:
        return original;
      case SessionRefreshOutcome.unavailable:
        throw http.ClientException('Não foi possível renovar a sessão agora.', request.url);
    }
  }

  @override
  void close() => _inner.close();

  static http.Request _copy(http.Request request) {
    final copy = request is http.Abortable
        ? http.AbortableRequest(request.method, request.url,
            abortTrigger: (request as http.Abortable).abortTrigger)
        : http.Request(request.method, request.url);
    copy
      ..headers.addAll(request.headers)
      ..followRedirects = request.followRedirects
      ..maxRedirects = request.maxRedirects
      ..persistentConnection = request.persistentConnection
      ..encoding = request.encoding
      ..bodyBytes = request.bodyBytes;
    return copy;
  }

  static http.StreamedResponse _rebuild(http.StreamedResponse response, List<int> body) {
    return http.StreamedResponse(
      Stream.value(body),
      response.statusCode,
      contentLength: body.length,
      request: response.request,
      headers: response.headers,
      isRedirect: response.isRedirect,
      persistentConnection: response.persistentConnection,
      reasonPhrase: response.reasonPhrase,
    );
  }

  static String? _problemCode(List<int> body) {
    if (body.isEmpty) return null;
    try {
      final decoded = jsonDecode(utf8.decode(body));
      if (decoded is Map<String, dynamic>) {
        final code = decoded['code'];
        return code is String ? code : null;
      }
    } catch (_) {
      // Corpo que não é ProblemDetail: não é a recusa de token do backend
    }
    return null;
  }
}
