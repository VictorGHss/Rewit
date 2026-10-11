import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../../business/domain/entities/business_response_entities.dart';
import '../../../business/domain/repositories/business_response_repository.dart';
import '../../../business/presentation/widgets/business_response_card.dart';
import '../../../business/presentation/widgets/publish_business_response_form.dart';

/// Widget para carregar e exibir respostas oficiais da empresa associadas a um comentário raiz (C11).
class DiscussionBusinessResponsesWidget extends StatefulWidget {
  final String discussionId;
  final BusinessResponseRepository repository;
  final BusinessResponseEligibility? eligibility;
  final Set<String> myBusinessAccountIds;

  const DiscussionBusinessResponsesWidget({
    super.key,
    required this.discussionId,
    required this.repository,
    this.eligibility,
    this.myBusinessAccountIds = const {},
  });

  @override
  State<DiscussionBusinessResponsesWidget> createState() =>
      _DiscussionBusinessResponsesWidgetState();
}

class _DiscussionBusinessResponsesWidgetState
    extends State<DiscussionBusinessResponsesWidget> {
  bool _isLoading = false;
  List<BusinessResponse> _responses = [];
  String? _errorMessage;
  String? _deletingResponseId;
  bool _showPublishForm = false;

  @override
  void initState() {
    super.initState();
    _loadResponses();
  }

  @override
  void didUpdateWidget(DiscussionBusinessResponsesWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.discussionId != widget.discussionId) {
      _loadResponses();
    }
  }

  Future<void> _loadResponses() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final pageResult = await widget.repository.getDiscussionBusinessResponses(
        widget.discussionId,
        page: 0,
        size: 20,
      );

      if (mounted) {
        setState(() {
          _responses = List.of(pageResult.content);
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
          _errorMessage = 'Falha ao carregar respostas oficiais do comentário.';
        });
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
    final created = await widget.repository.publishDiscussionBusinessResponse(
      discussionId: widget.discussionId,
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

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Resposta oficial publicada na discussão!'),
          backgroundColor: Colors.green,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final canPublish = widget.eligibility?.hasEligibleAccounts ?? false;

    if (_isLoading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 4.0, horizontal: 12.0),
        child: SizedBox(
          height: 16,
          width: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      );
    }

    if (_errorMessage != null && _responses.isEmpty) {
      return Padding(
        padding: const EdgeInsets.only(left: 16.0, top: 4.0, bottom: 4.0),
        child: Row(
          children: [
            Icon(Icons.error_outline, size: 14, color: theme.colorScheme.error),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                _errorMessage!,
                style: TextStyle(fontSize: 12, color: theme.colorScheme.error),
              ),
            ),
            TextButton(
              onPressed: _loadResponses,
              child: const Text('Tentar novamente', style: TextStyle(fontSize: 11)),
            ),
          ],
        ),
      );
    }

    if (_responses.isEmpty && !canPublish) {
      return const SizedBox.shrink();
    }

    return Padding(
      padding: const EdgeInsets.only(left: 16.0, top: 4.0, bottom: 4.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Respostas Oficiais Publicadas
          ..._responses.map((resp) {
            final canDeleteThis =
                widget.myBusinessAccountIds.contains(resp.businessAccountId);
            final isDeletingThis = _deletingResponseId == resp.id;

            return BusinessResponseCard(
              key: ValueKey('discussion_business_response_${resp.id}'),
              response: resp,
              canDelete: canDeleteThis,
              isDeleting: isDeletingThis,
              onDelete: () => _handleDelete(resp),
            );
          }),

          // Botão para abrir formulário de resposta comercial à discussão
          if (canPublish && !_showPublishForm) ...[
            Padding(
              padding: const EdgeInsets.only(top: 2.0),
              child: InkWell(
                key: ValueKey('reply_as_business_button_${widget.discussionId}'),
                onTap: () {
                  setState(() {
                    _showPublishForm = true;
                  });
                },
                borderRadius: BorderRadius.circular(4),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.store_mall_directory_outlined,
                        size: 14,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        'Responder como empresa',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                          color: theme.colorScheme.primary,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],

          // Formulário aberto
          if (canPublish && _showPublishForm && widget.eligibility != null) ...[
            Padding(
              padding: const EdgeInsets.only(top: 6.0),
              child: PublishBusinessResponseForm(
                eligibility: widget.eligibility!,
                onSubmit: _handlePublish,
                onCancel: () {
                  setState(() {
                    _showPublishForm = false;
                  });
                },
              ),
            ),
          ],
        ],
      ),
    );
  }
}
