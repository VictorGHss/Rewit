import 'package:rewit_mobile/features/reports/domain/entities/report_entities.dart';

/// DTO para desserialização de `ReportResponse` retornado por `POST /api/v1/reports`.
class ReportResponseDto {
  final String id;
  final String reviewId;
  final ReportReason reason;
  final String status;
  final DateTime createdAt;

  const ReportResponseDto({
    required this.id,
    required this.reviewId,
    required this.reason,
    required this.status,
    required this.createdAt,
  });

  factory ReportResponseDto.fromJson(Map<String, dynamic> json) {
    final rawId = json['id'];
    final rawReviewId = json['reviewId'];
    final rawReason = json['reason'];
    final rawStatus = json['status'];
    final rawCreatedAt = json['createdAt'];

    if (rawId is! String || rawId.trim().isEmpty) {
      throw const FormatException('Campo obrigatório ausente ou inválido em ReportResponse: id');
    }
    if (rawReviewId is! String || rawReviewId.trim().isEmpty) {
      throw const FormatException('Campo obrigatório ausente ou inválido em ReportResponse: reviewId');
    }
    if (rawReason is! String || rawReason.trim().isEmpty) {
      throw const FormatException('Campo obrigatório ausente ou inválido em ReportResponse: reason');
    }
    if (rawStatus is! String || rawStatus.trim().isEmpty) {
      throw const FormatException('Campo obrigatório ausente ou inválido em ReportResponse: status');
    }
    if (rawCreatedAt is! String || rawCreatedAt.trim().isEmpty) {
      throw const FormatException('Campo obrigatório ausente ou inválido em ReportResponse: createdAt');
    }

    final parsedCreatedAt = DateTime.tryParse(rawCreatedAt.trim());
    if (parsedCreatedAt == null) {
      throw const FormatException('Campo obrigatório ausente ou inválido em ReportResponse: createdAt');
    }

    return ReportResponseDto(
      id: rawId.trim(),
      reviewId: rawReviewId.trim(),
      reason: ReportReason.fromBackendValue(rawReason),
      status: rawStatus.trim(),
      createdAt: parsedCreatedAt,
    );
  }

  /// Status tipado derivado de [status].
  ReportStatus get reportStatus => ReportStatus.fromBackendValue(status);

  ReviewReportReceipt toEntity(ReviewReportOutcome outcome) {
    return ReviewReportReceipt(
      id: id,
      reviewId: reviewId,
      reason: reason,
      status: status,
      createdAt: createdAt,
      outcome: outcome,
    );
  }
}
