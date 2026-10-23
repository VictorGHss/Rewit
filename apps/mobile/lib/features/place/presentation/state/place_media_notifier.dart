import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/repositories/place_repository.dart';

/// Gera um UUID v4 criptograficamente seguro e em conformidade estrita com o RFC 4122.
String generateUuidV4() {
  final Random random = Random.secure();
  final values = List<int>.generate(16, (i) => random.nextInt(256));
  values[6] = (values[6] & 0x0f) | 0x40;
  values[8] = (values[8] & 0x3f) | 0x80;
  final hex = values.map((b) => b.toRadixString(16).padLeft(2, '0')).toList();
  return '${hex.sublist(0, 4).join()}-${hex.sublist(4, 6).join()}-${hex.sublist(6, 8).join()}-${hex.sublist(8, 10).join()}-${hex.sublist(10, 16).join()}';
}

/// Gerenciador de estado reativo para a galeria e capa de um estabelecimento físico.
class PlaceMediaNotifier extends ChangeNotifier {
  final PlaceRepository repository;
  final String Function()? uuidGenerator;

  static const int maxMediaPerPlace = 5;
  static const int maxFileSizeBytes = 10 * 1024 * 1024; // 10 MB

  List<PlaceMediaItem> _mediaList = [];
  bool _isLoading = false;
  bool _isUploading = false;
  String? _operatingMediaId;
  String? _errorMessage;
  String? _successMessage;

  PlaceMediaNotifier({
    required this.repository,
    this.uuidGenerator,
  });

  List<PlaceMediaItem> get mediaList => List.unmodifiable(_mediaList);
  bool get isLoading => _isLoading;
  bool get isUploading => _isUploading;
  String? get operatingMediaId => _operatingMediaId;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;

  bool get isLimitReached => _mediaList.length >= maxMediaPerPlace;

  PlaceMediaItem? get primaryMedia {
    for (final media in _mediaList) {
      if (media.isPrimary) return media;
    }
    return null;
  }

  void clearMessages() {
    if (_errorMessage != null || _successMessage != null) {
      _errorMessage = null;
      _successMessage = null;
      notifyListeners();
    }
  }

  /// Carrega as fotografias ativas vinculadas ao local.
  Future<void> loadMedia(String placeId) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final items = await repository.getPlaceMedia(placeId);
      _mediaList = List.of(items);
    } on ApiException catch (e) {
      _errorMessage = e.detail;
    } catch (_) {
      _errorMessage = 'Falha ao carregar as fotografias do estabelecimento.';
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  /// Envia uma nova fotografia para o local físico.
  Future<bool> uploadMedia({
    required String placeId,
    required Uint8List bytes,
    required String filename,
    String? mimeType,
  }) async {
    if (isLimitReached) {
      _errorMessage = 'Limite máximo de $maxMediaPerPlace fotografias já foi atingido para este local.';
      notifyListeners();
      return false;
    }

    if (bytes.lengthInBytes > maxFileSizeBytes) {
      _errorMessage = 'A fotografia excede o limite máximo permitido de 10 MB.';
      notifyListeners();
      return false;
    }

    if (_isUploading || _operatingMediaId != null) {
      return false;
    }

    _isUploading = true;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();

    try {
      final idempotencyKey = (uuidGenerator != null) ? uuidGenerator!() : generateUuidV4();
      final created = await repository.uploadPlaceMedia(
        placeId: placeId,
        fileBytes: bytes,
        filename: filename,
        mimeType: mimeType,
        idempotencyKey: idempotencyKey,
      );

      // Se a nova mídia foi criada como primária (ex: primeira foto enviada),
      // atualizamos as outras fotos em memória
      if (created.isPrimary) {
        _mediaList = _mediaList.map((m) => m.copyWith(isPrimary: false)).toList();
      }

      _mediaList = [..._mediaList, created];
      _successMessage = 'Fotografia adicionada com sucesso!';
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.detail;
      return false;
    } catch (_) {
      _errorMessage = 'Falha ao enviar a fotografia. Verifique o arquivo e tente novamente.';
      return false;
    } finally {
      _isUploading = false;
      notifyListeners();
    }
  }

  /// Define a fotografia indicada como foto principal/capa do local.
  Future<bool> setPrimaryMedia({
    required String placeId,
    required String mediaId,
  }) async {
    if (_operatingMediaId != null || _isUploading) {
      return false;
    }

    _operatingMediaId = mediaId;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();

    try {
      final updated = await repository.setPrimaryPlaceMedia(
        placeId: placeId,
        mediaId: mediaId,
      );

      _mediaList = _mediaList.map((m) {
        if (m.id == updated.id) {
          return updated;
        }
        return m.copyWith(isPrimary: false);
      }).toList();

      _successMessage = 'Foto de capa atualizada com sucesso!';
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.detail;
      return false;
    } catch (_) {
      _errorMessage = 'Não foi possível definir esta foto como principal. Tente novamente.';
      return false;
    } finally {
      _operatingMediaId = null;
      notifyListeners();
    }
  }

  /// Remove uma fotografia ativa do local.
  /// Não remove do estado local até confirmação de sucesso pelo backend.
  Future<bool> deleteMedia({
    required String placeId,
    required String mediaId,
  }) async {
    if (_operatingMediaId != null || _isUploading) {
      return false;
    }

    _operatingMediaId = mediaId;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();

    try {
      await repository.deletePlaceMedia(
        placeId: placeId,
        mediaId: mediaId,
      );

      _mediaList = _mediaList.where((m) => m.id != mediaId).toList();
      _successMessage = 'Fotografia removida com sucesso!';
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.detail;
      return false;
    } catch (_) {
      _errorMessage = 'Falha ao remover a fotografia. Tente novamente.';
      return false;
    } finally {
      _operatingMediaId = null;
      notifyListeners();
    }
  }
}
