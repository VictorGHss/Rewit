import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';
import '../../domain/entities/in_app_notification.dart';
import '../../domain/repositories/notification_repository.dart';
import '../state/notifications_notifier.dart';
import '../state/notifications_state.dart';
import '../widgets/notification_item_card.dart';

/// Tela dedicada à listagem e gestão de notificações in-app do usuário autenticado.
class NotificationsScreen extends StatefulWidget {
  final NotificationRepository? repository;
  final NotificationsNotifier? notifier;

  const NotificationsScreen({
    super.key,
    this.repository,
    this.notifier,
  }) : assert(
          repository != null || notifier != null,
          'Deve ser fornecido repository ou notifier',
        );

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  late final NotificationsNotifier _notifier;
  late final bool _internalNotifier;
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
      _internalNotifier = false;
    } else {
      _notifier = NotificationsNotifier(repository: widget.repository!);
      _internalNotifier = true;
    }

    _scrollController.addListener(_onScroll);

    // Carrega se estiver no estado inicial após a renderização do frame
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _notifier.state is NotificationsInitial) {
        _notifier.loadNotifications();
      }
    });
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    if (_internalNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final maxScroll = _scrollController.position.maxScrollExtent;
    final currentScroll = _scrollController.position.pixels;
    if (currentScroll >= maxScroll - 200) {
      _notifier.loadMore();
    }
  }

  Future<void> _handleNotificationTap(InAppNotification item) async {
    // Marca como lida antes de navegar. Em falha, o notifier já reverteu o estado otimista
    // e o usuário permanece na lista com o feedback de erro
    if (!item.isRead) {
      final marked = await _runAction(
        () => _notifier.markAsRead(item.id),
        fallbackMessage: 'Não foi possível marcar a notificação como lida.',
      );
      if (!marked || !mounted) return;
    }

    switch (item.type) {
      case NotificationType.newFollower:
        final targetUserId = item.referenceId ?? item.actorId;
        if (targetUserId != null && targetUserId.isNotEmpty) {
          Navigator.of(context).pushNamed(
            AppRouter.profile,
            arguments: targetUserId,
          );
        }
        break;

      case NotificationType.reviewHelpful:
        final reviewId = item.reviewId ?? item.referenceId;
        if (reviewId != null && reviewId.isNotEmpty) {
          Navigator.of(context).pushNamed(
            AppRouter.reviewDetail,
            arguments: ReviewDetailArgs(reviewId: reviewId),
          );
        }
        break;

      case NotificationType.newDiscussion:
        final reviewId = item.reviewId ?? item.referenceId;
        if (reviewId != null && reviewId.isNotEmpty) {
          Navigator.of(context).pushNamed(
            AppRouter.reviewDetail,
            arguments: ReviewDetailArgs(
              reviewId: reviewId,
              targetDiscussionId: item.discussionId,
              isReplyTarget: false,
            ),
          );
        }
        break;

      case NotificationType.discussionReply:
        final reviewId = item.reviewId;
        final targetDiscussionId = item.discussionId ?? item.referenceId;
        if (reviewId != null && reviewId.isNotEmpty) {
          Navigator.of(context).pushNamed(
            AppRouter.reviewDetail,
            arguments: ReviewDetailArgs(
              reviewId: reviewId,
              targetDiscussionId: targetDiscussionId,
              isReplyTarget: true,
            ),
          );
        } else {
          // Fallback seguro para notificações legadas sem reviewId associado
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Notificação de resposta em discussão marcada como lida.'),
              duration: Duration(seconds: 2),
            ),
          );
        }
        break;
    }
  }

  Future<void> _markAllAsRead() async {
    await _runAction(
      _notifier.markAllAsRead,
      fallbackMessage: 'Não foi possível marcar todas as notificações como lidas.',
    );
  }

  /// Executa uma ação do notifier e converte falhas em SnackBar de erro. Retorna se a ação concluiu.
  Future<bool> _runAction(
    Future<void> Function() action, {
    required String fallbackMessage,
  }) async {
    try {
      await action();
      return true;
    } on ApiException catch (e) {
      _showError(e.detail);
    } on NetworkException catch (e) {
      _showError(e.message);
    } catch (_) {
      _showError(fallbackMessage);
    }
    return false;
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Theme.of(context).colorScheme.error,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _notifier,
      builder: (context, _) {
        final state = _notifier.state;
        final hasUnread = state is NotificationsLoaded && state.hasUnread;
        final isMarkingAll = state is NotificationsLoaded && state.isMarkingAll;

        return Scaffold(
          appBar: AppBar(
            title: const Text('Notificações'),
            actions: [
              if (hasUnread)
                TextButton.icon(
                  onPressed: isMarkingAll ? null : _markAllAsRead,
                  icon: isMarkingAll
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.done_all, size: 18),
                  label: const Text('Marcar lidas'),
                ),
            ],
          ),
          body: _buildBody(context, state),
        );
      },
    );
  }

  Widget _buildBody(BuildContext context, NotificationsState state) {
    if (state is NotificationsLoading || state is NotificationsInitial) {
      return const Center(
        child: LoadingIndicator(message: 'Carregando notificações...'),
      );
    }

    if (state is NotificationsError) {
      return ErrorView(
        title: 'Não foi possível carregar as notificações',
        message: state.message,
        onRetry: () => _notifier.refresh(),
      );
    }

    if (state is NotificationsEmpty) {
      return RefreshIndicator(
        onRefresh: () => _notifier.refresh(),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            SizedBox(
              height: MediaQuery.of(context).size.height * 0.6,
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 32.0),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.notifications_none_outlined,
                        size: 64,
                        color: Theme.of(context).colorScheme.primary.withAlpha(140),
                      ),
                      const SizedBox(height: 16),
                      Text(
                        'Nenhuma notificação por enquanto',
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Quando outros membros seguirem você ou interagirem com suas avaliações, você verá aqui.',
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context).colorScheme.onSurface.withAlpha(160),
                            ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    }

    if (state is NotificationsLoaded) {
      return RefreshIndicator(
        onRefresh: () => _notifier.refresh(),
        child: ListView.builder(
          controller: _scrollController,
          physics: const AlwaysScrollableScrollPhysics(),
          itemCount: state.notifications.length + (state.isLoadingMore ? 1 : 0),
          itemBuilder: (context, index) {
            if (index >= state.notifications.length) {
              return const Padding(
                padding: EdgeInsets.symmetric(vertical: 16.0),
                child: Center(
                  child: SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              );
            }

            final item = state.notifications[index];
            return NotificationItemCard(
              notification: item,
              onTap: () => _handleNotificationTap(item),
            );
          },
        ),
      );
    }

    return const SizedBox.shrink();
  }
}
