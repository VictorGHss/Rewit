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

/// Representa uma operação lógica de upload de fotografia pendente ou em retentativa.
class PendingMediaUpload {
  final String idempotencyKey;
  final String placeId;
  final Uint8List bytes;
  final String filename;
  final String? mimeType;
  final DateTime createdAt;

  const PendingMediaUpload({
    required this.idempotencyKey,
    required this.placeId,
    required this.bytes,
    required this.filename,
    this.mimeType,
    required this.createdAt,
  });
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
  PendingMediaUpload? _pendingUpload;
  bool _hasInitialLoadError = false;

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
  PendingMediaUpload? get pendingUpload => _pendingUpload;
  bool get hasPendingRetry => _pendingUpload != null;
  bool get hasInitialLoadError => _hasInitialLoadError && _mediaList.isEmpty;

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

  /// Descarta explicitamente a operação de upload pendente atual.
  void cancelPendingUpload() {
    if (_pendingUpload != null) {
      _pendingUpload = null;
      notifyListeners();
    }
  }

  /// Carrega as fotografias ativas vinculadas ao local.
  Future<void> loadMedia(String placeId) async {
    _isLoading = true;
    _errorMessage = null;
    _hasInitialLoadError = false;
    notifyListeners();

    try {
      final items = await repository.getPlaceMedia(placeId);
      _mediaList = List.of(items);
      _hasInitialLoadError = false;
    } on ApiException catch (e) {
      _errorMessage = e.detail;
      _hasInitialLoadError = true;
    } catch (_) {
      _errorMessage = 'Falha ao carregar as fotografias do estabelecimento.';
      _hasInitialLoadError = true;
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  /// Envia uma nova fotografia para o local físico.
  /// Cada nova fotografia inicia explicitamente uma nova operação lógica com nova chave.
  Future<bool> uploadMedia({
    required String placeId,
    required Uint8List bytes,
    required String filename,
    String? mimeType,
    String? idempotencyKey,
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

    if (_pendingUpload != null) {
      _errorMessage = 'Existe um envio pendente. Tente novamente ou descarte a operação antes de adicionar outra foto.';
      notifyListeners();
      return false;
    }

    final effectiveKey = idempotencyKey ??
        ((uuidGenerator != null) ? uuidGenerator!() : generateUuidV4());

    _pendingUpload = PendingMediaUpload(
      idempotencyKey: effectiveKey,
      placeId: placeId,
      bytes: bytes,
      filename: filename,
      mimeType: mimeType,
      createdAt: DateTime.now(),
    );

    return _executeUpload(_pendingUpload!);
  }

  /// Retenta a operação lógica de upload pendente reutilizando estritamente a mesma chave de idempotência.
  Future<bool> retryUpload() async {
    final pending = _pendingUpload;
    if (pending == null) {
      return false;
    }

    if (isLimitReached) {
      _errorMessage = 'Limite máximo de $maxMediaPerPlace fotografias já foi atingido para este local.';
      notifyListeners();
      return false;
    }

    if (_isUploading || _operatingMediaId != null) {
      return false;
    }

    return _executeUpload(pending);
  }

  Future<bool> _executeUpload(PendingMediaUpload operation) async {
    _isUploading = true;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();

    try {
      final created = await repository.uploadPlaceMedia(
        placeId: operation.placeId,
        fileBytes: operation.bytes,
        filename: operation.filename,
        mimeType: operation.mimeType,
        idempotencyKey: operation.idempotencyKey,
      );

      // Tratamento para resposta de replay com mídia REMOVED (Correção C)
      if (created.status == 'REMOVED' || created.status != 'ACTIVE') {
        _pendingUpload = null;

        // Reconcilia a lista com o servidor para refletir o estado real
        try {
          final fresh = await repository.getPlaceMedia(operation.placeId);
          _mediaList = List.of(fresh);
        } catch (_) {
          _mediaList = _mediaList.where((m) => m.id != created.id).toList();
        }

        _errorMessage = 'Esta fotografia já foi processada anteriormente e não está mais ativa no estabelecimento.';
        return false;
      }

      // Sucesso: mídia ACTIVE
      // Se já existia na lista (replay da mesma mídia ativa), não duplica
      final alreadyExists = _mediaList.any((m) => m.id == created.id);
      if (!alreadyExists) {
        if (created.isPrimary) {
          _mediaList = _mediaList.map((m) => m.copyWith(isPrimary: false)).toList();
        }
        _mediaList = [..._mediaList, created];
      }

      // Encerra a operação pendente com sucesso
      _pendingUpload = null;
      _successMessage = 'Fotografia adicionada com sucesso!';
      return true;
    } on ApiException catch (e) {
      _errorMessage = e.detail;
      return false;
    } catch (_) {
      // Falha temporária de rede ou transporte:
      // Preserva a identidade da operação (_pendingUpload) com a mesma chave para retentativa
      _errorMessage = 'Falha ao enviar a fotografia. Verifique sua conexão e tente novamente.';
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

    final targetMedia = _mediaList.cast<PlaceMediaItem?>().firstWhere(
          (m) => m?.id == mediaId,
          orElse: () => null,
        );
    final wasPrimary = targetMedia?.isPrimary ?? false;

    _operatingMediaId = mediaId;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();

    try {
      await repository.deletePlaceMedia(
        placeId: placeId,
        mediaId: mediaId,
      );

      // 1. Confirma remoção no estado local
      _mediaList = _mediaList.where((m) => m.id != mediaId).toList();

      // 2. Se a foto excluída era a foto principal e restam fotos:
      // O backend promove deterministamente a foto ativa mais antiga (findFirstActiveByPlaceId)
      if (wasPrimary && _mediaList.isNotEmpty) {
        // Aplica promoção local provisória determinística (foto mais antiga por createdAt ASC, id ASC)
        final sortedRemaining = List<PlaceMediaItem>.from(_mediaList);
        sortedRemaining.sort((a, b) {
          final cmp = a.createdAt.compareTo(b.createdAt);
          if (cmp != 0) return cmp;
          return a.id.compareTo(b.id);
        });
        final nextPrimaryId = sortedRemaining.first.id;
        _mediaList = _mediaList.map((m) => m.copyWith(isPrimary: m.id == nextPrimaryId)).toList();

        // 3. Reconcilia com a resposta oficial da API sem inventar dados
        try {
          final fresh = await repository.getPlaceMedia(placeId);
          if (fresh.any((m) => m.isPrimary)) {
            _mediaList = List.of(fresh);
          } else if (fresh.isNotEmpty) {
            _mediaList = fresh.map((m) => m.copyWith(isPrimary: m.id == nextPrimaryId)).toList();
          } else {
            _mediaList = [];
          }
        } catch (_) {
          // Preserva a exclusão confirmada e a capa local caso o recarregamento falhe
        }
      }

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
