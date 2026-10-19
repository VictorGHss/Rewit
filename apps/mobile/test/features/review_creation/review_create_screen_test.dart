import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/device_location.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_privacy_rules.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_screen.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_notifier.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class MockPlaceRepositoryForReview implements PlaceRepository {
  CreatePlaceInput? capturedInput;
  PlaceDetail? placeToReturn;
  Exception? exceptionToThrow;
  Exception? uploadPhotoExceptionToThrow;
  int createPlaceCallCount = 0;
  int uploadPhotoCallCount = 0;
  final List<String> uploadedPlacePhotos = [];

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) async {
    createPlaceCallCount++;
    capturedInput = input;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    return placeToReturn ??
        PlaceDetail(
          id: 'd4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70',
          name: input.name,
          slug: 'pastel-da-dona-ana',
          category: 'GERAL',
          addressText: input.addressText,
          city: null,
          state: null,
          country: 'BR',
          latitude: null,
          longitude: null,
          validationRadiusMeters: 100,
          origin: 'MANUAL',
          isVerified: false,
          status: 'ACTIVE',
        );
  }

  @override
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? idempotencyKey,
  }) async {
    uploadPhotoCallCount++;
    if (uploadPhotoExceptionToThrow != null) {
      throw uploadPhotoExceptionToThrow!;
    }
    uploadedPlacePhotos.add(filename);
    return '/api/v1/places/$placeId/media/media-${uploadedPlacePhotos.length}';
  }

  @override
  Future<PlaceDetail> getPlaceById(String placeId) => throw UnimplementedError();

  @override
  Future<TargetStats> getTargetStats(String targetId) => throw UnimplementedError();

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) =>
      throw UnimplementedError();
}

class MockReviewMediaRepository implements ReviewMediaRepository {
  final List<String> uploadedFilenames = [];

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async => [];

  @override
  Future<ReviewMediaItem> uploadMedia({
    required String reviewId,
    required Uint8List bytes,
    required String filename,
    String? mimeType,
  }) async {
    uploadedFilenames.add(filename);
    return ReviewMediaItem(
      id: 'media-${uploadedFilenames.length}',
      reviewId: reviewId,
      url: '/api/v1/reviews/$reviewId/media/media-${uploadedFilenames.length}',
      mediaType: 'IMAGE',
      mimeType: mimeType ?? 'image/jpeg',
      sizeBytes: bytes.length,
      status: 'ACTIVE',
      createdAt: DateTime.now(),
    );
  }

  @override
  Future<void> deleteMedia({required String reviewId, required String mediaId}) async {}

  @override
  Future<Uint8List> getMediaBytes(String pathOrUrl) async => Uint8List(0);
}

class MockReviewCreationRepository implements ReviewCreationRepository {
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
          id: 'created-review-uuid-$callCount',
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
  Duration? simulatedDelay;

  @override
  Future<LocationResult> getCurrentLocation({Duration timeout = const Duration(seconds: 10)}) async {
    callCount++;
    if (simulatedDelay != null) {
      await Future.delayed(simulatedDelay!);
    }
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
    PlaceRepository? placeRepository,
    ReviewMediaRepository? mediaRepository,
    LocationService? locationService,
    ReviewCreateNotifier? notifier,
    ValueChanged<FeedReview>? onReviewCreated,
    String? initialTargetId,
    String? initialTargetName,
    String? initialTargetType,
    String? initialCategory,
    File? initialPhoto,
    Future<File?> Function(ImageSource source)? onPickManualPlacePhoto,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: ReviewCreateScreen(
        repository: repository,
        searchRepository: searchRepository,
        placeRepository: placeRepository,
        mediaRepository: mediaRepository,
        locationService: locationService,
        notifier: notifier,
        onReviewCreated: onReviewCreated,
        initialTargetId: initialTargetId,
        initialTargetName: initialTargetName,
        initialTargetType: initialTargetType,
        initialCategory: initialCategory,
        initialPhoto: initialPhoto,
        onPickManualPlacePhoto: onPickManualPlacePhoto,
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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

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

      // Card de alvo selecionado aparece com botão Trocar e nome legível (sem exibir ID interno)
      expect(find.text('Pizzaria Bella Napoli'), findsOneWidget);
      expect(find.text('Estabelecimento vinculado: Pizzaria Bella Napoli'), findsOneWidget);
      expect(find.text('Trocar'), findsOneWidget);
      expect(find.text('ID: 55555555-5555-5555-5555-555555555555'), findsNothing);

      // Preenche relato e publica
      final experienceField = find.widgetWithText(TextFormField, 'Conte sua experiência geral...');
      await tester.enterText(experienceField, 'Melhor pizza da cidade!');

      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(createdResult, isNotNull);
      expect(repository.capturedInput?.targets.first.rateableTargetId, '55555555-5555-5555-5555-555555555555');
      expect(repository.capturedInput?.contextPlaceId, '55555555-5555-5555-5555-555555555555');
      expect(find.text('Avaliação publicada com sucesso!'), findsOneWidget);
    });

