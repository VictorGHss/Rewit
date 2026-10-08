import 'dart:typed_data';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';

/// Status do ciclo de upload de uma mídia selecionada.
enum MediaUploadStatus {
  queued,
  uploading,
  uploaded,
  failed,
}

/// Item de mídia selecionado localmente pelo usuário antes ou durante o upload.
class SelectedMediaItem {
  final String id;
  final String name;
  final int sizeBytes;
  final String mimeType;
  final Uint8List bytes;
  final MediaUploadStatus status;
  final String? errorMessage;
  final String? errorCode;
  final bool isTerminalError;
  final int? retryAfterSeconds;
  final ReviewMediaItem? uploadedMedia;

  const SelectedMediaItem({
    required this.id,
    required this.name,
    required this.sizeBytes,
    required this.mimeType,
    required this.bytes,
    this.status = MediaUploadStatus.queued,
    this.errorMessage,
    this.errorCode,
    this.isTerminalError = false,
    this.retryAfterSeconds,
    this.uploadedMedia,
  });

  /// 10 MB em bytes
  static const int maxSizeBytes = 10 * 1024 * 1024;

  /// Limite máximo de mídias por avaliação
  static const int maxItemsPerReview = 5;

  bool get isSizeValid => sizeBytes <= maxSizeBytes && sizeBytes > 0;

  bool get isFormatValid =>
      mimeType == 'image/jpeg' ||
      mimeType == 'image/png' ||
      name.toLowerCase().endsWith('.jpg') ||
      name.toLowerCase().endsWith('.jpeg') ||
      name.toLowerCase().endsWith('.png');

  String get formattedSize {
    if (sizeBytes < 1024) return '$sizeBytes B';
    if (sizeBytes < 1024 * 1024) {
      return '${(sizeBytes / 1024).toStringAsFixed(1)} KB';
    }
    return '${(sizeBytes / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  SelectedMediaItem copyWith({
    MediaUploadStatus? status,
    String? errorMessage,
    String? errorCode,
    bool? isTerminalError,
    int? retryAfterSeconds,
    ReviewMediaItem? uploadedMedia,
  }) {
    return SelectedMediaItem(
      id: id,
      name: name,
      sizeBytes: sizeBytes,
      mimeType: mimeType,
      bytes: bytes,
      status: status ?? this.status,
      errorMessage: errorMessage ?? this.errorMessage,
      errorCode: errorCode ?? this.errorCode,
      isTerminalError: isTerminalError ?? this.isTerminalError,
      retryAfterSeconds: retryAfterSeconds ?? this.retryAfterSeconds,
      uploadedMedia: uploadedMedia ?? this.uploadedMedia,
    );
  }
}
