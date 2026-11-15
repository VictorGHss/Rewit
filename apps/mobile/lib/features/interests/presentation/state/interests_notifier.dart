import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';

/// Estado do questionário inicial de interesses e da edição pelo perfil.
///
/// O backend é a fonte de verdade: a seleção vem de GET /me/interests e cada avanço de pergunta grava um rascunho, para
/// que o questionário possa ser retomado depois de fechar o app ou em outro aparelho. A instância pertence a um único
/// usuário ([userId]); antes de gravar, confere que a sessão atual ainda é dele, para que respostas de uma sessão
/// encerrada nunca sigam com a sessão de outra conta.
class InterestsNotifier extends ChangeNotifier {
  final InterestRepository _repository;
  final String userId;
  final String? Function() _currentUserId;

  InterestCatalog? _catalog;
  UserInterests? _saved;
  Set<String> _selected = {};
  int _step = 0;
  bool _isLoading = false;
  bool _isSaving = false;
  bool _justCompleted = false;
  String? _loadError;
  String? _errorMessage;
  bool _disposed = false;

  /// [currentUserId] devolve o usuário da sessão ativa agora (nulo sem sessão).
  InterestsNotifier({
    required InterestRepository repository,
    required this.userId,
    required String? Function() currentUserId,
  })  : _repository = repository,
        _currentUserId = currentUserId;

  InterestCatalog? get catalog => _catalog;
  bool get isLoading => _isLoading;
  bool get isSaving => _isSaving;

  /// Falha ao carregar catálogo ou interesses; a tela oferece nova tentativa.
  String? get loadError => _loadError;

  /// Falha ao gravar ou seleção abaixo do mínimo; a seleção na tela é mantida.
  String? get errorMessage => _errorMessage;

  bool get isReady => _catalog != null && _saved != null;
  bool get isCompleted => _saved?.isCompleted ?? false;

  /// Concluído nesta sessão e ainda não confirmado pelo usuário na tela de conclusão.
  bool get justCompleted => _justCompleted;

  Set<String> get selectedCodes => Set.unmodifiable(_selected);
  int get selectedCount => _selected.length;
  int get minInterests => _catalog?.minInterestsToComplete ?? 0;
  bool get meetsMinimum => _selected.length >= minInterests;

  int get questionCount => _catalog?.questions.length ?? 0;
  int get step => _step;
  bool get isOnSummary => _catalog != null && _step >= questionCount;
  InterestQuestion? get currentQuestion => isOnSummary ? null : _catalog?.questions[_step];

  /// Interesses escolhidos, na ordem do catálogo.
  List<InterestOption> get selectedOptions =>
      (_catalog?.options ?? const <InterestOption>[]).where((o) => _selected.contains(o.code)).toList();

  bool get hasUnsavedChanges => !setEquals(_selected, _saved?.codes ?? const <String>{});

  /// Carrega catálogo e interesses gravados e posiciona o questionário onde ele parou.
  Future<void> load() async {
    if (_isLoading) return;
    _isLoading = true;
    _loadError = null;
    _errorMessage = null;
    _notify();

    try {
      final results = await Future.wait([_repository.getCatalog(), _repository.getMyInterests()]);
      _catalog = results[0] as InterestCatalog;
      _applySaved(results[1] as UserInterests);
      _step = _resumeStep();
    } catch (e) {
      _loadError = _describe(e, 'Não foi possível carregar seus interesses.');
    } finally {
      _isLoading = false;
      _notify();
    }
  }

  void toggle(String code) {
    final catalog = _catalog;
    if (_isSaving || catalog == null || !catalog.contains(code)) return;
    if (!_selected.remove(code)) {
      _selected.add(code);
    }
    _errorMessage = null;
    _notify();
  }

  /// Volta uma pergunta, sem gravar; a seleção atual é mantida.
  void back() {
    if (_isSaving || _step == 0) return;
    _step--;
    _errorMessage = null;
    _notify();
  }

  /// Grava o rascunho (se algo mudou desde a última gravação) e avança. Em falha, permanece na pergunta.
  Future<bool> next() async {
    if (_isSaving || !isReady || isOnSummary) return false;
    if (hasUnsavedChanges || _saved!.status == InterestOnboardingStatus.notStarted) {
      if (!await _save(complete: false)) return false;
    }
    _step++;
    _notify();
    return true;
  }

  /// Confirma o questionário (ou grava a edição de um questionário já concluído). Exige o mínimo do catálogo.
  Future<bool> complete() async {
    if (_isSaving || !isReady) return false;
    if (!meetsMinimum) {
      _errorMessage = 'Escolha pelo menos $minInterests interesses para continuar.';
      _notify();
      return false;
    }
    final wasCompleted = isCompleted;
    final saved = await _save(complete: true);
    if (saved && !wasCompleted) {
      _justCompleted = true;
      _notify();
    }
    return saved;
  }

  /// O usuário viu a confirmação de conclusão e segue para o app.
  void acknowledgeCompletion() {
    if (!_justCompleted) return;
    _justCompleted = false;
    _notify();
  }

  void _applySaved(UserInterests saved) {
    _saved = saved;
    final catalog = _catalog;
    _selected = saved.codes.where((code) => catalog?.contains(code) ?? false).toSet();
  }

  /// Retomada: a pergunta seguinte à última com alguma resposta gravada (o resumo, se for a última).
  int _resumeStep() {
    final questions = _catalog?.questions ?? const <InterestQuestion>[];
    var lastAnswered = -1;
    for (var i = 0; i < questions.length; i++) {
      if (questions[i].options.any((option) => _selected.contains(option.code))) {
        lastAnswered = i;
      }
    }
    return lastAnswered + 1;
  }

  Future<bool> _save({required bool complete}) async {
    if (_currentUserId() != userId) {
      _errorMessage = 'Sua sessão mudou. Entre novamente para continuar.';
      _notify();
      return false;
    }

    _isSaving = true;
    _errorMessage = null;
    _notify();
    try {
      final saved = await _repository.saveMyInterests(
        codes: selectedOptions.map((option) => option.code).toList(),
        completeOnboarding: complete,
      );
      _applySaved(saved);
      return true;
    } catch (e) {
      _errorMessage = _describe(e, 'Não foi possível salvar seus interesses.');
      return false;
    } finally {
      _isSaving = false;
      _notify();
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
