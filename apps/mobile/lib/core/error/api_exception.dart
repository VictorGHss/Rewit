import 'dart:convert';

/// Representação estruturada de erro RFC 7807 (ProblemDetail) emitida pela API Rewit.
class ProblemDetail {
  final String type;
  final String title;
  final int status;
  final String detail;
  final String? instance;
  final String? code;
  final DateTime? timestamp;
  final Map<String, String>? fieldErrors;
  final Map<String, dynamic>? invalidParams;

  const ProblemDetail({
    required this.type,
    required this.title,
    required this.status,
    required this.detail,
    this.instance,
    this.code,
    this.timestamp,
    this.fieldErrors,
    this.invalidParams,
  });

  factory ProblemDetail.fromJson(Map<String, dynamic> json, {int? fallbackStatus}) {
    DateTime? parsedTimestamp;
    final rawTimestamp = json['timestamp'];
    if (rawTimestamp is String) {
      try {
        parsedTimestamp = DateTime.parse(rawTimestamp);
      } catch (_) {
        parsedTimestamp = null;
      }
    }

    Map<String, String>? parsedFieldErrors;
    final rawFieldErrors = json['fieldErrors'];
    if (rawFieldErrors is Map) {
      parsedFieldErrors = rawFieldErrors.map(
        (key, value) => MapEntry(key.toString(), value.toString()),
      );
    }

    Map<String, dynamic>? parsedInvalidParams;
    final rawInvalidParams = json['invalidParams'];
    if (rawInvalidParams is Map<String, dynamic>) {
      parsedInvalidParams = rawInvalidParams;
    } else if (rawInvalidParams is Map) {
      parsedInvalidParams = Map<String, dynamic>.from(rawInvalidParams);
    }

    return ProblemDetail(
      type: json['type'] as String? ?? 'about:blank',
      title: json['title'] as String? ?? 'Erro na requisição',
      status: (json['status'] as num?)?.toInt() ?? fallbackStatus ?? 500,
      detail: json['detail'] as String? ?? 'Ocorreu um erro inesperado.',
      instance: json['instance'] as String?,
      code: json['code'] as String?,
      timestamp: parsedTimestamp,
      fieldErrors: parsedFieldErrors,
      invalidParams: parsedInvalidParams,
    );
  }

  factory ProblemDetail.fromResponseBody(String body, int statusCode) {
    if (body.trim().isEmpty) {
      return ProblemDetail(
        type: 'about:blank',
        title: _defaultTitleForStatus(statusCode),
        status: statusCode,
        detail: 'Resposta vazia do servidor.',
      );
    }

    try {
      final decoded = jsonDecode(body);
      if (decoded is Map<String, dynamic>) {
        return ProblemDetail.fromJson(decoded, fallbackStatus: statusCode);
      }
    } catch (_) {
      // Corpo não é JSON válido
    }

    return ProblemDetail(
      type: 'about:blank',
      title: _defaultTitleForStatus(statusCode),
      status: statusCode,
      detail: body.length > 200 ? body.substring(0, 200) : body,
    );
  }

  static String _defaultTitleForStatus(int statusCode) {
    switch (statusCode) {
      case 400:
        return 'Requisição Inválida';
      case 401:
        return 'Não Autorizado';
      case 403:
        return 'Acesso Negado';
      case 404:
        return 'Não Encontrado';
      case 409:
        return 'Conflito de Dados';
      case 422:
        return 'Dados Não Processáveis';
      case 429:
        return 'Limite de Requisições Excedido';
      case 500:
        return 'Erro Interno do Servidor';
      case 502:
        return 'Serviço Indisponível';
      case 503:
        return 'Serviço em Manutenção';
      default:
        return 'Erro HTTP $statusCode';
    }
  }

  /// Indica se há algum erro de validação em campos ou parâmetros.
  bool get hasFieldErrors =>
      (fieldErrors != null && fieldErrors!.isNotEmpty) ||
      (invalidParams != null && invalidParams!.isNotEmpty);

  /// Retorna o mapa consolidado de erros de campo, priorizando `fieldErrors`
  /// e caindo para `invalidParams` (suporte legado).
  Map<String, String> get allFieldErrors {
    if (fieldErrors != null && fieldErrors!.isNotEmpty) {
      return fieldErrors!;
    }
    if (invalidParams != null && invalidParams!.isNotEmpty) {
      return invalidParams!.map((key, value) => MapEntry(key, value.toString()));
    }
    return const {};
  }

  /// Retorna a mensagem de erro associada a um determinado campo, ou null se não houver.
  String? getFieldError(String fieldName) {
    if (fieldErrors != null && fieldErrors!.containsKey(fieldName)) {
      return fieldErrors![fieldName];
    }
    if (invalidParams != null && invalidParams!.containsKey(fieldName)) {
      return invalidParams![fieldName]?.toString();
    }
    return null;
  }

  /// Verifica se há erro registrado para o campo informado.
  bool hasFieldError(String fieldName) {
    return (fieldErrors != null && fieldErrors!.containsKey(fieldName)) ||
        (invalidParams != null && invalidParams!.containsKey(fieldName));
  }

  Map<String, dynamic> toJson() {
    return {
      'type': type,
      'title': title,
      'status': status,
      'detail': detail,
      if (instance != null) 'instance': instance,
      if (code != null) 'code': code,
      if (timestamp != null) 'timestamp': timestamp!.toIso8601String(),
      if (fieldErrors != null) 'fieldErrors': fieldErrors,
      if (invalidParams != null) 'invalidParams': invalidParams,
    };
  }

  @override
  String toString() {
    return 'ProblemDetail(status: $status, code: $code, title: "$title", detail: "$detail")';
  }
}

/// Exceção lançada pelo cliente HTTP ao receber resposta de erro do backend Rewit.
class ApiException implements Exception {
  final ProblemDetail problemDetail;
  final int? retryAfterSeconds;

  const ApiException(this.problemDetail, {this.retryAfterSeconds});

  int get statusCode => problemDetail.status;
  String get title => problemDetail.title;
  String get detail => problemDetail.detail;
  String? get errorCode => problemDetail.code;

  Map<String, String>? get fieldErrors => problemDetail.fieldErrors;
  Map<String, dynamic>? get invalidParams => problemDetail.invalidParams;
  bool get hasFieldErrors => problemDetail.hasFieldErrors;
  Map<String, String> get allFieldErrors => problemDetail.allFieldErrors;
  String? getFieldError(String fieldName) => problemDetail.getFieldError(fieldName);
  bool hasFieldError(String fieldName) => problemDetail.hasFieldError(fieldName);

  bool get isUnauthorized => statusCode == 401;
  bool get isForbidden => statusCode == 403;
  bool get isNotFound => statusCode == 404;
  bool get isConflict => statusCode == 409;
  bool get isValidationError => statusCode == 400 || statusCode == 422;
  bool get isRateLimited => statusCode == 429;
  bool get isServerError => statusCode >= 500;

  @override
  String toString() {
    return 'ApiException: [$statusCode${errorCode != null ? ' - $errorCode' : ''}] $detail';
  }
}

/// Exceção de rede ou transporte local (sem resposta HTTP do servidor).
class NetworkException implements Exception {
  final String message;
  final Object? cause;

  const NetworkException(this.message, [this.cause]);

  @override
  String toString() => 'NetworkException: $message${cause != null ? ' ($cause)' : ''}';
}
