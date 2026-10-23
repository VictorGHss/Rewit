import 'package:flutter/foundation.dart';
import '../../../../core/error/api_exception.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../../domain/repositories/place_commercial_repository.dart';

/// Notifier para consulta e edição das comodidades de um estabelecimento reivindicado (F2.2).
class PlaceAmenitiesEditNotifier extends ChangeNotifier {
  final PlaceCommercialRepository repository;

  bool _isLoading = false;
  bool _isSaving = false;
  String? _errorMessage;
  String? _successMessage;
  List<PlaceAmenityItem> _catalogOptions = [];
  Set<String> _selectedCodes = {};
  bool _configured = false;
  bool _disposed = false;

  PlaceAmenitiesEditNotifier({required this.repository});

  bool get isLoading => _isLoading;
  bool get isSaving => _isSaving;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;
  List<PlaceAmenityItem> get catalogOptions => List.unmodifiable(_catalogOptions);
  Set<String> get selectedCodes => Set.unmodifiable(_selectedCodes);
  bool get configured => _configured;
  bool get hasDeclaredNone => _configured && _selectedCodes.isEmpty;

  /// Carrega as opções disponíveis no catálogo e as comodidades já vinculadas ao local.
  Future<void> load(String placeId) async {
    if (_isLoading) return;
    _isLoading = true;
    _errorMessage = null;
    _successMessage = null;
    _notifySafely();

    try {
      final results = await Future.wait([
        repository.getAmenityOptions(),
        repository.getAmenities(placeId),
      ]);

      _catalogOptions = results[0] as List<PlaceAmenityItem>;
      final placeAmenities = results[1] as PlaceAmenities;

      _configured = placeAmenities.configured;
      _selectedCodes = placeAmenities.amenities.map((a) => a.code).toSet();
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao carregar catálogo de comodidades.';
    } finally {
      _isLoading = false;
      _notifySafely();
    }
  }

  /// Alterna a seleção de uma comodidade específica.
  void toggleAmenity(String code) {
    if (_selectedCodes.contains(code)) {
      _selectedCodes.remove(code);
    } else {
      _selectedCodes.add(code);
    }
    _errorMessage = null;
    _notifySafely();
  }

  /// Seleciona todas as opções do catálogo.
  void selectAll() {
    _selectedCodes = _catalogOptions.map((o) => o.code).toSet();
    _errorMessage = null;
    _notifySafely();
  }

  /// Desmarca todas as opções (declarando nenhuma).
  void clearAll() {
    _selectedCodes.clear();
    _errorMessage = null;
    _notifySafely();
  }

  /// Salva as comodidades selecionadas no backend.
  /// Envia lista vazia caso nenhuma esteja marcada, declarando formalmente ausência de comodidades.
  Future<bool> save({
    required String placeId,
    required String businessAccountId,
  }) async {
    if (_isSaving) return false;

    _errorMessage = null;
    _successMessage = null;
    _isSaving = true;
    _notifySafely();

    try {
      final updated = await repository.updateAmenities(
        placeId: placeId,
        businessAccountId: businessAccountId,
        amenityCodes: _selectedCodes.toList(),
      );

      _configured = updated.configured;
      _selectedCodes = updated.amenities.map((a) => a.code).toSet();
      _successMessage = 'Comodidades atualizadas com sucesso.';
      return true;
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao salvar comodidades do estabelecimento.';
      return false;
    } finally {
      _isSaving = false;
      _notifySafely();
    }
  }

  void _notifySafely() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
