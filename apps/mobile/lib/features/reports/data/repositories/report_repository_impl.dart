import 'dart:convert';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/reports/data/models/report_models.dart';
import 'package:rewit_mobile/features/reports/domain/entities/report_entities.dart';
import 'package:rewit_mobile/features/reports/domain/repositories/report_repository.dart';

/// Implementação do repositório de denúncias de avaliações integrado à API REST Rewit (`POST /api/v1/reports`).
class ReportRepositoryImpl implements ReportRepository {
  static const int maxDetailLength = 500;

  final RewitHttpClient _httpClient;

  ReportRepositoryImpl({required RewitHttpClient httpClient})
      : _httpClient = httpClient;

  @override
  Future<ReviewReportReceipt> reportReview({
    required String reviewId,
    required ReportReason reason,
    String? detail,
  }) async {
    final trimmedReviewId = reviewId.trim();
    if (trimmedReviewId.isEmpty) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Validação',
          status: 400,
          code: 'VALIDATION_ERROR',
          detail: 'O identificador da avaliação é obrigatório.',
        ),
      );
    }

    final trimmedDetail = detail?.trim();
    if (trimmedDetail != null && trimmedDetail.length > maxDetailLength) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Validação',
          status: 400,
          code: 'VALIDATION_ERROR',
          detail: 'O detalhe da denúncia não pode exceder 500 caracteres.',
        ),
      );
    }

    final Map<String, dynamic> payload = {
      'reviewId': trimmedReviewId,
      'reason': reason.backendValue,
      if (trimmedDetail != null && trimmedDetail.isNotEmpty)
        'detail': trimmedDetail,
    };

    final response = await _httpClient.post(
      ApiEndpoints.reports,
      body: payload,
      requiresAuth: true,
    );

    final ReviewReportOutcome outcome;
    if (response.statusCode == 201) {
      outcome = ReviewReportOutcome.created;
    } else if (response.statusCode == 200) {
      outcome = ReviewReportOutcome.alreadyReported;
    } else {
      throw FormatException(
        'Status HTTP inesperado ao registrar denúncia: ${response.statusCode}',
      );
    }

    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Corpo de ReportResponse inválido: esperado objeto JSON.');
    }

    final dto = ReportResponseDto.fromJson(decoded);
    return dto.toEntity(outcome);
  }
}
