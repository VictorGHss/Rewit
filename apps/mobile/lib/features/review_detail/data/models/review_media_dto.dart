import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';

/// DTO representacional para mídia de avaliação retornado pelo backend.
class ReviewMediaDto {
  final String id;
  final String reviewId;
  final String url;
  final String mediaType;
  final String mimeType;
  final int sizeBytes;
  final int? width;
  final int? height;
  final String status;
  final DateTime createdAt;

  const ReviewMediaDto({
    required this.id,
    required this.reviewId,
    required this.url,
    required this.mediaType,
    required this.mimeType,
    required this.sizeBytes,
    this.width,
    this.height,
    required this.status,
    required this.createdAt,
  });

  factory ReviewMediaDto.fromJson(Map<String, dynamic> json) {
    return ReviewMediaDto(
      id: json['id'] as String? ?? '',
      reviewId: json['reviewId'] as String? ?? '',
      url: json['url'] as String? ?? '',
      mediaType: json['mediaType'] as String? ?? 'IMAGE',
      mimeType: json['mimeType'] as String? ?? 'image/jpeg',
      sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
      width: (json['width'] as num?)?.toInt(),
      height: (json['height'] as num?)?.toInt(),
      status: json['status'] as String? ?? 'ACTIVE',
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'] as String) ?? DateTime.now()
          : DateTime.now(),
    );
  }

  ReviewMediaItem toEntity() {
    return ReviewMediaItem(
      id: id,
      reviewId: reviewId,
      url: url,
      mediaType: mediaType,
      mimeType: mimeType,
      sizeBytes: sizeBytes,
      width: width,
      height: height,
      status: status,
      createdAt: createdAt,
    );
  }
}
