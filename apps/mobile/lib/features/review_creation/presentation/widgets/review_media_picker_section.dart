import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_notifier.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_state.dart';

/// Seção visual interativa para seleção, pré-visualização e monitoramento
/// do upload de fotos na criação de avaliações.
class ReviewMediaPickerSection extends StatelessWidget {
  final ReviewCreateNotifier notifier;
  final bool enabled;

  const ReviewMediaPickerSection({
    super.key,
    required this.notifier,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final mediaList = notifier.selectedMedia;
    final canAddMore = mediaList.length < SelectedMediaItem.maxItemsPerReview && enabled;

    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 16),
      color: theme.colorScheme.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(100)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Cabeçalho da Seção com contagem (X/5)
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.photo_library_outlined,
                      size: 22,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'Fotos e Evidências',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.primary.withAlpha(30),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        'Pós-publicação',
                        style: TextStyle(
                          fontSize: 10,
                          fontWeight: FontWeight.w600,
                          color: theme.colorScheme.primary,
                        ),
                      ),
                    ),
                  ],
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: mediaList.length >= SelectedMediaItem.maxItemsPerReview
                        ? theme.colorScheme.errorContainer
                        : theme.colorScheme.primaryContainer.withAlpha(120),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    '${mediaList.length}/${SelectedMediaItem.maxItemsPerReview}',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                      color: mediaList.length >= SelectedMediaItem.maxItemsPerReview
                          ? theme.colorScheme.onErrorContainer
                          : theme.colorScheme.onPrimaryContainer,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              'Anexe até 5 fotos (JPEG ou PNG, máx. 10 MB cada). '
              'As fotos são processadas e sanitizadas pelo servidor após a publicação.',
              style: TextStyle(
                fontSize: 12,
                color: theme.colorScheme.onSurface.withAlpha(170),
                height: 1.3,
              ),
            ),
            const SizedBox(height: 12),

            // Pré-visualização das mídias selecionadas / fila de upload
            if (mediaList.isNotEmpty) ...[
              SizedBox(
                height: 130,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: mediaList.length,
                  separatorBuilder: (context, _) => const SizedBox(width: 10),
                  itemBuilder: (context, index) {
                    final item = mediaList[index];
                    return _SelectedMediaThumbnailCard(
                      item: item,
                      index: index,
                      enabled: enabled,
                      onRemove: () => notifier.removeSelectedMediaItem(index),
                      onRetry: () {
                        final state = notifier.state;
                        if (state is ReviewCreateSuccess) {
                          notifier.retryMediaUpload(index, state.createdReview.id);
                        }
                      },
                    );
                  },
                ),
              ),
              const SizedBox(height: 12),
            ],

            // Botão de Adição
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                key: const ValueKey('add_media_button'),
                onPressed: canAddMore ? () => notifier.pickMedia() : null,
                icon: const Icon(Icons.add_photo_alternate_outlined, size: 18),
                label: Text(
                  mediaList.isEmpty
                      ? 'Adicionar Fotos (Galeria)'
                      : 'Adicionar Mais Fotos (${mediaList.length}/${SelectedMediaItem.maxItemsPerReview})',
                ),
                style: OutlinedButton.styleFrom(
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SelectedMediaThumbnailCard extends StatelessWidget {
  final SelectedMediaItem item;
  final int index;
  final bool enabled;
  final VoidCallback onRemove;
  final VoidCallback onRetry;

  const _SelectedMediaThumbnailCard({
    required this.item,
    required this.index,
    required this.enabled,
    required this.onRemove,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Container(
      width: 110,
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: item.status == MediaUploadStatus.failed
              ? theme.colorScheme.error
              : item.status == MediaUploadStatus.uploaded
                  ? Colors.green
                  : theme.colorScheme.outlineVariant.withAlpha(100),
          width: item.status == MediaUploadStatus.failed || item.status == MediaUploadStatus.uploaded ? 1.5 : 1,
        ),
      ),
      child: Stack(
        children: [
          // Imagem miniatura
          Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: ClipRRect(
                  borderRadius: const BorderRadius.vertical(top: Radius.circular(7)),
                  child: item.bytes.isNotEmpty
                      ? Image.memory(
                          item.bytes,
                          fit: BoxFit.cover,
                          errorBuilder: (context, error, stackTrace) => Container(
                            color: Colors.grey.shade200,
                            child: const Icon(Icons.broken_image, size: 28),
                          ),
                        )
                      : Container(
                          color: Colors.grey.shade200,
                          child: const Icon(Icons.image, size: 28),
                        ),
                ),
              ),
              // Rodapé com tamanho e status
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
                color: theme.colorScheme.surface,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      item.formattedSize,
                      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w600),
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (item.status == MediaUploadStatus.uploaded)
                      const Text(
                        'Enviada',
                        style: TextStyle(fontSize: 9, color: Colors.green, fontWeight: FontWeight.bold),
                      )
                    else if (item.status == MediaUploadStatus.failed)
                      Text(
                        item.isTerminalError ? 'Inválida' : 'Falhou',
                        style: TextStyle(
                          fontSize: 9,
                          color: theme.colorScheme.error,
                          fontWeight: FontWeight.bold,
                        ),
                      )
                    else if (item.status == MediaUploadStatus.uploading)
                      const Text(
                        'Enviando...',
                        style: TextStyle(fontSize: 9, color: Colors.blue, fontWeight: FontWeight.bold),
                      )
                    else
                      const Text(
                        'Na fila',
                        style: TextStyle(fontSize: 9, color: Colors.grey),
                      ),
                  ],
                ),
              ),
            ],
          ),

          // Botão de remoção antes do upload (quando na fila)
          if (item.status == MediaUploadStatus.queued && enabled)
            Positioned(
              top: 2,
              right: 2,
              child: GestureDetector(
                onTap: onRemove,
                child: Container(
                  padding: const EdgeInsets.all(3),
                  decoration: BoxDecoration(
                    color: Colors.black.withAlpha(150),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(Icons.close, size: 14, color: Colors.white),
                ),
              ),
            ),

          // Overlay de progresso durante upload
          if (item.status == MediaUploadStatus.uploading)
            Container(
              decoration: BoxDecoration(
                color: Colors.black.withAlpha(100),
                borderRadius: BorderRadius.circular(7),
              ),
              child: const Center(
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                ),
              ),
            ),

          // Badge de sucesso
          if (item.status == MediaUploadStatus.uploaded)
            Positioned(
              top: 4,
              left: 4,
              child: Container(
                padding: const EdgeInsets.all(2),
                decoration: const BoxDecoration(
                  color: Colors.green,
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.check, size: 12, color: Colors.white),
              ),
            ),

          // Botão de retry ou aviso de erro quando falhou
          if (item.status == MediaUploadStatus.failed)
            Positioned(
              top: 2,
              right: 2,
              child: item.isTerminalError
                  ? Tooltip(
                      message: item.errorMessage ?? 'Erro no arquivo',
                      child: Container(
                        padding: const EdgeInsets.all(2),
                        decoration: BoxDecoration(
                          color: theme.colorScheme.error,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.error, size: 14, color: Colors.white),
                      ),
                    )
                  : GestureDetector(
                      onTap: onRetry,
                      child: Container(
                        padding: const EdgeInsets.all(3),
                        decoration: BoxDecoration(
                          color: theme.colorScheme.error,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(Icons.refresh, size: 14, color: Colors.white),
                      ),
                    ),
            ),
        ],
      ),
    );
  }
}
