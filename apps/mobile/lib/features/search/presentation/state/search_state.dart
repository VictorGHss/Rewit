import 'package:flutter/foundation.dart';
import '../../domain/entities/search_entities.dart';
import '../../domain/entities/search_filter.dart';

/// Hierarquia explícita e imutável de estados para o fluxo de Busca Global (C5.1).
@immutable
sealed class SearchState {
  final String query;
  final SearchFilter filter;

  const SearchState({
    this.query = '',
    this.filter = SearchFilter.all,
  });
}

/// Estado inicial: campo de busca limpo ou orientações preliminares ao usuário.
class SearchInitial extends SearchState {
  const SearchInitial({
    super.query = '',
    super.filter = SearchFilter.all,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SearchInitial &&
          runtimeType == other.runtimeType &&
          query == other.query &&
          filter == other.filter;

  @override
  int get hashCode => Object.hash(query, filter);
}

/// Estado de carregamento da primeira página de busca.
class SearchLoading extends SearchState {
  const SearchLoading({
    required super.query,
    super.filter = SearchFilter.all,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SearchLoading &&
          runtimeType == other.runtimeType &&
          query == other.query &&
          filter == other.filter;

  @override
  int get hashCode => Object.hash(query, filter);
}

/// Estado de resultados com dados paginados do catálogo.
class SearchResults extends SearchState {
  final List<SearchResultItem> allItems;
  final int page;
  final int totalElements;
  final bool isLast;
  final bool isLoadingMore;
  final String? loadMoreError;
  final int? loadMoreRetryAfterSeconds;

  const SearchResults({
    required super.query,
    required this.allItems,
    required this.page,
    required this.totalElements,
    required this.isLast,
    super.filter = SearchFilter.all,
    this.isLoadingMore = false,
    this.loadMoreError,
    this.loadMoreRetryAfterSeconds,
  });

  /// Resultados visivelmente filtrados pela categoria ativa no cliente.
  ///
  /// Como o endpoint Search V1 (`GET /api/v1/search`) não possui parâmetro de filtro por
  /// tipo na API, o filtro de Place/Product é aplicado estritamente no cliente sobre os
  /// itens acumulados e já paginados.
  List<SearchResultItem> get filteredItems {
    switch (filter) {
      case SearchFilter.all:
        return allItems;
      case SearchFilter.places:
        return allItems.where((item) => item.isPlace).toList();
      case SearchFilter.products:
        return allItems.where((item) => item.isProduct).toList();
    }
  }

  SearchResults copyWith({
    String? query,
    List<SearchResultItem>? allItems,
    int? page,
    int? totalElements,
    bool? isLast,
    SearchFilter? filter,
    bool? isLoadingMore,
    String? loadMoreError,
    int? loadMoreRetryAfterSeconds,
    bool clearLoadMoreError = false,
  }) {
    return SearchResults(
      query: query ?? this.query,
      allItems: allItems ?? this.allItems,
      page: page ?? this.page,
      totalElements: totalElements ?? this.totalElements,
      isLast: isLast ?? this.isLast,
      filter: filter ?? this.filter,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      loadMoreError: clearLoadMoreError ? null : (loadMoreError ?? this.loadMoreError),
      loadMoreRetryAfterSeconds: clearLoadMoreError
          ? null
          : (loadMoreRetryAfterSeconds ?? this.loadMoreRetryAfterSeconds),
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SearchResults &&
          runtimeType == other.runtimeType &&
          query == other.query &&
          page == other.page &&
          totalElements == other.totalElements &&
          isLast == other.isLast &&
          filter == other.filter &&
          isLoadingMore == other.isLoadingMore &&
          loadMoreError == other.loadMoreError &&
          loadMoreRetryAfterSeconds == other.loadMoreRetryAfterSeconds &&
          listEquals(allItems, other.allItems);

  @override
  int get hashCode => Object.hash(
        query,
        page,
        totalElements,
        isLast,
        filter,
        isLoadingMore,
        loadMoreError,
        loadMoreRetryAfterSeconds,
        Object.hashAll(allItems),
      );
}

/// Estado de busca finalizada com zero resultados encontrados no catálogo.
class SearchEmpty extends SearchState {
  const SearchEmpty({
    required super.query,
    super.filter = SearchFilter.all,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SearchEmpty &&
          runtimeType == other.runtimeType &&
          query == other.query &&
          filter == other.filter;

  @override
  int get hashCode => Object.hash(query, filter);
}

/// Estado de falha inicial retornado pela API (RFC 7807) ou por problemas de rede.
class SearchError extends SearchState {
  final String message;
  final int? retryAfterSeconds;

  const SearchError({
    required super.query,
    required this.message,
    this.retryAfterSeconds,
    super.filter = SearchFilter.all,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SearchError &&
          runtimeType == other.runtimeType &&
          query == other.query &&
          message == other.message &&
          retryAfterSeconds == other.retryAfterSeconds &&
          filter == other.filter;

  @override
  int get hashCode => Object.hash(query, message, retryAfterSeconds, filter);
}
