import 'package:flutter/material.dart';
import '../../domain/entities/feed_entities.dart';

/// Card representacional de uma avaliação no Feed V2.
class ReviewCard extends StatelessWidget {
  final FeedReview review;
  final VoidCallback? onTap;

  const ReviewCard({
    super.key,
    required this.review,
    this.onTap,
  });

  String _formatDate(DateTime date) {
    return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final author = review.author;
    final avgRating = review.averageRating;

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
              // Cabeçalho: Autor, Anonimato, Check-in e Data
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
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
                  // Timestamp
                  Text(
                    _formatDate(review.createdAt),
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurface.withAlpha(140),
                      fontSize: 11,
                    ),
                  ),
                ],
              ),

              // Badges opcionais: Presença confirmada no local (check-in verificado)
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

              // Rating consolidado ou alvos
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

              // Targets específicos (se houver comentários pontuais)
              if (review.targets.isNotEmpty) ...[
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: review.targets.map((target) {
                    return Chip(
                      visualDensity: VisualDensity.compact,
                      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      padding: const EdgeInsets.symmetric(horizontal: 4),
                      avatar: const Icon(Icons.star, size: 12, color: Colors.amber),
                      label: Text(
                        target.rating.toStringAsFixed(1),
                        style: const TextStyle(fontSize: 11),
                      ),
                    );
                  }).toList(),
                ),
              ],

              const SizedBox(height: 12),
              const Divider(height: 1, thickness: 0.5),
              const SizedBox(height: 8),

              // Rodapé: Helpful e Visibilidade
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
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
