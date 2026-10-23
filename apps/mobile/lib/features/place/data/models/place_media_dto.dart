import '../../domain/entities/place_media_item.dart';

/// DTO para deserialização da representação HTTP de fotografia de local ([PlaceMediaResponse]).
class PlaceMediaDto {
  final String id;
  final String placeId;
  final String url;
  final String mediaType;
  final String mimeType;
  final int sizeBytes;
  final int? width;
  final int? height;
  final String status;
  final bool isPrimary;
  final DateTime createdAt;

  const PlaceMediaDto({
    required this.id,
    required this.placeId,
    required this.url,
    required this.mediaType,
    required this.mimeType,
    required this.sizeBytes,
    this.width,
    this.height,
    required this.status,
    required this.isPrimary,
    required this.createdAt,
  });

  factory PlaceMediaDto.fromJson(Map<String, dynamic> json) {
    return PlaceMediaDto(
      id: json['id'] as String? ?? '',
      placeId: json['placeId'] as String? ?? '',
      url: json['url'] as String? ?? '',
      mediaType: json['mediaType'] as String? ?? 'PHOTO',
      mimeType: json['mimeType'] as String? ?? 'image/jpeg',
      sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
      width: (json['width'] as num?)?.toInt(),
      height: (json['height'] as num?)?.toInt(),
      status: json['status'] as String? ?? 'ACTIVE',
      isPrimary: json['isPrimary'] as bool? ?? false,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'].toString()) ?? DateTime.now()
          : DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'placeId': placeId,
      'url': url,
      'mediaType': mediaType,
      'mimeType': mimeType,
      'sizeBytes': sizeBytes,
      if (width != null) 'width': width,
      if (height != null) 'height': height,
      'status': status,
      'isPrimary': isPrimary,
      'createdAt': createdAt.toIso8601String(),
    };
  }

  PlaceMediaItem toEntity() {
    return PlaceMediaItem(
      id: id,
      placeId: placeId,
      url: url,
      mediaType: mediaType,
      mimeType: mimeType,
      sizeBytes: sizeBytes,
      width: width,
      height: height,
      status: status,
      isPrimary: isPrimary,
      createdAt: createdAt,
    );
  }
}

