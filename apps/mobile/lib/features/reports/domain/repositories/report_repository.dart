import 'package:rewit_mobile/features/reports/domain/entities/report_entities.dart';

/// Contrato de repositório para denúncias comunitárias de avaliações.
abstract class ReportRepository {
  /// Submete uma denúncia contra uma avaliação de outro usuário (`POST /api/v1/reports`).
  ///
  /// Distingue entre nova denúncia criada (`HTTP 201` -> [ReviewReportOutcome.created])
  /// e denúncia previamente registrada pelo mesmo usuário (`HTTP 200` -> [ReviewReportOutcome.alreadyReported]).
  Future<ReviewReportReceipt> reportReview({
    required String reviewId,
    required ReportReason reason,
    String? detail,
  });
}
