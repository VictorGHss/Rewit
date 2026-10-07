import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/discussions/presentation/state/discussion_notifier.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussions_section.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Tela de detalhe completo de uma avaliação com suporte a mídia, Helpful e discussões comunitárias.
class ReviewDetailScreen extends StatefulWidget {
  final String reviewId;
  final FeedReview? initialReview;
  final FeedRepository? feedRepository;
  final DiscussionRepository? discussionRepository;
  final DiscussionNotifier? discussionNotifier;

  const ReviewDetailScreen({
    super.key,
    required this.reviewId,
    this.initialReview,
    this.feedRepository,
    this.discussionRepository,
    this.discussionNotifier,
  });

  @override
  State<ReviewDetailScreen> createState() => _ReviewDetailScreenState();
}

class _ReviewDetailScreenState extends State<ReviewDetailScreen> {
  FeedReview? _review;
  bool _isLoading = false;
  String? _errorMessage;

  // Mídias da avaliação
  List<ReviewMediaItem> _mediaItems = [];
  bool _isLoadingMedia = false;

  // Interação com Helpful
  bool _isTogglingHelpful = false;

  // Notifier de Discussões
  DiscussionNotifier? _discussionNotifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    _review = widget.initialReview;

    if (widget.discussionNotifier != null) {
      _discussionNotifier = widget.discussionNotifier;
    } else if (widget.discussionRepository != null) {
      _discussionNotifier = DiscussionNotifier(repository: widget.discussionRepository!);
      _ownsNotifier = true;
    }

    if (_review == null && widget.feedRepository != null) {
      _loadReview();
    } else if (_review != null) {
      _loadMedia();
    }
  }

  @override
  void dispose() {
    if (_ownsNotifier) {
      _discussionNotifier?.dispose();
    }
    super.dispose();
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
        _loadMedia();
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

  Future<void> _loadMedia() async {
    if (widget.feedRepository == null) return;
    setState(() {
      _isLoadingMedia = true;
    });

    try {
      final media = await widget.feedRepository!.getReviewMedia(widget.reviewId);
      if (mounted) {
        setState(() {
          _mediaItems = media;
          _isLoadingMedia = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isLoadingMedia = false;
        });
      }
    }
  }

  Future<void> _handleToggleHelpful() async {
    final review = _review;
    if (review == null || widget.feedRepository == null || _isTogglingHelpful) return;

    setState(() {
      _isTogglingHelpful = true;
    });

    try {
      final result = await widget.feedRepository!.toggleHelpful(
        review.id,
        currentlyHelpful: review.isHelpfulByMe,
      );

      if (mounted) {
        setState(() {
          _review = review.copyWith(
            isHelpfulByMe: result.helpful,
            helpfulCount: result.helpfulCount,
          );
          _isTogglingHelpful = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isTogglingHelpful = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Falha ao atualizar voto útil. Tente novamente.')),
        );
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
            // 1. Cabeçalho do Autor
            InkWell(
              onTap: !review.isAnonymous &&
                      author.id != null &&
                      author.id!.isNotEmpty &&
                      author.displayName != 'Usuário excluído'
                  ? () => Navigator.of(context).pushNamed(
                        AppRouter.profile,
                        arguments: author.id,
                      )
                  : null,
              borderRadius: BorderRadius.circular(8),
              child: Row(
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
            ),
            const SizedBox(height: 16),

            // 2. Badge de Check-in (Verificação Presencial)
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
                    Flexible(
                      child: Text(
                        'Presença confirmada no estabelecimento (Check-in validado)',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: Colors.green.shade800,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            // 3. Alvos da Avaliação e Notas
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

            // 4. Texto completo da experiência
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

            // 5. Mídia quando disponível
            if (_isLoadingMedia) ...[
              const Center(
                child: Padding(
                  padding: EdgeInsets.symmetric(vertical: 8.0),
                  child: LoadingIndicator(message: 'Carregando mídias...'),
                ),
              ),
            ] else if (_mediaItems.isNotEmpty) ...[
              Text(
                'Fotos e Anexos (${_mediaItems.length})',
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                height: 110,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _mediaItems.length,
                  separatorBuilder: (context, _) => const SizedBox(width: 10),
                  itemBuilder: (context, index) {
                    final item = _mediaItems[index];
                    return Container(
                      width: 120,
                      decoration: BoxDecoration(
                        color: Colors.grey.shade100,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: Colors.grey.shade300),
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.image, size: 40, color: theme.colorScheme.primary),
                          const SizedBox(height: 4),
                          Text(
                            item.mimeType.split('/').last.toUpperCase(),
                            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold),
                          ),
                          Text(
                            '${(item.sizeBytes / 1024).toStringAsFixed(0)} KB',
                            style: TextStyle(fontSize: 10, color: Colors.grey.shade600),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),
              const SizedBox(height: 20),
            ],

            // 6. Seção Helpful Interativa
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
                      onPressed: _isTogglingHelpful ? null : _handleToggleHelpful,
                      icon: _isTogglingHelpful
                          ? const SizedBox(
                              width: 14,
                              height: 14,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : Icon(
                              review.isHelpfulByMe ? Icons.thumb_up : Icons.thumb_up_outlined,
                              size: 16,
                            ),
                      label: Text(review.isHelpfulByMe ? 'Útil' : 'Votar útil'),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),

            // 7. Seção de Discussões Comunitárias
            if (_discussionNotifier != null) ...[
              DiscussionsSection(
                reviewId: widget.reviewId,
                notifier: _discussionNotifier!,
                onAuthorTap: (authorId) {
                  Navigator.of(context).pushNamed(
                    AppRouter.profile,
                    arguments: authorId,
                  );
                },
              ),
            ] else ...[
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
                          'Conexão de discussões indisponível.',
                          style: TextStyle(color: Colors.grey, fontSize: 13),
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
    );
  }
}
