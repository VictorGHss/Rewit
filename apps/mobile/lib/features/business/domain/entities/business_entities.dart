/// Entidade de domínio para Contas Comerciais (C9).
class BusinessAccount {
  final String id;
  final String corporateName;
  final String taxId;
  final String verificationStatus; // 'PENDING', 'APPROVED', 'REJECTED'
  final String planTier;
  final DateTime createdAt;
  final DateTime updatedAt;

  const BusinessAccount({
    required this.id,
    required this.corporateName,
    required this.taxId,
    required this.verificationStatus,
    required this.planTier,
    required this.createdAt,
    required this.updatedAt,
  });

  bool get isPending => verificationStatus == 'PENDING';
  bool get isApproved => verificationStatus == 'APPROVED';
  bool get isRejected => verificationStatus == 'REJECTED';

  String get verificationStatusLabel {
    switch (verificationStatus) {
      case 'PENDING':
        return 'Em Análise';
      case 'APPROVED':
        return 'Verificada';
      case 'REJECTED':
        return 'Rejeitada';
      default:
        return verificationStatus;
    }
  }
}

class PlaceClaim {
  final String id;
  final String businessAccountId;
  final String corporateName;
  final String taxId;
  final String placeId;
  final String placeName;
  final String city;
  final String state;
  final String status; // 'PENDING', 'APPROVED', 'REJECTED'
  final String evidenceDescription;
  final DateTime createdAt;
  final DateTime? decidedAt;
  final String? decisionReason;

  const PlaceClaim({
    required this.id,
    required this.businessAccountId,
    required this.corporateName,
    required this.taxId,
    required this.placeId,
    required this.placeName,
    required this.city,
    required this.state,
    required this.status,
    required this.evidenceDescription,
    required this.createdAt,
    this.decidedAt,
    this.decisionReason,
  });

  bool get isPending => status == 'PENDING';
  bool get isApproved => status == 'APPROVED';
  bool get isRejected => status == 'REJECTED';

  String get statusLabel {
    switch (status) {
      case 'PENDING':
        return 'Pendente';
      case 'APPROVED':
        return 'Aprovada';
      case 'REJECTED':
        return 'Rejeitada';
      default:
        return status;
    }
  }
}
