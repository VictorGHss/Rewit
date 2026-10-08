import 'in_app_notification.dart';

/// Resultado paginado de notificações in-app no domínio.
class NotificationsPage {
  final List<InAppNotification> items;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const NotificationsPage({
    required this.items,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });
}
