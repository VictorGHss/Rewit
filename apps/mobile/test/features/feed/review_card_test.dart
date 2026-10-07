import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';

void main() {
  Widget buildSubject(FeedReview review, {VoidCallback? onTap}) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: ReviewCard(
          review: review,
          onTap: onTap,
        ),
      ),
    );
  }

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
  });
}
