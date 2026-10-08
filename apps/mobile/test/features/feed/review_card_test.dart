import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';

void main() {
  Widget buildSubject(
    FeedReview review, {
    VoidCallback? onTap,
    void Function(String)? onAuthorTap,
    void Function(String, String)? onTargetTap,
    VoidCallback? onHelpfulTap,
    bool isHelpfulLoading = false,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: ReviewCard(
          review: review,
          onTap: onTap,
          onAuthorTap: onAuthorTap,
          onTargetTap: onTargetTap,
          onHelpfulTap: onHelpfulTap,
          isHelpfulLoading: isHelpfulLoading,
        ),
      ),
    );
  }

  group('ReviewCard Timestamp Formatting', () {
    final now = DateTime(2026, 10, 8, 12, 0, 0);

    test('formata corretamente conforme regras relativas determinísticas', () {
      // < 60 segundos
      expect(ReviewCard.formatRelativeTime(now.subtract(const Duration(seconds: 45)), now: now), 'agora');
      // < 60 minutos
      expect(ReviewCard.formatRelativeTime(now.subtract(const Duration(minutes: 25)), now: now), 'há 25 min');
      // < 24 horas
      expect(ReviewCard.formatRelativeTime(now.subtract(const Duration(hours: 4)), now: now), 'há 4 h');
      // 1 dia
      expect(ReviewCard.formatRelativeTime(now.subtract(const Duration(days: 1)), now: now), 'ontem');
      // < 7 dias
      expect(ReviewCard.formatRelativeTime(now.subtract(const Duration(days: 4)), now: now), 'há 4 dias');
      // >= 7 dias (formato fixo dd/MM/yyyy)
      expect(ReviewCard.formatRelativeTime(DateTime(2026, 9, 20), now: now), '20/09/2026');
    });
  });

  group('ReviewCard Widget Tests', () {
    testWidgets('renderiza autor público com handle, displayName e rating consolidado', (tester) async {
      final review = FeedReview(
        id: 'rev-1',
        author: const FeedAuthor(
          id: 'user-1',
          handle: 'pedro',
          displayName: 'Pedro Santos',
          isAnonymous: false,
        ),
        experienceText: 'Ambiente aconchegante e comida excelente.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: false,
        isVerifiedOnSite: false,
        helpfulCount: 4,
        isHelpfulByMe: true,
        targets: [
          FeedTarget(
            id: 't1',
            targetId: 'place-1',
            rating: 5.0,
            specificComment: 'Atendimento nota 10',
            createdAt: DateTime(2026, 10, 6),
          ),
          FeedTarget(
            id: 't2',
            targetId: 'prod-2',
            rating: 4.0,
            createdAt: DateTime(2026, 10, 6),
          ),
        ],
        createdAt: DateTime(2026, 10, 6, 14, 30),
      );

      await tester.pumpWidget(buildSubject(review));

      expect(find.text('Pedro Santos'), findsOneWidget);
      expect(find.text('@pedro'), findsOneWidget);
      expect(find.text('Ambiente aconchegante e comida excelente.'), findsOneWidget);
      expect(find.text('4.5'), findsOneWidget); // média das notas
      expect(find.text('4 úteis'), findsOneWidget);
      expect(find.byIcon(Icons.thumb_up), findsOneWidget); // isHelpfulByMe true
      expect(find.text('Anônimo'), findsNothing);
      expect(find.text('Presença confirmada no local'), findsNothing);
    });

    testWidgets('renderiza autor anônimo e oculta handle', (tester) async {
      final review = FeedReview(
        id: 'rev-2',
        author: const FeedAuthor(
          displayName: 'Anônimo',
          isAnonymous: true,
        ),
        experienceText: 'Avaliação sem identificação.',
        visibility: 'FOLLOWERS',
        status: 'ACTIVE',
        isAnonymous: true,
        isVerifiedOnSite: true, // com check-in
        helpfulCount: 1,
        isHelpfulByMe: false,
        targets: [],
        createdAt: DateTime(2026, 10, 6),
      );

      await tester.pumpWidget(buildSubject(review));

      expect(find.text('Anônimo'), findsAtLeastNWidgets(1));
      expect(find.text('Presença confirmada no local'), findsOneWidget);
      expect(find.text('1 útil'), findsOneWidget);
      expect(find.byIcon(Icons.thumb_up_outlined), findsOneWidget);
      expect(find.text('Seguidores'), findsOneWidget);
    });

    testWidgets('chama onTap ao clicar no card', (tester) async {
      bool tapped = false;
      final review = FeedReview(
        id: 'rev-click',
        author: const FeedAuthor(displayName: 'Tester'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(buildSubject(review, onTap: () {
        tapped = true;
      }));

      await tester.tap(find.byType(ReviewCard));
      await tester.pumpAndSettle();

      expect(tapped, isTrue);
    });

    testWidgets('renderiza chips de target diferenciados (PLACE, PRODUCT, SERVICE) e navega ao clicar', (tester) async {
      String? clickedTargetId;
      String? clickedTargetType;

      final review = FeedReview(
        id: 'rev-targets',
        author: const FeedAuthor(id: 'u-1', displayName: 'Avaliador'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        targets: const [
          FeedTarget(
            id: 't-1',
            targetId: 'place-bistro',
            targetType: 'PLACE',
            rating: 4.8,
          ),
          FeedTarget(
            id: 't-2',
            targetId: 'prod-cafe',
            targetType: 'PRODUCT',
            rating: 4.5,
          ),
          FeedTarget(
            id: 't-3',
            targetId: 'serv-entrega',
            targetType: 'SERVICE',
            rating: 4.0,
          ),
        ],
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        buildSubject(
          review,
          onTargetTap: (id, type) {
            clickedTargetId = id;
            clickedTargetType = type;
          },
        ),
      );

      expect(find.text('Local • 4.8'), findsOneWidget);
      expect(find.text('Produto • 4.5'), findsOneWidget);
      expect(find.text('Serviço • 4.0'), findsOneWidget);

      // Clicar no Local dispara navegação com PLACE
      await tester.tap(find.text('Local • 4.8'));
      await tester.pumpAndSettle();
      expect(clickedTargetId, 'place-bistro');
      expect(clickedTargetType, 'PLACE');

      // Clicar no Produto dispara navegação com PRODUCT
      await tester.tap(find.text('Produto • 4.5'));
      await tester.pumpAndSettle();
      expect(clickedTargetId, 'prod-cafe');
      expect(clickedTargetType, 'PRODUCT');

      // Serviço não possui ação de navegação
      clickedTargetId = null;
      clickedTargetType = null;
      await tester.tap(find.text('Serviço • 4.0'));
      await tester.pumpAndSettle();
      expect(clickedTargetId, isNull);
      expect(clickedTargetType, isNull);
    });

    testWidgets('infere target como PLACE quando targetId coincide com contextPlaceId', (tester) async {
      String? clickedTargetId;
      String? clickedTargetType;

      final review = FeedReview(
        id: 'rev-ctx',
        contextPlaceId: 'bistro-context-place',
        author: const FeedAuthor(id: 'u-1', displayName: 'Avaliador'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        targets: const [
          FeedTarget(
            id: 't-ctx',
            targetId: 'bistro-context-place',
            targetType: null, // sem tipo explícito no DTO
            rating: 5.0,
          ),
        ],
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        buildSubject(
          review,
          onTargetTap: (id, type) {
            clickedTargetId = id;
            clickedTargetType = type;
          },
        ),
      );

      // Deve resolver como Local devido a contextPlaceId
      expect(find.text('Local • 5.0'), findsOneWidget);

      await tester.tap(find.text('Local • 5.0'));
      await tester.pumpAndSettle();
      expect(clickedTargetId, 'bistro-context-place');
      expect(clickedTargetType, 'PLACE');
    });

    testWidgets('botão útil interativo dispara onHelpfulTap sem propagar para onTap do card', (tester) async {
      bool cardTapped = false;
      bool helpfulTapped = false;

      final review = FeedReview(
        id: 'rev-helpful-btn',
        author: const FeedAuthor(id: 'u-1', displayName: 'Avaliador'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        helpfulCount: 3,
        isHelpfulByMe: false,
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        buildSubject(
          review,
          onTap: () => cardTapped = true,
          onHelpfulTap: () => helpfulTapped = true,
        ),
      );

      await tester.tap(find.text('3 úteis'));
      await tester.pumpAndSettle();

      expect(helpfulTapped, isTrue);
      expect(cardTapped, isFalse); // não propagou para o card
    });

    testWidgets('botão útil exibe indicador de progresso e bloqueia clique quando isHelpfulLoading for true', (tester) async {
      bool helpfulTapped = false;

      final review = FeedReview(
        id: 'rev-loading-helpful',
        author: const FeedAuthor(id: 'u-1', displayName: 'Avaliador'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        helpfulCount: 2,
        isHelpfulByMe: false,
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        buildSubject(
          review,
          isHelpfulLoading: true,
          onHelpfulTap: () => helpfulTapped = true,
        ),
      );

      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      // Clicar no botão em carregamento não dispara callback
      await tester.tap(find.byType(CircularProgressIndicator));
      await tester.pump();

      expect(helpfulTapped, isFalse);
    });

    testWidgets('navegação de autor respeita políticas de usuário ativo, anônimo e excluído', (tester) async {
      String? navigatedAuthorId;

      // 1. Autor ativo
      final activeReview = FeedReview(
        id: 'rev-act',
        author: const FeedAuthor(id: 'usr-active-1', displayName: 'Carlos'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        buildSubject(activeReview, onAuthorTap: (id) => navigatedAuthorId = id),
      );

      await tester.tap(find.text('Carlos'));
      await tester.pumpAndSettle();
      expect(navigatedAuthorId, 'usr-active-1');

      // 2. Autor anônimo
      navigatedAuthorId = null;
      final anonReview = FeedReview(
        id: 'rev-anon',
        isAnonymous: true,
        author: const FeedAuthor(displayName: 'Anônimo', isAnonymous: true),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        buildSubject(anonReview, onAuthorTap: (id) => navigatedAuthorId = id),
      );

      await tester.tap(find.text('Anônimo').first);
      await tester.pumpAndSettle();
      expect(navigatedAuthorId, isNull);

      // 3. Usuário excluído
      navigatedAuthorId = null;
      final deletedReview = FeedReview(
        id: 'rev-del',
        author: const FeedAuthor(id: 'usr-del', displayName: 'Usuário excluído'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        buildSubject(deletedReview, onAuthorTap: (id) => navigatedAuthorId = id),
      );

      await tester.tap(find.text('Usuário excluído'));
      await tester.pumpAndSettle();
      expect(navigatedAuthorId, isNull);
    });
  });
}
