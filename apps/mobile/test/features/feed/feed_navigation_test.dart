import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';

class MockAuthRepo implements AuthRepository {
  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'usr-nav-1',
        email: 'user@test.com',
        handle: 'navuser',
        displayName: 'Navegador Teste',
        reputationScore: 50,
      );

  @override
  Future<Authenticated> login({required String email, required String password}) async =>
      throw UnimplementedError();

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<void> logout() async {}
}

class MockFeedRepo implements FeedRepository {
  final FeedReview sampleReview = FeedReview(
    id: 'rev-det-1',
    author: const FeedAuthor(
      id: 'author-1',
      handle: 'joaosilva',
      displayName: 'João Silva',
      isAnonymous: false,
    ),
    experienceText: 'Excelente hambúrguer artesanal!',
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    isAnonymous: false,
    isVerifiedOnSite: true,
    helpfulCount: 7,
    isHelpfulByMe: false,
    targets: [
      FeedTarget(
        id: 't-1',
        targetId: 'place-burgers',
        rating: 4.9,
        specificComment: 'Carne no ponto perfeito',
        createdAt: DateTime(2026, 10, 6),
      )
    ],
    createdAt: DateTime(2026, 10, 6, 18, 0),
  );

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async {
    return FeedPage(
      items: [sampleReview],
      page: 0,
      size: 10,
      windowSize: 1,
      totalPages: 1,
    );
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async {
    return sampleReview;
  }
}

void main() {
  group('Feed Navigation to Detail Tests', () {
    late MockAuthRepo authRepo;
    late MockFeedRepo feedRepo;
    late AuthNotifier authNotifier;
    late FeedNotifier feedNotifier;
    late AppRouter router;

    setUp(() async {
      authRepo = MockAuthRepo();
      feedRepo = MockFeedRepo();
      authNotifier = AuthNotifier(authRepository: authRepo);
      feedNotifier = FeedNotifier(feedRepository: feedRepo);
      await authNotifier.checkAuthStatus();

      router = AppRouter(
        authNotifier: authNotifier,
        feedNotifier: feedNotifier,
        feedRepository: feedRepo,
      );
    });

    testWidgets('exibe FeedView e navega para ReviewDetailScreen ao clicar no ReviewCard', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          initialRoute: AppRouter.root,
          onGenerateRoute: router.onGenerateRoute,
        ),
      );

      // Espera inicializar FeedView e carregar items
      await tester.pump();
      await tester.pumpAndSettle();

      // Confirma que está no HomeScreen com o review carregado
      expect(find.text('João Silva'), findsOneWidget);
      expect(find.text('Excelente hambúrguer artesanal!'), findsOneWidget);
      expect(find.byType(ReviewCard), findsOneWidget);

      // Clica no card de review
      await tester.tap(find.byType(ReviewCard));
      await tester.pumpAndSettle();

      // Confirma que navegou para a tela ReviewDetailScreen
      expect(find.byType(ReviewDetailScreen), findsOneWidget);
      expect(find.text('Alvos da Avaliação'), findsOneWidget);
      expect(find.text('Carne no ponto perfeito'), findsOneWidget);
      expect(find.text('7 pessoas acharam útil'), findsOneWidget);
      expect(find.text('Fotos e anexos de mídia serão exibidos aqui.'), findsOneWidget);
      expect(find.text('Discussões e comentários comunitários em preparação.'), findsOneWidget);
    });
  });
}
