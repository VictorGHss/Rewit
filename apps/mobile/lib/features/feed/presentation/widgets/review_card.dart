import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/review_detail/presentation/widgets/authenticated_image.dart';
import '../../domain/entities/feed_entities.dart';

/// Card representacional evoluído de uma avaliação no Feed V2 (C5.5).
class ReviewCard extends StatelessWidget {
  final FeedReview review;
  final ReviewMediaRepository? mediaRepository;
  final VoidCallback? onTap;
  final void Function(String authorId)? onAuthorTap;
  final void Function(String targetId, String targetType)? onTargetTap;
  final VoidCallback? onHelpfulTap;
  final bool isHelpfulLoading;

  const ReviewCard({
    super.key,
    required this.review,
    this.mediaRepository,
    this.onTap,
    this.onAuthorTap,
    this.onTargetTap,
    this.onHelpfulTap,
    this.isHelpfulLoading = false,
  });

  /// Formatação determinística e amigável de tempo relativo sem dependências externas.
  static String formatRelativeTime(DateTime date, {DateTime? now}) {
    final current = now ?? DateTime.now();
    final difference = current.difference(date);

    if (difference.isNegative || difference.inSeconds < 60) {
      return 'agora';
    } else if (difference.inMinutes < 60) {
      return 'há ${difference.inMinutes} min';
    } else if (difference.inHours < 24) {
      return 'há ${difference.inHours} h';
    } else if (difference.inDays == 1) {
      return 'ontem';
    } else if (difference.inDays < 7) {
      return 'há ${difference.inDays} dias';
    } else {
      return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
    }
  }

  /// Resolve o tipo de alvo (PLACE, PRODUCT, SERVICE ou nulo para desconhecido).
  String? _resolveTargetType(FeedTarget target) {
    if (target.targetType != null && target.targetType!.isNotEmpty) {
      return target.targetType!.toUpperCase();
    }
    // Quando o targetId coincide com contextPlaceId da avaliação, é comprovadamente um LOCAL
    if (review.contextPlaceId != null && review.contextPlaceId == target.targetId) {
      return 'PLACE';
    }
    return null;
  }

