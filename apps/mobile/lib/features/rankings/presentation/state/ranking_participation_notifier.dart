import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';

import '../../domain/repositories/ranking_repository.dart';

/// Participação voluntária nos Rankings da Região. O valor exibido é sempre o confirmado pelo backend: começa
/// desconhecido, nunca é presumido em caso de erro e só muda depois que a gravação é aceita.
class RankingParticipationNotifier extends ChangeNotifier {
  final RankingRepository repository;

  /// Conta que abriu a tela; com outra sessão ativa, nada é gravado.
  final String userId;
  final String? Function() currentUserId;

  RankingParticipationNotifier({required this.repository, required this.userId, required this.currentUserId});

  bool? _confirmed;
  bool _isLoading = false;
  bool _isSaving = false;
  bool? _pendingValue;
  String? _loadError;
  String? _saveError;
  int _loadRequest = 0;
  bool _disposed = false;

  /// Participação confirmada pelo backend; nula enquanto não carregada ou se a consulta falhou.
  bool? get confirmed => _confirmed;
  bool get isLoading => _isLoading;
  bool get isSaving => _isSaving;
  String? get loadError => _loadError;

  /// Falha ao gravar; o interruptor continua no valor confirmado e [retrySave] repete a última tentativa.
  String? get saveError => _saveError;
  bool get canRetrySave => _saveError != null && _pendingValue != null && !_isSaving;

  Future<void> load() async {
    final request = ++_loadRequest;
    _isLoading = true;
    _loadError = null;
    _notify();
    try {
      final value = await repository.getParticipation();
      if (request != _loadRequest) return;
      _confirmed = value;
    } catch (e) {
      if (request != _loadRequest) return;
      _confirmed = null;
      _loadError = _describe(e, 'Não foi possível carregar sua participação nos rankings.');
    } finally {
      if (request == _loadRequest) {
        _isLoading = false;
        _notify();
      }
    }
  }

  /// Pede ao backend para ativar ou desativar a participação; devolve se a gravação foi confirmada.
  Future<bool> setParticipation(bool value) async {
    if (_isSaving || _confirmed == null) return false;
    if (currentUserId() != userId) {
      _saveError = 'Sua sessão mudou. Entre novamente para continuar.';
      _notify();
      return false;
    }
    _isSaving = true;
    _pendingValue = value;
    _saveError = null;
    _notify();
    try {
      final saved = await repository.updateParticipation(value);
      if (_disposed) return false;
      _confirmed = saved;
      _pendingValue = null;
      return true;
    } catch (e) {
      _saveError = _describe(e, 'Não foi possível salvar sua participação nos rankings.');
      return false;
    } finally {
      _isSaving = false;
      _notify();
    }
  }

  Future<bool> retrySave() async {
    final value = _pendingValue;
    if (value == null) return false;
    return setParticipation(value);
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
