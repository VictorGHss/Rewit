import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/notifications_page.dart';
import '../../domain/repositories/notification_repository.dart';
import '../models/notification_dtos.dart';

/// Implementação de [NotificationRepository] integrada à API REST do Rewit.
class NotificationRepositoryImpl implements NotificationRepository {
  final RewitHttpClient httpClient;

  const NotificationRepositoryImpl({required this.httpClient});

  @override
  Future<NotificationsPage> getNotifications({int page = 0, int size = 20}) async {
    final response = await httpClient.get(
      ApiEndpoints.notificationsPath,
      queryParameters: {
        'page': page,
        'size': size,
      },
    );

    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar notificações.');
    }

    return NotificationsPageDto.fromJson(decoded).toEntity();
  }

  @override
  Future<int> getUnreadCount() async {
    final response = await httpClient.get(ApiEndpoints.notificationsUnreadCount);

    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter contagem de não lidas.');
    }

    return UnreadCountDto.fromJson(decoded).count;
  }

  @override
  Future<void> markAsRead(String notificationId) async {
    await httpClient.patch(ApiEndpoints.notificationRead(notificationId));
  }

  @override
  Future<void> markAllAsRead() async {
    await httpClient.patch(ApiEndpoints.notificationsReadAll);
  }
}
