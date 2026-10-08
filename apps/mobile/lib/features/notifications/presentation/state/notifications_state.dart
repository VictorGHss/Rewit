import '../../domain/entities/in_app_notification.dart';

/// Estados reativos da funcionalidade de Notificações In-App.
sealed class NotificationsState {
  const NotificationsState();
}

/// Estado inicial antes de qualquer busca de notificações.
class NotificationsInitial extends NotificationsState {
  const NotificationsInitial();
}

/// Estado de carregamento inicial da lista de notificações.
class NotificationsLoading extends NotificationsState {
  const NotificationsLoading();
}

/// Estado quando não há notificações disponíveis para o usuário autenticado.
class NotificationsEmpty extends NotificationsState {
  const NotificationsEmpty();
}

/// Estado com notificações carregadas e paginação ativa.
class NotificationsLoaded extends NotificationsState {
  final List<InAppNotification> notifications;
  final int unreadCount;
  final int currentPage;
  final bool isLastPage;
  final int totalElements;
  final bool isLoadingMore;
  final String? loadMoreError;
  final bool isMarkingAll;

  const NotificationsLoaded({
    required this.notifications,
    required this.unreadCount,
    this.currentPage = 0,
    this.isLastPage = true,
    this.totalElements = 0,
    this.isLoadingMore = false,
    this.loadMoreError,
    this.isMarkingAll = false,
  });

  bool get hasUnread => unreadCount > 0 || notifications.any((n) => !n.isRead);

  NotificationsLoaded copyWith({
    List<InAppNotification>? notifications,
    int? unreadCount,
    int? currentPage,
    bool? isLastPage,
    int? totalElements,
    bool? isLoadingMore,
    String? loadMoreError,
    bool clearLoadMoreError = false,
    bool? isMarkingAll,
  }) {
    return NotificationsLoaded(
      notifications: notifications ?? this.notifications,
      unreadCount: unreadCount ?? this.unreadCount,
      currentPage: currentPage ?? this.currentPage,
      isLastPage: isLastPage ?? this.isLastPage,
      totalElements: totalElements ?? this.totalElements,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      loadMoreError: clearLoadMoreError ? null : (loadMoreError ?? this.loadMoreError),
      isMarkingAll: isMarkingAll ?? this.isMarkingAll,
    );
  }
}

/// Estado de erro genérico ao consultar ou manipular notificações.
class NotificationsError extends NotificationsState {
  final String message;

  const NotificationsError(this.message);
}
