import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/notifications/data/models/notification_dtos.dart';
import 'package:rewit_mobile/features/notifications/data/repositories/notification_repository_impl.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/in_app_notification.dart';

class _MockHttpBaseClient extends http.BaseClient {
  http.Request? capturedRequest;
  http.Response? responseToReturn;
  Exception? exceptionToThrow;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    capturedRequest = request as http.Request;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    final resp = responseToReturn ??
        http.Response(
          jsonEncode({}),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
    return http.StreamedResponse(
      Stream.value(resp.bodyBytes),
      resp.statusCode,
      headers: resp.headers,
    );
  }
}

void main() {
  group('Notification DTOs Tests', () {
    test('NotificationDto deserializa todos os 4 tipos de notificação corretamente', () {
      final jsonFollower = {
        'id': 'notif-1',
        'type': 'NEW_FOLLOWER',
        'actorId': 'user-10',
        'referenceId': 'user-10',
        'readAt': null,
        'createdAt': '2026-10-08T10:00:00Z',
      };
      final dtoFollower = NotificationDto.fromJson(jsonFollower);
      final entityFollower = dtoFollower.toEntity();
      expect(entityFollower.id, 'notif-1');
      expect(entityFollower.type, NotificationType.newFollower);
      expect(entityFollower.actorId, 'user-10');
      expect(entityFollower.referenceId, 'user-10');
      expect(entityFollower.isRead, isFalse);

      final jsonHelpful = {
        'id': 'notif-2',
        'type': 'REVIEW_HELPFUL',
        'actorId': null,
        'referenceId': 'review-99',
        'readAt': '2026-10-08T10:05:00Z',
        'createdAt': '2026-10-08T10:00:00Z',
      };
      final dtoHelpful = NotificationDto.fromJson(jsonHelpful);
      final entityHelpful = dtoHelpful.toEntity();
      expect(entityHelpful.id, 'notif-2');
      expect(entityHelpful.type, NotificationType.reviewHelpful);
      expect(entityHelpful.actorId, isNull);
      expect(entityHelpful.referenceId, 'review-99');
      expect(entityHelpful.isRead, isTrue);

      final jsonDiscussion = {
        'id': 'notif-3',
        'type': 'NEW_DISCUSSION',
        'actorId': 'user-20',
        'referenceId': 'disc-root-1',
        'reviewId': 'review-99',
        'discussionId': 'disc-root-1',
        'readAt': null,
        'createdAt': '2026-10-08T10:10:00Z',
      };
      final entityDiscussion = NotificationDto.fromJson(jsonDiscussion).toEntity();
      expect(entityDiscussion.type, NotificationType.newDiscussion);
      expect(entityDiscussion.reviewId, 'review-99');
      expect(entityDiscussion.discussionId, 'disc-root-1');

      final jsonReply = {
        'id': 'notif-4',
        'type': 'DISCUSSION_REPLY',
        'actorId': 'user-30',
        'referenceId': 'reply-55',
        'reviewId': 'review-99',
        'discussionId': 'reply-55',
        'rootDiscussionId': 'root-10',
        'readAt': null,
        'createdAt': '2026-10-08T10:15:00Z',
      };
      final dtoReply = NotificationDto.fromJson(jsonReply);
      final entityReply = dtoReply.toEntity();
      expect(entityReply.type, NotificationType.discussionReply);
      expect(entityReply.referenceId, 'reply-55');
      expect(entityReply.reviewId, 'review-99');
      expect(entityReply.discussionId, 'reply-55');
      expect(entityReply.rootDiscussionId, 'root-10');
      expect(dtoReply.toJson()['rootDiscussionId'], 'root-10');
    });

    test('NotificationDto preserva compatibilidade retroativa com payloads legados sem reviewId, discussionId e rootDiscussionId', () {
      final legacyJson = {
        'id': 'notif-legacy',
        'type': 'DISCUSSION_REPLY',
        'actorId': 'user-30',
        'referenceId': 'reply-55',
        'readAt': null,
        'createdAt': '2026-10-08T10:15:00Z',
      };
      final dto = NotificationDto.fromJson(legacyJson);
      final entity = dto.toEntity();
      expect(entity.id, 'notif-legacy');
      expect(entity.reviewId, isNull);
      expect(entity.discussionId, isNull);
      expect(entity.rootDiscussionId, isNull);
      expect(entity.referenceId, 'reply-55');
    });

    test('InAppNotification suporta copyWith, equality e hashCode com rootDiscussionId', () {
      final notif = InAppNotification(
        id: 'n-1',
        type: NotificationType.discussionReply,
        actorId: 'u-1',
        referenceId: 'r-1',
        reviewId: 'rev-1',
        discussionId: 'disc-1',
        rootDiscussionId: 'root-1',
        createdAt: DateTime.parse('2026-10-09T10:00:00Z'),
      );
      final cloned = notif.copyWith();
      expect(cloned, equals(notif));
      expect(cloned.hashCode, equals(notif.hashCode));
      expect(cloned.rootDiscussionId, 'root-1');

      final modified = notif.copyWith(rootDiscussionId: 'root-2');
      expect(modified.rootDiscussionId, 'root-2');
      expect(modified, isNot(equals(notif)));
    });

    test('NotificationsPageDto deserializa paginação completa', () {
      final json = {
        'content': [
          {
            'id': 'notif-1',
            'type': 'NEW_FOLLOWER',
            'actorId': 'user-1',
            'referenceId': 'user-1',
            'readAt': null,
            'createdAt': '2026-10-08T10:00:00Z',
          }
        ],
        'pageNumber': 1,
        'pageSize': 20,
        'totalElements': 42,
        'totalPages': 3,
        'isLast': false,
      };

      final page = NotificationsPageDto.fromJson(json).toEntity();
      expect(page.items.length, 1);
      expect(page.pageNumber, 1);
      expect(page.pageSize, 20);
      expect(page.totalElements, 42);
      expect(page.totalPages, 3);
      expect(page.isLast, isFalse);
    });

    test('UnreadCountDto deserializa contagem numérica corretamente', () {
      final json = {'count': 7};
      final dto = UnreadCountDto.fromJson(json);
      expect(dto.count, 7);
    });
  });

  group('NotificationRepositoryImpl Tests', () {
    late _MockHttpBaseClient mockClient;
    late RewitHttpClient httpClient;
    late NotificationRepositoryImpl repository;

    setUp(() {
      mockClient = _MockHttpBaseClient();
      httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.local',
        client: mockClient,
      );
      repository = NotificationRepositoryImpl(httpClient: httpClient);
    });

    test('getNotifications envia parâmetros de página e retorna NotificationsPage', () async {
      mockClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {
              'id': 'notif-1',
              'type': 'NEW_FOLLOWER',
              'actorId': 'user-1',
              'referenceId': 'user-1',
              'readAt': null,
              'createdAt': '2026-10-08T10:00:00Z',
            }
          ],
          'pageNumber': 0,
          'pageSize': 20,
          'totalElements': 1,
          'totalPages': 1,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );

      final result = await repository.getNotifications(page: 0, size: 20);

      expect(mockClient.capturedRequest?.method, 'GET');
      expect(mockClient.capturedRequest?.url.path, '/api/v1/me/notifications');
      expect(mockClient.capturedRequest?.url.queryParameters['page'], '0');
      expect(mockClient.capturedRequest?.url.queryParameters['size'], '20');
      expect(result.items.length, 1);
      expect(result.items.first.id, 'notif-1');
      expect(result.isLast, isTrue);
    });

    test('getUnreadCount consulta endpoint /unread-count e retorna quantidade', () async {
      mockClient.responseToReturn = http.Response(
        jsonEncode({'count': 5}),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );

      final count = await repository.getUnreadCount();

      expect(mockClient.capturedRequest?.method, 'GET');
      expect(mockClient.capturedRequest?.url.path, '/api/v1/me/notifications/unread-count');
      expect(count, 5);
    });

    test('markAsRead envia requisição PATCH para rota /{id}/read', () async {
      mockClient.responseToReturn = http.Response('', 204);

      await repository.markAsRead('notif-123');

      expect(mockClient.capturedRequest?.method, 'PATCH');
      expect(mockClient.capturedRequest?.url.path, '/api/v1/me/notifications/notif-123/read');
    });

    test('markAllAsRead envia requisição PATCH para rota /read-all', () async {
      mockClient.responseToReturn = http.Response('', 204);

      await repository.markAllAsRead();

      expect(mockClient.capturedRequest?.method, 'PATCH');
      expect(mockClient.capturedRequest?.url.path, '/api/v1/me/notifications/read-all');
    });
  });
}
