import 'package:flutter/foundation.dart';
import '../../../../core/error/api_exception.dart';
import '../../../search/domain/entities/search_entities.dart';
import '../../../search/domain/repositories/search_repository.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../../domain/repositories/place_commercial_repository.dart';

/// Notifier para gestão das ofertas comerciais oficiais de um estabelecimento (F2.3).
///
/// Suporta:
/// - Listagem paginada e reconciliação sem duplicação de produtos.
/// - Publicação ou atualização de termos comerciais (disponibilidade, preço em BRL).
/// - Retirada da vitrine via DELETE idempotente.
/// - Busca de produtos globais elegíveis no catálogo unificado (Search V1).
class PlaceOffersManagementNotifier extends ChangeNotifier {
  final PlaceCommercialRepository repository;
  final SearchRepository? searchRepository;

  final List<PlaceProductOffer> _offers = [];
  bool _isLoading = false;
  bool _isLoadingMore = false;
  bool _isSaving = false;
  bool _isDeleting = false;
  String? _deletingProductId;
  String? _errorMessage;
  String? _successMessage;

  int _currentPage = 0;
  int _totalElements = 0;
  bool _isLastPage = true;

  // Estado da busca de produtos globais
  List<SearchResultItem> _searchResults = [];
  bool _isSearching = false;
  String? _searchErrorMessage;
  String _searchQuery = '';
  int _searchSequence = 0;

  bool _disposed = false;

  PlaceOffersManagementNotifier({
    required this.repository,
    this.searchRepository,
  });

  List<PlaceProductOffer> get offers => List.unmodifiable(_offers);
  bool get isLoading => _isLoading;
  bool get isLoadingMore => _isLoadingMore;
  bool get isSaving => _isSaving;
  bool get isDeleting => _isDeleting;
  String? get deletingProductId => _deletingProductId;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;
  int get currentPage => _currentPage;
  int get totalElements => _totalElements;
  bool get isLastPage => _isLastPage;
  bool get isEmpty => _offers.isEmpty && !_isLoading;

  List<SearchResultItem> get searchResults => List.unmodifiable(_searchResults);
  bool get isSearching => _isSearching;
  String? get searchErrorMessage => _searchErrorMessage;
  String get searchQuery => _searchQuery;

  /// Carrega as ofertas existentes do estabelecimento a partir da primeira página.
  Future<void> loadOffers(String placeId, {bool refresh = false}) async {
    if (_isLoading) return;
    _isLoading = true;
    _errorMessage = null;
    _successMessage = null;
    _notifySafely();

    try {
      final page = await repository.getOffers(placeId, page: 0, size: 20);
      _offers.clear();
      _offers.addAll(page.content);
      _currentPage = page.pageNumber;
      _totalElements = page.totalElements;
      _isLastPage = page.isLast;
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao carregar ofertas comerciais do estabelecimento.';
    } finally {
      _isLoading = false;
      _notifySafely();
    }
  }

  /// Carrega a próxima página de ofertas, deduplicando por produto.
  Future<void> loadMoreOffers(String placeId) async {
    if (_isLoadingMore || _isLastPage || _isLoading) return;

    _isLoadingMore = true;
    _notifySafely();

    try {
      final nextPage = await repository.getOffers(
        placeId,
        page: _currentPage + 1,
        size: 20,
      );

      for (final offer in nextPage.content) {
        final existingIndex = _offers.indexWhere((o) => o.product.id == offer.product.id);
        if (existingIndex == -1) {
          _offers.add(offer);
        } else {
          _offers[existingIndex] = offer;
        }
      }

      _currentPage = nextPage.pageNumber;
      _totalElements = nextPage.totalElements;
      _isLastPage = nextPage.isLast;
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao carregar mais ofertas.';
    } finally {
      _isLoadingMore = false;
      _notifySafely();
    }
  }