    testWidgets('botão Trocar desmarca o alvo selecionado e reabre a busca', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

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

      // Context place deve exibir o nome legível sem expor o identificador interno
      expect(find.text('Estabelecimento vinculado: Café do Bosque'), findsOneWidget);
      expect(find.text('place-pre-selected-uuid'), findsNothing);

      // Não deve exibir o input de busca enquanto estiver selecionado
      expect(find.text('O que você quer avaliar? *'), findsNothing);
    });

    testWidgets('exibe card de check-in com estado inicial Sem check-in e botão Validar minha presença sem campos manuais de latitude/longitude', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
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

    testWidgets('captura de localização sem local de contexto não exibe Presença capturada e exibe aviso de local necessário', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

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
      // Não deve exibir "Presença capturada" sem local de contexto
      expect(find.text('Presença capturada'), findsNothing);
      expect(find.text('Local de contexto necessário para check-in'), findsOneWidget);
    });

    testWidgets('exibe aviso de localização aproximada quando precisão > 100m', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

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
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
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
      addTearDown(tester.view.resetDevicePixelRatio);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
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
      addTearDown(tester.view.resetDevicePixelRatio);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
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
      addTearDown(tester.view.resetDevicePixelRatio);

      final locService = MockLocationService();
      locService.resultToReturn = const LocationFailure(
        reason: LocationFailureReason.serviceDisabled,
        message: 'O serviço de localização está desativado no aparelho.',
      );

      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
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
      addTearDown(tester.view.resetDevicePixelRatio);

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
      addTearDown(tester.view.resetDevicePixelRatio);

      final locService = MockLocationService();
      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
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

    testWidgets('publicação bem-sucedida limpa estado e não reutiliza coordenadas na publicação seguinte', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final locService = MockLocationService();
      int callbackCount = 0;

      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
        onReviewCreated: (_) => callbackCount++,
      ));

      // 1. Captura localização para o Local A
      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pumpAndSettle();

      // Publica no Local A
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(callbackCount, 1);
      expect(repository.capturedInput?.userLatitude, -23.5505);

      // 2. Após sucesso, o formulário foi resetado. Configura novo alvo (Local B) sem check-in
      const placeB = '22222222-2222-2222-2222-222222222222';
      final targetField = find.widgetWithText(TextFormField, 'Identificador do Alvo (UUID) *');
      await tester.enterText(targetField, placeB);

      final contextPlaceField = find.widgetWithText(TextFormField, 'Identificador do Local (UUID)');
      await tester.enterText(contextPlaceField, placeB);

      // Publica no Local B sem solicitar nova presença
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(callbackCount, 2);
      expect(repository.capturedInput?.contextPlaceId, placeB);
      // Coordenadas do Local A NÃO devem ser reutilizadas
      expect(repository.capturedInput?.userLatitude, isNull);
      expect(repository.capturedInput?.userLongitude, isNull);
    });

    testWidgets('botão Publicar Avaliação fica desabilitado durante captura ativa de localização', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final locService = MockLocationService();
      locService.simulatedDelay = const Duration(milliseconds: 500);

      await tester.pumpWidget(buildSubject(
        repository: repository,
        locationService: locService,
        initialTargetId: validTargetId,
        initialTargetType: 'PLACE',
      ));

      // Inicia captura de localização
      await tester.tap(find.byKey(const Key('validate_presence_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('location_consent_confirm_button')));
      await tester.pump(); // Não settle, para pegar o estado intermediário

      // Botão Publicar Avaliação deve estar desabilitado
      final buttonFinder = find.widgetWithText(ElevatedButton, 'Publicar Avaliação');
      expect(buttonFinder, findsOneWidget);
      final elevatedButton = tester.widget<ElevatedButton>(buttonFinder);
      expect(elevatedButton.onPressed, isNull);

      // Aguarda concluir a captura
      await tester.pumpAndSettle();

      // Botão é reabilitado
      final reenabledButton = tester.widget<ElevatedButton>(buttonFinder);
      expect(reenabledButton.onPressed, isNotNull);
    });

    group('Regras e controles de privacidade (Security S2.1)', () {
      testWidgets('1. criação anônima com visibilidade pública submete com sucesso', (tester) async {
        tester.view.physicalSize = const Size(800, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        await tester.pumpWidget(buildSubject(
          repository: repository,
          initialTargetId: validTargetId,
          initialTargetType: 'PLACE',
        ));
        await tester.pumpAndSettle();

        // Ativa o switch de anonimato
        final switchFinder = find.byKey(const Key('create_anonymous_switch'));
        await tester.ensureVisible(switchFinder);
        await tester.tap(switchFinder);
        await tester.pumpAndSettle();

        // Submete
        final submitButton = find.widgetWithText(ElevatedButton, 'Publicar Avaliação');
        await tester.ensureVisible(submitButton);
        await tester.tap(submitButton);
        await tester.pumpAndSettle();

        expect(repository.capturedInput?.isAnonymous, isTrue);
        expect(repository.capturedInput?.visibility, 'PUBLIC');
      });

      testWidgets('2. criação anônima com visibilidade privada submete com sucesso', (tester) async {
        tester.view.physicalSize = const Size(800, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        await tester.pumpWidget(buildSubject(
          repository: repository,
          initialTargetId: validTargetId,
          initialTargetType: 'PLACE',
        ));
        await tester.pumpAndSettle();

        // Seleciona Visibilidade Privada no Dropdown
        final dropdownFinder = find.byKey(const Key('create_visibility_dropdown'));
        await tester.ensureVisible(dropdownFinder);
        await tester.tap(dropdownFinder);
        await tester.pumpAndSettle();

        final privateItem = find.text('Privado (apenas você)').last;
        await tester.tap(privateItem);
        await tester.pumpAndSettle();

        // Ativa o switch de anonimato
        final switchFinder = find.byKey(const Key('create_anonymous_switch'));
        await tester.ensureVisible(switchFinder);
        await tester.tap(switchFinder);
        await tester.pumpAndSettle();

        // Submete
        final submitButton = find.widgetWithText(ElevatedButton, 'Publicar Avaliação');
        await tester.ensureVisible(submitButton);
        await tester.tap(submitButton);
        await tester.pumpAndSettle();

        expect(repository.capturedInput?.isAnonymous, isTrue);
        expect(repository.capturedInput?.visibility, 'PRIVATE');
      });

      testWidgets('3. bloqueio da opção FOLLOWERS em avaliação anônima exibe aviso em português', (tester) async {
        tester.view.physicalSize = const Size(800, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        await tester.pumpWidget(buildSubject(
          repository: repository,
          initialTargetId: validTargetId,
          initialTargetType: 'PLACE',
        ));
        await tester.pumpAndSettle();

        // Ativa o switch de anonimato
        final switchFinder = find.byKey(const Key('create_anonymous_switch'));
        await tester.ensureVisible(switchFinder);
        await tester.tap(switchFinder);
        await tester.pumpAndSettle();

        // Verifica aviso explicativo de que seguidores não está disponível para anônimos
        expect(find.byKey(const Key('create_followers_unavailable_hint')), findsOneWidget);
        expect(find.text(ReviewPrivacyRules.followersUnavailableForAnonymous), findsOneWidget);

        // Abre o dropdown e verifica que o item Seguidores está desabilitado
        final dropdownFinder = find.byKey(const Key('create_visibility_dropdown'));
        await tester.ensureVisible(dropdownFinder);
        await tester.tap(dropdownFinder);
        await tester.pumpAndSettle();

        final followersItemFinder = find.widgetWithText(DropdownMenuItem<String>, 'Seguidores (apenas quem te segue)');
        expect(followersItemFinder, findsWidgets);
        final followersItemWidget = tester.widget<DropdownMenuItem<String>>(followersItemFinder.last);
        expect(followersItemWidget.enabled, isFalse);

        // Fecha o popup do dropdown tocando fora
        await tester.tapAt(const Offset(10, 10));
        await tester.pumpAndSettle();

        // A visibilidade deve continuar como PUBLIC (não trocou para FOLLOWERS)
        final submitButton = find.widgetWithText(ElevatedButton, 'Publicar Avaliação');
        await tester.ensureVisible(submitButton);
        await tester.tap(submitButton);
        await tester.pumpAndSettle();

        expect(repository.capturedInput?.isAnonymous, isTrue);
        expect(repository.capturedInput?.visibility, 'PUBLIC');
      });

      testWidgets('4. desativação do anonimato permite escolha explícita de FOLLOWERS e vice-versa', (tester) async {
        tester.view.physicalSize = const Size(800, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        await tester.pumpWidget(buildSubject(
          repository: repository,
          initialTargetId: validTargetId,
          initialTargetType: 'PLACE',
        ));
        await tester.pumpAndSettle();

        // 1. Escolhe Seguidores com avaliação identificada
        final dropdownFinder = find.byKey(const Key('create_visibility_dropdown'));
        await tester.ensureVisible(dropdownFinder);
        await tester.tap(dropdownFinder);
        await tester.pumpAndSettle();

        final followersItem = find.text('Seguidores (apenas quem te segue)').last;
        await tester.tap(followersItem);
        await tester.pumpAndSettle();

        // Com FOLLOWERS selecionado, o switch de anônimo exibe aviso e fica desabilitado
        final switchFinder = find.byKey(const Key('create_anonymous_switch'));
        await tester.ensureVisible(switchFinder);
        expect(find.text(ReviewPrivacyRules.anonymousUnavailableForFollowers), findsOneWidget);
        final switchWidget = tester.widget<SwitchListTile>(switchFinder);
        expect(switchWidget.onChanged, isNull);

        // 2. Troca visibilidade para Pública
        await tester.tap(dropdownFinder);
        await tester.pumpAndSettle();
        final publicItem = find.text('Público (visível a toda a comunidade)').last;
        await tester.tap(publicItem);
        await tester.pumpAndSettle();

        // Agora o switch de anônimo volta a ser habilitado
        final reenabledSwitch = tester.widget<SwitchListTile>(switchFinder);
        expect(reenabledSwitch.onChanged, isNotNull);

        // Ativa o anonimato
        await tester.tap(switchFinder);
        await tester.pumpAndSettle();

        // Submete
        final submitButton = find.widgetWithText(ElevatedButton, 'Publicar Avaliação');
        await tester.ensureVisible(submitButton);
        await tester.tap(submitButton);
        await tester.pumpAndSettle();

        expect(repository.capturedInput?.isAnonymous, isTrue);
        expect(repository.capturedInput?.visibility, 'PUBLIC');
      });
    });

    group('Fluxo S3-A: Cadastro manual de local (Pastel da Dona Ana) e publicação com UUID canônico', () {
      testWidgets(
        '1. busca sem resultado permite cadastrar Pastel da Dona Ana com endereço e fotografia, exibe nome legível sem ID interno e publica avaliação com UUID canônico e foto anexada',
        (tester) async {
          tester.view.physicalSize = const Size(800, 2400);
          tester.view.devicePixelRatio = 1.0;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);

          final tempDir = Directory.systemTemp.createTempSync('rewit_s3a_review_');
          final fakePhoto = File('${tempDir.path}/pastel_dona_ana.jpg');
          fakePhoto.writeAsBytesSync(List<int>.filled(2048, 7));
          addTearDown(() {
            if (tempDir.existsSync()) {
              tempDir.deleteSync(recursive: true);
            }
          });

          final searchRepo = MockSearchRepository()..itemsToReturn = [];
          final placeRepo = MockPlaceRepositoryForReview();
          final mediaRepo = MockReviewMediaRepository();

          await tester.pumpWidget(buildSubject(
            repository: repository,
            searchRepository: searchRepo,
            placeRepository: placeRepo,
            mediaRepository: mediaRepo,
            onPickManualPlacePhoto: (ImageSource source) async => fakePhoto,
          ));

          // 1. Digita "Pastel da Dona Ana" na busca de alvos
          final searchField = find.widgetWithText(TextFormField, 'O que você quer avaliar? *');
          await tester.enterText(searchField, 'Pastel da Dona Ana');
          await tester.pump(const Duration(milliseconds: 400));
          await tester.pumpAndSettle();

          // 2. Nenhum resultado retornado -> botão de cadastro manual visível
          expect(find.text('Nenhum resultado para "Pastel da Dona Ana".'), findsOneWidget);
          final registerBtn = find.byKey(const Key('register_manual_place_button'));
          expect(registerBtn, findsOneWidget);

          await tester.tap(registerBtn);
          await tester.pumpAndSettle();

          // 3. Tenta submeter o modal sem endereço nem foto -> valida campos obrigatórios
          final modalSubmitBtn = find.byKey(const Key('manual_place_submit_button'));
          await tester.ensureVisible(modalSubmitBtn);
          await tester.tap(modalSubmitBtn);
          await tester.pumpAndSettle();

          expect(find.text('Informe o endereço do local.'), findsOneWidget);

          // 4. Preenche endereço, tenta submeter sem foto -> valida foto obrigatória
          await tester.enterText(
            find.byKey(const Key('manual_place_address_field')),
            'Av. Brasil, 500 - Centro',
          );
          await tester.ensureVisible(modalSubmitBtn);
          await tester.tap(modalSubmitBtn);
          await tester.pumpAndSettle();

          expect(find.text('Adicione uma fotografia do local para concluir o cadastro.'), findsOneWidget);

          // 5. Anexa fotografia e conclui o cadastro do local
          await tester.tap(find.byKey(const Key('manual_place_camera_button')));
          await tester.pumpAndSettle();

          await tester.ensureVisible(modalSubmitBtn);
          await tester.tap(modalSubmitBtn);
          await tester.pumpAndSettle();

          // Verifica que o local foi criado no backend com nome e endereço e que a foto foi enviada para a mídia do local
          expect(placeRepo.capturedInput?.name, 'Pastel da Dona Ana');
          expect(placeRepo.capturedInput?.addressText, 'Av. Brasil, 500 - Centro');
          expect(placeRepo.uploadedPlacePhotos, ['pastel_dona_ana.jpg']);

          // 6. Verifica que na tela de criação o nome legível é exibido e que o UUID NÃO aparece como nome
          const canonicalUuid = 'd4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70';
          expect(find.text('Pastel da Dona Ana'), findsOneWidget);
          expect(find.text('Estabelecimento vinculado: Pastel da Dona Ana'), findsOneWidget);
          expect(find.textContaining('ID: $canonicalUuid'), findsNothing);

          // 7. Preenche relato e publica a avaliação
          final experienceField = find.widgetWithText(TextFormField, 'Conte sua experiência geral...');
          await tester.enterText(experienceField, 'O pastel de carne com queijo da Dona Ana é sensacional!');

          final publishBtn = find.widgetWithText(ElevatedButton, 'Publicar Avaliação');
          await tester.ensureVisible(publishBtn);
          await tester.tap(publishBtn);
          await tester.pumpAndSettle();

          // 8. Verifica que o payload enviado ao backend contém o UUID canônico e que a foto do local não foi duplicada como mídia da avaliação
          expect(repository.callCount, 1);
          expect(repository.capturedInput?.targets.first.rateableTargetId, canonicalUuid);
          expect(repository.capturedInput?.contextPlaceId, canonicalUuid);
          expect(mediaRepo.uploadedFilenames, isEmpty);
          expect(find.text('Avaliação publicada com sucesso!'), findsOneWidget);
        },
      );

      testWidgets(
        '2. exibe erro amigável no modal quando o backend retorna 409 Conflict (local já cadastrado)',
        (tester) async {
          tester.view.physicalSize = const Size(800, 2400);
          tester.view.devicePixelRatio = 1.0;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);

          final tempDir = Directory.systemTemp.createTempSync('rewit_s3a_409_');
          final fakePhoto = File('${tempDir.path}/pastel_409.jpg');
          fakePhoto.writeAsBytesSync(List<int>.filled(1024, 1));
          addTearDown(() {
            if (tempDir.existsSync()) {
              tempDir.deleteSync(recursive: true);
            }
          });

          final searchRepo = MockSearchRepository()..itemsToReturn = [];
          final placeRepo = MockPlaceRepositoryForReview()
            ..exceptionToThrow = const ApiException(
              ProblemDetail(
                type: 'https://api.rewit.com/errors/place-already-exists',
                title: 'Conflito',
                status: 409,
                detail: 'Este local já está cadastrado no catálogo.',
                code: 'PLACE_ALREADY_EXISTS',
              ),
            );

          await tester.pumpWidget(buildSubject(
            repository: repository,
            searchRepository: searchRepo,
            placeRepository: placeRepo,
            onPickManualPlacePhoto: (ImageSource source) async => fakePhoto,
          ));

          await tester.enterText(find.widgetWithText(TextFormField, 'O que você quer avaliar? *'), 'Pastel da Dona Ana');
          await tester.pump(const Duration(milliseconds: 400));
          await tester.pumpAndSettle();

          await tester.tap(find.byKey(const Key('register_manual_place_button')));
          await tester.pumpAndSettle();

          await tester.enterText(
            find.byKey(const Key('manual_place_address_field')),
            'Av. Brasil, 500 - Centro',
          );
          await tester.tap(find.byKey(const Key('manual_place_gallery_button')));
          await tester.pumpAndSettle();

          final modalSubmitBtn = find.byKey(const Key('manual_place_submit_button'));
          await tester.ensureVisible(modalSubmitBtn);
          await tester.tap(modalSubmitBtn);
          await tester.pumpAndSettle();

          expect(find.text('Este local já está cadastrado no catálogo.'), findsOneWidget);
        },
      );

      testWidgets(
        '3. trata falha parcial no upload da foto do local: preserva local criado e permite reenviar apenas a foto sem recriar o local',
        (tester) async {
          tester.view.physicalSize = const Size(800, 2400);
          tester.view.devicePixelRatio = 1.0;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);

          final tempDir = Directory.systemTemp.createTempSync('rewit_s3a_retry_');
          final fakePhoto = File('${tempDir.path}/pastel_retry.jpg');
          fakePhoto.writeAsBytesSync(List<int>.filled(1024, 9));
          addTearDown(() {
            if (tempDir.existsSync()) {
              tempDir.deleteSync(recursive: true);
            }
          });

          final searchRepo = MockSearchRepository()..itemsToReturn = [];
          final placeRepo = MockPlaceRepositoryForReview()
            ..uploadPhotoExceptionToThrow = const ApiException(
              ProblemDetail(
                type: 'https://api.rewit.com/errors/internal',
                title: 'Erro temporário no upload',
                status: 500,
                detail: 'Falha temporária ao salvar a foto do local.',
                code: 'MEDIA_UPLOAD_ERROR',
              ),
            );

          await tester.pumpWidget(buildSubject(
            repository: repository,
            searchRepository: searchRepo,
            placeRepository: placeRepo,
            onPickManualPlacePhoto: (ImageSource source) async => fakePhoto,
          ));

          await tester.enterText(find.widgetWithText(TextFormField, 'O que você quer avaliar? *'), 'Pastel da Dona Ana');
          await tester.pump(const Duration(milliseconds: 400));
          await tester.pumpAndSettle();

          await tester.tap(find.byKey(const Key('register_manual_place_button')));
          await tester.pumpAndSettle();

          await tester.enterText(
            find.byKey(const Key('manual_place_address_field')),
            'Av. Brasil, 500 - Centro',
          );
          await tester.tap(find.byKey(const Key('manual_place_camera_button')));
          await tester.pumpAndSettle();

          final modalSubmitBtn = find.byKey(const Key('manual_place_submit_button'));
          await tester.ensureVisible(modalSubmitBtn);
          await tester.tap(modalSubmitBtn);
          await tester.pumpAndSettle();

          // Na primeira tentativa: local criado (1x), upload da foto falhou (1x), modal continua aberto com aviso de recuperação
          expect(placeRepo.createPlaceCallCount, 1);
          expect(placeRepo.uploadPhotoCallCount, 1);
          expect(find.text('Falha temporária ao salvar a foto do local.'), findsOneWidget);
          expect(find.text('Reenviar Fotografia e Concluir'), findsOneWidget);

          // Corrige a falha de rede e clica em "Reenviar Fotografia e Concluir"
          placeRepo.uploadPhotoExceptionToThrow = null;
          await tester.ensureVisible(modalSubmitBtn);
          await tester.tap(modalSubmitBtn);
          await tester.pumpAndSettle();

          // O local NÃO foi recriado (continua 1), apenas o upload da foto foi reexecutado (2) e o modal fechou vinculando o local
          expect(placeRepo.createPlaceCallCount, 1);
          expect(placeRepo.uploadPhotoCallCount, 2);
          expect(placeRepo.uploadedPlacePhotos, ['pastel_retry.jpg']);
          expect(find.text('Estabelecimento vinculado: Pastel da Dona Ana'), findsOneWidget);
        },
      );
    });
  });
}
