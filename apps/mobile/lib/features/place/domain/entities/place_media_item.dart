/// Entidade de domínio para fotografias e mídias vinculadas a um local físico (Place).
class PlaceMediaItem {
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

  const PlaceMediaItem({
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

  bool get isActive => status == 'ACTIVE';

  PlaceMediaItem copyWith({
    String? id,
    String? placeId,
    String? url,
    String? mediaType,
    String? mimeType,
    int? sizeBytes,
    int? width,
    int? height,
    String? status,
    bool? isPrimary,
    DateTime? createdAt,
  }) {
    return PlaceMediaItem(
      id: id ?? this.id,
      placeId: placeId ?? this.placeId,
      url: url ?? this.url,
      mediaType: mediaType ?? this.mediaType,
      mimeType: mimeType ?? this.mimeType,
      sizeBytes: sizeBytes ?? this.sizeBytes,
      width: width ?? this.width,
      height: height ?? this.height,
      status: status ?? this.status,
      isPrimary: isPrimary ?? this.isPrimary,
      createdAt: createdAt ?? this.createdAt,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceMediaItem &&
          runtimeType == other.runtimeType &&
          id == other.id &&
          placeId == other.placeId &&
          isPrimary == other.isPrimary &&
          status == other.status;

  @override
  int get hashCode => Object.hash(id, placeId, isPrimary, status);
}

