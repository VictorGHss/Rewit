import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussion_item_widget.dart';

/// Widget para renderização de uma thread completa de discussão: comentário raiz e respostas embutidas.
class DiscussionThreadWidget extends StatelessWidget {
  final DiscussionThread thread;
  final void Function(DiscussionThread thread)? onReply;
  final void Function(DiscussionItem item)? onDelete;
  final void Function(DiscussionItem item)? onReport;
  final VoidCallback? onLoadMoreReplies;
  final bool isLoadingReplies;

  const DiscussionThreadWidget({
    super.key,
    required this.thread,
    this.onReply,
    this.onDelete,
    this.onReport,
    this.onLoadMoreReplies,
    this.isLoadingReplies = false,
  });

  DiscussionItem _rootAsItem() {
    return DiscussionItem(
      id: thread.id,
      reviewId: thread.reviewId,
      parentId: thread.parentId,
      state: thread.state,
      content: thread.content,
      author: thread.author,
      isFromOwner: thread.isFromOwner,
      createdAt: thread.createdAt,
      canReply: thread.canReply,
      canDelete: thread.canDelete,
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final rootItem = _rootAsItem();

    final hasReplies = thread.replies.isNotEmpty;
    final remainingReplies = thread.replyCount - thread.replies.length;
    final canLoadMoreReplies = thread.hasMoreReplies || remainingReplies > 0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Comentário raiz
        DiscussionItemWidget(
          item: rootItem,
          isRoot: true,
          onReply: thread.canReply && onReply != null ? () => onReply!(thread) : null,
          onDelete: thread.canDelete && onDelete != null ? () => onDelete!(rootItem) : null,
          onReport: thread.isVisible && onReport != null ? () => onReport!(rootItem) : null,
        ),

        // Bloco de respostas aninhadas (1 nível de profundidade)
        if (hasReplies || canLoadMoreReplies) ...[
          Padding(
            padding: const EdgeInsets.only(left: 20.0, top: 4.0),
            child: Container(
              decoration: BoxDecoration(
                border: Border(
                  left: BorderSide(
                    color: theme.colorScheme.outlineVariant.withAlpha(80),
                    width: 2,
                  ),
                ),
              ),
              padding: const EdgeInsets.only(left: 12.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Respostas carregadas
                  ...thread.replies.map((reply) {
                    return DiscussionItemWidget(
                      item: reply,
                      isRoot: false,
                      // 1 nível de nesting estrito: não há resposta para respostas
                      onReply: null,
                      onDelete: reply.canDelete && onDelete != null ? () => onDelete!(reply) : null,
                      onReport: reply.isVisible && onReport != null ? () => onReport!(reply) : null,
                    );
                  }),

                  // Botão para carregar mais respostas quando houver além das embutidas
                  if (canLoadMoreReplies && onLoadMoreReplies != null) ...[
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4.0),
                      child: TextButton.icon(
                        onPressed: isLoadingReplies ? null : onLoadMoreReplies,
                        icon: isLoadingReplies
                            ? const SizedBox(
                                width: 14,
                                height: 14,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : const Icon(Icons.subdirectory_arrow_right, size: 16),
                        label: Text(
                          isLoadingReplies
                              ? 'Carregando respostas...'
                              : (remainingReplies > 0
                                  ? 'Carregar mais respostas ($remainingReplies restantes)'
                                  : 'Carregar mais respostas'),
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }
}
