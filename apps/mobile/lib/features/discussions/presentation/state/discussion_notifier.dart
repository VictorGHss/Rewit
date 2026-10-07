import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';

/// Estados possíveis da visualização de discussões.
abstract class DiscussionState {
  const DiscussionState();
}

class DiscussionInitial extends DiscussionState {
  const DiscussionInitial();
}

class DiscussionLoading extends DiscussionState {
  const DiscussionLoading();
}

class DiscussionEmpty extends DiscussionState {
  const DiscussionEmpty();
}

class DiscussionLoaded extends DiscussionState {
  final List<DiscussionThread> threads;
  final int page;
  final bool hasMore;
  final bool isLoadingMore;
  final String? loadMoreError;
  final Map<String, bool> loadingReplies;

  const DiscussionLoaded({
    required this.threads,
    this.page = 0,
    this.hasMore = false,
    this.isLoadingMore = false,
    this.loadMoreError,
    this.loadingReplies = const {},
  });

  DiscussionLoaded copyWith({
    List<DiscussionThread>? threads,
    int? page,
    bool? hasMore,
    bool? isLoadingMore,
    String? loadMoreError,
    Map<String, bool>? loadingReplies,
  }) {
    return DiscussionLoaded(
      threads: threads ?? this.threads,
      page: page ?? this.page,
      hasMore: hasMore ?? this.hasMore,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      loadMoreError: loadMoreError,
      loadingReplies: loadingReplies ?? this.loadingReplies,
    );
  }
}

class DiscussionError extends DiscussionState {
  final String message;
  final int? statusCode;
  final String? errorCode;

  const DiscussionError({
    required this.message,
    this.statusCode,
    this.errorCode,
  });
}

/// Notifier reativo para controle do ciclo de vida das discussões de uma avaliação.
class DiscussionNotifier extends ChangeNotifier {
  final DiscussionRepository _repository;

  DiscussionState _state = const DiscussionInitial();
  DiscussionState get state => _state;

  DiscussionNotifier({required DiscussionRepository repository})
      : _repository = repository;

  /// Carrega a lista inicial de discussões para uma avaliação.
  Future<void> loadDiscussions(String reviewId) async {
    _state = const DiscussionLoading();
    notifyListeners();

    try {
      final page = await _repository.getDiscussions(reviewId, page: 0, size: 20);
      if (page.content.isEmpty) {
        _state = const DiscussionEmpty();
      } else {
        _state = DiscussionLoaded(
          threads: page.content,
          page: page.pageNumber,
          hasMore: !page.isLast,
        );
      }
    } on ApiException catch (e) {
      _state = DiscussionError(
        message: e.detail,
        statusCode: e.statusCode,
        errorCode: e.errorCode,
      );
    } catch (e) {
      _state = const DiscussionError(
        message: 'Não foi possível carregar as discussões. Verifique sua conexão.',
      );
    }

    notifyListeners();
  }

  /// Recarrega as discussões silenciosamente (pull-to-refresh ou após mutação).
  Future<void> refresh(String reviewId) async {
    try {
      final page = await _repository.getDiscussions(reviewId, page: 0, size: 20);
      if (page.content.isEmpty) {
        _state = const DiscussionEmpty();
      } else {
        _state = DiscussionLoaded(
          threads: page.content,
          page: page.pageNumber,
          hasMore: !page.isLast,
        );
      }
    } on ApiException catch (e) {
      _state = DiscussionError(
        message: e.detail,
        statusCode: e.statusCode,
        errorCode: e.errorCode,
      );
    } catch (_) {
      // Preserva estado atual caso refresh de rede falhe
    }

    notifyListeners();
  }

