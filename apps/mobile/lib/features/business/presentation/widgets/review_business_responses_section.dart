import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';
import '../../domain/entities/business_response_entities.dart';
import '../../domain/repositories/business_repository.dart';
import '../../domain/repositories/business_response_repository.dart';
import 'business_response_card.dart';
import 'publish_business_response_form.dart';

/// Seção completa de respostas oficiais exibida na tela de detalhes da avaliação (C11).
class ReviewBusinessResponsesSection extends StatefulWidget {
  final String reviewId;
  final BusinessResponseRepository repository;
  final BusinessRepository? businessRepository;

  const ReviewBusinessResponsesSection({
    super.key,
    required this.reviewId,
    required this.repository,
    this.businessRepository,
  });

  @override
  State<ReviewBusinessResponsesSection> createState() =>
      _ReviewBusinessResponsesSectionState();
}

class _ReviewBusinessResponsesSectionState
    extends State<ReviewBusinessResponsesSection> {
  bool _isLoading = true;
  String? _errorMessage;
  List<BusinessResponse> _responses = [];
  int _page = 0;
  bool _hasMore = false;
  bool _isLoadingMore = false;

  BusinessResponseEligibility? _eligibility;
  Set<String> _myBusinessAccountIds = {};
  String? _deletingResponseId;
  bool _showPublishForm = false;

  @override
  void initState() {
    super.initState();
    _loadInitialData();
  }

  @override
  void didUpdateWidget(ReviewBusinessResponsesSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.reviewId != widget.reviewId) {
      _loadInitialData();
    }
  }

  Future<void> _loadInitialData() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      // 1. Carregar contas controladas pelo usuário autenticado (para autorização de exclusão)
      if (widget.businessRepository != null) {
        try {
          final accounts = await widget.businessRepository!.getMyBusinessAccounts();
          _myBusinessAccountIds = accounts.map((a) => a.id).toSet();
        } catch (_) {
          _myBusinessAccountIds = {};
        }
      }

      // 2. Carregar respostas oficiais da avaliação
      final pageResult = await widget.repository.getReviewBusinessResponses(
        widget.reviewId,
        page: 0,
        size: 20,
      );

      // 3. Consultar elegibilidade para responder
      try {
        _eligibility = await widget.repository.getReviewEligibility(widget.reviewId);
      } catch (_) {
        _eligibility = null;
      }

      if (mounted) {
        setState(() {
          _responses = List.of(pageResult.content);
          _page = pageResult.pageNumber;
          _hasMore = !pageResult.isLast;
          _isLoading = false;
        });
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = e.detail;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = 'Falha ao carregar respostas oficiais da avaliação.';
        });
      }
    }
  }

  Future<void> _loadMoreResponses() async {
    if (_isLoadingMore || !_hasMore) return;

    setState(() {
      _isLoadingMore = true;
    });

    try {
      final nextPage = _page + 1;
      final pageResult = await widget.repository.getReviewBusinessResponses(
        widget.reviewId,
        page: nextPage,
        size: 20,
      );

      if (mounted) {
        setState(() {
          _responses.addAll(pageResult.content);
          _page = pageResult.pageNumber;
          _hasMore = !pageResult.isLast;
          _isLoadingMore = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isLoadingMore = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Falha ao carregar mais respostas.')),
        );
      }
    }
  }

  Future<void> _handleDelete(BusinessResponse response) async {
    setState(() {
      _deletingResponseId = response.id;
    });

    try {
      await widget.repository.deleteBusinessResponse(response.id);

      if (mounted) {
        setState(() {
          _responses.removeWhere((r) => r.id == response.id);
          _deletingResponseId = null;
        });

        // Recarrega elegibilidade (empresa pode voltar a estar elegível após remoção)
        _reloadEligibility();

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Resposta oficial removida com sucesso.'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _deletingResponseId = null;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Falha ao remover resposta: ${e.detail}'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _deletingResponseId = null;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('Erro inesperado ao remover resposta.'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    }
  }

  Future<void> _handlePublish({
    required String businessAccountId,
    required String placeId,
    required String content,
  }) async {
    final created = await widget.repository.publishReviewBusinessResponse(
      reviewId: widget.reviewId,
      businessAccountId: businessAccountId,
      placeId: placeId,
      content: content,
    );

    if (mounted) {
      setState(() {
        if (!_responses.any((r) => r.id == created.id)) {
          _responses.insert(0, created);
        }
        _showPublishForm = false;
      });

      // Recarrega elegibilidade após publicação
      _reloadEligibility();

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Resposta oficial publicada com sucesso!'),
          backgroundColor: Colors.green,
        ),
      );
    }
  }

  Future<void> _reloadEligibility() async {
    try {
      final updated = await widget.repository.getReviewEligibility(widget.reviewId);
      if (mounted) {
        setState(() {
          _eligibility = updated;
        });
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final canPublish = _eligibility?.hasEligibleAccounts ?? false;

    // Se estiver carregando inicialmente
    if (_isLoading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 12.0),
        child: Center(
          child: LoadingIndicator(message: 'Carregando respostas oficiais...'),
        ),
      );
    }

    // Se houve erro e nenhuma resposta está em cache
    if (_errorMessage != null && _responses.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8.0),
        child: ErrorView(
          title: 'Respostas da Empresa',
          message: _errorMessage!,
          onRetry: _loadInitialData,
        ),
      );
    }

    // Se não há respostas e o usuário não pode publicar
    if (_responses.isEmpty && !canPublish) {
      return const SizedBox.shrink();
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Cabeçalho da Seção
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Row(
              children: [
                Icon(
                  Icons.business,
                  size: 20,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Text(
                  'Respostas Oficiais (${_responses.length})',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: theme.colorScheme.primary,
                  ),
                ),
              ],
            ),
            if (canPublish && !_showPublishForm) ...[
              TextButton.icon(
                key: const Key('open_publish_business_response_button'),
                onPressed: () {
                  setState(() {
                    _showPublishForm = true;
                  });
                },
                icon: const Icon(Icons.add_comment_outlined, size: 16),
                label: const Text('Responder como empresa'),
              ),
            ],
          ],
        ),
        const SizedBox(height: 8),

        // Formulário de Publicação (quando aberto)
        if (canPublish && _showPublishForm && _eligibility != null) ...[
          PublishBusinessResponseForm(
            eligibility: _eligibility!,
            onSubmit: _handlePublish,
            onCancel: () {
              setState(() {
                _showPublishForm = false;
              });
            },
          ),
          const SizedBox(height: 8),
        ],

        // Lista de Respostas Oficiais
        if (_responses.isNotEmpty) ...[
          ..._responses.map((resp) {
            final canDeleteThis = _myBusinessAccountIds.contains(resp.businessAccountId);
            final isDeletingThis = _deletingResponseId == resp.id;

            return BusinessResponseCard(
              key: ValueKey('review_business_response_${resp.id}'),
              response: resp,
              canDelete: canDeleteThis,
              isDeleting: isDeletingThis,
              onDelete: () => _handleDelete(resp),
            );
          }),
          if (_hasMore) ...[
            const SizedBox(height: 8),
            Center(
              child: _isLoadingMore
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : OutlinedButton(
                      onPressed: _loadMoreResponses,
                      child: const Text('Carregar mais respostas oficiais'),
                    ),
            ),
          ],
          const SizedBox(height: 16),
        ],
      ],
    );
  }
}
