import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';

/// Cliente HTTP centralizado para a API Rewit.
///
/// Encapsula:
/// - Base URL e timeouts configuráveis;
/// - Headers padrão (`Accept`, `Content-Type`);
/// - Injeção automática de Bearer Token via [TokenStorage];
/// - Parsing estruturado de erros RFC 7807 ([ProblemDetail]);
/// - Extração de cabeçalho `Retry-After` em 429;
/// - Mapeamento para [ApiException] e [NetworkException];
/// - Segurança estrita: nunca faz log de senhas, tokens ou dados sensíveis.
class RewitHttpClient {
  final String baseUrl;
  final Duration timeout;
  final http.Client _client;
  final TokenStorage? _tokenStorage;
  final void Function()? onSessionExpired;

  RewitHttpClient({
    required this.baseUrl,
    this.timeout = const Duration(seconds: 15),
    http.Client? client,
    TokenStorage? tokenStorage,
    this.onSessionExpired,
  })  : _client = client ?? http.Client(),
        _tokenStorage = tokenStorage;

  /// Fecha o cliente HTTP subjacente.
  void close() {
    _client.close();
  }

  /// Executa uma requisição GET.
  Future<http.Response> get(
    String path, {
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) {
    return _send(
      method: 'GET',
      path: path,
      headers: headers,
      queryParameters: queryParameters,
      requiresAuth: requiresAuth,
    );
  }

  /// Executa uma requisição POST.
  Future<http.Response> post(
    String path, {
    Object? body,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) {
    return _send(
      method: 'POST',
      path: path,
      body: body,
      headers: headers,
      queryParameters: queryParameters,
      requiresAuth: requiresAuth,
    );
  }

  /// Executa uma requisição PUT.
  Future<http.Response> put(
    String path, {
    Object? body,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) {
    return _send(
      method: 'PUT',
      path: path,
      body: body,
      headers: headers,
      queryParameters: queryParameters,
      requiresAuth: requiresAuth,
    );
  }

  /// Executa uma requisição PATCH.
  Future<http.Response> patch(
    String path, {
    Object? body,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) {
    return _send(
      method: 'PATCH',
      path: path,
      body: body,
      headers: headers,
      queryParameters: queryParameters,
      requiresAuth: requiresAuth,
    );
  }

  /// Executa uma requisição DELETE.
  Future<http.Response> delete(
    String path, {
    Object? body,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) {
    return _send(
      method: 'DELETE',
      path: path,
      body: body,
      headers: headers,
      queryParameters: queryParameters,
      requiresAuth: requiresAuth,
    );
  }

  Future<http.Response> _send({
    required String method,
    required String path,
    Object? body,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) async {
    final uri = _buildUri(path, queryParameters);
    final mergedHeaders = await _buildHeaders(headers, requiresAuth: requiresAuth);

    String? encodedBody;
    if (body != null) {
      if (body is String) {
        encodedBody = body;
      } else {
        encodedBody = jsonEncode(body);
      }
    }

    try {
      http.Response response;
      switch (method.toUpperCase()) {
        case 'GET':
          response = await _client.get(uri, headers: mergedHeaders).timeout(timeout);
          break;
        case 'POST':
          response = await _client.post(uri, headers: mergedHeaders, body: encodedBody).timeout(timeout);
          break;
        case 'PUT':
          response = await _client.put(uri, headers: mergedHeaders, body: encodedBody).timeout(timeout);
          break;
        case 'PATCH':
          response = await _client.patch(uri, headers: mergedHeaders, body: encodedBody).timeout(timeout);
          break;
        case 'DELETE':
          response = await _client.delete(uri, headers: mergedHeaders, body: encodedBody).timeout(timeout);
          break;
        default:
          throw UnsupportedError('Método HTTP $method não suportado.');
      }

      return _handleResponse(response);
    } on TimeoutException {
      throw const NetworkException('Tempo limite de conexão esgotado (timeout).');
    } on http.ClientException catch (e) {
      throw NetworkException('Falha de conexão com o servidor.', e);
    } on ApiException {
      rethrow;
    } on NetworkException {
      rethrow;
    } catch (e) {
      throw NetworkException('Erro inesperado na comunicação de rede.', e);
    }
  }

  Uri _buildUri(String path, Map<String, dynamic>? queryParameters) {
    final cleanBase = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
    final cleanPath = path.startsWith('/') ? path : '/$path';
    final fullUrl = '$cleanBase$cleanPath';

    final uri = Uri.parse(fullUrl);
    if (queryParameters != null && queryParameters.isNotEmpty) {
      final sanitizedParams = queryParameters.map(
        (key, value) => MapEntry(key, value?.toString() ?? ''),
      );
      return uri.replace(queryParameters: sanitizedParams);
    }
    return uri;
  }

  Future<Map<String, String>> _buildHeaders(
    Map<String, String>? customHeaders, {
    required bool requiresAuth,
  }) async {
    final headers = <String, String>{
      'Accept': 'application/json',
      'Content-Type': 'application/json; charset=UTF-8',
    };

    if (requiresAuth && _tokenStorage != null) {
      final token = await _tokenStorage.getAccessToken();
      if (token != null && token.isNotEmpty) {
        headers['Authorization'] = 'Bearer $token';
      }
    }

    if (customHeaders != null) {
      headers.addAll(customHeaders);
    }

    return headers;
  }

  http.Response _handleResponse(http.Response response) {
    final statusCode = response.statusCode;

    // Respostas de sucesso (2xx)
    if (statusCode >= 200 && statusCode < 300) {
      return response;
    }

    // Extrair cabeçalho Retry-After se presente (relevante em 429)
    int? retryAfterSeconds;
    final retryAfterHeader = response.headers['retry-after'];
    if (retryAfterHeader != null) {
      retryAfterSeconds = int.tryParse(retryAfterHeader.trim());
    }

    // Parsing estruturado de erro RFC 7807
    final problem = ProblemDetail.fromResponseBody(response.body, statusCode);

    // Se a sessão expirou / 401 não autorizado
    if (statusCode == 401) {
      onSessionExpired?.call();
    }

    throw ApiException(
      problem,
      retryAfterSeconds: retryAfterSeconds,
    );
  }
}
