import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/in_app_notification.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/notifications_page.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/notifications/presentation/state/notifications_notifier.dart';
import 'package:rewit_mobile/features/notifications/presentation/state/notifications_state.dart';

class _FakeNotificationRepository implements NotificationRepository {
  List<InAppNotification> notificationsToReturn = [];
  int unreadCountToReturn = 0;
  bool isLastToReturn = true;
  int totalElementsToReturn = 0;
  int totalPagesToReturn = 1;
  Duration delay = Duration.zero;

  bool shouldThrowOnGet = false;
  bool shouldThrowOnMarkRead = false;
  bool shouldThrowOnMarkAll = false;

  final List<String> markedReadIds = [];
  bool markAllCalled = false;

  @override
  Future<NotificationsPage> getNotifications({int page = 0, int size = 20}) async {
    if (delay > Duration.zero) await Future.delayed(delay);
    if (shouldThrowOnGet) throw Exception('Falha de conexão');
    return NotificationsPage(
      items: notificationsToReturn,
      pageNumber: page,
      pageSize: size,
      totalElements: totalElementsToReturn,
      totalPages: totalPagesToReturn,
      isLast: isLastToReturn,
    );
  }

  @override
  Future<int> getUnreadCount() async {
    if (delay > Duration.zero) await Future.delayed(delay);
    if (shouldThrowOnGet) throw Exception('Falha ao obter contagem');
    return unreadCountToReturn;
  }

  @override
  Future<void> markAsRead(String notificationId) async {
    if (delay > Duration.zero) await Future.delayed(delay);
    if (shouldThrowOnMarkRead) throw Exception('Falha ao marcar lida');
    markedReadIds.add(notificationId);
  }

  @override
  Future<void> markAllAsRead() async {
    if (delay > Duration.zero) await Future.delayed(delay);
    if (shouldThrowOnMarkAll) throw Exception('Falha ao marcar todas como lidas');
    markAllCalled = true;
  }
}

