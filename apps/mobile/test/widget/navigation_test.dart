import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';

import 'package:rewit_mobile/features/notifications/domain/entities/notifications_page.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';

class StubNotificationRepo implements NotificationRepository {
  @override
  Future<NotificationsPage> getNotifications({int page = 0, int size = 20}) async =>
      const NotificationsPage(
        items: [],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      );

  @override
  Future<int> getUnreadCount() async => 3;

  @override
  Future<void> markAsRead(String notificationId) async {}

  @override
  Future<void> markAllAsRead() async {}
}

class StubAuthRepo implements AuthRepository {
  bool logoutCalled = false;

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'user-1',
        email: 'ana@rewit.com',
        handle: 'anapaula',
        displayName: 'Ana Paula',
        isVerified: true,
        reputationScore: 120,
      );

  @override
  Future<Authenticated> login({required String email, required String password}) async =>
      throw UnimplementedError();

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<void> logout() async {
    logoutCalled = true;
  }

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async =>
      throw UnimplementedError();

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {}
}

void main() {
  Widget buildSubject(AuthNotifier notifier) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: HomeScreen(authNotifier: notifier),
    );
  }

  group('HomeScreen & Navigation Widget Tests', () {
    late StubAuthRepo repo;
    late AuthNotifier notifier;

    setUp(() async {
      repo = StubAuthRepo();
      notifier = AuthNotifier(authRepository: repo);
      // Simula estado autenticado
      await notifier.checkAuthStatus();
    });

    testWidgets('exibe informações do usuário autenticado no Feed inicial', (tester) async {
      await tester.pumpWidget(buildSubject(notifier));

      expect(find.text('Ana Paula'), findsOneWidget);
      expect(find.text('@anapaula'), findsOneWidget);
      expect(find.text('Reputação: 120'), findsOneWidget);
      expect(find.byIcon(Icons.verified), findsOneWidget);
      expect(find.text('Feed de Atividades'), findsOneWidget);
    });

    testWidgets('permite alternar abas de navegação para os placeholders', (tester) async {
      await tester.pumpWidget(buildSubject(notifier));

      // Aba Buscar
      await tester.tap(find.text('Buscar'));
      await tester.pumpAndSettle();
      expect(find.text('Busca de Lugares'), findsOneWidget);

      // Aba Avaliar
      await tester.tap(find.text('Avaliar'));
      await tester.pumpAndSettle();
      expect(find.text('Criar Avaliação'), findsOneWidget);

      // Aba Perfil
      await tester.tap(find.text('Perfil'));
      await tester.pumpAndSettle();
      expect(find.text('Perfil de Usuário'), findsOneWidget);

      // Volta para Início
      await tester.tap(find.text('Início'));
      await tester.pumpAndSettle();
      expect(find.text('Feed de Atividades'), findsOneWidget);
    });

    testWidgets('diálogo de confirmação de logout chama notifier.logout ao confirmar', (tester) async {
      await tester.pumpWidget(buildSubject(notifier));

      // Clica no ícone de logout no AppBar
      await tester.tap(find.byIcon(Icons.logout));
      await tester.pumpAndSettle();

      expect(find.text('Encerrar Sessão'), findsOneWidget);
      expect(find.text('Deseja realmente sair da sua conta?'), findsOneWidget);

      // Confirma logout
      await tester.tap(find.widgetWithText(ElevatedButton, 'Sair'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(repo.logoutCalled, isTrue);
      expect(notifier.isAuthenticated, isFalse);
    });

    testWidgets('AppRouter renderiza LoginScreen para usuário desautenticado e HomeScreen para autenticado', (tester) async {
      final unauthNotifier = AuthNotifier(authRepository: repo);
      // Estado unauthenticated
      await unauthNotifier.checkAuthStatus();
      await unauthNotifier.logout();

      final router = AppRouter(authNotifier: unauthNotifier);

      await tester.pumpWidget(MaterialApp(
        key: const ValueKey('unauth-app'),
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      // Verifica tela de login
      expect(find.text('Rewit'), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, 'Entrar'), findsOneWidget);

      // Agora com usuário autenticado
      final authNotifier = AuthNotifier(authRepository: repo);
      await authNotifier.checkAuthStatus();
      final authRouter = AppRouter(authNotifier: authNotifier);

      await tester.pumpWidget(MaterialApp(
        key: const ValueKey('auth-app'),
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: authRouter.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      // Verifica tela Home autenticada
      expect(find.text('Ana Paula'), findsOneWidget);
      expect(find.text('@anapaula'), findsOneWidget);
    });

    testWidgets('exibe ícone de notificações com badge de não lidas no AppBar da HomeScreen e navega para Notificações', (tester) async {
      final notifRepo = StubNotificationRepo();
      final authNotifier = AuthNotifier(authRepository: repo);
      await authNotifier.checkAuthStatus();
      final router = AppRouter(
        authNotifier: authNotifier,
        notificationRepository: notifRepo,
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.notifications_outlined), findsOneWidget);
      expect(find.text('3'), findsOneWidget); // Badge label

      // Clica no ícone de notificações
      await tester.tap(find.byIcon(Icons.notifications_outlined));
      await tester.pumpAndSettle();

      // Confirma que abriu a tela de notificações
      expect(find.text('Notificações'), findsOneWidget);
      expect(find.text('Nenhuma notificação por enquanto'), findsOneWidget);
    });
  });
}