  /// Carrega mais páginas de raízes de discussão (paginação).
  Future<void> loadMore(String reviewId) async {
    final current = _state;
    if (current is! DiscussionLoaded || !current.hasMore || current.isLoadingMore) {
      return;
    }

    _state = current.copyWith(isLoadingMore: true, loadMoreError: null);
    notifyListeners();

    try {
      final nextPage = current.page + 1;
      final result = await _repository.getDiscussions(reviewId, page: nextPage, size: 20);

      _state = current.copyWith(
        threads: [...current.threads, ...result.content],
        page: result.pageNumber,
        hasMore: !result.isLast,
        isLoadingMore: false,
      );
    } on ApiException catch (e) {
      _state = current.copyWith(
        isLoadingMore: false,
        loadMoreError: e.detail,
      );
    } catch (_) {
      _state = current.copyWith(
        isLoadingMore: false,
        loadMoreError: 'Falha ao carregar mais comentários.',
      );
    }

    notifyListeners();
  }

  /// Carrega respostas adicionais de uma raiz específica.
  Future<void> loadMoreReplies(String discussionId) async {
    final current = _state;
    if (current is! DiscussionLoaded) return;

    final index = current.threads.indexWhere((t) => t.id == discussionId);
    if (index == -1) return;

    final thread = current.threads[index];
    if (current.loadingReplies[discussionId] == true) return;

    final newLoadingReplies = Map<String, bool>.from(current.loadingReplies)
      ..[discussionId] = true;
    _state = current.copyWith(loadingReplies: newLoadingReplies);
    notifyListeners();

    try {
      // Calcula página de respostas baseado na quantidade já existente
      final page = (thread.replies.length / 20).floor();
      final repliesPage = await _repository.getReplies(discussionId, page: page, size: 20);

      final existingIds = thread.replies.map((r) => r.id).toSet();
      final newReplies = repliesPage.content.where((r) => !existingIds.contains(r.id)).toList();
      final updatedReplies = [...thread.replies, ...newReplies];

      final updatedThread = thread.copyWith(
        replies: updatedReplies,
        hasMoreReplies: !repliesPage.isLast && updatedReplies.length < repliesPage.totalElements,
      );

      final updatedThreads = List<DiscussionThread>.from(current.threads);
      updatedThreads[index] = updatedThread;

      final finishedLoadingReplies = Map<String, bool>.from(current.loadingReplies)
        ..remove(discussionId);

      _state = current.copyWith(
        threads: updatedThreads,
        loadingReplies: finishedLoadingReplies,
      );
    } catch (_) {
      final finishedLoadingReplies = Map<String, bool>.from(current.loadingReplies)
        ..remove(discussionId);
      _state = current.copyWith(loadingReplies: finishedLoadingReplies);
    }

    notifyListeners();
  }

  /// Cria novo comentário raiz ou resposta.
  Future<void> createComment({
    required String reviewId,
    required String content,
    String? parentId,
  }) async {
    final trimmed = content.trim();
    if (trimmed.isEmpty) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Validação',
          status: 400,
          detail: 'O conteúdo do comentário é obrigatório.',
        ),
      );
    }

    if (trimmed.length > 2000) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Validação',
          status: 400,
          detail: 'O comentário excede o limite máximo permitido de 2000 caracteres.',
        ),
      );
    }

    await _repository.createDiscussion(
      reviewId: reviewId,
      content: trimmed,
      parentId: parentId,
    );

    // Atualiza a thread com a estrutura correta retornada pelo backend
    await refresh(reviewId);
  }

  /// Exclui um comentário publicado pelo usuário.
  Future<void> deleteComment({
    required String reviewId,
    required String discussionId,
  }) async {
    try {
      await _repository.deleteDiscussion(discussionId);
      await refresh(reviewId);
    } on ApiException catch (e) {
      if (e.errorCode == 'DISCUSSION_UNDER_REVIEW_MUTATION_DENIED' || e.statusCode == 409) {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Operação não permitida',
            status: 409,
            code: 'DISCUSSION_UNDER_REVIEW_MUTATION_DENIED',
            detail: 'Este comentário está em análise pela moderação e não pode ser alterado ou excluído no momento.',
          ),
        );
      }
      rethrow;
    }
  }

  /// Submete denúncia com confirmação genérica padronizada.
  Future<String> reportComment({
    required String discussionId,
    required ReportReason reason,
    String? detail,
  }) async {
    return await _repository.reportDiscussion(
      discussionId: discussionId,
      reason: reason,
      detail: detail,
    );
  }
}
