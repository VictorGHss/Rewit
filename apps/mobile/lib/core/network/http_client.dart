import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
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
    bool treatInvalidCredentialsAsBusinessError = false,
  }) {
    return _send(
      method: 'POST',
      path: path,
      body: body,
      headers: headers,
      queryParameters: queryParameters,
      requiresAuth: requiresAuth,
      treatInvalidCredentialsAsBusinessError: treatInvalidCredentialsAsBusinessError,
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

  static const int _maxRedirects = 5;
  static const Set<int> _redirectStatusCodes = {301, 302, 303, 307, 308};

  /// Verifica se [pathOrUrl] resolve para a mesma origem (`scheme`, `host` e `port` efetiva)
  /// configurada em [baseUrl].
  bool isSameOriginUrl(String pathOrUrl) {
    try {
      final targetUri = _buildUri(pathOrUrl, null);
      return isSameOriginUri(targetUri);
    } on NetworkException {
      return false;
    } on FormatException {
      return false;
    }
  }

  /// Verifica se [targetUri] possui exatamente a mesma origem (`scheme`, `host` e `port` efetiva)
  /// da [baseUrl] configurada na instância.
  bool isSameOriginUri(Uri targetUri) {
    final baseUri = Uri.tryParse(baseUrl.trim());
    if (baseUri == null || !_isValidHttpOriginUri(baseUri)) {
      return false;
    }
    if (!_isValidHttpOriginUri(targetUri)) {
      return false;
    }

    return baseUri.scheme.toLowerCase() == targetUri.scheme.toLowerCase() &&
        baseUri.host.toLowerCase() == targetUri.host.toLowerCase() &&
        _effectivePort(baseUri) == _effectivePort(targetUri);
  }

  /// Executa uma requisição POST multipart (para upload de arquivos binários).
  Future<http.Response> postMultipart(
    String path, {
    required String fieldName,
    required List<int> fileBytes,
    required String filename,
    String? mimeType,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) async {
    final uri = _buildUri(path, queryParameters);
    final isSameOrigin = isSameOriginUri(uri);

    if (requiresAuth && !isSameOrigin) {
      throw const NetworkException(
        'Autenticação bloqueada para destino fora da origem da API.',
      );
    }
    if (!isSameOrigin && _containsAuthorizationHeader(headers)) {
      throw const NetworkException(
        'Cabeçalho Authorization não permitido para destino fora da origem da API.',
      );
    }

    final request = http.MultipartRequest('POST', uri)
      ..followRedirects = false;

    if (requiresAuth && isSameOrigin && _tokenStorage != null) {
      final token = await _tokenStorage.getAccessToken();
      if (token != null && token.isNotEmpty) {
        request.headers['Authorization'] = 'Bearer $token';
      }
    }
    request.headers['Accept'] = 'application/json';
    if (headers != null) {
      request.headers.addAll(headers);
    }
    if (!isSameOrigin) {
      _stripAuthorizationHeaders(request.headers);
    }

    MediaType? contentType;
    if (mimeType != null && mimeType.contains('/')) {
      final parts = mimeType.split('/');
      contentType = MediaType(parts[0], parts[1]);
    }

    request.files.add(
      http.MultipartFile.fromBytes(
        fieldName,
        fileBytes,
        filename: filename,
        contentType: contentType,
      ),
    );

    try {
      final streamedResponse = await _client.send(request).timeout(timeout);
      final response = await http.Response.fromStream(streamedResponse);
      return _handleResponse(
        response,
        requiresAuth: requiresAuth && isSameOrigin,
      );
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

  /// Recupera os bytes brutos de um recurso (ex: download de imagem sanitizada ou pública).
  ///
  /// Quando [requiresAuth] não é informado, aplica autenticação apenas para destinos
  /// pertencentes à mesma origem de [baseUrl], mantendo downloads externos públicos
  /// sem envio de token. Se [requiresAuth] for explicitamente `true` para uma origem
  /// externa, a chamada falha explicitamente com [NetworkException].
  Future<Uint8List> getBytes(
    String path, {
    Map<String, String>? headers,
    bool? requiresAuth,
  }) async {
    final uri = _buildUri(path, null);
    final effectiveRequiresAuth = requiresAuth ?? isSameOriginUri(uri);
    final response = await get(
      path,
      headers: headers,
      requiresAuth: effectiveRequiresAuth,
    );
    return response.bodyBytes;
  }

  Future<http.Response> _send({
    required String method,
    required String path,
    Object? body,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
    bool treatInvalidCredentialsAsBusinessError = false,
  }) async {
    final initialUri = _buildUri(path, queryParameters);
    final initialHeaders = await _buildHeaders(
      headers,
      targetUri: initialUri,
      requiresAuth: requiresAuth,
    );

    String? encodedBody;
    if (body != null) {
      if (body is String) {
        encodedBody = body;
      } else {
        encodedBody = jsonEncode(body);
      }
    }

    final normalizedMethod = method.toUpperCase();
    if (!_isSupportedMethod(normalizedMethod)) {
      throw UnsupportedError('Método HTTP $method não suportado.');
    }

    try {
      var currentUri = initialUri;
      var currentMethod = normalizedMethod;
      var currentBody = encodedBody;
      final currentHeaders = Map<String, String>.from(initialHeaders);
      var allowAuthOnCurrentHop = requiresAuth && isSameOriginUri(currentUri);
      var redirectsCount = 0;

      while (true) {
        if (!allowAuthOnCurrentHop || !isSameOriginUri(currentUri)) {
          allowAuthOnCurrentHop = false;
          _stripAuthorizationHeaders(currentHeaders);
        }

        final request = http.Request(currentMethod, currentUri)
          ..followRedirects = false;
        request.headers.addAll(currentHeaders);
        if (currentBody != null) {
          request.body = currentBody;
        }

        final streamedResponse = await _client.send(request).timeout(timeout);
        final response = await http.Response.fromStream(streamedResponse);

        if (!_redirectStatusCodes.contains(response.statusCode)) {
          return _handleResponse(
            response,
            requiresAuth: allowAuthOnCurrentHop,
            treatInvalidCredentialsAsBusinessError: treatInvalidCredentialsAsBusinessError,
          );
        }

        final locationHeader = _extractHeaderCaseInsensitive(response.headers, 'location');
        if (locationHeader == null || locationHeader.trim().isEmpty) {
          return _handleResponse(
            response,
            requiresAuth: allowAuthOnCurrentHop,
            treatInvalidCredentialsAsBusinessError: treatInvalidCredentialsAsBusinessError,
          );
        }

        redirectsCount++;
        if (redirectsCount > _maxRedirects) {
          throw const NetworkException('Número máximo de redirecionamentos HTTP excedido.');
        }

        final nextUri = _resolveRedirectUri(currentUri, locationHeader.trim());
        final nextIsSameOrigin = isSameOriginUri(nextUri);

        final convertsToGet = response.statusCode == 301 ||
            response.statusCode == 302 ||
            response.statusCode == 303;

        if (!nextIsSameOrigin && !convertsToGet && currentMethod != 'GET') {
          throw const NetworkException(
            'Redirecionamento de requisição não-GET para origem externa bloqueado.',
          );
        }

        if (convertsToGet && currentMethod != 'GET') {
          currentMethod = 'GET';
          currentBody = null;
        }

        if (!nextIsSameOrigin) {
          allowAuthOnCurrentHop = false;
          _stripAuthorizationHeaders(currentHeaders);
        }

        currentUri = nextUri;
      }
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

  bool _isSupportedMethod(String method) {
    switch (method) {
      case 'GET':
      case 'POST':
      case 'PUT':
      case 'PATCH':
      case 'DELETE':
        return true;
      default:
        return false;
    }
  }

  Uri _buildUri(String path, Map<String, dynamic>? queryParameters) {
    final trimmedPath = path.trim();

    if (trimmedPath.startsWith('//')) {
      final baseUri = Uri.tryParse(baseUrl.trim());
      final scheme = (baseUri != null && baseUri.hasScheme) ? baseUri.scheme.toLowerCase() : 'https';
      final uri = Uri.parse('$scheme:$trimmedPath');
      return _applyQueryParameters(uri, queryParameters);
    }

    final hasHttpScheme = RegExp(r'^https?://', caseSensitive: false).hasMatch(trimmedPath);
    if (hasHttpScheme) {
      final uri = Uri.parse(trimmedPath);
      return _applyQueryParameters(uri, queryParameters);
    }

    final hasOtherScheme = RegExp(r'^[a-zA-Z][a-zA-Z0-9+.-]*:').hasMatch(trimmedPath);
    if (hasOtherScheme) {
      throw const NetworkException('Esquema de URL não suportado.');
    }

    final cleanBase = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
    final cleanPath = trimmedPath.startsWith('/') ? trimmedPath : '/$trimmedPath';
    final fullUrl = '$cleanBase$cleanPath';

    final uri = Uri.parse(fullUrl);
    return _applyQueryParameters(uri, queryParameters);
  }

  Uri _applyQueryParameters(Uri uri, Map<String, dynamic>? queryParameters) {
    if (queryParameters != null && queryParameters.isNotEmpty) {
      final sanitizedParams = queryParameters.map(
        (key, value) => MapEntry(key, value?.toString() ?? ''),
      );
      return uri.replace(queryParameters: sanitizedParams);
    }
    return uri;
  }

  Uri _resolveRedirectUri(Uri currentUri, String location) {
    final resolved = currentUri.resolve(location);
    if (!_isValidHttpOriginUri(resolved)) {
      throw const NetworkException('Destino de redirecionamento HTTP inválido ou não suportado.');
    }
    return resolved;
  }

  bool _isValidHttpOriginUri(Uri uri) {
    if (!uri.hasScheme || !uri.hasAuthority || uri.host.isEmpty) {
      return false;
    }
    if (uri.userInfo.isNotEmpty) {
      return false;
    }
    final scheme = uri.scheme.toLowerCase();
    return scheme == 'http' || scheme == 'https';
  }

  int _effectivePort(Uri uri) {
    if (uri.hasPort) {
      return uri.port;
    }
    final scheme = uri.scheme.toLowerCase();
    if (scheme == 'https') {
      return 443;
    }
    if (scheme == 'http') {
      return 80;
    }
    return uri.port;
  }

  bool _containsAuthorizationHeader(Map<String, String>? headers) {
    if (headers == null || headers.isEmpty) {
      return false;
    }
    return headers.keys.any((key) => key.toLowerCase() == 'authorization');
  }

  void _stripAuthorizationHeaders(Map<String, String> headers) {
    headers.removeWhere((key, _) => key.toLowerCase() == 'authorization');
  }

  String? _extractHeaderCaseInsensitive(Map<String, String> headers, String targetKey) {
    final lowerTarget = targetKey.toLowerCase();
    for (final entry in headers.entries) {
      if (entry.key.toLowerCase() == lowerTarget) {
        return entry.value;
      }
    }
    return null;
  }

  Future<Map<String, String>> _buildHeaders(
    Map<String, String>? customHeaders, {
    required Uri targetUri,
    required bool requiresAuth,
  }) async {
    final isSameOrigin = isSameOriginUri(targetUri);

    if (requiresAuth && !isSameOrigin) {
      throw const NetworkException(
        'Autenticação bloqueada para destino fora da origem da API.',
      );
    }

    if (!isSameOrigin && _containsAuthorizationHeader(customHeaders)) {
      throw const NetworkException(
        'Cabeçalho Authorization não permitido para destino fora da origem da API.',
      );
    }

    final headers = <String, String>{
      'Accept': 'application/json',
      'Content-Type': 'application/json; charset=UTF-8',
    };

    if (requiresAuth && isSameOrigin && _tokenStorage != null) {
      final token = await _tokenStorage.getAccessToken();
      if (token != null && token.isNotEmpty) {
        headers['Authorization'] = 'Bearer $token';
      }
    }

    if (customHeaders != null) {
      headers.addAll(customHeaders);
    }

    if (!isSameOrigin) {
      _stripAuthorizationHeaders(headers);
    }

    return headers;
  }

  http.Response _handleResponse(
    http.Response response, {
    required bool requiresAuth,
    bool treatInvalidCredentialsAsBusinessError = false,
  }) {
    final statusCode = response.statusCode;

    // Respostas de sucesso (2xx)
    if (statusCode >= 200 && statusCode < 300) {
      return response;
    }

    // Extrair cabeçalho Retry-After se presente (relevante em 429)
    int? retryAfterSeconds;
    final retryAfterHeader = response.headers['retry-after'] ?? response.headers['Retry-After'];
    if (retryAfterHeader != null) {
      retryAfterSeconds = int.tryParse(retryAfterHeader.trim());
    }

    // Parsing estruturado de erro RFC 7807
    final problem = ProblemDetail.fromResponseBody(response.body, statusCode);

    // Se a sessão expirou em requisição autenticada (401 não autorizado)
    if (statusCode == 401 && requiresAuth) {
      final isLocalBusinessError = treatInvalidCredentialsAsBusinessError && problem.code == 'INVALID_CREDENTIALS';
      if (!isLocalBusinessError) {
        onSessionExpired?.call();
      }
    }

    throw ApiException(
      problem,
      retryAfterSeconds: retryAfterSeconds,
    );
  }
}
