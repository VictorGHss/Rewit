/// Entidade representando anexo de imagem em uma avaliação.
class ReviewMediaItem {
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

  const ReviewMediaItem({
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
}
