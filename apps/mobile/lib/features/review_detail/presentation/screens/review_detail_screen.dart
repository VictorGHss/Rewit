import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Tela de detalhe de uma avaliação específica.
///
/// Preparada para receber futuramente:
/// - Módulo de discussões e comentários comunitários;
/// - Mídia e fotos anexadas;
/// - Interação em tempo real com helpful;
/// - Edição e exclusão pelo autor.
class ReviewDetailScreen extends StatefulWidget {
  final String reviewId;
  final FeedReview? initialReview;
  final FeedRepository? feedRepository;

  const ReviewDetailScreen({
    super.key,
    required this.reviewId,
    this.initialReview,
    this.feedRepository,
  });

  @override
  State<ReviewDetailScreen> createState() => _ReviewDetailScreenState();
}

class _ReviewDetailScreenState extends State<ReviewDetailScreen> {
  FeedReview? _review;
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _review = widget.initialReview;
    if (_review == null && widget.feedRepository != null) {
      _loadReview();
    }
  }

  Future<void> _loadReview() async {
    if (widget.feedRepository == null) return;
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final review = await widget.feedRepository!.getReviewById(widget.reviewId);
      if (mounted) {
        setState(() {
          _review = review;
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage = 'Não foi possível carregar os detalhes da avaliação.';
          _isLoading = false;
        });
      }
    }
  }

  String _formatDate(DateTime date) {
    return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year} às ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Avaliação')),
        body: const Center(
          child: LoadingIndicator(message: 'Carregando detalhes...'),
        ),
      );
    }

    if (_errorMessage != null || _review == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Avaliação')),
        body: Center(
          child: ErrorView(
            title: 'Erro ao carregar avaliação',
            message: _errorMessage ?? 'Avaliação não encontrada.',
            onRetry: widget.feedRepository != null ? _loadReview : null,
          ),
        ),
      );
    }

    final review = _review!;
    final author = review.author;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Avaliação'),
        actions: [
          IconButton(
            icon: const Icon(Icons.share_outlined),
            tooltip: 'Compartilhar',
            onPressed: () {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Compartilhamento em breve.')),
              );
            },
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Cabeçalho do Autor
            Row(
              children: [
                CircleAvatar(
                  radius: 24,
                  backgroundColor: review.isAnonymous
                      ? Colors.grey.shade300
                      : theme.colorScheme.primary.withAlpha(30),
                  child: review.isAnonymous
                      ? Icon(Icons.person_off_outlined, color: Colors.grey.shade700)
                      : Text(
                          author.displayName.isNotEmpty
                              ? author.displayName[0].toUpperCase()
                              : 'U',
                          style: TextStyle(
                            fontSize: 20,
                            color: theme.colorScheme.primary,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Flexible(
                            child: Text(
                              author.displayName,
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
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
                                  fontSize: 11,
                                  color: Colors.grey.shade700,
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                      if (!review.isAnonymous && author.handle != null) ...[
                        Text(
                          '@${author.handle}',
                          style: TextStyle(
                            color: theme.colorScheme.onSurface.withAlpha(160),
                            fontSize: 13,
                          ),
                        ),
                      ],
                      Text(
                        _formatDate(review.createdAt),
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(130),
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),

            // Badge de Check-in
            if (review.isVerifiedOnSite) ...[
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                decoration: BoxDecoration(
                  color: Colors.green.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.green.shade300),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.check_circle, size: 16, color: Colors.green.shade700),
                    const SizedBox(width: 6),
                    Text(
                      'Presença confirmada no estabelecimento (Check-in validado)',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: Colors.green.shade800,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            // Alvos avaliados
            if (review.targets.isNotEmpty) ...[
              Text(
                'Alvos da Avaliação',
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              ...review.targets.map((target) {
                return Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  color: theme.colorScheme.surface,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                    side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(12.0),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Alvo: ${target.targetId}',
                                style: const TextStyle(fontWeight: FontWeight.w500, fontSize: 13),
                                overflow: TextOverflow.ellipsis,
                              ),
                              if (target.specificComment != null && target.specificComment!.isNotEmpty) ...[
                                const SizedBox(height: 4),
                                Text(
                                  target.specificComment!,
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: theme.colorScheme.onSurface.withAlpha(180),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                        Row(
                          children: [
                            const Icon(Icons.star, color: Colors.amber, size: 18),
                            const SizedBox(width: 4),
                            Text(
                              target.rating.toStringAsFixed(1),
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 14,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                );
              }),
              const SizedBox(height: 16),
            ],

            // Texto completo da experiência
            if (review.experienceText != null && review.experienceText!.isNotEmpty) ...[
              Text(
                'Experiência',
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                review.experienceText!,
                style: theme.textTheme.bodyLarge?.copyWith(
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 20),
            ],

            // Seção Helpful
            Card(
              elevation: 0,
              color: theme.colorScheme.surface,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
                side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Row(
                      children: [
                        Icon(
                          review.isHelpfulByMe ? Icons.thumb_up : Icons.thumb_up_outlined,
                          color: review.isHelpfulByMe
                              ? theme.colorScheme.primary
                              : theme.colorScheme.onSurface.withAlpha(180),
                          size: 20,
                        ),
                        const SizedBox(width: 8),
                        Text(
                          '${review.helpfulCount} pessoas acharam útil',
                          style: TextStyle(
                            fontWeight: FontWeight.w500,
                            color: theme.colorScheme.onSurface.withAlpha(200),
                          ),
                        ),
                      ],
                    ),
                    OutlinedButton.icon(
                      onPressed: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Voto útil interativo será habilitado em breve.'),
                          ),
                        );
                      },
                      icon: const Icon(Icons.thumb_up_outlined, size: 16),
                      label: const Text('Útil'),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),

            // Seção de Mídia (Placeholder preparado)
            Card(
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
                side: BorderSide(color: Colors.grey.withAlpha(60)),
              ),
              child: const Padding(
                padding: EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    Icon(Icons.photo_library_outlined, color: Colors.grey),
                    SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Fotos e anexos de mídia serão exibidos aqui.',
                        style: TextStyle(color: Colors.grey, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Seção de Discussões (Placeholder preparado - sem implementar discussions antes da finalização pelo Core)
            Card(
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
                side: BorderSide(color: Colors.grey.withAlpha(60)),
              ),
              child: const Padding(
                padding: EdgeInsets.all(16.0),
                child: Row(
                  children: [
                    Icon(Icons.forum_outlined, color: Colors.grey),
                    SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Discussões e comentários comunitários em preparação.',
                        style: TextStyle(color: Colors.grey, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
