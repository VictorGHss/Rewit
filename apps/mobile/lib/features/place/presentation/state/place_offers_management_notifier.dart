import 'package:flutter/foundation.dart';
import '../../../../core/error/api_exception.dart';
import '../../../search/domain/entities/search_entities.dart';
import '../../../search/domain/repositories/search_repository.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../../domain/repositories/place_commercial_repository.dart';

/// Notifier para gestão das ofertas comerciais oficiais de um estabelecimento (F2.3).
///
/// Suporta:
/// - Listagem paginada e sincronização consistente a partir da API real após mutações.
/// - Publicação ou atualização de termos comerciais (disponibilidade, preço em BRL com validação decimal estrita).
/// - Retirada da vitrine via DELETE idempotente e re-sincronização de offsets.
/// - Busca de produtos globais elegíveis no catálogo unificado (Search V1) com cancelamento de buscas desatualizadas.
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
  int _loadSequence = 0;

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
  ///
  /// Se [refresh] for verdadeiro, anula qualquer carregamento anterior e força uma nova busca da página 0.
  Future<void> loadOffers(String placeId, {bool refresh = false}) async {
    if (_isLoading && !refresh) return;

    final currentSeq = ++_loadSequence;
    _isLoading = true;
    _errorMessage = null;
    _successMessage = null;
    _notifySafely();

    try {
      final page = await repository.getOffers(placeId, page: 0, size: 20);
      if (currentSeq != _loadSequence) return;

      _offers.clear();
      _offers.addAll(page.content);
      _currentPage = page.pageNumber;
      _totalElements = page.totalElements;
      _isLastPage = page.isLast;
    } catch (e) {
      if (currentSeq != _loadSequence) return;
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao carregar ofertas comerciais do estabelecimento.';
    } finally {
      if (currentSeq == _loadSequence) {
        _isLoading = false;
        _notifySafely();
      }
    }
  }

  /// Carrega a próxima página de ofertas, deduplicando por produto.
  Future<void> loadMoreOffers(String placeId) async {
    if (_isLoadingMore || _isLastPage || _isLoading) return;

    final currentSeq = _loadSequence;
    _isLoadingMore = true;
    _notifySafely();

    try {
      final nextPage = await repository.getOffers(
        placeId,
        page: _currentPage + 1,
        size: 20,
      );

      if (currentSeq != _loadSequence) return;

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
      if (currentSeq != _loadSequence) return;
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao carregar mais ofertas.';
    } finally {
      if (currentSeq == _loadSequence) {
        _isLoadingMore = false;
        _notifySafely();
      }
    }
  }

  /// Publica ou atualiza a oferta de um produto no estabelecimento.
  ///
  /// Valida estritamente a precisão decimal do preço (máximo 2 casas decimais).
  /// Após persistência bem-sucedida, sincroniza a listagem com a API real reiniciando a paginação.
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
      final roundedTo2 = double.parse(price.toStringAsFixed(2));
      if ((price - roundedTo2).abs() > 1e-6) {
        _errorMessage = 'O preço não pode ter mais de duas casas decimais.';
        _notifySafely();
        return false;
      }
    }

    _isSaving = true;
    final isExisting = _offers.any((o) => o.product.id == productId);
    final currentLoadSeq = ++_loadSequence;
    _notifySafely();

    try {
      await repository.upsertOffer(
        placeId: placeId,
        productId: productId,
        businessAccountId: businessAccountId,
        availability: availability,
        price: price,
        currency: currency,
      );

      // Sincroniza os dados reais do backend para manter ordenação e offsets consistentes
      final freshPage = await repository.getOffers(placeId, page: 0, size: 20);
      if (currentLoadSeq == _loadSequence) {
        _offers.clear();
        _offers.addAll(freshPage.content);
        _currentPage = freshPage.pageNumber;
        _totalElements = freshPage.totalElements;
        _isLastPage = freshPage.isLast;
        _successMessage = isExisting
            ? 'Oferta atualizada com sucesso.'
            : 'Oferta publicada com sucesso.';
      }
      return true;
    } catch (e) {
      if (currentLoadSeq == _loadSequence) {
        _errorMessage = e is ApiException
            ? e.detail
            : 'Falha ao salvar oferta comercial.';
      }
      return false;
    } finally {
      if (currentLoadSeq == _loadSequence) {
        _isSaving = false;
        _notifySafely();
      }
    }
  }

  /// Retira da vitrine a oferta comercial de um produto no estabelecimento.
  ///
  /// Após deleção na API real, sincroniza a listagem a partir da primeira página.
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
    final currentLoadSeq = ++_loadSequence;
    _notifySafely();

    try {
      await repository.deleteOffer(
        placeId: placeId,
        productId: productId,
        businessAccountId: businessAccountId,
      );

      // Sincroniza os dados reais do backend reiniciando o cursor da listagem
      final freshPage = await repository.getOffers(placeId, page: 0, size: 20);
      if (currentLoadSeq == _loadSequence) {
        _offers.clear();
        _offers.addAll(freshPage.content);
        _currentPage = freshPage.pageNumber;
        _totalElements = freshPage.totalElements;
        _isLastPage = freshPage.isLast;
        _successMessage = 'Oferta retirada da vitrine.';
      }
      return true;
    } catch (e) {
      if (currentLoadSeq == _loadSequence) {
        _errorMessage = e is ApiException
            ? e.detail
            : 'Falha ao retirar oferta da vitrine.';
      }
      return false;
    } finally {
      if (currentLoadSeq == _loadSequence) {
        _isDeleting = false;
        _deletingProductId = null;
        _notifySafely();
      }
    }
  }

  /// Busca produtos no catálogo unificado (Search V1), filtrando apenas alvos de tipo PRODUCT e status ACTIVE.
  ///
  /// Limpa resultados imediatamente para evitar apresentar dados de consultas anteriores durante digitação.
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

    _searchResults = [];
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

      // Descarta resposta se uma busca mais recente ou clearSearch tiver sido acionada
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

  /// Limpa os resultados e o termo da busca de produtos, invalidando requisições em trânsito.
  void clearSearch() {
    _searchSequence++;
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
