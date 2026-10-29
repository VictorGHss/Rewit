import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';

import '../../domain/entities/check_in_entities.dart';
import '../../domain/repositories/check_in_repository.dart';

/// Histórico de check-ins verificados do usuário: primeira página, próximas páginas e nova tentativa.
class MyCheckInsNotifier extends ChangeNotifier {
  static const int pageSize = 20;

  final CheckInRepository repository;

  MyCheckInsNotifier({required this.repository});

  List<MyCheckIn> _items = const [];
  int _totalElements = 0;
  int _nextPage = 0;
  bool _isLast = false;
  bool _isLoading = false;
  bool _isLoadingMore = false;
  bool _loaded = false;
  String? _errorMessage;
  String? _loadMoreError;
  int _requestId = 0;
  bool _disposed = false;

  List<MyCheckIn> get items => _items;
  int get totalElements => _totalElements;
  bool get isLoading => _isLoading;
  bool get isLoadingMore => _isLoadingMore;
  bool get hasMore => _loaded && !_isLast;
  bool get isEmpty => _loaded && _items.isEmpty;

  /// Falha ao carregar a primeira página; a tela oferece nova tentativa.
  String? get errorMessage => _errorMessage;

  /// Falha ao carregar a próxima página; os itens já carregados continuam visíveis.
  String? get loadMoreError => _loadMoreError;

  Future<void> load() async {
    final requestId = ++_requestId;
    _isLoading = true;
    _isLoadingMore = false;
    _errorMessage = null;
    _loadMoreError = null;
    _notify();
    try {
      final page = await repository.getMyCheckIns(page: 0, size: pageSize);
      if (requestId != _requestId) return;
      _items = page.items;
      _totalElements = page.totalElements;
      _nextPage = page.pageNumber + 1;
      _isLast = page.isLast;
      _loaded = true;
    } catch (e) {
      if (requestId != _requestId) return;
      _errorMessage = _describe(e, 'Não foi possível carregar seus check-ins.');
    } finally {
      if (requestId == _requestId) {
        _isLoading = false;
        _notify();
      }
    }
  }

  Future<void> loadMore() async {
    if (!hasMore || _isLoading || _isLoadingMore) return;
    final requestId = _requestId;
    _isLoadingMore = true;
    _loadMoreError = null;
    _notify();
    try {
      final page = await repository.getMyCheckIns(page: _nextPage, size: pageSize);
      if (requestId != _requestId) return;
      final known = _items.map((item) => item.id).toSet();
      _items = [..._items, ...page.items.where((item) => !known.contains(item.id))];
      _totalElements = page.totalElements;
      _nextPage = page.pageNumber + 1;
      _isLast = page.isLast;
    } catch (e) {
      if (requestId != _requestId) return;
      _loadMoreError = _describe(e, 'Não foi possível carregar mais check-ins.');
    } finally {
      if (requestId == _requestId) {
        _isLoadingMore = false;
        _notify();
      }
    }
  }

  static String _describe(Object error, String fallback) {
    if (error is NetworkException) {
      return 'Sem conexão com o servidor. Verifique sua internet e tente novamente.';
    }
    if (error is ApiException) {
      return error.isUnauthorized ? 'Sua sessão expirou. Faça login novamente.' : error.detail;
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