void main() {
  group('NotificationsNotifier Tests', () {
    late _FakeNotificationRepository fakeRepo;
    late NotificationsNotifier notifier;

    final sampleItem1 = InAppNotification(
      id: 'n-1',
      type: NotificationType.newFollower,
      actorId: 'user-1',
      referenceId: 'user-1',
      readAt: null,
      createdAt: DateTime.now().subtract(const Duration(minutes: 5)),
    );

    final sampleItem2 = InAppNotification(
      id: 'n-2',
      type: NotificationType.reviewHelpful,
      referenceId: 'review-1',
      readAt: null,
      createdAt: DateTime.now().subtract(const Duration(hours: 1)),
    );

    setUp(() {
      fakeRepo = _FakeNotificationRepository();
      notifier = NotificationsNotifier(repository: fakeRepo);
    });

    test('estado inicial é NotificationsInitial e unreadCount é 0', () {
      expect(notifier.state, isA<NotificationsInitial>());
      expect(notifier.unreadCount, 0);
    });

    test('fetchUnreadCount atualiza _unreadCount', () async {
      fakeRepo.unreadCountToReturn = 3;
      await notifier.fetchUnreadCount();
      expect(notifier.unreadCount, 3);
    });

    test('loadNotifications carrega itens e unreadCount com sucesso', () async {
      fakeRepo.notificationsToReturn = [sampleItem1, sampleItem2];
      fakeRepo.unreadCountToReturn = 2;
      fakeRepo.totalElementsToReturn = 2;

      await notifier.loadNotifications();

      expect(notifier.state, isA<NotificationsLoaded>());
      final loaded = notifier.state as NotificationsLoaded;
      expect(loaded.notifications.length, 2);
      expect(loaded.unreadCount, 2);
      expect(loaded.hasUnread, isTrue);
      expect(notifier.unreadCount, 2);
    });

    test('loadNotifications define NotificationsEmpty quando lista vazia', () async {
      fakeRepo.notificationsToReturn = [];
      fakeRepo.unreadCountToReturn = 0;

      await notifier.loadNotifications();

      expect(notifier.state, isA<NotificationsEmpty>());
      expect(notifier.unreadCount, 0);
    });

    test('loadNotifications define NotificationsError em caso de falha', () async {
      fakeRepo.shouldThrowOnGet = true;

      await notifier.loadNotifications();

      expect(notifier.state, isA<NotificationsError>());
    });

    test('sequence guard: respostas obsoletas são ignoradas', () async {
      // Usando implementação mockada ad-hoc para testar concorrência
      final slowRepo = _FakeNotificationRepository();
      final slowNotifier = NotificationsNotifier(repository: slowRepo);

      // Dispara primeira busca lenta
      slowRepo.delay = const Duration(milliseconds: 50);
      final future1 = slowNotifier.loadNotifications();

      // Dispara segunda busca rápida imediatamente
      slowRepo.delay = Duration.zero;
      slowRepo.notificationsToReturn = [sampleItem2];
      slowRepo.unreadCountToReturn = 0;
      final future2 = slowNotifier.loadNotifications();

      await Future.wait([future1, future2]);

      // O estado final deve refletir a segunda chamada (sampleItem2)
      expect(slowNotifier.state, isA<NotificationsLoaded>());
      final loaded = slowNotifier.state as NotificationsLoaded;
      expect(loaded.notifications.first.id, sampleItem2.id);
    });

    test('loadMore anexa páginas e deduplica itens duplicados por ID', () async {
      fakeRepo.notificationsToReturn = [sampleItem1];
      fakeRepo.unreadCountToReturn = 1;
      fakeRepo.isLastToReturn = false;
      fakeRepo.totalElementsToReturn = 3;

      await notifier.loadNotifications();

      // Próxima página contém sampleItem1 (duplicado que pode ter vindo) e sampleItem2
      fakeRepo.notificationsToReturn = [sampleItem1, sampleItem2];
      fakeRepo.isLastToReturn = true;

      await notifier.loadMore();

      final loaded = notifier.state as NotificationsLoaded;
      expect(loaded.notifications.length, 2); // Não 3!
      expect(loaded.notifications.map((n) => n.id).toList(), ['n-1', 'n-2']);
      expect(loaded.isLastPage, isTrue);
    });

    test('markAsRead realiza atualização otimista imediata e decrementa unreadCount', () async {
      fakeRepo.notificationsToReturn = [sampleItem1, sampleItem2];
      fakeRepo.unreadCountToReturn = 2;

      await notifier.loadNotifications();
      expect(notifier.unreadCount, 2);

      await notifier.markAsRead('n-1');

      final loaded = notifier.state as NotificationsLoaded;
      expect(loaded.notifications.first.isRead, isTrue);
      expect(loaded.unreadCount, 1);
      expect(notifier.unreadCount, 1);
      expect(fakeRepo.markedReadIds, contains('n-1'));
    });

    test('markAsRead reverte estado otimista se repositório lançar exceção', () async {
      fakeRepo.notificationsToReturn = [sampleItem1];
      fakeRepo.unreadCountToReturn = 1;
      fakeRepo.shouldThrowOnMarkRead = true;

      await notifier.loadNotifications();
      expect(notifier.unreadCount, 1);

      await expectLater(
        notifier.markAsRead('n-1'),
        throwsA(isA<Exception>()),
      );

      final loaded = notifier.state as NotificationsLoaded;
      expect(loaded.notifications.first.isRead, isFalse);
      expect(loaded.unreadCount, 1);
      expect(notifier.unreadCount, 1);
    });

    test('markAllAsRead marca todos os itens e zera unreadCount otimisticamente', () async {
      fakeRepo.notificationsToReturn = [sampleItem1, sampleItem2];
      fakeRepo.unreadCountToReturn = 2;

      await notifier.loadNotifications();

      await notifier.markAllAsRead();

      final loaded = notifier.state as NotificationsLoaded;
      expect(loaded.notifications.every((n) => n.isRead), isTrue);
      expect(loaded.unreadCount, 0);
      expect(notifier.unreadCount, 0);
      expect(fakeRepo.markAllCalled, isTrue);
    });

    test('markAllAsRead reverte alterações se repositório lançar erro', () async {
      fakeRepo.notificationsToReturn = [sampleItem1, sampleItem2];
      fakeRepo.unreadCountToReturn = 2;
      fakeRepo.shouldThrowOnMarkAll = true;

      await notifier.loadNotifications();

      await expectLater(
        notifier.markAllAsRead(),
        throwsA(isA<Exception>()),
      );

      final loaded = notifier.state as NotificationsLoaded;
      expect(loaded.notifications.every((n) => !n.isRead), isTrue);
      expect(loaded.unreadCount, 2);
      expect(notifier.unreadCount, 2);
    });

    test('markAllAsRead com falha após markAsRead restaura apenas a contagem anterior', () async {
      fakeRepo.notificationsToReturn = [sampleItem1, sampleItem2];
      fakeRepo.unreadCountToReturn = 2;

      await notifier.loadNotifications();
      await notifier.markAsRead('n-1');
      expect(notifier.unreadCount, 1);

      fakeRepo.shouldThrowOnMarkAll = true;
      await expectLater(
        notifier.markAllAsRead(),
        throwsA(isA<Exception>()),
      );

      final loaded = notifier.state as NotificationsLoaded;
      expect(loaded.notifications.firstWhere((n) => n.id == 'n-1').isRead, isTrue);
      expect(loaded.notifications.firstWhere((n) => n.id == 'n-2').isRead, isFalse);
      expect(loaded.unreadCount, 1);
      expect(notifier.unreadCount, 1);
      expect(loaded.isMarkingAll, isFalse);
    });
  });
}
