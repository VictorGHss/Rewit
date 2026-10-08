import 'dart:typed_data';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';

/// Contrato do repositório para gerenciamento de mídias anexadas a uma avaliação.
abstract class ReviewMediaRepository {
  /// Lista as mídias ativas de uma avaliação.
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId);

  /// Realiza o upload de uma imagem anexa para a avaliação.
  Future<ReviewMediaItem> uploadMedia({
    required String reviewId,
    required Uint8List bytes,
    required String filename,
    String? mimeType,
  });

  /// Exclui uma mídia previamente anexada à avaliação (somente autor).
  Future<void> deleteMedia({
    required String reviewId,
    required String mediaId,
  });

  /// Recupera os bytes brutos da imagem sanitizada para exibição segura.
  Future<Uint8List> getMediaBytes(String pathOrUrl);
}
