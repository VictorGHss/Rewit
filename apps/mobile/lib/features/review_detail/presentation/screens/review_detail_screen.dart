import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/discussions/presentation/state/discussion_notifier.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussions_section.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_edit_screen.dart';
import 'package:rewit_mobile/features/review_detail/presentation/widgets/authenticated_image.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Argumentos tipados para abertura contextual da tela de detalhes da avaliação (C5.12, C6).
class ReviewDetailArgs {
  final String reviewId;
  final FeedReview? initialReview;
  final String? targetDiscussionId;
  final String? rootDiscussionId;
  final bool isReplyTarget;

  const ReviewDetailArgs({
    required this.reviewId,
    this.initialReview,
    this.targetDiscussionId,
    this.rootDiscussionId,
    this.isReplyTarget = false,
  });
}

/// Tela de detalhe completo de uma avaliação com suporte a mídia, Helpful e discussões comunitárias.
class ReviewDetailScreen extends StatefulWidget {
  final String reviewId;
  final FeedReview? initialReview;
  final String? targetDiscussionId;
  final String? rootDiscussionId;
  final bool isReplyTarget;
  final FeedRepository? feedRepository;
  final ReviewMediaRepository? mediaRepository;
  final DiscussionRepository? discussionRepository;
  final DiscussionNotifier? discussionNotifier;
  final FeedNotifier? feedNotifier;
  final String? currentUserId;
  final bool? isAuthor;
  final DateTime Function()? nowProvider;

  const ReviewDetailScreen({
    super.key,
    required this.reviewId,
    this.initialReview,
    this.targetDiscussionId,
    this.rootDiscussionId,
    this.isReplyTarget = false,
    this.feedRepository,
    this.mediaRepository,
    this.discussionRepository,
    this.discussionNotifier,
    this.feedNotifier,
    this.currentUserId,
    this.isAuthor,
    this.nowProvider,
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
  String? _mediaErrorMessage;
  String? _deletingMediaId;

  // Interação com Helpful
  bool _isTogglingHelpful = false;

  // Ciclo de vida da própria avaliação (C5.7)
  bool _isDeleting = false;
  bool _wasEdited = false;
  int _mutationVersion = 0;

  // Notifier de Discussões
  DiscussionNotifier? _discussionNotifier;
  bool _ownsNotifier = false;

  bool get _isAuthor {
    if (widget.isAuthor != null) return widget.isAuthor!;
    // Fonte principal: posse contextual do backend, que também cobre a própria review anônima
    final isMine = _review?.isMine;
    if (isMine != null) return isMine;
    // Fallback para payloads sem isMine (feed/listagens): comparação pelo id público do autor
    final authorId = _review?.author.id;
    if (authorId == null || authorId.isEmpty) return false;
    final currentUserId = widget.currentUserId;
    if (currentUserId == null || currentUserId.isEmpty) return false;
    return currentUserId == authorId;
  }

  DateTime get _now => widget.nowProvider?.call() ?? DateTime.now();

  bool get _canEdit {
    if (!_isAuthor) return false;
    final review = _review;
    if (review == null) return false;
    if (review.status.toUpperCase() != 'ACTIVE') return false;
    return _now.isBefore(review.createdAt.add(const Duration(hours: 24)));
  }

  bool get _canDelete {
    if (!_isAuthor) return false;
    final review = _review;
    if (review == null) return false;
    return review.status.toUpperCase() == 'ACTIVE';
  }


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
      if (widget.feedRepository != null) {
        _loadReview();
      }
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
    final hasInitialReview = _review != null;
    if (!hasInitialReview) {
      setState(() {
        _isLoadingMedia = false;
        _isLoading = true;
        _errorMessage = null;
      });
    }

    final requestVersion = _mutationVersion;

    try {
      final review = await widget.feedRepository!.getReviewById(widget.reviewId);
      if (mounted) {
        setState(() {
          if (requestVersion != _mutationVersion) {
            // Uma mutação local ocorreu enquanto a requisição canônica estava em andamento.
            // Preserva a mutação local mais recente, apenas enriquecendo isMine se ainda não definido.
            if (_review != null && review.isMine != null && _review!.isMine == null) {
              _review = _review!.copyWith(isMine: review.isMine);
            }
          } else {
            _review = review.copyWith(
              mediaItems: _mediaItems.isNotEmpty ? _mediaItems : review.mediaItems,
              isHelpfulByMe: _isTogglingHelpful ? _review?.isHelpfulByMe : review.isHelpfulByMe,
              helpfulCount: _isTogglingHelpful ? _review?.helpfulCount : review.helpfulCount,
              isMine: review.isMine ?? _review?.isMine,
            );
          }
          _isLoading = false;
        });
        if (!hasInitialReview) {
          _loadMedia();
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          if (_review == null) {
            _errorMessage = 'Não foi possível carregar os detalhes da avaliação.';
          }
        });
      }
    }
  }

