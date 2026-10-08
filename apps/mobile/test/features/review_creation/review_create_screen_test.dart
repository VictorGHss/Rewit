import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/device_location.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_screen.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_notifier.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

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

class MockSearchRepository implements SearchRepository {
  List<SearchResultItem> itemsToReturn = [];

  @override
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
    return SearchPage(
      items: itemsToReturn,
      pageNumber: page,
      pageSize: size,
      totalElements: itemsToReturn.length,
      totalPages: 1,
      isLast: true,
    );
  }
}

class MockLocationService implements LocationService {
  LocationResult resultToReturn = const LocationSuccess(
    DeviceLocation(
      latitude: -23.5505,
      longitude: -46.6333,
      accuracyMeters: 12.0,
    ),
  );
  int callCount = 0;
  bool openSettingsCalled = false;

  @override
  Future<LocationResult> getCurrentLocation({Duration timeout = const Duration(seconds: 10)}) async {
    callCount++;
    return resultToReturn;
  }

  @override
  Future<bool> openAppSettings() async {
    openSettingsCalled = true;
    return true;
  }
}

void main() {
  const validTargetId = '11111111-1111-1111-1111-111111111111';

  Widget buildSubject({
    required ReviewCreationRepository repository,
    SearchRepository? searchRepository,
    LocationService? locationService,
    ReviewCreateNotifier? notifier,
    ValueChanged<FeedReview>? onReviewCreated,
    String? initialTargetId,
    String? initialTargetName,
    String? initialTargetType,
    String? initialCategory,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: ReviewCreateScreen(
        repository: repository,
        searchRepository: searchRepository,
        locationService: locationService,
        notifier: notifier,
        onReviewCreated: onReviewCreated,
        initialTargetId: initialTargetId,
        initialTargetName: initialTargetName,
        initialTargetType: initialTargetType,
        initialCategory: initialCategory,
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

    testWidgets('busca e seleciona alvo via Search V1 exibindo card selecionado e submetendo UUID correto', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final searchRepo = MockSearchRepository();
      searchRepo.itemsToReturn = const [
        SearchResultItem(
          id: '55555555-5555-5555-5555-555555555555',
          name: 'Pizzaria Bella Napoli',
          category: 'Pizzarias',
          targetType: TargetType.place,
          rawTargetType: 'PLACE',
          status: 'ACTIVE',
        ),
      ];

      FeedReview? createdResult;

      await tester.pumpWidget(buildSubject(
        repository: repository,
        searchRepository: searchRepo,
        onReviewCreated: (review) {
          createdResult = review;
        },
      ));

      // Campo de busca é exibido
      expect(find.text('O que você quer avaliar? *'), findsOneWidget);

      // Digita nome para busca
      await tester.enterText(find.widgetWithText(TextFormField, 'O que você quer avaliar? *'), 'pizza');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // Encontra resultado
      expect(find.text('Pizzaria Bella Napoli'), findsOneWidget);
      expect(find.text('Local'), findsOneWidget);

      // Clica em Selecionar
      await tester.tap(find.text('Selecionar'));
      await tester.pumpAndSettle();

      // Card de alvo selecionado aparece com botão Trocar
      expect(find.text('Pizzaria Bella Napoli'), findsOneWidget);
      expect(find.text('Trocar'), findsOneWidget);
      expect(find.text('ID: 55555555-5555-5555-5555-555555555555'), findsOneWidget);

      // Preenche relato e publica
      final experienceField = find.widgetWithText(TextFormField, 'Conte sua experiência geral...');
      await tester.enterText(experienceField, 'Melhor pizza da cidade!');

      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(createdResult, isNotNull);
      expect(repository.capturedInput?.targets.first.rateableTargetId, '55555555-5555-5555-5555-555555555555');
      expect(find.text('Avaliação publicada com sucesso!'), findsOneWidget);
    });

    testWidgets('botão Trocar desmarca o alvo selecionado e reabre a busca', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final searchRepo = MockSearchRepository();
      searchRepo.itemsToReturn = const [
        SearchResultItem(
          id: '55555555-5555-5555-5555-555555555555',
          name: 'Pizzaria Bella Napoli',
          category: 'Pizzarias',
          targetType: TargetType.place,
          rawTargetType: 'PLACE',
          status: 'ACTIVE',
        ),
      ];

      await tester.pumpWidget(buildSubject(
        repository: repository,
        searchRepository: searchRepo,
      ));

      // Busca e seleciona
      await tester.enterText(find.widgetWithText(TextFormField, 'O que você quer avaliar? *'), 'pizza');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Selecionar'));
      await tester.pumpAndSettle();

      expect(find.text('Pizzaria Bella Napoli'), findsOneWidget);
      expect(find.text('Trocar'), findsOneWidget);

      // Clica em Trocar
      await tester.tap(find.text('Trocar'));
      await tester.pumpAndSettle();

      // Card foi removido e campo de busca voltou
      expect(find.text('O que você quer avaliar? *'), findsOneWidget);
      expect(find.text('Trocar'), findsNothing);
    });

    testWidgets('inicializa com alvo pré-selecionado exibindo card do alvo e contextPlaceId preenchido', (tester) async {
      await tester.pumpWidget(buildSubject(
        repository: repository,
        initialTargetId: 'place-pre-selected-uuid',
        initialTargetName: 'Café do Bosque',
        initialTargetType: 'PLACE',
        initialCategory: 'Cafeterias',
      ));
      await tester.pumpAndSettle();

      // Card já deve estar visível com o nome do local pré-selecionado
      expect(find.text('Café do Bosque'), findsOneWidget);
      expect(find.text('Trocar'), findsOneWidget);

      // Context place ID deve estar preenchido
      expect(find.text('place-pre-selected-uuid'), findsOneWidget);

      // Não deve exibir o input de busca enquanto estiver selecionado
      expect(find.text('O que você quer avaliar? *'), findsNothing);
    });

    testWidgets('exibe card de check-in com estado inicial Sem check-in e botão Validar minha presença sem campos manuais de latitude/longitude', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      expect(find.text('Presença e Check-in no Local (Opcional)'), findsOneWidget);
      expect(find.text('Sem check-in'), findsOneWidget);
      expect(find.byKey(const Key('validate_presence_button')), findsOneWidget);

      // Garante que campos manuais de latitude e longitude não existem na tela
      expect(find.text('Latitude (-90 a 90)'), findsNothing);
      expect(find.text('Longitude (-180 a 180)'), findsNothing);
    });

    testWidgets('clicar em Validar minha presença exibe diálogo de consentimento de privacidade e cancelar descarta', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();

      // Diálogo de consentimento exibido
      expect(find.text('Validação de Presença'), findsOneWidget);
      expect(
        find.text('Para validar que você está no local, o Rewit precisa usar sua localização atual por alguns instantes.\n\nA localização não será acompanhada em segundo plano.'),
        findsOneWidget,
      );
      expect(find.byKey(const Key('location_consent_cancel_button')), findsOneWidget);
      expect(find.byKey(const Key('location_consent_confirm_button')), findsOneWidget);

      // Clica em Cancelar
      await tester.tap(find.byKey(const Key('location_consent_cancel_button')));
      await tester.pumpAndSettle();

      expect(find.text('Validação de Presença'), findsNothing);
      expect(locService.callCount, 0);
      expect(find.text('Sem check-in'), findsOneWidget);
    });

    testWidgets('confirmar consentimento captura localização e exibe Presença capturada com precisão sem exibir coordenadas brutas', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();

      expect(locService.callCount, 1);
      expect(find.text('Presença capturada'), findsOneWidget);
      expect(find.text('Precisão aproximada: 12 m'), findsOneWidget);

      // Nunca expor coordenadas brutas (lat/long) na UI
      expect(find.textContaining('-23.5505'), findsNothing);
      expect(find.textContaining('-46.6333'), findsNothing);

      // Botões de ação pós-captura
      expect(find.byKey(const Key('update_location_button')), findsOneWidget);
      expect(find.byKey(const Key('remove_checkin_button')), findsOneWidget);
    });

    testWidgets('exibe aviso de localização aproximada quando precisão > 100m', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      locService.resultToReturn = const LocationSuccess(
        DeviceLocation(
          latitude: -23.5505,
          longitude: -46.6333,
          accuracyMeters: 140.0,
        ),
      );

      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();

      expect(find.text('Presença capturada'), findsOneWidget);
      expect(find.text('Precisão aproximada: 140 m'), findsOneWidget);
      expect(
        find.text('Localização aproximada concedida. A precisão pode não ser suficiente para validar presença no local.'),
        findsOneWidget,
      );
    });

    testWidgets('botão Atualizar localização dispara nova captura', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      // Primeira captura via consentimento
      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();
      expect(locService.callCount, 1);

      // Atualiza localização diretamente
      await tester.tap(find.byKey(const Key('update_location_button')));
      await tester.pumpAndSettle();
      expect(locService.callCount, 2);
    });

    testWidgets('botão Remover check-in descarta coordenadas e retorna para Sem check-in', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();
      expect(find.text('Presença capturada'), findsOneWidget);

      // Clica em Remover check-in
      await tester.tap(find.byKey(const Key('remove_checkin_button')));
      await tester.pumpAndSettle();

      expect(find.text('Sem check-in'), findsOneWidget);
      expect(find.byKey(const Key('validate_presence_button')), findsOneWidget);
    });

    testWidgets('exibe mensagem de erro e botão Tentar novamente quando captura falha', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      locService.resultToReturn = const LocationFailure(
        reason: LocationFailureReason.serviceDisabled,
        message: 'O serviço de localização está desativado no aparelho.',
      );

      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();

      expect(find.text('O serviço de localização está desativado no aparelho.'), findsOneWidget);
      expect(find.byKey(const Key('retry_location_button')), findsOneWidget);
      expect(find.byKey(const Key('open_settings_button')), findsNothing);

      // Testa Tentar novamente após serviço reativado
      locService.resultToReturn = const LocationSuccess(
        DeviceLocation(latitude: -23.5505, longitude: -46.6333, accuracyMeters: 10.0),
      );
      await tester.tap(find.byKey(const Key('retry_location_button')));
      await tester.pumpAndSettle();

      expect(find.text('Presença capturada'), findsOneWidget);
    });

    testWidgets('exibe botão Abrir configurações quando permissão for negada permanentemente', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      locService.resultToReturn = const LocationFailure(
        reason: LocationFailureReason.permissionDeniedForever,
        message: 'Permissão de localização permanentemente negada. Ative nas configurações do dispositivo.',
      );

      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();

      expect(find.text('Permissão de localização permanentemente negada. Ative nas configurações do dispositivo.'), findsOneWidget);
      expect(find.byKey(const Key('open_settings_button')), findsOneWidget);

      await tester.tap(find.byKey(const Key('open_settings_button')));
      await tester.pumpAndSettle();

      expect(locService.openSettingsCalled, isTrue);
    });

    testWidgets('submissão com check-in envia coordenadas capturadas no repositório', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
      ));

      // Captura localização
      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();

      // Preenche alvo
      final targetField = find.widgetWithText(TextFormField, 'Identificador do Alvo (UUID) *');
      await tester.enterText(targetField, validTargetId);

      // Publica
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(repository.capturedInput?.userLatitude, -23.5505);
      expect(repository.capturedInput?.userLongitude, -46.6333);
      expect(repository.capturedInput?.locationAccuracyMeters, 12.0);
    });
  });
}
