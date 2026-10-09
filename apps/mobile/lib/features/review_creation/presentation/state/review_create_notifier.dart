import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import '../../domain/entities/review_creation_input.dart';
import '../../domain/repositories/review_creation_repository.dart';
import 'review_create_state.dart';

/// Estado do ciclo de captura de localização sob demanda para check-in (C5.10).
enum LocationCaptureStatus {
  idle,
  requesting,
  captured,
  error,
}

/// Gerenciador de estado reativo para o formulário e submissão de avaliações.
class ReviewCreateNotifier extends ChangeNotifier {
  final ReviewCreationRepository repository;
  final ReviewMediaRepository? mediaRepository;
  final MediaPickerService? mediaPickerService;
  final LocationService? locationService;

  static final RegExp _uuidRegex = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );

  ReviewCreateState _state = const ReviewCreateInitial();
  ReviewCreateState get state => _state;

  bool _isDisposed = false;
  int _locationCaptureSequence = 0;

  @override
  void dispose() {
    _isDisposed = true;
    super.dispose();
  }

  void _safeNotifyListeners() {
    if (!_isDisposed) {
      notifyListeners();
    }
  }

  // Campos do formulário
  final List<CreateReviewTargetInput> _targets = [
    const CreateReviewTargetInput(
      rateableTargetId: '',
      rating: 5.0,
      specificComment: null,
    ),
  ];

  final List<SelectedMediaItem> _selectedMedia = [];

  String? _contextPlaceId;
  String? _experienceText;
  bool _isAnonymous = false;
  String _visibility = 'PUBLIC';
  double? _userLatitude;
  double? _userLongitude;
  double? _locationAccuracyMeters;
  LocationCaptureStatus _locationStatus = LocationCaptureStatus.idle;
  String? _locationErrorMessage;
  LocationFailureReason? _locationFailureReason;

  ReviewCreateNotifier({
    required this.repository,
    this.mediaRepository,
    this.mediaPickerService,
    this.locationService,
    String? initialTargetId,
    String? initialTargetName,
    String? initialTargetType,
    String? initialCategory,
  }) {
    if (initialTargetId != null && initialTargetId.isNotEmpty) {
      _targets[0] = CreateReviewTargetInput(
        rateableTargetId: initialTargetId,
        rating: 5.0,
        targetName: initialTargetName,
        targetType: initialTargetType,
        category: initialCategory,
      );
      if (initialTargetType == 'PLACE' || initialTargetType == null) {
        _contextPlaceId = initialTargetId;
      }
    }
  }

  List<CreateReviewTargetInput> get targets => List.unmodifiable(_targets);
  List<SelectedMediaItem> get selectedMedia => List.unmodifiable(_selectedMedia);
  String? get contextPlaceId => _contextPlaceId;
  String? get experienceText => _experienceText;
  bool get isAnonymous => _isAnonymous;
  String get visibility => _visibility;
  double? get userLatitude => _userLatitude;
  double? get userLongitude => _userLongitude;
  double? get locationAccuracyMeters => _locationAccuracyMeters;
  bool get isSubmitting => _state.isSubmitting;

  LocationCaptureStatus get locationStatus => _locationStatus;
  String? get locationErrorMessage => _locationErrorMessage;
  LocationFailureReason? get locationFailureReason => _locationFailureReason;
  bool get isDisposed => _isDisposed;
  bool get hasContextPlace =>
      _contextPlaceId != null && _contextPlaceId!.trim().isNotEmpty;
  bool get hasLocation =>
      _locationStatus == LocationCaptureStatus.captured &&
      _userLatitude != null &&
      _userLongitude != null;
  bool get hasValidCheckin => hasContextPlace && hasLocation;
  bool get isRequestingLocation => _locationStatus == LocationCaptureStatus.requesting;
  bool get isApproximateLocation =>
      _locationAccuracyMeters != null && _locationAccuracyMeters! > 100.0;

  /// Adiciona um novo alvo para avaliação multi-alvo (Step 11.0).
  void addTarget({
    String targetId = '',
    double rating = 5.0,
    String? comment,
    String? targetName,
    String? targetType,
    String? category,
  }) {
    if (_isDisposed || isSubmitting) return;
    _targets.add(
      CreateReviewTargetInput(
        rateableTargetId: targetId,
        rating: rating,
        specificComment: comment,
        targetName: targetName,
        targetType: targetType,
        category: category,
      ),
    );
    _safeNotifyListeners();
  }

  /// Remove um alvo pelo índice (mantendo pelo menos 1 alvo).
  void removeTarget(int index) {
    if (_isDisposed || isSubmitting) return;
    if (_targets.length > 1 && index >= 0 && index < _targets.length) {
      _targets.removeAt(index);
      _safeNotifyListeners();
    }
  }

  /// Seleciona um item retornado pela busca no alvo do índice especificado.
  void selectTarget(int index, SearchResultItem item) {
    if (_isDisposed || isSubmitting) return;
    if (index >= 0 && index < _targets.length) {
      final current = _targets[index];
      _targets[index] = current.copyWith(
        rateableTargetId: item.id,
        targetName: item.name,
        targetType: item.rawTargetType,
        category: item.category,
      );
      // Auto-preenche o estabelecimento de contexto se for um Local e ainda não estiver definido
      if ((_contextPlaceId == null || _contextPlaceId!.isEmpty) && item.isPlace) {
        _contextPlaceId = item.id;
      }
      _safeNotifyListeners();
    }
  }

  /// Limpa a seleção do alvo no índice especificado, reabrindo a busca.
  void clearTargetSelection(int index) {
    if (_isDisposed || isSubmitting) return;
    if (index >= 0 && index < _targets.length) {
      final current = _targets[index];
      _targets[index] = CreateReviewTargetInput(
        rateableTargetId: '',
        rating: current.rating,
        specificComment: current.specificComment,
      );
      _safeNotifyListeners();
    }
  }

  /// Verifica se um targetId já foi selecionado em outro item da avaliação.
  bool isTargetAlreadySelected(String targetId, {int? excludeIndex}) {
    if (targetId.trim().isEmpty) return false;
    final normalized = targetId.trim().toLowerCase();
    for (int i = 0; i < _targets.length; i++) {
      if (excludeIndex != null && i == excludeIndex) continue;
      if (_targets[i].rateableTargetId.trim().toLowerCase() == normalized) {
        return true;
      }
    }
    return false;
  }

  /// Atualiza os dados de um alvo específico.
  void updateTarget(
    int index, {
    String? targetId,
    double? rating,
    String? specificComment,
    String? targetName,
    String? targetType,
    String? category,
  }) {
    if (_isDisposed || isSubmitting) return;
    if (index >= 0 && index < _targets.length) {
      final current = _targets[index];
      _targets[index] = current.copyWith(
        rateableTargetId: targetId ?? current.rateableTargetId,
        rating: rating ?? current.rating,
        specificComment: specificComment ?? current.specificComment,
        targetName: targetName ?? current.targetName,
        targetType: targetType ?? current.targetType,
        category: category ?? current.category,
      );
      _safeNotifyListeners();
    }
  }

  /// Atualiza o local de contexto (estabelecimento principal).
  void setContextPlaceId(String? placeId) {
    if (_isDisposed || isSubmitting) return;
    _contextPlaceId = placeId?.trim().isEmpty ?? true ? null : placeId!.trim();
    _safeNotifyListeners();
  }

  /// Atualiza o texto geral de relato da experiência.
  void setExperienceText(String? text) {
    if (_isDisposed || isSubmitting) return;
    _experienceText = text;
    _safeNotifyListeners();
  }

  /// Alterna o modo de publicação anônima.
  void setAnonymous(bool value) {
    if (_isDisposed || isSubmitting) return;
    _isAnonymous = value;
    _safeNotifyListeners();
  }

  /// Define o nível de visibilidade da publicação.
  void setVisibility(String value) {
    if (_isDisposed || isSubmitting) return;
    _visibility = value;
    _safeNotifyListeners();
  }

  /// Captura a localização atual sob demanda (chamada única) com proteção contra concorrência e descarte de dados após falha ou submissão (C5.10).
  Future<bool> captureLocation({Duration timeout = const Duration(seconds: 10)}) async {
    if (_isDisposed || isSubmitting || _locationStatus == LocationCaptureStatus.requesting) {
      return false;
    }

    final seq = ++_locationCaptureSequence;
    _locationStatus = LocationCaptureStatus.requesting;
    _locationErrorMessage = null;
    _locationFailureReason = null;
    // Limpa imediatamente coordenadas existentes para não manter dados obsoletos
    _userLatitude = null;
    _userLongitude = null;
    _locationAccuracyMeters = null;
    _safeNotifyListeners();

    if (locationService == null) {
      _locationStatus = LocationCaptureStatus.error;
      _locationFailureReason = LocationFailureReason.serviceDisabled;
      _locationErrorMessage = 'Serviço de localização não configurado.';
      _safeNotifyListeners();
      return false;
    }

    try {
      final result = await locationService!.getCurrentLocation(timeout: timeout);

      if (_isDisposed || seq != _locationCaptureSequence || isSubmitting || _state is ReviewCreateSuccess) {
        return false;
      }

      switch (result) {
        case LocationSuccess(:final location):
          _userLatitude = location.latitude;
          _userLongitude = location.longitude;
          _locationAccuracyMeters = location.accuracyMeters;
          _locationStatus = LocationCaptureStatus.captured;
          _locationErrorMessage = null;
          _locationFailureReason = null;
          _safeNotifyListeners();
          return true;

        case LocationFailure(:final reason, :final message):
          _userLatitude = null;
          _userLongitude = null;
          _locationAccuracyMeters = null;
          _locationStatus = LocationCaptureStatus.error;
          _locationFailureReason = reason;
          _locationErrorMessage = message;
          _safeNotifyListeners();
          return false;
      }
    } catch (_) {
      if (_isDisposed || seq != _locationCaptureSequence) {
        return false;
      }
      _userLatitude = null;
      _userLongitude = null;
      _locationAccuracyMeters = null;
      _locationStatus = LocationCaptureStatus.error;
      _locationFailureReason = LocationFailureReason.error;
      _locationErrorMessage = 'Não foi possível obter sua localização agora.';
      _safeNotifyListeners();
      return false;
    }
  }

  /// Define coordenadas de presença para check-in no local (compatibilidade e testes).
  @visibleForTesting
  void setCoordinates({
    double? latitude,
    double? longitude,
    double? accuracyMeters,
  }) {
    if (_isDisposed || isSubmitting) return;
    _userLatitude = latitude;
    _userLongitude = longitude;
    _locationAccuracyMeters = accuracyMeters;
    _locationStatus = (latitude != null && longitude != null)
        ? LocationCaptureStatus.captured
        : LocationCaptureStatus.idle;
    _locationErrorMessage = null;
    _locationFailureReason = null;
    _safeNotifyListeners();
  }

  /// Remove coordenadas de presença e descarta os dados da memória da criação.
  void clearCoordinates() {
    if (_isDisposed || isSubmitting) return;
    _userLatitude = null;
    _userLongitude = null;
    _locationAccuracyMeters = null;
    _locationStatus = LocationCaptureStatus.idle;
    _locationErrorMessage = null;
    _locationFailureReason = null;
    _safeNotifyListeners();
  }

  /// Abre as configurações do sistema para ajuste manual de permissões se necessário.
  Future<bool> openAppSettings() async {
    if (_isDisposed || locationService == null) return false;
    return locationService!.openAppSettings();
  }

  /// Seleciona mídias da galeria até o limite restante de 5 anexos.
  Future<void> pickMedia() async {
    if (_isDisposed || isSubmitting || mediaPickerService == null) return;
    final remainingSlots = SelectedMediaItem.maxItemsPerReview - _selectedMedia.length;
    if (remainingSlots <= 0) return;

    final picked = await mediaPickerService!.pickImages(maxImages: remainingSlots);
    if (_isDisposed) return;
    if (picked.isNotEmpty) {
      final toAdd = picked.take(remainingSlots);
      _selectedMedia.addAll(toAdd);
      _safeNotifyListeners();
    }
  }

  /// Adiciona diretamente um item de mídia selecionado (útil para testes ou integrações diretas).
  void addSelectedMediaItem(SelectedMediaItem item) {
    if (_isDisposed || isSubmitting) return;
    if (_selectedMedia.length < SelectedMediaItem.maxItemsPerReview) {
      _selectedMedia.add(item);
      _safeNotifyListeners();
    }
  }

  /// Remove uma mídia selecionada pelo índice.
  void removeSelectedMediaItem(int index) {
    if (_isDisposed || isSubmitting) return;
    if (index >= 0 && index < _selectedMedia.length) {
      _selectedMedia.removeAt(index);
      _safeNotifyListeners();
    }
  }

  /// Limpa todas as mídias selecionadas.
  void clearSelectedMedia() {
    if (_isDisposed || isSubmitting) return;
    _selectedMedia.clear();
    _safeNotifyListeners();
  }

  /// Reenvia uma mídia que falhou durante o envio pós-publicação.
  Future<bool> retryMediaUpload(int index, String reviewId) async {
    if (_isDisposed || mediaRepository == null || index < 0 || index >= _selectedMedia.length) {
      return false;
    }
    final item = _selectedMedia[index];
    if (item.isTerminalError || item.status == MediaUploadStatus.uploaded) {
      return false;
    }

    _selectedMedia[index] = item.copyWith(status: MediaUploadStatus.uploading);
    _safeNotifyListeners();

    try {
      final uploaded = await mediaRepository!.uploadMedia(
        reviewId: reviewId,
        bytes: item.bytes,
        filename: item.name,
        mimeType: item.mimeType,
      );
      if (_isDisposed) return false;
      _selectedMedia[index] = item.copyWith(
        status: MediaUploadStatus.uploaded,
        uploadedMedia: uploaded,
        errorMessage: null,
        errorCode: null,
      );
      _safeNotifyListeners();
      return true;
    } on ApiException catch (e) {
      if (_isDisposed) return false;
      final isTerminal = e.statusCode == 413 ||
          e.statusCode == 415 ||
          e.statusCode == 403 ||
          e.errorCode == 'MEDIA_SIZE_EXCEEDED' ||
          e.errorCode == 'UNSUPPORTED_MEDIA_TYPE' ||
          e.errorCode == 'MAX_MEDIA_LIMIT_REACHED' ||
          e.errorCode == 'FORBIDDEN';
      _selectedMedia[index] = item.copyWith(
        status: MediaUploadStatus.failed,
        errorCode: e.errorCode,
        errorMessage: e.detail,
        isTerminalError: isTerminal,
        retryAfterSeconds: e.retryAfterSeconds,
      );
      _safeNotifyListeners();
      return false;
    } catch (_) {
      if (_isDisposed) return false;
      _selectedMedia[index] = item.copyWith(
        status: MediaUploadStatus.failed,
        errorMessage: 'Falha ao reenviar anexo de imagem.',
        isTerminalError: false,
      );
      _safeNotifyListeners();
      return false;
    }
  }

  /// Validação local espelhando os invariantes do backend.
  String? validate() {
    if (_targets.isEmpty) {
      return 'A publicação deve conter pelo menos um alvo avaliado.';
    }

    final seenTargetIds = <String>{};

    for (int i = 0; i < _targets.length; i++) {
      final target = _targets[i];
      final targetId = target.rateableTargetId.trim();

      if (targetId.isEmpty) {
        return 'Informe o identificador do alvo ${i + 1}.';
      }

      if (!_uuidRegex.hasMatch(targetId)) {
        return 'O identificador do alvo ${i + 1} não é um UUID válido.';
      }

      final normalizedId = targetId.toLowerCase();
      if (seenTargetIds.contains(normalizedId)) {
        return 'Não é permitido avaliar o mesmo alvo mais de uma vez (alvo duplicado).';
      }
      seenTargetIds.add(normalizedId);

      if (target.rating < 1.0 || target.rating > 5.0) {
        return 'A nota do alvo ${i + 1} deve estar entre 1.0 e 5.0.';
      }

      // Validação de precisão de no máximo 1 casa decimal
      final scaled = (target.rating * 10).roundToDouble();
      if ((target.rating * 10 - scaled).abs() > 0.001) {
        return 'A nota do alvo ${i + 1} deve ter no máximo uma casa decimal.';
      }
    }

    if (_contextPlaceId != null && !_uuidRegex.hasMatch(_contextPlaceId!)) {
      return 'O identificador do estabelecimento de contexto não é um UUID válido.';
    }

    if (_experienceText != null && _experienceText!.length > 2000) {
      return 'O texto da experiência não pode exceder 2000 caracteres.';
    }

    if (!const ['PUBLIC', 'PRIVATE', 'FOLLOWERS'].contains(_visibility)) {
      return 'Visibilidade inválida. Escolha Público, Privado ou Seguidores.';
    }

    if (_userLatitude != null && (_userLatitude! < -90.0 || _userLatitude! > 90.0)) {
      return 'A latitude deve estar entre -90.0 e 90.0.';
    }

    if (_userLongitude != null && (_userLongitude! < -180.0 || _userLongitude! > 180.0)) {
      return 'A longitude deve estar entre -180.0 e 180.0.';
    }

    if (_locationAccuracyMeters != null && _locationAccuracyMeters! < 0.0) {
      return 'A precisão da localização não pode ser negativa.';
    }

    if (_selectedMedia.length > SelectedMediaItem.maxItemsPerReview) {
      return 'É permitido anexar no máximo ${SelectedMediaItem.maxItemsPerReview} fotos por avaliação.';
    }

    return null;
  }

  /// Submete a criação da avaliação ao backend com proteção contra cliques múltiplos
  /// e realiza o upload sequencial controlado das mídias anexadas.
  Future<bool> submit() async {
    if (_isDisposed || isSubmitting || isRequestingLocation) return false;

    // Descarta qualquer captura de localização em andamento
    _locationCaptureSequence++;

    final validationError = validate();
    if (validationError != null) {
      _state = ReviewCreateError(
        message: validationError,
        statusCode: 422,
        errorCode: 'VALIDATION_ERROR',
      );
      _safeNotifyListeners();
      return false;
    }

    _state = const ReviewCreateSubmitting();
    _safeNotifyListeners();

    try {
      final validCheckin = hasValidCheckin;

      final input = CreateReviewInput(
        contextPlaceId: _contextPlaceId,
        experienceText: _experienceText,
        isAnonymous: _isAnonymous,
        visibility: _visibility,
        userLatitude: validCheckin ? _userLatitude : null,
        userLongitude: validCheckin ? _userLongitude : null,
        locationAccuracyMeters: validCheckin ? _locationAccuracyMeters : null,
        targets: _targets
            .map((t) => t.copyWith(rateableTargetId: t.rateableTargetId.trim()))
            .toList(),
      );

      final created = await repository.createReview(input);

      if (_isDisposed) return false;

      // Limpa imediatamente coordenadas da memória da criação após submissão bem-sucedida
      _userLatitude = null;
      _userLongitude = null;
      _locationAccuracyMeters = null;
      _locationStatus = LocationCaptureStatus.idle;

      if (_selectedMedia.isEmpty || mediaRepository == null) {
        _state = ReviewCreateSuccess(created);
        _safeNotifyListeners();
        return true;
      }

      // Upload sequencial controlado das mídias anexadas
      int uploadedCount = 0;
      int failedCount = 0;

      for (int i = 0; i < _selectedMedia.length; i++) {
        if (_isDisposed) return false;
        final item = _selectedMedia[i];

        // Validação preventiva de tamanho no cliente
        if (item.sizeBytes > SelectedMediaItem.maxSizeBytes) {
          _selectedMedia[i] = item.copyWith(
            status: MediaUploadStatus.failed,
            errorCode: 'MEDIA_SIZE_EXCEEDED',
            errorMessage: 'O tamanho do arquivo excede o limite máximo permitido de 10 MB',
            isTerminalError: true,
          );
          failedCount++;
          _safeNotifyListeners();
          continue;
        }

        // Validação preventiva de formato no cliente
        if (!item.isFormatValid) {
          _selectedMedia[i] = item.copyWith(
            status: MediaUploadStatus.failed,
            errorCode: 'UNSUPPORTED_MEDIA_TYPE',
            errorMessage: 'Formato não suportado. Utilize apenas JPEG ou PNG.',
            isTerminalError: true,
          );
          failedCount++;
          _safeNotifyListeners();
          continue;
        }

        _selectedMedia[i] = item.copyWith(status: MediaUploadStatus.uploading);
        _safeNotifyListeners();

        try {
          final uploadedItem = await mediaRepository!.uploadMedia(
            reviewId: created.id,
            bytes: item.bytes,
            filename: item.name,
            mimeType: item.mimeType,
          );
          if (_isDisposed) return false;
          _selectedMedia[i] = item.copyWith(
            status: MediaUploadStatus.uploaded,
            uploadedMedia: uploadedItem,
            errorMessage: null,
            errorCode: null,
          );
          uploadedCount++;
          _safeNotifyListeners();
        } on ApiException catch (e) {
          if (_isDisposed) return false;
          failedCount++;
          final isTerminal = e.statusCode == 413 ||
              e.statusCode == 415 ||
              e.statusCode == 403 ||
              e.errorCode == 'MEDIA_SIZE_EXCEEDED' ||
              e.errorCode == 'UNSUPPORTED_MEDIA_TYPE' ||
              e.errorCode == 'MAX_MEDIA_LIMIT_REACHED' ||
              e.errorCode == 'FORBIDDEN';
          _selectedMedia[i] = item.copyWith(
            status: MediaUploadStatus.failed,
            errorCode: e.errorCode,
            errorMessage: e.detail,
            isTerminalError: isTerminal,
            retryAfterSeconds: e.retryAfterSeconds,
          );
          _safeNotifyListeners();
        } catch (_) {
          if (_isDisposed) return false;
          failedCount++;
          _selectedMedia[i] = item.copyWith(
            status: MediaUploadStatus.failed,
            errorMessage: 'Falha no envio da imagem.',
            isTerminalError: false,
          );
          _safeNotifyListeners();
        }
      }

      if (_isDisposed) return false;

      _state = ReviewCreateSuccess(
        created,
        uploadedMediaCount: uploadedCount,
        failedMediaCount: failedCount,
        mediaErrorMessage: failedCount > 0
            ? '$failedCount anexo(s) falharam no upload.'
            : null,
      );
      _safeNotifyListeners();
      return true;
    } on ApiException catch (e) {
      if (_isDisposed) return false;
      _state = ReviewCreateError(
        message: e.detail,
        statusCode: e.statusCode,
        errorCode: e.errorCode,
        retryAfterSeconds: e.retryAfterSeconds,
        problemDetail: e.problemDetail,
      );
      _safeNotifyListeners();
      return false;
    } on NetworkException catch (e) {
      if (_isDisposed) return false;
      _state = ReviewCreateError(
        message: e.message,
      );
      _safeNotifyListeners();
      return false;
    } on FormatException catch (e) {
      if (_isDisposed) return false;
      _state = ReviewCreateError(
        message: e.message,
      );
      _safeNotifyListeners();
      return false;
    } catch (_) {
      if (_isDisposed) return false;
      _state = const ReviewCreateError(
        message: 'Ocorreu um erro inesperado ao publicar sua avaliação.',
      );
      _safeNotifyListeners();
      return false;
    }
  }

  /// Limpa o estado e redefine os campos do formulário para o padrão.
  void reset() {
    if (_isDisposed) return;
    _state = const ReviewCreateInitial();
    _targets.clear();
    _targets.add(
      const CreateReviewTargetInput(
        rateableTargetId: '',
        rating: 5.0,
        specificComment: null,
      ),
    );
    _selectedMedia.clear();
    _contextPlaceId = null;
    _experienceText = null;
    _isAnonymous = false;
    _visibility = 'PUBLIC';
    _userLatitude = null;
    _userLongitude = null;
    _locationAccuracyMeters = null;
    _locationStatus = LocationCaptureStatus.idle;
    _locationErrorMessage = null;
    _locationFailureReason = null;
    _safeNotifyListeners();
  }
}
