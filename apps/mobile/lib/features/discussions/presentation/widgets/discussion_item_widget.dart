import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';

/// Widget para renderização acessível de um item individual de discussão (raiz ou resposta).
class DiscussionItemWidget extends StatelessWidget {
  final DiscussionItem item;
  final bool isRoot;
  final VoidCallback? onReply;
  final VoidCallback? onDelete;
  final VoidCallback? onReport;

  const DiscussionItemWidget({
    super.key,
    required this.item,
    this.isRoot = false,
    this.onReply,
    this.onDelete,
    this.onReport,
  });

  String _formatDate(DateTime date) {
    return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')} às ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    // 1. Caso REMOVED: Omitir conteúdo e autor, renderizar placeholder acessível
    if (item.isRemoved) {
      return Semantics(
        label: 'Comentário removido pela moderação ou pelo autor.',
        container: true,
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 4),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: Colors.grey.shade100,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: Colors.grey.shade300),
          ),
          child: Row(
            children: [
              Icon(Icons.remove_circle_outline, size: 16, color: Colors.grey.shade600),
              const SizedBox(width: 8),
              Text(
                'Comentário removido',
                style: TextStyle(
                  fontStyle: FontStyle.italic,
                  color: Colors.grey.shade700,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
      );
    }

    final author = item.author;
    final displayName = author?.displayName ?? (item.isFromOwner ? 'Autor da Avaliação' : 'Usuário');
    final handle = author?.handle;

    return Semantics(
      label: item.isPendingReview
          ? 'Comentário de $displayName em análise pela moderação.'
          : 'Comentário de $displayName: ${item.content ?? ""}',
      container: true,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: item.isPendingReview
              ? Colors.amber.shade50.withAlpha(120)
              : theme.colorScheme.surface,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: item.isPendingReview
                ? Colors.amber.shade300
                : theme.colorScheme.outlineVariant.withAlpha(60),
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Banner de quarentena acessível para o próprio autor
            if (item.isPendingReview) ...[
              Semantics(
                label: 'Aviso: Seu comentário está em análise pela moderação.',
                child: Container(
                  margin: const EdgeInsets.only(bottom: 8),
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: Colors.amber.shade100,
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: Colors.amber.shade400),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.hourglass_empty, size: 14, color: Colors.amber.shade900),
                      const SizedBox(width: 6),
                      Text(
                        'Em análise pela moderação (visível apenas para você)',
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          color: Colors.amber.shade900,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],

            // Cabeçalho do comentário
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                CircleAvatar(
                  radius: isRoot ? 14 : 11,
                  backgroundColor: item.isFromOwner
                      ? theme.colorScheme.primary.withAlpha(30)
                      : Colors.grey.shade200,
                  child: Text(
                    displayName.isNotEmpty ? displayName[0].toUpperCase() : 'U',
                    style: TextStyle(
                      fontSize: isRoot ? 12 : 10,
                      fontWeight: FontWeight.bold,
                      color: item.isFromOwner ? theme.colorScheme.primary : Colors.grey.shade800,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: 6,
                    children: [
                      Text(
                        displayName,
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: isRoot ? 13 : 12,
                        ),
                      ),
                      if (handle != null && handle.isNotEmpty) ...[
                        Text(
                          '@$handle',
                          style: TextStyle(
                            color: theme.colorScheme.onSurface.withAlpha(150),
                            fontSize: 11,
                          ),
                        ),
                      ],
                      if (item.isFromOwner) ...[
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                          decoration: BoxDecoration(
                            color: theme.colorScheme.primary.withAlpha(20),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            'Autor da avaliação',
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.bold,
                              color: theme.colorScheme.primary,
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                Text(
                  _formatDate(item.createdAt),
                  style: TextStyle(
                    color: theme.colorScheme.onSurface.withAlpha(120),
                    fontSize: 10,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),

            // Conteúdo do comentário
            if (item.content != null && item.content!.isNotEmpty) ...[
              Padding(
                padding: EdgeInsets.only(left: isRoot ? 36 : 30),
                child: Text(
                  item.content!,
                  style: TextStyle(
                    fontSize: isRoot ? 14 : 13,
                    height: 1.35,
                    color: theme.colorScheme.onSurface,
                  ),
                ),
              ),
            ],
            const SizedBox(height: 6),

            // Barra de ações
            Padding(
              padding: EdgeInsets.only(left: isRoot ? 36 : 30),
              child: Row(
                children: [
                  // Ação Responder (permitido apenas na raiz e se canReply for true)
                  if (item.canReply && onReply != null) ...[
                    InkWell(
                      onTap: onReply,
                      borderRadius: BorderRadius.circular(4),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.reply, size: 14, color: theme.colorScheme.primary),
                            const SizedBox(width: 4),
                            Text(
                              'Responder',
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
                    const SizedBox(width: 14),
                  ],

                  // Ação Excluir (somente quando canDelete for exatamente true)
                  if (item.canDelete && onDelete != null) ...[
                    InkWell(
                      onTap: onDelete,
                      borderRadius: BorderRadius.circular(4),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.delete_outline, size: 14, color: Colors.red.shade700),
                            const SizedBox(width: 4),
                            Text(
                              'Excluir',
                              style: TextStyle(
                                fontSize: 12,
                                color: Colors.red.shade700,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(width: 14),
                  ],

                  // Ação Denunciar (visível em comentários publicados normais)
                  if (item.isVisible && onReport != null) ...[
                    InkWell(
                      onTap: onReport,
                      borderRadius: BorderRadius.circular(4),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.flag_outlined, size: 13, color: Colors.grey.shade600),
                            const SizedBox(width: 4),
                            Text(
                              'Denunciar',
                              style: TextStyle(
                                fontSize: 11,
                                color: Colors.grey.shade600,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
