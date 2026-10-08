import '../entities/notifications_page.dart';

/// Contrato de domínio para operações de notificações in-app.
abstract class NotificationRepository {
  /// Lista notificações paginadas em ordem cronológica reversa.
  Future<NotificationsPage> getNotifications({int page = 0, int size = 20});

  /// Obtém a contagem de notificações não lidas.
  Future<int> getUnreadCount();

  /// Marca uma notificação específica como lida.
  Future<void> markAsRead(String notificationId);

  /// Marca todas as notificações do usuário autenticado como lidas.
  Future<void> markAllAsRead();
}
