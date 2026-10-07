import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_screen.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_notifier.dart';

class MockReviewCreationRepository implements ReviewCreationRepository {
  CreateReviewInput? capturedInput;
  FeedReview? mockResult;
  Exception? exceptionToThrow;

  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    capturedInput = input;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    return mockResult ??
        FeedReview(
          id: 'created-review-uuid-1',
          author: const FeedAuthor(displayName: 'Autor Teste'),
          visibility: input.visibility,
          status: 'VISIBLE',
          createdAt: DateTime.now(),
          targets: input.targets
              .map((t) => FeedTarget(
                    id: 'target-item-id',
                    targetId: t.rateableTargetId,
                    rating: t.rating,
                    specificComment: t.specificComment,
                  ))
              .toList(),
        );
  }
}

void main() {
  const validTargetId = '11111111-1111-1111-1111-111111111111';

  Widget buildSubject({
    required ReviewCreationRepository repository,
    ReviewCreateNotifier? notifier,
    ValueChanged<FeedReview>? onReviewCreated,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: ReviewCreateScreen(
        repository: repository,
        notifier: notifier,
        onReviewCreated: onReviewCreated,
      ),
    );
  }

  group('ReviewCreateScreen Widget Tests', () {
    late MockReviewCreationRepository repository;

    setUp(() {
      repository = MockReviewCreationRepository();
    });

    testWidgets('renderiza todos os componentes do formulário de criação', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      await tester.pumpWidget(buildSubject(repository: repository));

      // Título do AppBar
      expect(find.text('Criar Avaliação'), findsOneWidget);

      // Seção de alvos
      expect(find.text('Alvos da Avaliação *'), findsOneWidget);
      expect(find.text('Alvo Principal'), findsOneWidget);
      expect(find.text('Nota de Avaliação *'), findsOneWidget);
      expect(find.text('Adicionar Outro Alvo / Item'), findsOneWidget);

      // Seções opcionais
      expect(find.text('Local de Contexto (Opcional)'), findsOneWidget);
      expect(find.text('Relato da Experiência (Opcional)'), findsOneWidget);
      expect(find.text('Visibilidade e Privacidade'), findsOneWidget);
      expect(find.text('Publicar como Anônimo'), findsOneWidget);
      expect(find.text('Presença e Check-in no Local (Opcional)'), findsOneWidget);

      // Ponto de extensão para mídia
      expect(find.text('Fotos e Evidências'), findsOneWidget);
      expect(find.text('Pós-publicação'), findsOneWidget);

      // Botão de submissão
      expect(find.text('Publicar Avaliação'), findsOneWidget);
    });

    testWidgets('permite adicionar e remover múltiplos alvos interativamente', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      await tester.pumpWidget(buildSubject(repository: repository));

      // Inicialmente tem 1 alvo
      expect(find.text('Alvo Principal'), findsOneWidget);
      expect(find.text('Alvo Adicional'), findsNothing);

      // Clica para adicionar outro alvo
      await tester.tap(find.text('Adicionar Outro Alvo / Item'));
      await tester.pumpAndSettle();

      expect(find.text('Alvo Principal'), findsOneWidget);
      expect(find.text('Alvo Adicional'), findsOneWidget);
      expect(find.text('2 alvo(s)'), findsOneWidget);

      // Remove o segundo alvo
      await tester.tap(find.byTooltip('Remover este alvo').last);
      await tester.pumpAndSettle();

      expect(find.text('Alvo Adicional'), findsNothing);
      expect(find.text('1 alvo(s)'), findsOneWidget);
    });

    testWidgets('exibe banner de erro de validação ao submeter sem targetId', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      await tester.pumpWidget(buildSubject(repository: repository));

      // Clica no botão de publicação
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(find.text('Falha na Validação'), findsOneWidget);
      expect(find.text('VALIDATION_ERROR'), findsOneWidget);
      expect(find.text('Informe o identificador do alvo 1.'), findsOneWidget);
    });

    testWidgets('submissão com sucesso dispara callback onReviewCreated e SnackBar', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      FeedReview? createdResult;

      await tester.pumpWidget(buildSubject(
        repository: repository,
        onReviewCreated: (review) {
          createdResult = review;
        },
      ));

      // Preenche ID do alvo
      final targetField = find.widgetWithText(TextFormField, 'Identificador do Alvo (UUID) *');
      await tester.enterText(targetField, validTargetId);

      // Preenche relato
      final experienceField = find.widgetWithText(TextFormField, 'Conte sua experiência geral...');
      await tester.enterText(experienceField, 'Ótimo atendimento e ambiente super agradável!');

      // Clica em publicar
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(createdResult, isNotNull);
      expect(createdResult?.id, 'created-review-uuid-1');
      expect(find.text('Avaliação publicada com sucesso!'), findsOneWidget);
    });

    testWidgets('exibe banner de erro 429 com Retry-After quando rate limit é excedido', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.com/errors/rate-limit',
          title: 'Too Many Requests',
          status: 429,
          detail: 'Limite de criação de avaliações excedido.',
          code: 'RATE_LIMIT_EXCEEDED',
        ),
        retryAfterSeconds: 60,
      );

      await tester.pumpWidget(buildSubject(repository: repository));

      // Preenche ID do alvo
      final targetField = find.widgetWithText(TextFormField, 'Identificador do Alvo (UUID) *');
      await tester.enterText(targetField, validTargetId);

      // Clica em Publicar
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      // Verifica banner de 429 com contagem regressiva
      expect(find.text('Limite Excedido (429)'), findsOneWidget);
      expect(find.text('RATE_LIMIT_EXCEEDED'), findsOneWidget);
      expect(
        find.text('Por favor, aguarde 60 segundos antes de tentar novamente.'),
        findsOneWidget,
      );
    });

    testWidgets('exibe erros de campo específicos do backend (fieldErrors) no banner de erro', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/validation-error',
          title: 'Erro de Validação de Dados',
          status: 400,
          detail: 'Parâmetros da requisição inválidos',
          code: 'VALIDATION_ERROR',
          fieldErrors: {
            'targets[0].rating': 'A nota deve ser no mínimo 1.0',
          },
        ),
      );

      await tester.pumpWidget(buildSubject(repository: repository));

      // Preenche ID do alvo
      final targetField = find.widgetWithText(TextFormField, 'Identificador do Alvo (UUID) *');
      await tester.enterText(targetField, validTargetId);

      // Clica em Publicar
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      // Verifica exibição da mensagem genérica inalterada e do erro de campo
      expect(find.text('Parâmetros da requisição inválidos'), findsOneWidget);
      expect(find.text('VALIDATION_ERROR'), findsOneWidget);
      expect(find.text('• targets[0].rating: A nota deve ser no mínimo 1.0'), findsOneWidget);
    });
  });
}
