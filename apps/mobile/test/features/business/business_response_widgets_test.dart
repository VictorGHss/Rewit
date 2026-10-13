import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/business/domain/entities/business_response_entities.dart';
import 'package:rewit_mobile/features/business/presentation/widgets/business_response_card.dart';
import 'package:rewit_mobile/features/business/presentation/widgets/publish_business_response_form.dart';

void main() {
  group('BusinessResponseCard Widget Tests (C11)', () {
    final response = BusinessResponse(
      id: 'resp-1',
      reviewId: 'rev-1',
      discussionId: null,
      businessAccountId: 'acc-1',
      corporateName: 'Pizzaria Bella Itália Ltda',
      placeId: 'plc-1',
      placeName: 'Bella Itália - Jardins',
      content: 'Agradecemos sua visita e lamentamos a demora na entrega.',
      createdAt: DateTime(2026, 10, 11, 14, 30),
    );

    testWidgets('exibe selo de resposta oficial identificando empresa e local', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusinessResponseCard(
              response: response,
              canDelete: false,
            ),
          ),
        ),
      );

      // Selo oficial
      expect(
        find.text('Resposta oficial · Pizzaria Bella Itália Ltda · Bella Itália - Jardins'),
        findsOneWidget,
      );
      // Conteúdo
      expect(
        find.text('Agradecemos sua visita e lamentamos a demora na entrega.'),
        findsOneWidget,
      );
      // Data formatada
      expect(find.textContaining('11/10/2026 às 14:30'), findsOneWidget);
      // Não exibe botão de exclusão quando canDelete é false
      expect(find.text('Excluir'), findsNothing);
    });

    testWidgets('exibe botão de exclusão e diálogo de confirmação quando canDelete é true', (tester) async {
      bool deleteCalled = false;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusinessResponseCard(
              response: response,
              canDelete: true,
              onDelete: () {
                deleteCalled = true;
              },
            ),
          ),
        ),
      );

      // Botão de exclusão visível
      expect(find.text('Excluir'), findsOneWidget);

      // Clica em Excluir
      await tester.tap(find.text('Excluir'));
      await tester.pumpAndSettle();

      // Diálogo de confirmação aberto
      expect(find.text('Excluir resposta oficial'), findsOneWidget);
      expect(
        find.text('Deseja realmente remover esta resposta oficial da empresa? Esta ação não pode ser desfeita.'),
        findsOneWidget,
      );

      // Clica em Cancelar
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();
      expect(deleteCalled, isFalse);

      // Clica em Excluir novamente e confirma
      await tester.tap(find.text('Excluir'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('confirm_delete_business_response_button')));
      await tester.pumpAndSettle();
      expect(deleteCalled, isTrue);
    });
  });

  group('PublishBusinessResponseForm Widget Tests (C11)', () {
    const eligibilityMultiple = BusinessResponseEligibility(
      reviewId: 'rev-1',
      respondable: true,
      accounts: [
        EligibleBusinessAccount(
          businessAccountId: 'acc-1',
          corporateName: 'Padaria Alfa Ltda',
          places: [
            EligiblePlace(placeId: 'plc-1', placeName: 'Alfa Matriz'),
            EligiblePlace(placeId: 'plc-2', placeName: 'Alfa Filial'),
          ],
          activeResponseId: null,
          canPublish: true,
        ),
        EligibleBusinessAccount(
          businessAccountId: 'acc-2',
          corporateName: 'Padaria Beta Ltda',
          places: [
            EligiblePlace(placeId: 'plc-3', placeName: 'Beta Centro'),
          ],
          activeResponseId: null,
          canPublish: true,
        ),
      ],
    );

    testWidgets('permite seleção de empresa e local quando múltiplos existem', (tester) async {
      String? submittedAccount;
      String? submittedPlace;
      String? submittedContent;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                submittedAccount = businessAccountId;
                submittedPlace = placeId;
                submittedContent = content;
              },
            ),
          ),
        ),
      );

      // Dropdowns visíveis
      expect(find.byKey(const Key('business_account_dropdown')), findsOneWidget);
      expect(find.byKey(const Key('eligible_place_dropdown')), findsOneWidget);

      // Preenche conteúdo
      await tester.enterText(
        find.byKey(const Key('business_response_content_field')),
        'Mensagem oficial da empresa.',
      );

      // Envia
      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      expect(submittedAccount, 'acc-1');
      expect(submittedPlace, 'plc-1');
      expect(submittedContent, 'Mensagem oficial da empresa.');
    });

    testWidgets('valida conteúdo não vazio', (tester) async {
      bool submitted = false;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                submitted = true;
              },
            ),
          ),
        ),
      );

      // Tenta enviar com campo vazio
      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      expect(submitted, isFalse);
      expect(find.text('O conteúdo da resposta é obrigatório.'), findsOneWidget);
    });

    testWidgets('valida limite de 2000 caracteres', (tester) async {
      bool submitted = false;
      final controller = TextEditingController(text: 'A' * 2001);

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              controller: controller,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                submitted = true;
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      expect(submitted, isFalse);
      expect(find.text('A resposta não pode ultrapassar 2000 caracteres.'), findsOneWidget);
    });

    testWidgets('exibe mensagem compreensível em caso de erro 429 de rate limit', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                throw const ApiException(
                  ProblemDetail(
                    type: 'about:blank',
                    title: 'Limite excedido',
                    status: 429,
                    detail: 'Rate limit excedido.',
                    code: 'RATE_LIMIT_EXCEEDED',
                  ),
                );
              },
            ),
          ),
        ),
      );

      await tester.enterText(
        find.byKey(const Key('business_response_content_field')),
        'Testando taxa de requisições.',
      );

      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      expect(
        find.text('Limite de publicações atingido temporariamente (10 requisições/min). Tente novamente em breve.'),
        findsOneWidget,
      );
    });

    testWidgets('exibe tempo restante quando erro 429 inclui retryAfterSeconds', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                throw const ApiException(
                  ProblemDetail(
                    type: 'about:blank',
                    title: 'Limite excedido',
                    status: 429,
                    detail: 'Rate limit excedido.',
                    code: 'RATE_LIMIT_EXCEEDED',
                  ),
                  retryAfterSeconds: 45,
                );
              },
            ),
          ),
        ),
      );

      await tester.enterText(
        find.byKey(const Key('business_response_content_field')),
        'Testando taxa com Retry-After.',
      );

      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      expect(
        find.text('Limite de publicações atingido temporariamente (10 requisições/min). Tente novamente em 45 segundos.'),
        findsOneWidget,
      );
    });

    testWidgets('exibe mensagem de erro de campo específico retornado na ProblemDetail RFC 7807', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                throw const ApiException(
                  ProblemDetail(
                    type: 'https://api.rewit.app/errors/validation-error',
                    title: 'Erro de Validação de Dados',
                    status: 400,
                    detail: 'Parâmetros da requisição inválidos',
                    code: 'VALIDATION_ERROR',
                    fieldErrors: {
                      'content': 'O conteúdo não pode conter linguagem ofensiva.',
                    },
                  ),
                );
              },
            ),
          ),
        ),
      );

      await tester.enterText(
        find.byKey(const Key('business_response_content_field')),
        'Texto com erro de validação.',
      );

      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      expect(
        find.text('O conteúdo não pode conter linguagem ofensiva.'),
        findsOneWidget,
      );
    });

    testWidgets('evita múltiplos envios concorrentes quando submissão já está em andamento', (tester) async {
      int submitCount = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                submitCount++;
                await Future.delayed(const Duration(milliseconds: 100));
              },
            ),
          ),
        ),
      );

      await tester.enterText(
        find.byKey(const Key('business_response_content_field')),
        'Mensagem enviada com proteção de concorrência.',
      );

      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pump();

      expect(find.text('Publicando...'), findsOneWidget);

      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();

      expect(submitCount, 1);
    });

    testWidgets('suporta caracteres UTF-8 e emojis no conteúdo da resposta', (tester) async {
      String? submittedContent;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PublishBusinessResponseForm(
              eligibility: eligibilityMultiple,
              onSubmit: ({required businessAccountId, required placeId, required content}) async {
                submittedContent = content;
              },
            ),
          ),
        ),
      );

      await tester.enterText(
        find.byKey(const Key('business_response_content_field')),
        'Olá! Agradecemos o carinho ❤️🥐☕. Até breve! ✨',
      );

      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      expect(submittedContent, 'Olá! Agradecemos o carinho ❤️🥐☕. Até breve! ✨');
    });
  });
}