  Future<void> _loadMedia() async {
    setState(() {
      _isLoadingMedia = true;
      _mediaErrorMessage = null;
    });

    try {
      List<ReviewMediaItem> media;
      if (widget.mediaRepository != null) {
        media = await widget.mediaRepository!.getReviewMedia(widget.reviewId);
      } else if (widget.feedRepository != null) {
        media = await widget.feedRepository!.getReviewMedia(widget.reviewId);
      } else {
        media = [];
      }

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
          _mediaErrorMessage = 'Não foi possível carregar as fotos da avaliação.';
        });
      }
    }
  }

  Future<void> _confirmAndDeleteMedia(ReviewMediaItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Excluir Foto'),
        content: const Text(
          'Deseja realmente remover esta foto da avaliação? Esta ação não pode ser desfeita.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
            ),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Excluir'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() {
      _deletingMediaId = item.id;
    });

    try {
      if (widget.mediaRepository != null) {
        await widget.mediaRepository!.deleteMedia(
          reviewId: widget.reviewId,
          mediaId: item.id,
        );
      }
      if (mounted) {
        setState(() {
          _mutationVersion++;
          _mediaItems.removeWhere((m) => m.id == item.id);
          _deletingMediaId = null;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Foto removida com sucesso!'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _deletingMediaId = null;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Falha ao excluir foto: ${e.detail}'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _deletingMediaId = null;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('Erro inesperado ao excluir foto.'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    }
  }

  void _openMediaPreview(ReviewMediaItem item) {
    showDialog<void>(
      context: context,
      builder: (ctx) => Dialog(
        insetPadding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AppBar(
              title: Text(item.mimeType.split('/').last.toUpperCase()),
              actions: [
                if (_canDelete)
                  IconButton(
                    icon: const Icon(Icons.delete_outline),
                    tooltip: 'Excluir Foto',
                    onPressed: () {
                      Navigator.of(ctx).pop();
                      _confirmAndDeleteMedia(item);
                    },
                  ),
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.of(ctx).pop(),
                ),
              ],
            ),
            if (widget.mediaRepository != null)
              AuthenticatedImage(
                url: item.url,
                mediaRepository: widget.mediaRepository!,
                fit: BoxFit.contain,
                height: 300,
              )
            else
              Container(
                height: 300,
                color: Colors.grey.shade100,
                child: const Center(child: Icon(Icons.image, size: 60)),
              ),
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Tamanho: ${(item.sizeBytes / 1024).toStringAsFixed(1)} KB',
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
                  ),
                  Text(
                    _formatDate(item.createdAt),
                    style: TextStyle(fontSize: 12, color: Colors.grey.shade600),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
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
          _mutationVersion++;
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

  Future<void> _navigateToEdit() async {
    final review = _review;
    if (review == null || widget.feedRepository == null || _isDeleting) return;

    final updatedReview = await Navigator.of(context).push<FeedReview>(
      MaterialPageRoute(
        builder: (context) => ReviewEditScreen(
          review: review,
          feedRepository: widget.feedRepository!,
        ),
      ),
    );

    if (updatedReview != null && mounted) {
      setState(() {
        _mutationVersion++;
        _review = updatedReview.copyWith(
          mediaItems: _mediaItems.isNotEmpty ? _mediaItems : updatedReview.mediaItems,
        );
        _wasEdited = true;
      });
      widget.feedNotifier?.updateReview(_review!);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Avaliação atualizada com sucesso!'),
          backgroundColor: Colors.green,
        ),
      );
    }
  }

  Future<void> _confirmAndDeleteReview() async {
    if (_isDeleting || widget.feedRepository == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Excluir Avaliação'),
        content: const Text(
          'Deseja realmente excluir esta avaliação? Essa publicação será removida e deixará de aparecer para outras pessoas.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            key: const Key('confirm_delete_review_button'),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
            ),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Excluir'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() {
      _isDeleting = true;
    });

    try {
      await widget.feedRepository!.deleteReview(widget.reviewId);

      if (mounted) {
        widget.feedNotifier?.removeReview(widget.reviewId);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Avaliação excluída com sucesso.'),
            backgroundColor: Colors.green,
          ),
        );
        Navigator.of(context).pop({'deleted': true, 'reviewId': widget.reviewId});
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isDeleting = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Falha ao excluir avaliação: ${e.detail}'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } on NetworkException catch (e) {
      if (mounted) {
        setState(() {
          _isDeleting = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.message),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isDeleting = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('Erro inesperado ao excluir avaliação.'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
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

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (!_isDeleting) {
          Navigator.of(context).pop(_wasEdited ? _review : null);
        }
      },
      child: Scaffold(
        appBar: AppBar(
          leading: BackButton(
            onPressed: _isDeleting
                ? null
                : () => Navigator.of(context).pop(_wasEdited ? _review : null),
          ),
          title: const Text('Avaliação'),
          actions: [
            if (_canEdit) ...[
              IconButton(
                key: const Key('review_detail_edit_button'),
                icon: const Icon(Icons.edit_outlined),
                tooltip: 'Editar Avaliação',
                onPressed: _isDeleting ? null : _navigateToEdit,
              ),
            ],
            if (_canDelete) ...[
              IconButton(
                key: const Key('review_detail_delete_button'),
                icon: _isDeleting
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.delete_outline),
                tooltip: 'Excluir Avaliação',
                onPressed: _isDeleting ? null : _confirmAndDeleteReview,
              ),
            ],
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
                  child: LoadingIndicator(message: 'Carregando fotos...'),
                ),
              ),
            ] else if (_mediaErrorMessage != null) ...[
              Card(
                color: theme.colorScheme.errorContainer.withAlpha(80),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                child: Padding(
                  padding: const EdgeInsets.all(12.0),
                  child: Row(
                    children: [
                      Icon(Icons.error_outline, color: theme.colorScheme.error),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          _mediaErrorMessage!,
                          style: TextStyle(fontSize: 13, color: theme.colorScheme.onErrorContainer),
                        ),
                      ),
                      TextButton(
                        onPressed: _loadMedia,
                        child: const Text('Tentar novamente'),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
            ] else if (_mediaItems.isNotEmpty) ...[
              Text(
                'Fotos e Anexos (${_mediaItems.length})',
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                height: 120,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _mediaItems.length,
                  separatorBuilder: (context, _) => const SizedBox(width: 10),
                  itemBuilder: (context, index) {
                    final item = _mediaItems[index];
                    final isDeletingThis = _deletingMediaId == item.id;

                    return GestureDetector(
                      onTap: isDeletingThis ? null : () => _openMediaPreview(item),
                      child: Container(
                        width: 120,
                        decoration: BoxDecoration(
                          color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(100)),
                        ),
                        child: Stack(
                          children: [
                            Positioned.fill(
                              child: widget.mediaRepository != null
                                  ? AuthenticatedImage(
                                      url: item.url,
                                      mediaRepository: widget.mediaRepository!,
                                      borderRadius: BorderRadius.circular(7),
                                      fit: BoxFit.cover,
                                    )
                                  : Center(
                                      child: Column(
                                        mainAxisAlignment: MainAxisAlignment.center,
                                        children: [
                                          Icon(Icons.image, size: 36, color: theme.colorScheme.primary),
                                          const SizedBox(height: 2),
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
                                    ),
                            ),
                            // Rodapé com tamanho quando renderizado com AuthenticatedImage
                            if (widget.mediaRepository != null)
                              Positioned(
                                bottom: 0,
                                left: 0,
                                right: 0,
                                child: Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                  decoration: BoxDecoration(
                                    color: Colors.black.withAlpha(130),
                                    borderRadius: const BorderRadius.vertical(bottom: Radius.circular(7)),
                                  ),
                                  child: Text(
                                    '${(item.sizeBytes / 1024).toStringAsFixed(0)} KB',
                                    style: const TextStyle(
                                      fontSize: 10,
                                      color: Colors.white,
                                      fontWeight: FontWeight.w600,
                                    ),
                                    textAlign: TextAlign.center,
                                  ),
                                ),
                              ),
                            // Botão de exclusão (apenas para o autor em ACTIVE)
                            if (_canDelete)
                              Positioned(
                                top: 4,
                                right: 4,
                                child: isDeletingThis
                                    ? Container(
                                        padding: const EdgeInsets.all(4),
                                        decoration: BoxDecoration(
                                          color: Colors.black.withAlpha(150),
                                          shape: BoxShape.circle,
                                        ),
                                        child: const SizedBox(
                                          width: 14,
                                          height: 14,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                            color: Colors.white,
                                          ),
                                        ),
                                      )
                                    : GestureDetector(
                                        key: ValueKey('delete_media_${item.id}'),
                                        onTap: () => _confirmAndDeleteMedia(item),
                                        child: Container(
                                          padding: const EdgeInsets.all(4),
                                          decoration: BoxDecoration(
                                            color: Colors.black.withAlpha(150),
                                            shape: BoxShape.circle,
                                          ),
                                          child: const Icon(
                                            Icons.delete_outline,
                                            size: 16,
                                            color: Colors.white,
                                          ),
                                        ),
                                      ),
                              ),
                          ],
                        ),
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
                targetDiscussionId: widget.targetDiscussionId,
                rootDiscussionId: widget.rootDiscussionId,
                isReplyTarget: widget.isReplyTarget,
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
    ),
    );
  }
}
