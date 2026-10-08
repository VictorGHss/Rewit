import 'dart:async';
import 'package:flutter/foundation.dart';
import '../../../../core/error/api_exception.dart';
import '../../domain/entities/search_entities.dart';
import '../../domain/entities/search_filter.dart';
import '../../domain/repositories/search_repository.dart';
import 'search_state.dart';

/// Gerenciador de estado explícito para a Busca Global (C5.1).
///
/// Encapsula debounce (350ms), cache de sessão em memória, proteção contra respostas
/// fora de ordem (stale responses), scroll infinito paginado e filtragem client-side.
class SearchNotifier extends ChangeNotifier {
  final SearchRepository searchRepository;
  final int pageSize;
  final Duration debounceDuration;

  SearchNotifier({
    required this.searchRepository,
    this.pageSize = 20,
    this.debounceDuration = const Duration(milliseconds: 350),
  });

  SearchState _state = const SearchInitial();
  SearchState get state => _state;

  Timer? _debounceTimer;
  int _searchSequence = 0;
  bool _isDisposed = false;

  // Cache em memória durante a sessão da tela para evitar requisições idênticas
  final Map<String, SearchPage> _queryCache = {};

  /// Chamado a cada caractere digitado no campo de busca.
  void onQueryChanged(String query) {
    _debounceTimer?.cancel();
    final trimmed = query.trim();

    if (trimmed.isEmpty) {
      _state = const SearchInitial();
      notifyListeners();
      return;
    }

    if (trimmed.length < 2) {
      _state = SearchInitial(query: trimmed, filter: _state.filter);
      notifyListeners();
      return;
    }

    _debounceTimer = Timer(debounceDuration, () {
      search(trimmed);
    });
  }

  /// Executa a busca textual no catálogo.
  Future<void> search(String query, {bool refresh = false}) async {
    _debounceTimer?.cancel();
    final trimmed = query.trim();

    if (trimmed.isEmpty) {
      _state = const SearchInitial();
      notifyListeners();
      return;
    }

    if (trimmed.length < 2) {
      _state = SearchInitial(query: trimmed, filter: _state.filter);
      notifyListeners();
      return;
    }

    final normalized = trimmed.toLowerCase();

    // 1. Consulta cache de sessão em memória se não for atualização forçada
    if (!refresh && _queryCache.containsKey(normalized)) {
      final cachedPage = _queryCache[normalized]!;
      if (cachedPage.items.isEmpty) {
        _state = SearchEmpty(query: trimmed, filter: _state.filter);
      } else {
        _state = SearchResults(
          query: trimmed,
          allItems: cachedPage.items,
          page: cachedPage.pageNumber,
          totalElements: cachedPage.totalElements,
          isLast: cachedPage.isLast,
          filter: _state.filter,
        );
      }
      notifyListeners();
      return;
    }

    // 2. Incrementa sequência para descartar respostas defasadas
    final currentSeq = ++_searchSequence;
    _state = SearchLoading(query: trimmed, filter: _state.filter);
    notifyListeners();

    try {
      final page = await searchRepository.search(
        query: trimmed,
        page: 0,
        size: pageSize,
      );

      if (_isDisposed || currentSeq != _searchSequence) return;

      _queryCache[normalized] = page;

      if (page.items.isEmpty) {
        _state = SearchEmpty(query: trimmed, filter: _state.filter);
      } else {
        _state = SearchResults(
          query: trimmed,
          allItems: page.items,
          page: page.pageNumber,
          totalElements: page.totalElements,
          isLast: page.isLast,
          filter: _state.filter,
        );
      }
      notifyListeners();
    } on ApiException catch (e) {
      if (_isDisposed || currentSeq != _searchSequence) return;
      _state = SearchError(
        query: trimmed,
        message: e.detail,
        retryAfterSeconds: e.retryAfterSeconds,
        filter: _state.filter,
      );
      notifyListeners();
    } on NetworkException catch (e) {
      if (_isDisposed || currentSeq != _searchSequence) return;
      _state = SearchError(
        query: trimmed,
        message: e.message,
        filter: _state.filter,
      );
      notifyListeners();
    } catch (_) {
      if (_isDisposed || currentSeq != _searchSequence) return;
      _state = SearchError(
        query: trimmed,
        message: 'Não foi possível buscar itens no momento.',
        filter: _state.filter,
      );
      notifyListeners();
    }
  }

