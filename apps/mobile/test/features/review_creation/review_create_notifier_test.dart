import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_notifier.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_state.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';

class FakeReviewCreationRepository implements ReviewCreationRepository {
  CreateReviewInput? capturedInput;
  FeedReview? mockResult;
  Exception? exceptionToThrow;
  int callCount = 0;

  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    callCount++;
    capturedInput = input;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    return mockResult ??
        FeedReview(
          id: 'created-id-1',
          author: const FeedAuthor(displayName: 'Test User'),
          visibility: input.visibility,
          status: 'VISIBLE',
          createdAt: DateTime.now(),
          targets: input.targets
              .map((t) => FeedTarget(
                    id: 'target-1',
                    targetId: t.rateableTargetId,
                    rating: t.rating,
                    specificComment: t.specificComment,
                  ))
              .toList(),
        );
  }
}

void main() {
  group('ReviewCreateNotifier Tests', () {
    late FakeReviewCreationRepository repository;
    late ReviewCreateNotifier notifier;

    const validTargetId1 = '11111111-1111-1111-1111-111111111111';
    const validTargetId2 = '22222222-2222-2222-2222-222222222222';
    const validPlaceId = '33333333-3333-3333-3333-333333333333';

    setUp(() {
      repository = FakeReviewCreationRepository();
      notifier = ReviewCreateNotifier(repository: repository);
    });

    test('estado inicial possui 1 alvo padrão e campos zerados', () {
      expect(notifier.state, isA<ReviewCreateInitial>());
      expect(notifier.targets.length, 1);
      expect(notifier.targets.first.rateableTargetId, '');
      expect(notifier.targets.first.rating, 5.0);
      expect(notifier.isAnonymous, isFalse);
      expect(notifier.visibility, 'PUBLIC');
      expect(notifier.isSubmitting, isFalse);
    });

    test('permite adicionar e remover alvos sem permitir menos de 1', () {
      notifier.addTarget(targetId: validTargetId1, rating: 4.0);
      expect(notifier.targets.length, 2);

      notifier.removeTarget(0);
      expect(notifier.targets.length, 1);
      expect(notifier.targets.first.rateableTargetId, validTargetId1);

      // Não permite remover o último alvo remanescente
      notifier.removeTarget(0);
      expect(notifier.targets.length, 1);
    });

    test('atualiza alvos e dados de formulário', () {
      notifier.updateTarget(
        0,
        targetId: validTargetId1,
        rating: 3.5,
        specificComment: 'Bom atendimento',
      );
      notifier.setContextPlaceId(validPlaceId);
      notifier.setExperienceText('Lugar agradável');
      notifier.setAnonymous(true);
      notifier.setVisibility('FOLLOWERS');
      notifier.setCoordinates(latitude: -23.5, longitude: -46.6, accuracyMeters: 5.0);

      expect(notifier.targets.first.rateableTargetId, validTargetId1);
      expect(notifier.targets.first.rating, 3.5);
      expect(notifier.targets.first.specificComment, 'Bom atendimento');
      expect(notifier.contextPlaceId, validPlaceId);
      expect(notifier.experienceText, 'Lugar agradável');
      expect(notifier.isAnonymous, isTrue);
      expect(notifier.visibility, 'FOLLOWERS');
      expect(notifier.userLatitude, -23.5);
      expect(notifier.userLongitude, -46.6);
      expect(notifier.locationAccuracyMeters, 5.0);
    });

    test('selectTarget preenche dados do alvo e auto-preenche contextPlaceId se for PLACE', () {
      const placeItem = SearchResultItem(
        id: validPlaceId,
        name: 'Padaria Modelo',
        category: 'Panificação',
        targetType: TargetType.place,
        rawTargetType: 'PLACE',
        status: 'ACTIVE',
      );

      notifier.selectTarget(0, placeItem);

      expect(notifier.targets.first.rateableTargetId, validPlaceId);
      expect(notifier.targets.first.targetName, 'Padaria Modelo');
      expect(notifier.targets.first.targetType, 'PLACE');
      expect(notifier.targets.first.category, 'Panificação');
      expect(notifier.contextPlaceId, validPlaceId);
    });

    test('selectTarget para PRODUCT não sobrescreve contextPlaceId existente', () {
      notifier.setContextPlaceId(validPlaceId);

      const productItem = SearchResultItem(
        id: validTargetId1,
        name: 'Bolo de Chocolate',
        category: 'Doces',
        targetType: TargetType.product,
        rawTargetType: 'PRODUCT',
        status: 'ACTIVE',
      );

      notifier.selectTarget(0, productItem);

      expect(notifier.targets.first.rateableTargetId, validTargetId1);
      expect(notifier.targets.first.targetName, 'Bolo de Chocolate');
      expect(notifier.targets.first.targetType, 'PRODUCT');
      expect(notifier.contextPlaceId, validPlaceId);
    });

    test('clearTargetSelection redefine alvo para vazio preservando rating e comentário', () {
      notifier.updateTarget(
        0,
        targetId: validTargetId1,
        targetName: 'Café',
        targetType: 'PRODUCT',
        rating: 4.5,
        specificComment: 'Muito bom',
      );

      notifier.clearTargetSelection(0);

      expect(notifier.targets.first.rateableTargetId, '');
      expect(notifier.targets.first.targetName, isNull);
      expect(notifier.targets.first.targetType, isNull);
      expect(notifier.targets.first.rating, 4.5);
      expect(notifier.targets.first.specificComment, 'Muito bom');
    });

    test('isTargetAlreadySelected detecta alvos duplicados case-insensitively', () {
      notifier.updateTarget(0, targetId: validTargetId1);
      notifier.addTarget(targetId: validTargetId2);

      expect(notifier.isTargetAlreadySelected(validTargetId1), isTrue);
      expect(notifier.isTargetAlreadySelected(validTargetId1.toUpperCase()), isTrue);
      expect(notifier.isTargetAlreadySelected(validTargetId2), isTrue);
      expect(notifier.isTargetAlreadySelected(validPlaceId), isFalse);

      // Excluindo o índice atual (para permitir o alvo atual ao editar)
      expect(notifier.isTargetAlreadySelected(validTargetId1, excludeIndex: 0), isFalse);
      expect(notifier.isTargetAlreadySelected(validTargetId1, excludeIndex: 1), isTrue);
    });

    group('Validações Locais', () {
      test('rejeita alvo com identificador vazio', () {
        notifier.updateTarget(0, targetId: '   ');
        final error = notifier.validate();
        expect(error, contains('Informe o identificador do alvo 1'));
      });

      test('rejeita alvo com identificador que não é UUID', () {
        notifier.updateTarget(0, targetId: 'not-a-uuid');
        final error = notifier.validate();
        expect(error, contains('não é um UUID válido'));
      });

      test('rejeita múltiplos alvos com mesmo UUID (alvo duplicado)', () {
        notifier.updateTarget(0, targetId: validTargetId1);
        notifier.addTarget(targetId: validTargetId1);

        final error = notifier.validate();
        expect(error, contains('alvo duplicado'));
      });

      test('rejeita nota fora do intervalo 1.0 a 5.0', () {
        notifier.updateTarget(0, targetId: validTargetId1, rating: 0.5);
        expect(notifier.validate(), contains('deve estar entre 1.0 e 5.0'));

        notifier.updateTarget(0, rating: 5.5);
        expect(notifier.validate(), contains('deve estar entre 1.0 e 5.0'));
      });

      test('rejeita nota com mais de uma casa decimal', () {
        notifier.updateTarget(0, targetId: validTargetId1, rating: 4.25);
        expect(notifier.validate(), contains('máximo uma casa decimal'));
      });

      test('rejeita local de contexto que não seja UUID', () {
        notifier.updateTarget(0, targetId: validTargetId1);
        notifier.setContextPlaceId('invalido');
        expect(notifier.validate(), contains('estabelecimento de contexto não é um UUID válido'));
      });

      test('rejeita relato com mais de 2000 caracteres', () {
        notifier.updateTarget(0, targetId: validTargetId1);
        notifier.setExperienceText('a' * 2001);
        expect(notifier.validate(), contains('não pode exceder 2000 caracteres'));
      });

      test('rejeita coordenadas fora dos limites geográficos', () {
        notifier.updateTarget(0, targetId: validTargetId1);
        notifier.setCoordinates(latitude: 95.0);
        expect(notifier.validate(), contains('latitude deve estar entre -90.0 e 90.0'));

        notifier.setCoordinates(latitude: 0, longitude: -185.0);
        expect(notifier.validate(), contains('longitude deve estar entre -180.0 e 180.0'));

        notifier.setCoordinates(latitude: 0, longitude: 0, accuracyMeters: -1.0);
        expect(notifier.validate(), contains('precisão da localização não pode ser negativa'));
      });
    });

    group('Submissão e Proteção Anti-Duplo Clique', () {
      test('submissão com dados inválidos atualiza estado para ReviewCreateError sem chamar repositório', () async {
        final success = await notifier.submit();

        expect(success, isFalse);
        expect(notifier.state, isA<ReviewCreateError>());
        final errorState = notifier.state as ReviewCreateError;
        expect(errorState.isValidationError, isTrue);
        expect(repository.callCount, 0);
      });

      test('submissão com sucesso transita para ReviewCreateSuccess e invoca repositório', () async {
        notifier.updateTarget(0, targetId: validTargetId1, rating: 4.5);
        notifier.addTarget(targetId: validTargetId2, rating: 5.0);
        notifier.setExperienceText('Experiência maravilhosa!');

        final success = await notifier.submit();

        expect(success, isTrue);
        expect(notifier.state, isA<ReviewCreateSuccess>());
        expect(repository.callCount, 1);
        expect(repository.capturedInput?.targets.length, 2);
        expect(repository.capturedInput?.experienceText, 'Experiência maravilhosa!');
      });

      test('impede submissão concorrente/duplo clique enquanto isSubmitting é true', () async {
        notifier.updateTarget(0, targetId: validTargetId1);

        // Dispara duas chamadas
        final future1 = notifier.submit();
        final future2 = notifier.submit();

        final results = await Future.wait([future1, future2]);

        // Apenas uma foi executada pelo repositório
        expect(repository.callCount, 1);
        expect(results.contains(true), isTrue);
        expect(results.contains(false), isTrue);
      });

      test('captura ApiException e formata erro RFC 7807 e Retry-After em caso de 429', () async {
        notifier.updateTarget(0, targetId: validTargetId1);
        repository.exceptionToThrow = const ApiException(
          ProblemDetail(
            type: 'https://api.rewit.com/errors/rate-limit',
            title: 'Too Many Requests',
            status: 429,
            detail: 'Você atingiu o limite de publicações.',
            code: 'RATE_LIMIT_EXCEEDED',
          ),
          retryAfterSeconds: 45,
        );

        final success = await notifier.submit();

        expect(success, isFalse);
        expect(notifier.state, isA<ReviewCreateError>());
        final errorState = notifier.state as ReviewCreateError;
        expect(errorState.isRateLimited, isTrue);
        expect(errorState.retryAfterSeconds, 45);
        expect(errorState.message, contains('atingiu o limite'));
        expect(errorState.errorCode, 'RATE_LIMIT_EXCEEDED');
      });

      test('reset restaura o estado inicial e limpa os campos', () {
        notifier.updateTarget(0, targetId: validTargetId1);
        notifier.addTarget(targetId: validTargetId2);
        notifier.setExperienceText('Texto');
        notifier.setAnonymous(true);

        notifier.reset();

        expect(notifier.state, isA<ReviewCreateInitial>());
        expect(notifier.targets.length, 1);
        expect(notifier.targets.first.rateableTargetId, '');
        expect(notifier.experienceText, isNull);
        expect(notifier.isAnonymous, isFalse);
      });
    });
  });
}
