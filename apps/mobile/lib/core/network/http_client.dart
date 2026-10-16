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

  /// Limite máximo padrão para download de mídia e imagens (10 MB),
  /// derivado do contrato de mídia do Rewit ([ReviewMedia.MAX_FILE_SIZE_BYTES]).
  static const int defaultMaxImageSizeBytes = 10 * 1024 * 1024;

  /// Limite máximo de leitura para o corpo de respostas intermediárias de redirecionamento (16 KB),
  /// evitando acúmulo desnecessário de memória durante múltiplos saltos HTTP 3xx.
  static const int _maxRedirectResponseBodyBytes = 16 * 1024;

  /// Limite máximo de leitura para respostas de texto/JSON da API (15 MB),
  /// prevenindo ataques de esgotamento de memória por payloads excessivos.
  static const int defaultMaxResponseBodyBytes = 15 * 1024 * 1024;

  /// Cabeçalhos sensíveis que devem ser permanentemente removidos quando a requisição
  /// sai da origem configurada da API.
  static const Set<String> _sensitiveHeaderNames = {
    'authorization',
    'cookie',
    'proxy-authorization',
    'set-cookie',
    'x-csrf-token',
    'x-xsrf-token',
    'x-api-key',
  };

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
    final stopwatch = Stopwatch()..start();
    Duration getRemainingTimeout() {
      final remaining = timeout - stopwatch.elapsed;
      if (remaining <= Duration.zero) {
        throw TimeoutException('Tempo limite total da operação esgotado.', timeout);
      }
      return remaining;
    }

    final uri = _buildUri(path, queryParameters);
    final isSameOrigin = isSameOriginUri(uri);

    if (requiresAuth && !isSameOrigin) {
      throw const NetworkException(
        'Autenticação bloqueada para destino fora da origem da API.',
      );
    }
    if (!isSameOrigin && _containsSensitiveHeader(headers)) {
      throw const NetworkException(
        'Cabeçalhos sensíveis de autenticação não são permitidos para destino fora da origem da API.',
      );
    }

    final abortCompleter = Completer<void>();
    void triggerAbort() {
      if (!abortCompleter.isCompleted) {
        abortCompleter.complete();
      }
    }

    final request = http.AbortableMultipartRequest('POST', uri, abortTrigger: abortCompleter.future)
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
      _stripSensitiveHeaders(request.headers);
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

    Timer? abortTimer;
    try {
      final remainingSendTimeout = getRemainingTimeout();
      abortTimer = Timer(remainingSendTimeout, triggerAbort);

      final streamedResponse = await _client.send(request).timeout(remainingSendTimeout);
      abortTimer.cancel();
      abortTimer = null;

      // Janela A2: se o prazo esgotou após send() completar mas antes da leitura do corpo
      if (stopwatch.elapsed >= timeout) {
        triggerAbort();
        await _discardStream(streamedResponse.stream);
        throw TimeoutException('Tempo limite total da operação esgotado.', timeout);
      }

      final response = await _buildResponseFromStream(
        streamedResponse,
        remainingTimeout: getRemainingTimeout(),
        maxBytes: defaultMaxResponseBodyBytes,
        abortCompleter: abortCompleter,
      );
      return _handleResponse(
        response,
        requiresAuth: requiresAuth && isSameOrigin,
      );
    } on TimeoutException {
      triggerAbort();
      throw const NetworkException('Tempo limite de conexão esgotado (timeout).');
    } on http.RequestAbortedException {
      throw const NetworkException('Tempo limite de conexão esgotado (timeout).');
    } on http.ClientException catch (e) {
      if (e.message.toLowerCase().contains('abort') || e is http.RequestAbortedException) {
        throw NetworkException('Tempo limite de conexão esgotado (timeout).', e);
      }
      throw NetworkException('Falha de conexão com o servidor.', e);
    } on ApiException {
      rethrow;
    } on NetworkException {
      rethrow;
    } catch (e) {
      throw NetworkException('Erro inesperado na comunicação de rede.', e);
    } finally {
      abortTimer?.cancel();
    }
  }

  /// Recupera os bytes brutos de um recurso (ex: download de imagem sanitizada ou pública).
  ///
  /// Aplica limite estrito de bytes [maxBytes] (padrão de 10 MB, alinhado ao contrato
  /// do Rewit), interrompendo imediatamente o consumo de rede caso a resposta exceda o teto.
  ///
  /// Quando [requiresAuth] não é informado, aplica autenticação apenas para destinos
  /// pertencentes à mesma origem de [baseUrl], mantendo downloads externos públicos
  /// sem envio de token. Se [requiresAuth] for explicitamente `true` para uma origem
  /// externa, a chamada falha explicitamente com [NetworkException].
  Future<Uint8List> getBytes(
    String path, {
    Map<String, String>? headers,
    bool? requiresAuth,
    int maxBytes = defaultMaxImageSizeBytes,
  }) async {
    final uri = _buildUri(path, null);
    final effectiveRequiresAuth = requiresAuth ?? isSameOriginUri(uri);
    final response = await _send(
      method: 'GET',
      path: path,
      headers: headers,
      requiresAuth: effectiveRequiresAuth,
      maxResponseBodyBytes: maxBytes,
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
    int? maxResponseBodyBytes,
  }) async {
    final stopwatch = Stopwatch()..start();
    Duration getRemainingTimeout() {
      final remaining = timeout - stopwatch.elapsed;
      if (remaining <= Duration.zero) {
        throw TimeoutException('Tempo limite total da operação esgotado.', timeout);
      }
      return remaining;
    }

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
      final currentMethod = normalizedMethod;
      final currentBody = encodedBody;
      final currentHeaders = Map<String, String>.from(initialHeaders);
      var allowAuthOnCurrentHop = requiresAuth && isSameOriginUri(currentUri);
      var hasLeftOrigin = !isSameOriginUri(currentUri);
      var redirectsCount = 0;

      while (true) {
        if (hasLeftOrigin || !allowAuthOnCurrentHop || !isSameOriginUri(currentUri)) {
          allowAuthOnCurrentHop = false;
          _stripSensitiveHeaders(currentHeaders);
        }

        final abortCompleter = Completer<void>();
        void triggerAbort() {
          if (!abortCompleter.isCompleted) {
            abortCompleter.complete();
          }
        }

        final request = http.AbortableRequest(
          currentMethod,
          currentUri,
          abortTrigger: abortCompleter.future,
        )..followRedirects = false;
        request.headers.addAll(currentHeaders);
        if (currentBody != null) {
          request.body = currentBody;
        }

        Timer? abortTimer;
        http.StreamedResponse streamedResponse;
        try {
          final remainingSendTimeout = getRemainingTimeout();
          abortTimer = Timer(remainingSendTimeout, triggerAbort);

          streamedResponse = await _client.send(request).timeout(remainingSendTimeout);
        } on TimeoutException {
          triggerAbort();
          rethrow;
        } finally {
          abortTimer?.cancel();
        }

        // Janela A2: se o prazo esgotou após send() completar mas antes da leitura do corpo
        if (stopwatch.elapsed >= timeout) {
          triggerAbort();
          await _discardStream(streamedResponse.stream);
          throw TimeoutException('Tempo limite total da operação esgotado.', timeout);
        }

        if (!_redirectStatusCodes.contains(streamedResponse.statusCode)) {
          final response = await _buildResponseFromStream(
            streamedResponse,
            remainingTimeout: getRemainingTimeout(),
            maxBytes: maxResponseBodyBytes ?? defaultMaxResponseBodyBytes,
            abortCompleter: abortCompleter,
          );
          return _handleResponse(
            response,
            requiresAuth: allowAuthOnCurrentHop,
            treatInvalidCredentialsAsBusinessError: treatInvalidCredentialsAsBusinessError,
          );
        }

        // Redirecionamento 3xx recebido
        final locationHeader = _extractHeaderCaseInsensitive(streamedResponse.headers, 'location');

        // Política fail-closed: não converter mutações de estado automaticamente em GET
        if (currentMethod != 'GET') {
          await _discardStream(streamedResponse.stream);
          triggerAbort();
          throw NetworkException(
            'Redirecionamento automático de método mutador ($currentMethod) não permitido (HTTP ${streamedResponse.statusCode}).',
          );
        }

        if (locationHeader == null || locationHeader.trim().isEmpty) {
          final response = await _buildResponseFromStream(
            streamedResponse,
            remainingTimeout: getRemainingTimeout(),
            maxBytes: maxResponseBodyBytes ?? defaultMaxResponseBodyBytes,
            abortCompleter: abortCompleter,
          );
          return _handleResponse(
            response,
            requiresAuth: allowAuthOnCurrentHop,
            treatInvalidCredentialsAsBusinessError: treatInvalidCredentialsAsBusinessError,
          );
        }

        // Lê e descarta com limite o corpo da resposta 3xx intermediária para liberar a conexão
        await _drainStreamWithLimit(
          streamedResponse.stream,
          timeout: getRemainingTimeout(),
          maxBytes: _maxRedirectResponseBodyBytes,
          abortCompleter: abortCompleter,
        );

        redirectsCount++;
        if (redirectsCount > _maxRedirects) {
          triggerAbort();
          throw const NetworkException('Número máximo de redirecionamentos HTTP excedido.');
        }

        final nextUri = _resolveRedirectUri(currentUri, locationHeader.trim());
        final nextIsSameOrigin = isSameOriginUri(nextUri);

        if (!nextIsSameOrigin) {
          hasLeftOrigin = true;
          allowAuthOnCurrentHop = false;
          _stripSensitiveHeaders(currentHeaders);
        } else if (hasLeftOrigin) {
          // Se saiu da origem em qualquer ponto da cadeia, a remoção de credenciais é permanente:
          // ao retornar à origem da API, não recoloque as credenciais sensíveis.
          allowAuthOnCurrentHop = false;
          _stripSensitiveHeaders(currentHeaders);
        }

        currentUri = nextUri;
      }
    } on TimeoutException {
      throw const NetworkException('Tempo limite de conexão esgotado (timeout).');
    } on http.RequestAbortedException {
      throw const NetworkException('Tempo limite de conexão esgotado (timeout).');
    } on http.ClientException catch (e) {
      if (e.message.toLowerCase().contains('abort') || e is http.RequestAbortedException) {
        throw NetworkException('Tempo limite de conexão esgotado (timeout).', e);
      }
      throw NetworkException('Falha de conexão com o servidor.', e);
    } on ApiException {
      rethrow;
    } on NetworkException {
      rethrow;
    } catch (e) {
      throw NetworkException('Erro inesperado na comunicação de rede.', e);
    }
  }

  /// Descarta e cancela a inscrição no stream imediatamente para liberar sockets e recursos de rede.
  Future<void> _discardStream(Stream<List<int>> stream) async {
    try {
      final subscription = stream.listen((_) {});
      await subscription.cancel();
    } catch (_) {
      // Ignora exceções ao descartar stream já fechado
    }
  }

  /// Lê com segurança os bytes de [stream] respeitando o limite de tempo [timeout]
  /// e o tamanho máximo de bytes [maxBytes].
  ///
  /// Caso o tempo expire ou o limite de bytes seja atingido, a inscrição no stream é
  /// explicitamente cancelada e o [abortCompleter] é acionado para fechar a conexão de rede
  /// e evitar consumo contínuo de recursos.
  Future<Uint8List> _readStreamWithLimitAndTimeout(
    Stream<List<int>> stream, {
    required Duration timeout,
    int? maxBytes,
    Completer<void>? abortCompleter,
  }) {
    final completer = Completer<Uint8List>();
    final bytesBuilder = BytesBuilder(copy: false);
    var totalBytes = 0;
    StreamSubscription<List<int>>? subscription;
    Timer? timer;

    void cleanUp() {
      timer?.cancel();
      timer = null;
    }

    void triggerAbort() {
      if (abortCompleter != null && !abortCompleter.isCompleted) {
        abortCompleter.complete();
      }
    }

    timer = Timer(timeout, () {
      triggerAbort();
      subscription?.cancel();
      cleanUp();
      if (!completer.isCompleted) {
        completer.completeError(
          TimeoutException('Tempo limite excedido na leitura do corpo da resposta.', timeout),
        );
      }
    });

    subscription = stream.listen(
      (chunk) {
        totalBytes += chunk.length;
        if (maxBytes != null && totalBytes > maxBytes) {
          triggerAbort();
          subscription?.cancel();
          cleanUp();
          if (!completer.isCompleted) {
            completer.completeError(
              NetworkException(
                'Tamanho da resposta excede o limite permitido de $maxBytes bytes.',
              ),
            );
          }
          return;
        }
        bytesBuilder.add(chunk);
      },
      onError: (Object error, StackTrace stackTrace) {
        cleanUp();
        if (!completer.isCompleted) {
          completer.completeError(error, stackTrace);
        }
      },
      onDone: () {
        cleanUp();
        if (!completer.isCompleted) {
          completer.complete(bytesBuilder.takeBytes());
        }
      },
      cancelOnError: true,
    );

    return completer.future;
  }

  /// Drena e descarta com segurança os bytes de [stream] para respostas intermediárias 3xx,
  /// respeitando o limite de bytes e timeout para não manter conexões abertas nem consumir memória.
  Future<void> _drainStreamWithLimit(
    Stream<List<int>> stream, {
    required Duration timeout,
    required int maxBytes,
    Completer<void>? abortCompleter,
  }) async {
    try {
      await _readStreamWithLimitAndTimeout(
        stream,
        timeout: timeout,
        maxBytes: maxBytes,
        abortCompleter: abortCompleter,
      );
    } on NetworkException {
      // Ignora excesso de corpo em redirecionamento intermediário após cancelar stream
    }
  }

  Future<http.Response> _buildResponseFromStream(
    http.StreamedResponse streamedResponse, {
    required Duration remainingTimeout,
    int? maxBytes,
    Completer<void>? abortCompleter,
  }) async {
    if (remainingTimeout <= Duration.zero) {
      if (abortCompleter != null && !abortCompleter.isCompleted) {
        abortCompleter.complete();
      }
      await _discardStream(streamedResponse.stream);
      throw TimeoutException('Tempo limite total da operação esgotado.', timeout);
    }

    final bodyBytes = await _readStreamWithLimitAndTimeout(
      streamedResponse.stream,
      timeout: remainingTimeout,
      maxBytes: maxBytes,
      abortCompleter: abortCompleter,
    );

    return http.Response.bytes(
      bodyBytes,
      streamedResponse.statusCode,
      request: streamedResponse.request,
      headers: streamedResponse.headers,
      isRedirect: streamedResponse.isRedirect,
      persistentConnection: streamedResponse.persistentConnection,
      reasonPhrase: streamedResponse.reasonPhrase,
    );
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
    Uri uri;

    if (trimmedPath.startsWith('//')) {
      final baseUri = Uri.tryParse(baseUrl.trim());
      final scheme = (baseUri != null && baseUri.hasScheme) ? baseUri.scheme.toLowerCase() : 'https';
      uri = Uri.parse('$scheme:$trimmedPath');
    } else {
      final hasHttpScheme = RegExp(r'^https?://', caseSensitive: false).hasMatch(trimmedPath);
      if (hasHttpScheme) {
        uri = Uri.parse(trimmedPath);
      } else {
        final hasOtherScheme = RegExp(r'^[a-zA-Z][a-zA-Z0-9+.-]*:').hasMatch(trimmedPath);
        if (hasOtherScheme) {
          throw const NetworkException('Esquema de URL não suportado.');
        }

        final cleanBase = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
        final cleanPath = trimmedPath.startsWith('/') ? trimmedPath : '/$trimmedPath';
        final fullUrl = '$cleanBase$cleanPath';

        uri = Uri.parse(fullUrl);
      }
    }

    if (uri.userInfo.isNotEmpty) {
      throw const NetworkException('URLs com credenciais embutidas (userinfo) não são permitidas.');
    }

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

  bool _containsSensitiveHeader(Map<String, String>? headers) {
    if (headers == null || headers.isEmpty) {
      return false;
    }
    return headers.keys.any((key) => _sensitiveHeaderNames.contains(key.toLowerCase()));
  }

  void _stripSensitiveHeaders(Map<String, String> headers) {
    headers.removeWhere((key, _) => _sensitiveHeaderNames.contains(key.toLowerCase()));
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

    if (!isSameOrigin && _containsSensitiveHeader(customHeaders)) {
      throw const NetworkException(
        'Cabeçalhos sensíveis de autenticação não são permitidos para destino fora da origem da API.',
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
      _stripSensitiveHeaders(headers);
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