  /// Carrega a próxima página de resultados (scroll infinito).
  Future<void> loadMore() async {
    if (_state is! SearchResults) return;
    final currentResults = _state as SearchResults;

    if (currentResults.isLast || currentResults.isLoadingMore) return;

    _state = currentResults.copyWith(
      isLoadingMore: true,
      clearLoadMoreError: true,
    );
    notifyListeners();

    final nextPage = currentResults.page + 1;
    final currentSeq = _searchSequence;

    try {
      final page = await searchRepository.search(
        query: currentResults.query,
        page: nextPage,
        size: pageSize,
      );

      if (_isDisposed || currentSeq != _searchSequence) return;

      // Deduplicação estrita de itens para evitar chaves repetidas
      final existingIds = currentResults.allItems.map((e) => e.id).toSet();
      final newItems = page.items.where((e) => !existingIds.contains(e.id)).toList();
      final combined = [...currentResults.allItems, ...newItems];

      // Atualiza o cache da query em memória
      final normalized = currentResults.query.toLowerCase();
      _queryCache[normalized] = SearchPage(
        items: combined,
        pageNumber: page.pageNumber,
        pageSize: page.pageSize,
        totalElements: page.totalElements,
        totalPages: page.totalPages,
        isLast: page.isLast,
      );

      _state = currentResults.copyWith(
        allItems: combined,
        page: page.pageNumber,
        totalElements: page.totalElements,
        isLast: page.isLast,
        isLoadingMore: false,
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (_isDisposed || currentSeq != _searchSequence) return;
      _state = currentResults.copyWith(
        isLoadingMore: false,
        loadMoreError: e.detail,
        loadMoreRetryAfterSeconds: e.retryAfterSeconds,
      );
      notifyListeners();
    } on NetworkException catch (e) {
      if (_isDisposed || currentSeq != _searchSequence) return;
      _state = currentResults.copyWith(
        isLoadingMore: false,
        loadMoreError: e.message,
      );
      notifyListeners();
    } catch (_) {
      if (_isDisposed || currentSeq != _searchSequence) return;
      _state = currentResults.copyWith(
        isLoadingMore: false,
        loadMoreError: 'Falha ao carregar mais resultados.',
      );
      notifyListeners();
    }
  }

  /// Altera o filtro ativo (Todos, Locais ou Produtos).
  void setFilter(SearchFilter filter) {
    if (_state.filter == filter) return;
    if (_state is SearchResults) {
      _state = (_state as SearchResults).copyWith(filter: filter);
    } else if (_state is SearchEmpty) {
      _state = SearchEmpty(query: _state.query, filter: filter);
    } else if (_state is SearchLoading) {
      _state = SearchLoading(query: _state.query, filter: filter);
    } else if (_state is SearchError) {
      final err = _state as SearchError;
      _state = SearchError(
        query: err.query,
        message: err.message,
        retryAfterSeconds: err.retryAfterSeconds,
        filter: filter,
      );
    } else {
      _state = SearchInitial(query: _state.query, filter: filter);
    }
    notifyListeners();
  }

  /// Retenta a última operação com falha.
  void retry() {
    if (_state is SearchError) {
      search(_state.query, refresh: true);
    } else if (_state is SearchResults && (_state as SearchResults).loadMoreError != null) {
      loadMore();
    }
  }

  /// Limpa a busca e redefine para o estado inicial.
  void clear() {
    _debounceTimer?.cancel();
    _state = const SearchInitial();
    notifyListeners();
  }

  @override
  void dispose() {
    _isDisposed = true;
    _debounceTimer?.cancel();
    super.dispose();
  }
}
