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
  final Map<String, dynamic>? invalidParams;

  const ProblemDetail({
    required this.type,
    required this.title,
    required this.status,
    required this.detail,
    this.instance,
    this.code,
    this.timestamp,
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

    return ProblemDetail(
      type: json['type'] as String? ?? 'about:blank',
      title: json['title'] as String? ?? 'Erro na requisição',
      status: (json['status'] as num?)?.toInt() ?? fallbackStatus ?? 500,
      detail: json['detail'] as String? ?? 'Ocorreu um erro inesperado.',
      instance: json['instance'] as String?,
      code: json['code'] as String?,
      timestamp: parsedTimestamp,
      invalidParams: json['invalidParams'] as Map<String, dynamic>?,
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

  Map<String, dynamic> toJson() {
    return {
      'type': type,
      'title': title,
      'status': status,
      'detail': detail,
      if (instance != null) 'instance': instance,
      if (code != null) 'code': code,
      if (timestamp != null) 'timestamp': timestamp!.toIso8601String(),
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
