import '../../domain/entities/business_entities.dart';

class BusinessAccountDto {
  final String id;
  final String corporateName;
  final String taxId;
  final String verificationStatus;
  final String planTier;
  final DateTime createdAt;
  final DateTime updatedAt;

  const BusinessAccountDto({
    required this.id,
    required this.corporateName,
    required this.taxId,
    required this.verificationStatus,
    required this.planTier,
    required this.createdAt,
    required this.updatedAt,
  });

  factory BusinessAccountDto.fromJson(Map<String, dynamic> json) {
    return BusinessAccountDto(
      id: json['id'] as String? ?? '',
      corporateName: json['corporateName'] as String? ?? '',
      taxId: json['taxId'] as String? ?? '',
      verificationStatus: json['verificationStatus'] as String? ?? 'PENDING',
      planTier: json['planTier'] as String? ?? 'FREE',
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'].toString()) ?? DateTime.now()
          : DateTime.now(),
      updatedAt: json['updatedAt'] != null
          ? DateTime.tryParse(json['updatedAt'].toString()) ?? DateTime.now()
          : DateTime.now(),
    );
  }

  BusinessAccount toEntity() {
    return BusinessAccount(
      id: id,
      corporateName: corporateName,
      taxId: taxId,
      verificationStatus: verificationStatus,
      planTier: planTier,
      createdAt: createdAt,
      updatedAt: updatedAt,
    );
  }
}

class PlaceClaimDto {
  final String id;
  final String businessAccountId;
  final String corporateName;
  final String taxId;
  final String placeId;
  final String placeName;
  final String city;
  final String state;
  final String status;
  final String evidenceDescription;
  final DateTime createdAt;
  final DateTime? decidedAt;
  final String? decisionReason;

  const PlaceClaimDto({
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

  factory PlaceClaimDto.fromJson(Map<String, dynamic> json) {
    return PlaceClaimDto(
      id: json['id'] as String? ?? '',
      businessAccountId: json['businessAccountId'] as String? ?? '',
      corporateName: json['corporateName'] as String? ?? '',
      taxId: json['taxId'] as String? ?? '',
      placeId: json['placeId'] as String? ?? '',
      placeName: json['placeName'] as String? ?? '',
      city: json['city'] as String? ?? '',
      state: json['state'] as String? ?? '',
      status: json['status'] as String? ?? 'PENDING',
      evidenceDescription: json['evidenceDescription'] as String? ?? '',
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'].toString()) ?? DateTime.now()
          : DateTime.now(),
      decidedAt: json['decidedAt'] != null
          ? DateTime.tryParse(json['decidedAt'].toString())
          : null,
      decisionReason: json['decisionReason'] as String?,
    );
  }

  PlaceClaim toEntity() {
    return PlaceClaim(
      id: id,
      businessAccountId: businessAccountId,
      corporateName: corporateName,
      taxId: taxId,
      placeId: placeId,
      placeName: placeName,
      city: city,
      state: state,
      status: status,
      evidenceDescription: evidenceDescription,
      createdAt: createdAt,
      decidedAt: decidedAt,
      decisionReason: decisionReason,
    );
  }
}
