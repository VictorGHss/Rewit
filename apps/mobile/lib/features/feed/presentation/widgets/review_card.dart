import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_privacy_rules.dart';
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
    if (review.contextPlaceId != null &&
        review.contextPlaceId == target.targetId) {
      return 'PLACE';
    }
    return null;
  }

  void _showMediaPreviewDialog(BuildContext context, ReviewMediaItem media) {
    showDialog<void>(
      context: context,
      builder: (ctx) {
        final dialogScheme = Theme.of(ctx).colorScheme;
        return Dialog(
          insetPadding: RewitSpacing.screenPadding,
          clipBehavior: Clip.antiAlias,
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
                        color: dialogScheme.surfaceContainerHighest,
                        child: Center(
                          child: Icon(
                            Icons.image,
                            size: 48,
                            color: dialogScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildTargetChip(BuildContext context, FeedTarget target) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final semantic = RewitSemanticColors.of(context);
    final resolvedType = _resolveTargetType(target);
    final isPlace = resolvedType == 'PLACE';
    final isProduct = resolvedType == 'PRODUCT';
    final isService = resolvedType == 'SERVICE';
    final isNavigable = (isPlace || isProduct) && onTargetTap != null;

    IconData icon;
    String labelText;
    Color chipBgColor;
    Color chipBorderColor;
    Color chipTextColor;
    Color chipIconColor;
    String? tooltipMessage;

    if (isPlace) {
      icon = Icons.place_outlined;
      labelText = 'Local • ${target.rating.toStringAsFixed(1)}';
      chipBgColor = semantic.placeTarget.container;
      chipBorderColor = semantic.placeTarget.border;
      chipTextColor = semantic.placeTarget.onContainer;
      chipIconColor = semantic.placeTarget.accent;
      tooltipMessage = 'Ver detalhes do local';
    } else if (isProduct) {
      icon = Icons.shopping_bag_outlined;
      labelText = 'Produto • ${target.rating.toStringAsFixed(1)}';
      chipBgColor = semantic.productTarget.container;
      chipBorderColor = semantic.productTarget.border;
      chipTextColor = semantic.productTarget.onContainer;
      chipIconColor = semantic.productTarget.accent;
      tooltipMessage = 'Ver detalhes do produto';
    } else if (isService) {
      icon = Icons.room_service_outlined;
      labelText = 'Serviço • ${target.rating.toStringAsFixed(1)}';
      chipBgColor = semantic.serviceTarget.container;
      chipBorderColor = semantic.serviceTarget.border;
      chipTextColor = semantic.serviceTarget.onContainer;
      chipIconColor = semantic.serviceTarget.accent;
    } else {
      icon = Icons.star;
      labelText = target.rating.toStringAsFixed(1);
      chipBgColor = scheme.surfaceContainerHigh;
      chipBorderColor = scheme.outlineVariant;
      chipTextColor = scheme.onSurface;
      chipIconColor = semantic.starFilled;
    }

    final chipWidget = Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: chipBgColor,
        borderRadius: RewitRadius.borderSm,
        border: Border.all(
          color: chipBorderColor,
          width: 0.8,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            icon,
            size: 13,
            color: chipIconColor,
          ),
          const SizedBox(width: 4),
          Flexible(
            child: Text(
              labelText,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: chipTextColor,
              ),
            ),
          ),
          if (isNavigable) ...[
            const SizedBox(width: 2),
            Icon(
              Icons.chevron_right,
              size: 13,
              color: chipTextColor,
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
          borderRadius: RewitRadius.borderSm,
          child: chipWidget,
        ),
      );
    }

    return chipWidget;
  }

  /// Visibilidade efetiva: `PRIVATE` nunca aparece como pública, e uma avaliação antiga anônima com `FOLLOWERS`
  /// aparece como visível só para o autor, que é como o backend a trata.
  Widget _buildVisibilityLabel(ColorScheme scheme) {
    final label = ReviewPrivacyRules.visibilityLabel(
      isAnonymous: review.isAnonymous,
      visibility: review.visibility,
    );
    final description = ReviewPrivacyRules.visibilityDescription(
      isAnonymous: review.isAnonymous,
      visibility: review.visibility,
    );
    final effective = ReviewPrivacyRules.effectiveVisibility(
      isAnonymous: review.isAnonymous,
      visibility: review.visibility,
    );
    final icon = switch (effective) {
      'FOLLOWERS' => Icons.group_outlined,
      'PRIVATE' => Icons.lock_outline,
      _ => Icons.public,
    };
    final color = scheme.onSurfaceVariant;

    return Tooltip(
      message: description,
      child: Semantics(
        label: description,
        excludeSemantics: true,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 13, color: color),
            const SizedBox(width: 4),
            Flexible(
              child: Text(
                label,
                key: Key('review_card_visibility_${review.id}'),
                style: TextStyle(fontSize: 11, color: color),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final semantic = RewitSemanticColors.of(context);
    final author = review.author;
    final avgRating = review.averageRating;
    final authorId = author.id;
    final canNavigateToAuthor = !review.isAnonymous &&
        authorId != null &&
        authorId.isNotEmpty &&
        author.displayName != 'Usuário excluído';

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
      child: InkWell(
        onTap: onTap,
        borderRadius: RewitRadius.borderLg,
        child: Padding(
          padding: RewitSpacing.cardPadding,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Cabeçalho: Autor, Anonimato e Timestamp Relativo
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: InkWell(
                      onTap: canNavigateToAuthor
                          ? () => onAuthorTap?.call(authorId)
                          : null,
                      borderRadius: RewitRadius.borderSm,
                      child: Row(
                        children: [
                          // Avatar
                          CircleAvatar(
                            radius: 20,
                            backgroundColor: review.isAnonymous
                                ? semantic.anonymousAvatarBackground
                                : scheme.secondaryContainer,
                            child: review.isAnonymous
                                ? Icon(
                                    Icons.person_off_outlined,
                                    size: 20,
                                    color: semantic.anonymousAvatarForeground,
                                  )
                                : Text(
                                    author.displayName.isNotEmpty
                                        ? author.displayName[0].toUpperCase()
                                        : 'U',
                                    style: TextStyle(
                                      color: scheme.onSecondaryContainer,
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
                                        style:
                                            theme.textTheme.titleSmall?.copyWith(
                                          fontWeight: FontWeight.bold,
                                        ),
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                    if (review.isAnonymous) ...[
                                      const SizedBox(width: 6),
                                      Container(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 6,
                                          vertical: 2,
                                        ),
                                        decoration: BoxDecoration(
                                          color: scheme.surfaceContainerHigh,
                                          borderRadius: RewitRadius.borderXs,
                                          border: Border.all(
                                            color: scheme.outlineVariant,
                                            width: 0.8,
                                          ),
                                        ),
                                        child: Text(
                                          'Anônimo',
                                          style: TextStyle(
                                            fontSize: 10,
                                            color: scheme.onSurfaceVariant,
                                            fontWeight: FontWeight.w500,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                if (!review.isAnonymous &&
                                    author.handle != null) ...[
                                  Text(
                                    '@${author.handle}',
                                    style: theme.textTheme.bodySmall?.copyWith(
                                      color: scheme.onSurfaceVariant,
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
                  // Timestamp Relativo: cede espaço ao autor e quebra a linha com fonte ampliada
                  Flexible(
                    child: Text(
                      formatRelativeTime(review.createdAt),
                      textAlign: TextAlign.end,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                        fontSize: 11,
                      ),
                    ),
                  ),
                ],
              ),

              // Badge: Presença confirmada no local (check-in verificado)
              if (review.isVerifiedOnSite) ...[
                const SizedBox(height: 10),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: semantic.verified.container,
                    borderRadius: RewitRadius.borderSm,
                    border: Border.all(color: semantic.verified.border),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.verified,
                        size: 14,
                        color: semantic.verified.accent,
                      ),
                      const SizedBox(width: 4),
                      Flexible(
                        child: Text(
                          'Presença confirmada no local',
                          style: TextStyle(
                            fontSize: 11,
                            color: semantic.verified.onContainer,
                            fontWeight: FontWeight.w600,
                          ),
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
                    Icon(Icons.star, color: semantic.starFilled, size: 18),
                    const SizedBox(width: 4),
                    Text(
                      avgRating.toStringAsFixed(1),
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        review.targets.length == 1
                            ? '(${review.targets.length} item avaliado)'
                            : '(${review.targets.length} itens avaliados)',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                  ],
                ),
              ],

              // Texto da experiência
              if (review.experienceText != null &&
                  review.experienceText!.isNotEmpty) ...[
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
              if (review.mediaItems != null &&
                  review.mediaItems!.isNotEmpty) ...[
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
                        borderRadius: RewitRadius.borderMd,
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
                                    color: scheme.surfaceContainerHighest,
                                    child: Center(
                                      child: Icon(
                                        Icons.image,
                                        size: 24,
                                        color: scheme.onSurfaceVariant,
                                      ),
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

              // Rodapé: Helpful interativo e Visibilidade. Cada lado ocupa só o espaço que sobra do outro, e o
              // voto útil tem área de toque mínima de 48 dp, separada do toque no card
              Row(
                children: [
                  Flexible(
                    child: Tooltip(
                      message: review.isHelpfulByMe
                          ? 'Remover voto útil'
                          : 'Marcar como útil',
                      child: Material(
                        type: MaterialType.transparency,
                        child: InkWell(
                          key: Key('review_card_helpful_${review.id}'),
                          onTap: isHelpfulLoading ? null : onHelpfulTap,
                          borderRadius: RewitRadius.borderMd,
                          child: ConstrainedBox(
                            constraints: const BoxConstraints(
                              minHeight: 48,
                              minWidth: 48,
                            ),
                            child: Padding(
                              padding:
                                  const EdgeInsets.symmetric(horizontal: 8),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  if (isHelpfulLoading)
                                    const SizedBox(
                                      width: 16,
                                      height: 16,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  else
                                    Icon(
                                      review.isHelpfulByMe
                                          ? Icons.thumb_up
                                          : Icons.thumb_up_outlined,
                                      size: 16,
                                      color: review.isHelpfulByMe
                                          ? scheme.primary
                                          : scheme.onSurfaceVariant,
                                    ),
                                  const SizedBox(width: 6),
                                  Flexible(
                                    child: Text(
                                      '${review.helpfulCount} ${review.helpfulCount == 1 ? 'útil' : 'úteis'}',
                                      style: TextStyle(
                                        fontSize: 12,
                                        color: review.isHelpfulByMe
                                            ? scheme.primary
                                            : scheme.onSurfaceVariant,
                                        fontWeight: review.isHelpfulByMe
                                            ? FontWeight.w600
                                            : FontWeight.normal,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Flexible(
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: _buildVisibilityLabel(scheme),
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
