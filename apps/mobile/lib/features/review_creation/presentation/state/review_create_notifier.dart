import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import '../../domain/entities/review_creation_input.dart';
import '../../domain/repositories/review_creation_repository.dart';
import 'review_create_state.dart';

/// Gerenciador de estado reativo para o formulário e submissão de avaliações.
class ReviewCreateNotifier extends ChangeNotifier {
  final ReviewCreationRepository repository;
  final ReviewMediaRepository? mediaRepository;
  final MediaPickerService? mediaPickerService;

  static final RegExp _uuidRegex = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );

  ReviewCreateState _state = const ReviewCreateInitial();
  ReviewCreateState get state => _state;

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

  ReviewCreateNotifier({
    required this.repository,
    this.mediaRepository,
    this.mediaPickerService,
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

  /// Adiciona um novo alvo para avaliação multi-alvo (Step 11.0).
  void addTarget({
    String targetId = '',
    double rating = 5.0,
    String? comment,
    String? targetName,
    String? targetType,
    String? category,
  }) {
    if (isSubmitting) return;
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
    notifyListeners();
  }

  /// Remove um alvo pelo índice (mantendo pelo menos 1 alvo).
  void removeTarget(int index) {
    if (isSubmitting) return;
    if (_targets.length > 1 && index >= 0 && index < _targets.length) {
      _targets.removeAt(index);
      notifyListeners();
    }
  }

  /// Seleciona um item retornado pela busca no alvo do índice especificado.
  void selectTarget(int index, SearchResultItem item) {
    if (isSubmitting) return;
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
      notifyListeners();
    }
  }

  /// Limpa a seleção do alvo no índice especificado, reabrindo a busca.
  void clearTargetSelection(int index) {
    if (isSubmitting) return;
    if (index >= 0 && index < _targets.length) {
      final current = _targets[index];
      _targets[index] = CreateReviewTargetInput(
        rateableTargetId: '',
        rating: current.rating,
        specificComment: current.specificComment,
      );
      notifyListeners();
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
    if (isSubmitting) return;
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
      notifyListeners();
    }
  }

  /// Atualiza o local de contexto (estabelecimento principal).
  void setContextPlaceId(String? placeId) {
    if (isSubmitting) return;
    _contextPlaceId = placeId?.trim().isEmpty ?? true ? null : placeId!.trim();
    notifyListeners();
  }

  /// Atualiza o texto geral de relato da experiência.
  void setExperienceText(String? text) {
    if (isSubmitting) return;
    _experienceText = text;
    notifyListeners();
  }

  /// Alterna o modo de publicação anônima.
  void setAnonymous(bool value) {
    if (isSubmitting) return;
    _isAnonymous = value;
    notifyListeners();
  }

  /// Define o nível de visibilidade da publicação.
  void setVisibility(String value) {
    if (isSubmitting) return;
    _visibility = value;
    notifyListeners();
  }

  /// Define coordenadas de presença para check-in no local.
  void setCoordinates({
    double? latitude,
    double? longitude,
    double? accuracyMeters,
  }) {
    if (isSubmitting) return;
    _userLatitude = latitude;
    _userLongitude = longitude;
    _locationAccuracyMeters = accuracyMeters;
    notifyListeners();
  }

  /// Remove coordenadas de presença.
  void clearCoordinates() {
    if (isSubmitting) return;
    _userLatitude = null;
    _userLongitude = null;
    _locationAccuracyMeters = null;
    notifyListeners();
  }

  /// Seleciona mídias da galeria até o limite restante de 5 anexos.
  Future<void> pickMedia() async {
    if (isSubmitting || mediaPickerService == null) return;
    final remainingSlots = SelectedMediaItem.maxItemsPerReview - _selectedMedia.length;
    if (remainingSlots <= 0) return;

    final picked = await mediaPickerService!.pickImages(maxImages: remainingSlots);
    if (picked.isNotEmpty) {
      final toAdd = picked.take(remainingSlots);
      _selectedMedia.addAll(toAdd);
      notifyListeners();
    }
  }

  /// Adiciona diretamente um item de mídia selecionado (útil para testes ou integrações diretas).
  void addSelectedMediaItem(SelectedMediaItem item) {
    if (isSubmitting) return;
    if (_selectedMedia.length < SelectedMediaItem.maxItemsPerReview) {
      _selectedMedia.add(item);
      notifyListeners();
    }
  }

  /// Remove uma mídia selecionada pelo índice.
  void removeSelectedMediaItem(int index) {
    if (isSubmitting) return;
    if (index >= 0 && index < _selectedMedia.length) {
      _selectedMedia.removeAt(index);
      notifyListeners();
    }
  }

  /// Limpa todas as mídias selecionadas.
  void clearSelectedMedia() {
    if (isSubmitting) return;
    _selectedMedia.clear();
    notifyListeners();
  }

  /// Reenvia uma mídia que falhou durante o envio pós-publicação.
  Future<bool> retryMediaUpload(int index, String reviewId) async {
    if (mediaRepository == null || index < 0 || index >= _selectedMedia.length) {
      return false;
    }
    final item = _selectedMedia[index];
    if (item.isTerminalError || item.status == MediaUploadStatus.uploaded) {
      return false;
    }

    _selectedMedia[index] = item.copyWith(status: MediaUploadStatus.uploading);
    notifyListeners();

    try {
      final uploaded = await mediaRepository!.uploadMedia(
        reviewId: reviewId,
        bytes: item.bytes,
        filename: item.name,
        mimeType: item.mimeType,
      );
      _selectedMedia[index] = item.copyWith(
        status: MediaUploadStatus.uploaded,
        uploadedMedia: uploaded,
        errorMessage: null,
        errorCode: null,
      );
      notifyListeners();
      return true;
    } on ApiException catch (e) {
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
      notifyListeners();
      return false;
    } catch (_) {
      _selectedMedia[index] = item.copyWith(
        status: MediaUploadStatus.failed,
        errorMessage: 'Falha ao reenviar anexo de imagem.',
        isTerminalError: false,
      );
      notifyListeners();
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
    if (isSubmitting) return false;

    final validationError = validate();
    if (validationError != null) {
      _state = ReviewCreateError(
        message: validationError,
        statusCode: 422,
        errorCode: 'VALIDATION_ERROR',
      );
      notifyListeners();
      return false;
    }

    _state = const ReviewCreateSubmitting();
    notifyListeners();

    try {
      final input = CreateReviewInput(
        contextPlaceId: _contextPlaceId,
        experienceText: _experienceText,
        isAnonymous: _isAnonymous,
        visibility: _visibility,
        userLatitude: _userLatitude,
        userLongitude: _userLongitude,
        locationAccuracyMeters: _locationAccuracyMeters,
        targets: _targets
            .map((t) => t.copyWith(rateableTargetId: t.rateableTargetId.trim()))
            .toList(),
      );

      final created = await repository.createReview(input);

      if (_selectedMedia.isEmpty || mediaRepository == null) {
        _state = ReviewCreateSuccess(created);
        notifyListeners();
        return true;
      }

      // Upload sequencial controlado das mídias anexadas
      int uploadedCount = 0;
      int failedCount = 0;

      for (int i = 0; i < _selectedMedia.length; i++) {
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
          notifyListeners();
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
          notifyListeners();
          continue;
        }

        _selectedMedia[i] = item.copyWith(status: MediaUploadStatus.uploading);
        notifyListeners();

        try {
          final uploadedItem = await mediaRepository!.uploadMedia(
            reviewId: created.id,
            bytes: item.bytes,
            filename: item.name,
            mimeType: item.mimeType,
          );
          _selectedMedia[i] = item.copyWith(
            status: MediaUploadStatus.uploaded,
            uploadedMedia: uploadedItem,
            errorMessage: null,
            errorCode: null,
          );
          uploadedCount++;
          notifyListeners();
        } on ApiException catch (e) {
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
          notifyListeners();
        } catch (_) {
          failedCount++;
          _selectedMedia[i] = item.copyWith(
            status: MediaUploadStatus.failed,
            errorMessage: 'Falha no envio da imagem.',
            isTerminalError: false,
          );
          notifyListeners();
        }
      }

      _state = ReviewCreateSuccess(
        created,
        uploadedMediaCount: uploadedCount,
        failedMediaCount: failedCount,
        mediaErrorMessage: failedCount > 0
            ? '$failedCount anexo(s) falharam no upload.'
            : null,
      );
      notifyListeners();
      return true;
    } on ApiException catch (e) {
      _state = ReviewCreateError(
        message: e.detail,
        statusCode: e.statusCode,
        errorCode: e.errorCode,
        retryAfterSeconds: e.retryAfterSeconds,
        problemDetail: e.problemDetail,
      );
      notifyListeners();
      return false;
    } on NetworkException catch (e) {
      _state = ReviewCreateError(
        message: e.message,
      );
      notifyListeners();
      return false;
    } on FormatException catch (e) {
      _state = ReviewCreateError(
        message: e.message,
      );
      notifyListeners();
      return false;
    } catch (_) {
      _state = const ReviewCreateError(
        message: 'Ocorreu um erro inesperado ao publicar sua avaliação.',
      );
      notifyListeners();
      return false;
    }
  }

  /// Limpa o estado e redefine os campos do formulário para o padrão.
  void reset() {
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
    notifyListeners();
  }
}
