import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import '../../domain/entities/review_creation_input.dart';
import '../../domain/repositories/review_creation_repository.dart';
import 'review_create_state.dart';

/// Gerenciador de estado reativo para o formulário e submissão de avaliações.
class ReviewCreateNotifier extends ChangeNotifier {
  final ReviewCreationRepository repository;

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

  String? _contextPlaceId;
  String? _experienceText;
  bool _isAnonymous = false;
  String _visibility = 'PUBLIC';
  double? _userLatitude;
  double? _userLongitude;
  double? _locationAccuracyMeters;

  ReviewCreateNotifier({required this.repository});

  List<CreateReviewTargetInput> get targets => List.unmodifiable(_targets);
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

    return null;
  }

  /// Submete a criação da avaliação ao backend com proteção contra cliques múltiplos.
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
      _state = ReviewCreateSuccess(created);
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
