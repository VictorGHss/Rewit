import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/in_app_notification.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/notifications_page.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/notifications/presentation/screens/notifications_screen.dart';
import 'package:rewit_mobile/features/notifications/presentation/state/notifications_notifier.dart';

class _FakeNotificationRepository implements NotificationRepository {
  List<InAppNotification> notificationsToReturn = [];
  int unreadCountToReturn = 0;
  bool isLastToReturn = true;
  int totalElementsToReturn = 0;
  int totalPagesToReturn = 1;

  bool shouldThrow = false;
  Object? markReadError;
  Object? markAllError;
  final List<String> markedReadIds = [];
  bool markAllCalled = false;

  @override
  Future<NotificationsPage> getNotifications({int page = 0, int size = 20}) async {
    if (shouldThrow) throw Exception('Erro na API');
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
    if (shouldThrow) throw Exception('Erro na API');
    return unreadCountToReturn;
  }

  @override
  Future<void> markAsRead(String notificationId) async {
    if (markReadError != null) throw markReadError!;
    markedReadIds.add(notificationId);
  }

  @override
  Future<void> markAllAsRead() async {
    if (markAllError != null) throw markAllError!;
    markAllCalled = true;
  }
}

void main() {
  group('NotificationsScreen Widget Tests', () {
    late _FakeNotificationRepository fakeRepo;
    late NotificationsNotifier notifier;

    final notifFollower = InAppNotification(
      id: 'notif-1',
      type: NotificationType.newFollower,
      actorId: 'user-42',
      referenceId: 'user-42',
      readAt: null,
      createdAt: DateTime.now().subtract(const Duration(minutes: 10)),
    );

    final notifHelpful = InAppNotification(
      id: 'notif-2',
      type: NotificationType.reviewHelpful,
      referenceId: 'review-100',
      readAt: null,
      createdAt: DateTime.now().subtract(const Duration(hours: 1)),
    );

    final notifDiscussion = InAppNotification(
      id: 'notif-3',
      type: NotificationType.newDiscussion,
      actorId: 'user-99',
      referenceId: 'review-100',
      readAt: DateTime.now().subtract(const Duration(days: 1)),
      createdAt: DateTime.now().subtract(const Duration(days: 1)),
    );

    final notifReply = InAppNotification(
      id: 'notif-4',
      type: NotificationType.discussionReply,
      actorId: 'user-101',
      referenceId: 'reply-50',
      readAt: null,
      createdAt: DateTime.now().subtract(const Duration(minutes: 2)),
    );

    setUp(() {
      fakeRepo = _FakeNotificationRepository();
      notifier = NotificationsNotifier(repository: fakeRepo);
    });

    Widget createTestWidget({RouteFactory? onGenerateRoute}) {
      return MaterialApp(
        onGenerateRoute: onGenerateRoute ??
            (settings) {
              if (settings.name == AppRouter.profile) {
                return MaterialPageRoute(
                  builder: (context) => Scaffold(
                    body: Text('Perfil: ${settings.arguments}'),
                  ),
                );
              }
              if (settings.name == AppRouter.reviewDetail) {
                return MaterialPageRoute(
                  builder: (context) => Scaffold(
                    body: Text('Review: ${settings.arguments}'),
                  ),
                );
              }
              return null;
            },
        home: NotificationsScreen(notifier: notifier),
      );
    }

    testWidgets('exibe indicador de carregamento durante busca inicial', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower];
      fakeRepo.unreadCountToReturn = 1;

      await tester.pumpWidget(createTestWidget());

      expect(find.text('Carregando notificações...'), findsOneWidget);

      await tester.pumpAndSettle();

      expect(find.text('Carregando notificações...'), findsNothing);
      expect(find.text('Novo seguidor'), findsOneWidget);
    });

    testWidgets('exibe estado vazio amigável quando não há notificações', (tester) async {
      fakeRepo.notificationsToReturn = [];
      fakeRepo.unreadCountToReturn = 0;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      expect(find.text('Nenhuma notificação por enquanto'), findsOneWidget);
      expect(find.byIcon(Icons.notifications_none_outlined), findsOneWidget);
    });

    testWidgets('exibe tela de erro com botão tentar novamente', (tester) async {
      fakeRepo.shouldThrow = true;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      expect(find.text('Não foi possível carregar as notificações'), findsOneWidget);
      expect(find.text('Tentar novamente'), findsOneWidget);

      // Clicar em tentar novamente após erro corrigido
      fakeRepo.shouldThrow = false;
      fakeRepo.notificationsToReturn = [notifFollower];
      fakeRepo.unreadCountToReturn = 1;

      await tester.tap(find.text('Tentar novamente'));
      await tester.pumpAndSettle();

      expect(find.text('Novo seguidor'), findsOneWidget);
    });

    testWidgets('renderiza os 4 tipos de notificações com títulos e ícones corretos', (tester) async {
      fakeRepo.notificationsToReturn = [
        notifFollower,
        notifHelpful,
        notifDiscussion,
        notifReply,
      ];
      fakeRepo.unreadCountToReturn = 3;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      expect(find.text('Novo seguidor'), findsOneWidget);
      expect(find.text('Avaliação útil'), findsOneWidget);
      expect(find.text('Nova discussão'), findsOneWidget);
      expect(find.text('Resposta em discussão'), findsOneWidget);

      expect(find.byIcon(Icons.person_add_rounded), findsOneWidget);
      expect(find.byIcon(Icons.thumb_up_alt_rounded), findsOneWidget);
      expect(find.byIcon(Icons.forum_rounded), findsOneWidget);
      expect(find.byIcon(Icons.reply_rounded), findsOneWidget);

      // Botão marcar lidas visível quando há não lidas
      expect(find.text('Marcar lidas'), findsOneWidget);
    });

    testWidgets('clicar em Marcar lidas dispara markAllAsRead', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower, notifHelpful];
      fakeRepo.unreadCountToReturn = 2;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      expect(find.text('Marcar lidas'), findsOneWidget);
      await tester.tap(find.text('Marcar lidas'));
      await tester.pumpAndSettle();

      expect(fakeRepo.markAllCalled, isTrue);
      // Após marcar todas, o botão não deve mais ser exibido
      expect(find.text('Marcar lidas'), findsNothing);
    });

    testWidgets('clicar em notificação de seguidor navega para Perfil', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower];
      fakeRepo.unreadCountToReturn = 1;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Novo seguidor'));
      await tester.pumpAndSettle();

      expect(find.text('Perfil: user-42'), findsOneWidget);
      expect(fakeRepo.markedReadIds, contains('notif-1'));
    });

    testWidgets('clicar em notificação útil navega para Review Detail', (tester) async {
      fakeRepo.notificationsToReturn = [notifHelpful];
      fakeRepo.unreadCountToReturn = 1;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Avaliação útil'));
      await tester.pumpAndSettle();

      expect(find.text('Review: review-100'), findsOneWidget);
      expect(fakeRepo.markedReadIds, contains('notif-2'));
    });

    testWidgets('clicar em notificação de discussão navega para Review Detail', (tester) async {
      fakeRepo.notificationsToReturn = [notifDiscussion];
      fakeRepo.unreadCountToReturn = 0;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Nova discussão'));
      await tester.pumpAndSettle();

      expect(find.text('Review: review-100'), findsOneWidget);
    });

    testWidgets('clicar em notificação de resposta marca como lida e exibe aviso informativo sem quebrar', (tester) async {
      fakeRepo.notificationsToReturn = [notifReply];
      fakeRepo.unreadCountToReturn = 1;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Resposta em discussão'));
      await tester.pump();

      expect(find.text('Notificação de resposta em discussão marcada como lida.'), findsOneWidget);
      expect(fakeRepo.markedReadIds, contains('notif-4'));
    });

    testWidgets('markAsRead com sucesso ao tocar decrementa unreadCount e navega', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower, notifHelpful];
      fakeRepo.unreadCountToReturn = 2;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Novo seguidor'));
      await tester.pumpAndSettle();

      expect(find.text('Perfil: user-42'), findsOneWidget);
      expect(notifier.unreadCount, 1);
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('falha no markAsRead reverte, exibe erro da API e permanece na lista', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower, notifHelpful];
      fakeRepo.unreadCountToReturn = 2;
      fakeRepo.markReadError = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Erro interno',
          status: 500,
          detail: 'Serviço de notificações indisponível.',
        ),
      );

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Novo seguidor'));
      await tester.pumpAndSettle();

      expect(find.text('Serviço de notificações indisponível.'), findsOneWidget);
      expect(find.text('Perfil: user-42'), findsNothing);
      expect(find.text('Novo seguidor'), findsOneWidget);
      expect(notifier.unreadCount, 2);
      expect(fakeRepo.markedReadIds, isEmpty);
    });

    testWidgets('falha no markAsRead de resposta não exibe aviso de sucesso', (tester) async {
      fakeRepo.notificationsToReturn = [notifReply];
      fakeRepo.unreadCountToReturn = 1;
      fakeRepo.markReadError = const NetworkException('Sem conexão com a internet');

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Resposta em discussão'));
      await tester.pumpAndSettle();

      expect(find.text('Sem conexão com a internet'), findsOneWidget);
      expect(find.text('Notificação de resposta em discussão marcada como lida.'), findsNothing);
      expect(notifier.unreadCount, 1);
    });

    testWidgets('markAllAsRead com sucesso zera unreadCount sem exibir erro', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower, notifHelpful];
      fakeRepo.unreadCountToReturn = 2;

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Marcar lidas'));
      await tester.pumpAndSettle();

      expect(notifier.unreadCount, 0);
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('falha no markAllAsRead reverte, exibe erro de rede e mantém botão', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower, notifHelpful];
      fakeRepo.unreadCountToReturn = 2;
      fakeRepo.markAllError = const NetworkException('Sem conexão com a internet');

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Marcar lidas'));
      await tester.pumpAndSettle();

      expect(find.text('Sem conexão com a internet'), findsOneWidget);
      expect(find.text('Marcar lidas'), findsOneWidget);
      expect(find.text('Novo seguidor'), findsOneWidget);
      expect(notifier.unreadCount, 2);
      expect(fakeRepo.markAllCalled, isFalse);
    });

    testWidgets('falha inesperada no markAllAsRead exibe mensagem genérica', (tester) async {
      fakeRepo.notificationsToReturn = [notifFollower];
      fakeRepo.unreadCountToReturn = 1;
      fakeRepo.markAllError = Exception('falha desconhecida');

      await tester.pumpWidget(createTestWidget());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Marcar lidas'));
      await tester.pumpAndSettle();

      expect(find.text('Não foi possível marcar todas as notificações como lidas.'), findsOneWidget);
      expect(notifier.unreadCount, 1);
    });
  });
}