  void _showMediaPreviewDialog(BuildContext context, ReviewMediaItem media) {
    showDialog<void>(
      context: context,
      builder: (ctx) => Dialog(
        insetPadding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AppBar(
              title: Text(media.mimeType.split('/').last.toUpperCase()),
              actions: [
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.of(ctx).pop(),
                ),
              ],
            ),
            AspectRatio(
              aspectRatio: 4 / 3,
              child: mediaRepository != null
                  ? AuthenticatedImage(
                      url: media.url,
                      mediaRepository: mediaRepository!,
                      fit: BoxFit.contain,
                    )
                  : Container(
                      color: Colors.grey.shade200,
                      child: const Center(
                        child: Icon(Icons.image, size: 48, color: Colors.grey),
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTargetChip(BuildContext context, FeedTarget target) {
    final theme = Theme.of(context);
    final resolvedType = _resolveTargetType(target);
    final isPlace = resolvedType == 'PLACE';
    final isProduct = resolvedType == 'PRODUCT';
    final isService = resolvedType == 'SERVICE';
    final isNavigable = (isPlace || isProduct) && onTargetTap != null;

    IconData icon;
    String labelText;
    Color? chipBgColor;
    Color? chipBorderColor;
    Color? chipTextColor;
    String? tooltipMessage;

    if (isPlace) {
      icon = Icons.place_outlined;
      labelText = 'Local • ${target.rating.toStringAsFixed(1)}';
      chipBgColor = Colors.teal.shade50;
      chipBorderColor = Colors.teal.shade200;
      chipTextColor = Colors.teal.shade900;
      tooltipMessage = 'Ver detalhes do local';
    } else if (isProduct) {
      icon = Icons.shopping_bag_outlined;
      labelText = 'Produto • ${target.rating.toStringAsFixed(1)}';
      chipBgColor = Colors.indigo.shade50;
      chipBorderColor = Colors.indigo.shade200;
      chipTextColor = Colors.indigo.shade900;
      tooltipMessage = 'Ver detalhes do produto';
    } else if (isService) {
      icon = Icons.room_service_outlined;
      labelText = 'Serviço • ${target.rating.toStringAsFixed(1)}';
      chipBgColor = Colors.orange.shade50;
      chipBorderColor = Colors.orange.shade200;
      chipTextColor = Colors.orange.shade900;
    } else {
      icon = Icons.star;
      labelText = target.rating.toStringAsFixed(1);
    }

    final chipWidget = Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: chipBgColor ?? theme.colorScheme.surfaceContainerHighest.withAlpha(80),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: chipBorderColor ?? theme.colorScheme.outlineVariant.withAlpha(100),
          width: 0.8,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            icon,
            size: 13,
            color: chipTextColor ?? (isPlace || isProduct || isService ? null : Colors.amber.shade700),
          ),
          const SizedBox(width: 4),
          Text(
            labelText,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: chipTextColor ?? theme.colorScheme.onSurface,
            ),
          ),
          if (isNavigable) ...[
            const SizedBox(width: 2),
            Icon(
              Icons.chevron_right,
              size: 13,
              color: chipTextColor ?? theme.colorScheme.onSurface.withAlpha(150),
            ),
          ],
        ],
      ),
    );

    if (isNavigable) {
      return Tooltip(
        message: tooltipMessage ?? '',
        child: InkWell(
          onTap: () => onTargetTap?.call(target.targetId, resolvedType!),
          borderRadius: BorderRadius.circular(6),
          child: chipWidget,
        ),
      );
    }

    return chipWidget;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final author = review.author;
    final avgRating = review.averageRating;
    final authorId = author.id;
    final canNavigateToAuthor = !review.isAnonymous &&
        authorId != null &&
        authorId.isNotEmpty &&
        author.displayName != 'Usuário excluído';

    return Card(
      elevation: 0.5,
      margin: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Cabeçalho: Autor, Anonimato e Timestamp Relativo
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: InkWell(
                      onTap: canNavigateToAuthor ? () => onAuthorTap?.call(authorId) : null,
                      borderRadius: BorderRadius.circular(6),
                      child: Row(
                        children: [
                          // Avatar
                          CircleAvatar(
                            radius: 20,
                            backgroundColor: review.isAnonymous
                                ? Colors.grey.shade300
                                : theme.colorScheme.primary.withAlpha(30),
                            child: review.isAnonymous
                                ? Icon(Icons.person_off_outlined, size: 20, color: Colors.grey.shade700)
                                : Text(
                                    author.displayName.isNotEmpty
                                        ? author.displayName[0].toUpperCase()
                                        : 'U',
                                    style: TextStyle(
                                      color: theme.colorScheme.primary,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                          ),
                          const SizedBox(width: 12),
                          // Informações do Autor
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Flexible(
                                      child: Text(
                                        author.displayName,
                                        style: theme.textTheme.titleSmall?.copyWith(
                                          fontWeight: FontWeight.bold,
                                        ),
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                    if (review.isAnonymous) ...[
                                      const SizedBox(width: 6),
                                      Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                        decoration: BoxDecoration(
                                          color: Colors.grey.shade200,
                                          borderRadius: BorderRadius.circular(4),
                                        ),
                                        child: Text(
                                          'Anônimo',
                                          style: TextStyle(
                                            fontSize: 10,
                                            color: Colors.grey.shade700,
                                            fontWeight: FontWeight.w500,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                if (!review.isAnonymous && author.handle != null) ...[
                                  Text(
                                    '@${author.handle}',
                                    style: theme.textTheme.bodySmall?.copyWith(
                                      color: theme.colorScheme.onSurface.withAlpha(160),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  // Timestamp Relativo
                  Text(
                    formatRelativeTime(review.createdAt),
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurface.withAlpha(140),
                      fontSize: 11,
                    ),
                  ),
                ],
              ),

              // Badge: Presença confirmada no local (check-in verificado)
              if (review.isVerifiedOnSite) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: Colors.green.shade50,
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: Colors.green.shade200),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.verified, size: 14, color: Colors.green.shade700),
                      const SizedBox(width: 4),
                      Text(
                        'Presença confirmada no local',
                        style: TextStyle(
                          fontSize: 11,
                          color: Colors.green.shade800,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ],

              // Rating consolidado
              if (avgRating != null) ...[
                const SizedBox(height: 12),
                Row(
                  children: [
                    const Icon(Icons.star, color: Colors.amber, size: 18),
                    const SizedBox(width: 4),
                    Text(
                      avgRating.toStringAsFixed(1),
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      review.targets.length == 1
                          ? '(${review.targets.length} item avaliado)'
                          : '(${review.targets.length} itens avaliados)',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurface.withAlpha(160),
                      ),
                    ),
                  ],
                ),
              ],

              // Texto da experiência
              if (review.experienceText != null && review.experienceText!.isNotEmpty) ...[
                const SizedBox(height: 10),
                Text(
                  review.experienceText!,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    height: 1.4,
                  ),
                  maxLines: 4,
                  overflow: TextOverflow.ellipsis,
                ),
              ],

              // Targets com diferenciação visual (LOCAL, PRODUTO, SERVIÇO) e navegação
              if (review.targets.isNotEmpty) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: review.targets.map((target) {
                    return _buildTargetChip(context, target);
                  }).toList(),
                ),
              ],

              // Prévia de mídia (renderizada apenas quando presente no modelo, sem chamadas N+1)
              if (review.mediaItems != null && review.mediaItems!.isNotEmpty) ...[
                const SizedBox(height: 12),
                SizedBox(
                  height: 72,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    itemCount: review.mediaItems!.length,
                    separatorBuilder: (_, __) => const SizedBox(width: 8),
                    itemBuilder: (context, index) {
                      final media = review.mediaItems![index];
                      return ClipRRect(
                        borderRadius: BorderRadius.circular(8),
                        child: InkWell(
                          onTap: () => _showMediaPreviewDialog(context, media),
                          child: SizedBox(
                            width: 72,
                            height: 72,
                            child: mediaRepository != null
                                ? AuthenticatedImage(
                                    url: media.url,
                                    mediaRepository: mediaRepository!,
                                    fit: BoxFit.cover,
                                  )
                                : Container(
                                    color: Colors.grey.shade200,
                                    child: const Center(
                                      child: Icon(Icons.image, size: 24, color: Colors.grey),
                                    ),
                                  ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],

              const SizedBox(height: 12),
              const Divider(height: 1, thickness: 0.5),
              const SizedBox(height: 6),

              // Rodapé: Helpful interativo e Visibilidade
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Material(
                    color: Colors.transparent,
                    child: InkWell(
                      onTap: isHelpfulLoading ? null : onHelpfulTap,
                      borderRadius: BorderRadius.circular(8),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (isHelpfulLoading)
                              const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            else
                              Icon(
                                review.isHelpfulByMe ? Icons.thumb_up : Icons.thumb_up_outlined,
                                size: 16,
                                color: review.isHelpfulByMe
                                    ? theme.colorScheme.primary
                                    : theme.colorScheme.onSurface.withAlpha(180),
                              ),
                            const SizedBox(width: 6),
                            Text(
                              '${review.helpfulCount} ${review.helpfulCount == 1 ? 'útil' : 'úteis'}',
                              style: TextStyle(
                                fontSize: 12,
                                color: review.isHelpfulByMe
                                    ? theme.colorScheme.primary
                                    : theme.colorScheme.onSurface.withAlpha(180),
                                fontWeight: review.isHelpfulByMe ? FontWeight.w600 : FontWeight.normal,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  Text(
                    review.visibility == 'FOLLOWERS' ? 'Seguidores' : 'Público',
                    style: TextStyle(
                      fontSize: 11,
                      color: theme.colorScheme.onSurface.withAlpha(140),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
