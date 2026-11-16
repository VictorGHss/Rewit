import 'package:rewit_mobile/features/reports/domain/entities/report_reason.dart';

export 'package:rewit_mobile/features/reports/domain/entities/report_reason.dart';

/// Status de processamento de uma denúncia no backend Rewit (`ReportStatus`).
enum ReportStatus {
  /// Denúncia registrada e aguardando análise da moderação.
  pending('PENDING'),

  /// Denúncia aceita após análise da moderação.
  accepted('ACCEPTED'),

  /// Denúncia rejeitada após análise ou restauração de conteúdo.
  rejected('REJECTED'),

  /// Valor não reconhecido pelo cliente, tratado de forma neutra.
  unknown('UNKNOWN');

  final String backendValue;

  const ReportStatus(this.backendValue);

  /// Converte o código textual retornado pelo backend para [ReportStatus].
  static ReportStatus fromBackendValue(String? raw) {
    final normalized = raw?.trim().toUpperCase();
    for (final value in ReportStatus.values) {
      if (value != ReportStatus.unknown && value.backendValue == normalized) {
        return value;
      }
    }
    return ReportStatus.unknown;
  }
}

/// Resultado semântico da submissão de uma denúncia de avaliação.
enum ReviewReportOutcome {
  /// Nova denúncia registrada com sucesso (`HTTP 201 Created`).
  created,

  /// Avaliação já havia sido denunciada anteriormente pelo mesmo usuário (`HTTP 200 OK`).
  alreadyReported,
}

/// Comprovante de domínio retornado após o registro ou confirmação idempotente de uma denúncia de avaliação.
class ReviewReportReceipt {
  final String id;
  final String reviewId;
  final ReportReason reason;
  final String status;
  final DateTime createdAt;
  final ReviewReportOutcome outcome;

  const ReviewReportReceipt({
    required this.id,
    required this.reviewId,
    required this.reason,
    required this.status,
    required this.createdAt,
    required this.outcome,
  });

  /// Status tipado da denúncia derivado do valor retornado pelo backend.
  ReportStatus get reportStatus => ReportStatus.fromBackendValue(status);

  bool get isNewlyCreated => outcome == ReviewReportOutcome.created;
  bool get isAlreadyReported => outcome == ReviewReportOutcome.alreadyReported;
}
