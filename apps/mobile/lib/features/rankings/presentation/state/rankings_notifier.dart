import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';

import '../../domain/entities/ranking_entities.dart';
import '../../domain/repositories/ranking_repository.dart';

/// Estado da tela Rankings da Região: métrica, filtros, páginas e erros.
///
/// Cada mudança de métrica ou de filtro recomeça da primeira página e descarta o que veio antes; uma resposta de uma
/// consulta anterior, ou de outra sessão, nunca sobrescreve a seleção atual.
class RankingsNotifier extends ChangeNotifier {
  static const int pageSize = 20;

  /// O backend publica só o Top 100: a paginação para aí.
  static const int maxEntries = 100;

  final RankingRepository repository;

  /// Conta que abriu a tela; respostas que chegarem com outra sessão ativa são descartadas.
  final String userId;
  final String? Function() currentUserId;

  RankingsNotifier({required this.repository, required this.userId, required this.currentUserId});

  RankingMetric _metric = RankingMetric.checkIns;
  RankingFilters _filters = const RankingFilters();
  List<RankingEntry> _entries = const [];
  RankingPage? _lastPage;
  bool _loaded = false;
  bool _isLoading = false;
  bool _isLoadingMore = false;
  String? _errorMessage;
  String? _loadMoreError;
  bool _sessionEnded = false;
  int _requestId = 0;
  bool _disposed = false;

  RankingMetric get metric => _metric;
  RankingFilters get filters => _filters;
  List<RankingEntry> get entries => _entries;
  bool get isLoading => _isLoading;
  bool get isLoadingMore => _isLoadingMore;
  String? get errorMessage => _errorMessage;
  String? get loadMoreError => _loadMoreError;
  bool get sessionEnded => _sessionEnded;

  /// Sem UF não há consulta: a tela pede o estado.
  bool get needsState => !_filters.hasState;
  bool get isEmpty => _loaded && _entries.isEmpty;
  bool get hasMore => _loaded && !(_lastPage?.isLast ?? true) && _entries.length < maxEntries;
  bool get partialPeriod => _lastPage?.partialPeriod ?? false;
  DateTime? get periodStart => _lastPage?.periodStart;
  DateTime? get periodEndExclusive => _lastPage?.periodEndExclusive;

  void selectMetric(RankingMetric metric) {
    if (metric == _metric || _sessionEnded) return;
    _metric = metric;
    _reload();
  }

  void updateFilters(RankingFilters filters) {
    if (filters == _filters || _sessionEnded) return;
    _filters = filters;
    _reload();
  }

  /// Recarrega a primeira página com a seleção atual (nova tentativa, volta das configurações).
  Future<void> refresh() => _reload();

  Future<void> _reload() async {
    final requestId = ++_requestId;
    _entries = const [];
    _lastPage = null;
    _loaded = false;
    _errorMessage = null;
    _loadMoreError = null;
    _isLoadingMore = false;
    if (_sessionEnded || needsState) {
      _isLoading = false;
      _notify();
      return;
    }
    if (!_sameSession()) {
      _endSession();
      return;
    }
    _isLoading = true;
    _notify();

    final metric = _metric;
    final filters = _filters;
    try {
      final page = await repository.getRanking(metric, filters, page: 0, size: pageSize);
      if (requestId != _requestId) return;
      if (!_sameSession()) {
        _endSession();
        return;
      }
      _apply(page, append: false);
    } catch (e) {
      if (requestId != _requestId) return;
      _errorMessage = _describe(e, 'Não foi possível carregar o ranking.');
    } finally {
      if (requestId == _requestId) {
        _isLoading = false;
        _notify();
      }
    }
  }

  Future<void> loadMore() async {
    if (!hasMore || _isLoading || _isLoadingMore || _sessionEnded) return;
    final requestId = _requestId;
    final nextPage = _lastPage!.pageNumber + 1;
    _isLoadingMore = true;
    _loadMoreError = null;
    _notify();
    try {
      final page = await repository.getRanking(_metric, _filters, page: nextPage, size: pageSize);
      if (requestId != _requestId) return;
      if (!_sameSession()) {
        _endSession();
        return;
      }
      _apply(page, append: true);
    } catch (e) {
      if (requestId != _requestId) return;
      _loadMoreError = _describe(e, 'Não foi possível carregar mais posições.');
    } finally {
      if (requestId == _requestId) {
        _isLoadingMore = false;
        _notify();
      }
    }
  }

  void _apply(RankingPage page, {required bool append}) {
    final combined = append ? [..._entries, ...page.entries] : page.entries;
    _entries = combined.length > maxEntries ? combined.sublist(0, maxEntries) : combined;
    _lastPage = page;
    _loaded = true;
  }

  /// A sessão que abriu a tela acabou (logout, expiração ou outra conta): nada mais é mostrado nem consultado.
  void invalidate() => _endSession();

  void _endSession() {
    _requestId++;
    _sessionEnded = true;
    _entries = const [];
    _lastPage = null;
    _loaded = false;
    _isLoading = false;
    _isLoadingMore = false;
    _errorMessage = null;
    _loadMoreError = null;
    _notify();
  }

  bool _sameSession() => currentUserId() == userId;

  static String _describe(Object error, String fallback) {
    if (error is NetworkException) {
      return 'Sem conexão com o servidor. Verifique sua internet e tente novamente.';
    }
    if (error is ApiException) {
      return error.isUnauthorized ? 'Sua sessão expirou. Faça login novamente.' : error.detail;
    }
    if (error is FormatException) {
      return 'O servidor respondeu num formato inesperado. Tente novamente mais tarde.';
    }
    return fallback;
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