  /// Publica ou atualiza a oferta de um produto no estabelecimento.
  Future<bool> upsertOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
    required String availability,
    double? price,
    String currency = 'BRL',
  }) async {
    if (_isSaving) return false;

    _errorMessage = null;
    _successMessage = null;

    if (price != null) {
      if (price < 0) {
        _errorMessage = 'O preço não pode ser negativo.';
        _notifySafely();
        return false;
      }
      if (price > 9999999999.99) {
        _errorMessage = 'O preço excede o limite máximo permitido.';
        _notifySafely();
        return false;
      }
    }

    _isSaving = true;
    _notifySafely();

    try {
      final savedOffer = await repository.upsertOffer(
        placeId: placeId,
        productId: productId,
        businessAccountId: businessAccountId,
        availability: availability,
        price: price,
        currency: currency,
      );

      final index = _offers.indexWhere((o) => o.product.id == productId);
      if (index != -1) {
        _offers[index] = savedOffer;
      } else {
        _offers.insert(0, savedOffer);
        _totalElements++;
      }

      _successMessage = index != -1
          ? 'Oferta atualizada com sucesso.'
          : 'Oferta publicada com sucesso.';
      return true;
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao salvar oferta comercial.';
      return false;
    } finally {
      _isSaving = false;
      _notifySafely();
    }
  }

  /// Retira da vitrine a oferta comercial de um produto no estabelecimento.
  Future<bool> deleteOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
  }) async {
    if (_isDeleting) return false;

    _errorMessage = null;
    _successMessage = null;
    _isDeleting = true;
    _deletingProductId = productId;
    _notifySafely();

    try {
      await repository.deleteOffer(
        placeId: placeId,
        productId: productId,
        businessAccountId: businessAccountId,
      );

      _offers.removeWhere((o) => o.product.id == productId);
      if (_totalElements > 0) {
        _totalElements--;
      }

      _successMessage = 'Oferta retirada da vitrine.';
      return true;
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao retirar oferta da vitrine.';
      return false;
    } finally {
      _isDeleting = false;
      _deletingProductId = null;
      _notifySafely();
    }
  }

  /// Busca produtos no catálogo unificado (Search V1), filtrando apenas alvos de tipo PRODUCT e status ACTIVE.
  Future<void> searchProducts(String query) async {
    _searchQuery = query;
    final currentSeq = ++_searchSequence;

    if (query.trim().length < 2) {
      _searchResults = [];
      _searchErrorMessage = null;
      _isSearching = false;
      _notifySafely();
      return;
    }

    _isSearching = true;
    _searchErrorMessage = null;
    _notifySafely();

    if (searchRepository == null) {
      _searchErrorMessage = 'Mecanismo de busca de catálogo indisponível.';
      _isSearching = false;
      _notifySafely();
      return;
    }

    try {
      final page = await searchRepository!.search(
        query: query.trim(),
        page: 0,
        size: 20,
      );

      // Descarta resposta se uma busca mais recente tiver sido iniciada
      if (currentSeq != _searchSequence) return;

      // Filtra estritamente alvos que são produtos e estão ativos no catálogo
      _searchResults = page.items
          .where((item) => item.isProduct && item.status.toUpperCase() == 'ACTIVE')
          .toList();
    } catch (e) {
      if (currentSeq != _searchSequence) return;
      _searchErrorMessage = e is ApiException
          ? e.detail
          : 'Falha na busca de produtos no catálogo.';
      _searchResults = [];
    } finally {
      if (currentSeq == _searchSequence) {
        _isSearching = false;
        _notifySafely();
      }
    }
  }

  /// Limpa os resultados e o termo da busca de produtos.
  void clearSearch() {
    _searchQuery = '';
    _searchResults = [];
    _searchErrorMessage = null;
    _isSearching = false;
    _notifySafely();
  }

  /// Verifica se um produto já possui oferta cadastrada neste estabelecimento.
  PlaceProductOffer? findExistingOffer(String productId) {
    try {
      return _offers.firstWhere((o) => o.product.id == productId);
    } catch (_) {
      return null;
    }
  }

  void clearMessages() {
    _errorMessage = null;
    _successMessage = null;
    _notifySafely();
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

