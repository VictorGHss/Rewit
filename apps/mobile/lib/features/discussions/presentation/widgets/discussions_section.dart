import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/presentation/state/discussion_notifier.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/create_discussion_form.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussion_thread_widget.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/report_discussion_dialog.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Seção completa de discussões para incorporação na tela de detalhes da avaliação.
class DiscussionsSection extends StatefulWidget {
  final String reviewId;
  final DiscussionNotifier notifier;

  const DiscussionsSection({
    super.key,
    required this.reviewId,
    required this.notifier,
  });

  @override
  State<DiscussionsSection> createState() => _DiscussionsSectionState();
}

class _DiscussionsSectionState extends State<DiscussionsSection> {
  String? _replyingToAuthorName;
  String? _replyingToParentId;

  @override
  void initState() {
    super.initState();
    if (widget.notifier.state is DiscussionInitial) {
      widget.notifier.loadDiscussions(widget.reviewId);
    }
  }

  void _startReply(DiscussionThread thread) {
    setState(() {
      _replyingToParentId = thread.id;
      _replyingToAuthorName = thread.author?.displayName ?? (thread.isFromOwner ? 'Autor da Avaliação' : 'Usuário');
    });
  }

  void _cancelReply() {
    setState(() {
      _replyingToParentId = null;
      _replyingToAuthorName = null;
    });
  }

  Future<void> _confirmDelete(DiscussionItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Excluir comentário'),
        content: const Text('Tem certeza que deseja excluir seu comentário? Esta ação não pode ser desfeita.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Excluir'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    try {
      await widget.notifier.deleteComment(
        reviewId: widget.reviewId,
        discussionId: item.id,
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Comentário excluído com sucesso.')),
        );
      }
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.detail),
            backgroundColor: Colors.red.shade800,
          ),
        );
      }
    }
  }

  void _openReport(DiscussionItem item) {
    ReportDiscussionDialog.show(
      context,
      discussionId: item.id,
      onConfirm: (reason, detail) {
        return widget.notifier.reportComment(
          discussionId: item.id,
          reason: reason,
          detail: detail,
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ListenableBuilder(
      listenable: widget.notifier,
      builder: (context, _) {
        final state = widget.notifier.state;

        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Cabeçalho da Seção de Discussões
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.forum_outlined, size: 20),
                    const SizedBox(width: 8),
                    Text(
                      'Comentários da Comunidade',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                IconButton(
                  icon: const Icon(Icons.refresh, size: 20),
                  tooltip: 'Atualizar comentários',
                  onPressed: () => widget.notifier.refresh(widget.reviewId),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Conteúdo baseado no estado
            if (state is DiscussionLoading) ...[
              const Center(
                child: Padding(
                  padding: EdgeInsets.symmetric(vertical: 24.0),
                  child: LoadingIndicator(message: 'Carregando discussões...'),
                ),
              ),
            ] else if (state is DiscussionError) ...[
              ErrorView(
                title: 'Erro ao carregar discussões',
                message: state.message,
                onRetry: () => widget.notifier.loadDiscussions(widget.reviewId),
              ),
              const SizedBox(height: 12),
            ] else if (state is DiscussionEmpty) ...[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 16),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surface,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(60)),
                ),
                child: Column(
                  children: [
                    Icon(Icons.chat_bubble_outline, size: 36, color: Colors.grey.shade400),
                    const SizedBox(height: 8),
                    Text(
                      'Nenhum comentário ainda.',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: theme.colorScheme.onSurface.withAlpha(180),
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Seja o primeiro a compartilhar sua opinião sobre esta avaliação!',
                      style: TextStyle(
                        fontSize: 12,
                        color: theme.colorScheme.onSurface.withAlpha(140),
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              CreateDiscussionForm(
                replyingToAuthorName: _replyingToAuthorName,
                replyingToParentId: _replyingToParentId,
                onCancelReply: _cancelReply,
                onSubmit: (content, parentId) {
                  return widget.notifier.createComment(
                    reviewId: widget.reviewId,
                    content: content,
                    parentId: parentId,
                  );
                },
              ),
            ] else if (state is DiscussionLoaded) ...[
              ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: state.threads.length,
                separatorBuilder: (context, index) => const SizedBox(height: 10),
                itemBuilder: (context, index) {
                  final thread = state.threads[index];
                  return DiscussionThreadWidget(
                    thread: thread,
                    onReply: _startReply,
                    onDelete: _confirmDelete,
                    onReport: _openReport,
                    isLoadingReplies: state.loadingReplies[thread.id] == true,
                    onLoadMoreReplies: () {
                      widget.notifier.loadMoreReplies(thread.id);
                    },
                  );
                },
              ),
              if (state.hasMore) ...[
                const SizedBox(height: 12),
                Center(
                  child: state.isLoadingMore
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : OutlinedButton.icon(
                          onPressed: () => widget.notifier.loadMore(widget.reviewId),
                          icon: const Icon(Icons.arrow_downward, size: 16),
                          label: const Text('Carregar mais comentários'),
                        ),
                ),
              ],
              const SizedBox(height: 16),
              CreateDiscussionForm(
                replyingToAuthorName: _replyingToAuthorName,
                replyingToParentId: _replyingToParentId,
                onCancelReply: _cancelReply,
                onSubmit: (content, parentId) {
                  return widget.notifier.createComment(
                    reviewId: widget.reviewId,
                    content: content,
                    parentId: parentId,
                  );
                },
              ),
            ],
          ],
        );
      },
    );
  }
}
