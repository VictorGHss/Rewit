import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/manual_place_registration_sheet.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/screens/search_screen.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/target_item_input_widget.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/target_search_selector_widget.dart';

class _RecordingPlaceRepository implements PlaceRepository {
  int createPlaceCalls = 0;
  final List<CreatePlaceInput> createdInputs = [];
  int uploadCalls = 0;
  final List<Map<String, dynamic>> uploadArguments = [];

  PlaceDetail? placeToReturn;
  Exception? createPlaceException;
  Exception? uploadPhotoException;

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) async {
    createPlaceCalls++;
    createdInputs.add(input);
    if (createPlaceException != null) {
      throw createPlaceException!;
    }
    return placeToReturn ??
        PlaceDetail(
          id: 'place-uuid-$createPlaceCalls',
          name: input.name,
          slug: 'slug-${input.name}',
          category: 'GERAL',
          addressText: input.addressText,
          latitude: input.latitude,
          longitude: input.longitude,
          origin: 'MANUAL',
          validationRadiusMeters: 100,
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
    uploadCalls++;
    uploadArguments.add({
      'placeId': placeId,
      'fileBytes': fileBytes,
      'filename': filename,
      'idempotencyKey': idempotencyKey,
    });
    if (uploadPhotoException != null) {
      throw uploadPhotoException!;
    }
    return '/api/v1/places/$placeId/media/photo-$uploadCalls';
  }

  @override
  Future<PlaceDetail> getPlaceById(String id) async => throw UnimplementedError();

  @override
  Future<TargetStats> getTargetStats(String id) async => throw UnimplementedError();

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async =>
      throw UnimplementedError();
}

class _StubSearchRepository implements SearchRepository {
  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async {
    return const SearchPage(
      items: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }
}

class _FakeAuthNotifier extends ChangeNotifier implements AuthNotifier {
  AuthState _state;
  _FakeAuthNotifier([AuthState? initial]) : _state = initial ?? const AuthInitial();

  @override
  AuthState get state => _state;

  @override
  bool get isAuthenticated => _state is Authenticated;

  @override
  bool get isLoading => _state is Authenticating;

  void emit(AuthState newState) {
    _state = newState;
    notifyListeners();
  }

  // Sem renovação de sessão neste fake: nenhum 401 tem verificação associada
  @override
  int get sessionCheckSequence => 0;

  @override
  Future<SessionRefreshOutcome>? sessionCheckSince(int sequence) => null;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

ApiException _makeApiException(int status, String code, String detail) {
  return ApiException(ProblemDetail(
    type: 'about:blank',
    title: 'Erro de teste',
    status: status,
    code: code,
    detail: detail,
  ));
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ManualPlaceRegistrationSheet & SearchScreen - Isolamento de Estado e Idempotência (S3-A.7)', () {
    late _RecordingPlaceRepository repo;
    late Directory tempDir;
    late File testPhotoFile1;
    late File testPhotoFile2;
    late PendingPlaceRegistrationStore store;

    setUp(() {
      repo = _RecordingPlaceRepository();
      store = PendingPlaceRegistrationStore();
      tempDir = Directory.systemTemp.createTempSync('rewit_test_');
      testPhotoFile1 = File('${tempDir.path}/mock_photo_1.jpg');
      testPhotoFile1.writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46]));
      testPhotoFile2 = File('${tempDir.path}/mock_photo_2.jpg');
      testPhotoFile2.writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x47]));
    });

    tearDown(() {
      try {
        tempDir.deleteSync(recursive: true);
      } catch (_) {}
    });

    Widget buildSheet({
      required PlaceRepository repository,
      File? initialPhoto,
      PlaceDetail? initialPendingPlace,
      String? initialUploadIdempotencyKey,
    }) {
      File? photoToPick = initialPhoto ?? testPhotoFile1;
      return MaterialApp(
        home: Scaffold(
          body: ManualPlaceRegistrationSheet(
            placeRepository: repository,
            initialName: 'Café Teste',
            initialLatitude: -25.42,
            initialLongitude: -49.27,
            initialPendingPlace: initialPendingPlace,
            initialUploadIdempotencyKey: initialUploadIdempotencyKey,
            onPickPhoto: (source) async => photoToPick,
          ),
        ),
      );
    }

    testWidgets('1. Uma nova operação lógica de upload recebe uma chave de idempotência UUID v4', (tester) async {
      await tester.pumpWidget(buildSheet(repository: repo));

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua das Palmeiras, 100',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 1);
      final key = repo.uploadArguments.first['idempotencyKey'] as String?;
      expect(key, isNotNull);
      expect(key!.isNotEmpty, isTrue);
      final uuidRegex = RegExp(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$');
      expect(uuidRegex.hasMatch(key), isTrue, reason: 'A chave de idempotência deve ser um UUID v4 válido');
    });

    testWidgets('2. Um retry da mesma operação com o mesmo payload reutiliza a chave', (tester) async {
      repo.uploadPhotoException = _makeApiException(503, 'UNAVAILABLE', 'Falha transitória');

      await tester.pumpWidget(buildSheet(repository: repo));

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua das Palmeiras, 100',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      // Primeira tentativa -> falha no upload
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.uploadCalls, 1);
      final firstKey = repo.uploadArguments[0]['idempotencyKey'] as String?;
      expect(firstKey, isNotNull);

      // Remove exceção para simular recuperação
      repo.uploadPhotoException = null;

      // Clica em 'Reenviar Fotografia e Concluir'
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.uploadCalls, 2);
      final secondKey = repo.uploadArguments[1]['idempotencyKey'] as String?;
      expect(secondKey, equals(firstKey), reason: 'O retry deve reutilizar a mesma chave de idempotência');
    });

    testWidgets('3. Selecionar uma nova foto gera uma nova chave de upload lógica', (tester) async {
      File photoPicked = testPhotoFile1;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ManualPlaceRegistrationSheet(
              placeRepository: repo,
              initialName: 'Café Teste',
              onPickPhoto: (source) async => photoPicked,
            ),
          ),
        ),
      );

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua das Palmeiras, 100',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      // Força falha no upload para reter o local criado
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha');
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 1);
      final firstKey = repo.uploadArguments[0]['idempotencyKey'] as String?;

      // Usuário remove a foto atual e escolhe uma nova fotografia
      await tester.tap(find.byKey(const Key('manual_place_remove_photo_button')));
      await tester.pumpAndSettle();

      photoPicked = testPhotoFile2;
      await tester.tap(find.text('Galeria'));
      await tester.pumpAndSettle();

      // Sucesso no novo upload
      repo.uploadPhotoException = null;
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1, reason: 'Local original deve ser mantido');
      expect(repo.uploadCalls, 2);
      final secondKey = repo.uploadArguments[1]['idempotencyKey'] as String?;
      expect(secondKey, isNot(equals(firstKey)), reason: 'Uma nova fotografia deve gerar uma nova chave de idempotência');
    });

    testWidgets('4. Falha no upload e fechamento da modal retorna resultado com isPhotoPending: true', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERROR', 'Falha no storage');

      ManualPlaceRegistrationResult? result;

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                onPressed: () async {
                  result = await ManualPlaceRegistrationSheet.show(
                    context,
                    placeRepository: repo,
                    initialName: 'Café Teste',
                    onPickPhoto: (source) async => testPhotoFile1,
                  );
                },
                child: const Text('Abrir'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Abrir'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua das Palmeiras, 100',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      // Submissão falha no upload após criar local
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 1);

      // Clica no botão fechar da modal
      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      // Modal fechada retorna o resultado com isPhotoPending: true
      expect(result, isNotNull);
      expect(result!.isPhotoPending, isTrue);
      expect(result!.place.name, 'Café Teste');
      expect(result!.uploadIdempotencyKey, isNotNull);
    });

    testWidgets('5. PopScope no fechamento por back gesture retorna resultado com isPhotoPending: true', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERROR', 'Falha no storage');

      ManualPlaceRegistrationResult? result;

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                onPressed: () async {
                  result = await ManualPlaceRegistrationSheet.show(
                    context,
                    placeRepository: repo,
                    initialName: 'Café Teste',
                    onPickPhoto: (source) async => testPhotoFile1,
                  );
                },
                child: const Text('Abrir'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Abrir'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua das Palmeiras, 100',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      // Simula fechar tocando na barreira externa (ou back button Android)
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();

      expect(result, isNotNull);
      expect(result!.isPhotoPending, isTrue);
      expect(result!.place.id, 'place-uuid-1');
    });

    testWidgets('6. Reabrir o cadastro com o mesmo nome para um novo registro NÃO reutiliza o local já concluído', (tester) async {
      ManualPlaceRegistrationResult? firstResult;
      ManualPlaceRegistrationResult? secondResult;

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: Column(
                children: [
                  ElevatedButton(
                    key: const Key('btn_open_first'),
                    onPressed: () async {
                      firstResult = await ManualPlaceRegistrationSheet.show(
                        context,
                        placeRepository: repo,
                        initialName: 'Padaria Central',
                        onPickPhoto: (source) async => testPhotoFile1,
                      );
                    },
                    child: const Text('Primeira Abertura'),
                  ),
                  ElevatedButton(
                    key: const Key('btn_open_second'),
                    onPressed: () async {
                      secondResult = await ManualPlaceRegistrationSheet.show(
                        context,
                        placeRepository: repo,
                        initialName: 'Padaria Central',
                        onPickPhoto: (source) async => testPhotoFile2,
                      );
                    },
                    child: const Text('Segunda Abertura'),
                  ),
                ],
              ),
            ),
          ),
        ),
      );

      // 1. Cadastra e conclui o primeiro local
      await tester.tap(find.byKey(const Key('btn_open_first')));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua Direita, 10',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(firstResult, isNotNull);
      expect(firstResult!.isPhotoPending, isFalse);
      expect(firstResult!.place.id, 'place-uuid-1');

      // 2. Abre um novo cadastro com o mesmo nome em endereço diferente
      await tester.tap(find.byKey(const Key('btn_open_second')));
      await tester.pumpAndSettle();

      // O campo de endereço deve estar limpo (não herdou o anterior)
      expect(find.byKey(const Key('manual_place_partial_created_banner')), findsNothing);
      expect(tester.widget<TextFormField>(find.byKey(const Key('manual_place_address_field'))).controller?.text, isEmpty);

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Av. Paulista, 2000',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(secondResult, isNotNull);
      expect(secondResult!.isPhotoPending, isFalse);
      expect(secondResult!.place.id, 'place-uuid-2');
      expect(secondResult!.place.id, isNot(equals(firstResult!.place.id)), reason: 'Novo cadastro com mesmo nome deve gerar novo local autônomo');
      expect(repo.createPlaceCalls, 2);
    });

    testWidgets('7. Dois estabelecimentos com o mesmo nome e endereços distintos não compartilham estado', (tester) async {
      ManualPlaceRegistrationResult? resA;
      ManualPlaceRegistrationResult? resB;

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: Column(
                children: [
                  ElevatedButton(
                    key: const Key('btn_place_a'),
                    onPressed: () async {
                      resA = await ManualPlaceRegistrationSheet.show(
                        context,
                        placeRepository: repo,
                        initialName: 'Farmácia Popular',
                        onPickPhoto: (source) async => testPhotoFile1,
                      );
                    },
                    child: const Text('Farmácia A'),
                  ),
                  ElevatedButton(
                    key: const Key('btn_place_b'),
                    onPressed: () async {
                      resB = await ManualPlaceRegistrationSheet.show(
                        context,
                        placeRepository: repo,
                        initialName: 'Farmácia Popular',
                        onPickPhoto: (source) async => testPhotoFile2,
                      );
                    },
                    child: const Text('Farmácia B'),
                  ),
                ],
              ),
            ),
          ),
        ),
      );

      // Farmácia A (Rua Norte)
      await tester.tap(find.byKey(const Key('btn_place_a')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua Norte, 100');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      // Farmácia B (Rua Sul)
      await tester.tap(find.byKey(const Key('btn_place_b')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua Sul, 200');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(resA!.place.id, 'place-uuid-1');
      expect(resB!.place.id, 'place-uuid-2');
      expect(repo.uploadArguments[0]['placeId'], 'place-uuid-1');
      expect(repo.uploadArguments[1]['placeId'], 'place-uuid-2');
      expect(repo.uploadArguments[0]['placeId'], isNot(equals(repo.uploadArguments[1]['placeId'])));
    });

    testWidgets('8. SearchScreen preserva local pendente na falha e permite retomação sem 409', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha temporária');
      ManualPlaceRegistrationResult? completedResult;

      await tester.pumpWidget(
        MaterialApp(
          home: SearchScreen(
            searchRepository: _StubSearchRepository(),
            placeRepository: repo,
            onPickManualPlacePhoto: (source) async => testPhotoFile1,
            onManualPlaceRegistered: (result) {
              completedResult = result;
            },
          ),
        ),
      );

      // Busca um local inexistente
      await tester.enterText(find.byType(TextFormField), 'Livraria Central');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('search_register_manual_place_button')), findsOneWidget);
      await tester.tap(find.byKey(const Key('search_register_manual_place_button')));
      await tester.pumpAndSettle();

      // Preenche endereço e envia foto (falha no storage)
      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua das Letras, 50');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 1);

      // Fecha a sheet
      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      // SearchScreen exibe o banner de cadastro pendente
      expect(find.byKey(const Key('search_pending_registration_banner')), findsOneWidget);
      expect(find.text('Foto pendente: Livraria Central'), findsOneWidget);

      // Recupera o storage
      repo.uploadPhotoException = null;

      // Clica no botão Retomar do banner
      await tester.tap(find.byKey(const Key('search_resume_pending_place_button')));
      await tester.pumpAndSettle();

      // A sheet deve abrir no modo de retomada com banner informativo
      expect(find.byKey(const Key('manual_place_partial_created_banner')), findsOneWidget);
      expect(find.text('Reenviar Fotografia e Concluir'), findsOneWidget);

      // A fotografia original já vem preservada no estado pendente
      expect(find.byKey(const Key('manual_place_photo_preview')), findsOneWidget);

      // Conclui o envio
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      // CRÍTICO: Não chamou createPlace uma 2ª vez (evitou HTTP 409)
      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 2);

      expect(completedResult, isNotNull);
      expect(completedResult!.isPhotoPending, isFalse);
      expect(completedResult!.place.id, 'place-uuid-1');

      // Após sucesso, o banner na SearchScreen foi removido
      expect(find.byKey(const Key('search_pending_registration_banner')), findsNothing);
    });

    testWidgets('9. Logout e troca de conta no SearchScreen limpam o estado pendente', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha temporária');

      final authNotifier = _FakeAuthNotifier();
      // Inicializa como usuário A
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-a-123', handle: 'user_a', email: 'a@test.com', displayName: 'User A'),
          tokens: AuthTokens(accessToken: 'token-a', refreshToken: 'ref-a'),
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SearchScreen(
            searchRepository: _StubSearchRepository(),
            placeRepository: repo,
            authNotifier: authNotifier,
            pendingRegistrationStore: store,
            onPickManualPlacePhoto: (source) async => testPhotoFile1,
          ),
        ),
      );

      // Usuário A tenta registrar e o upload falha
      await tester.enterText(find.byType(TextFormField), 'Restaurante A');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('search_register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua A, 1');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      // Banner visível para o usuário A
      expect(find.byKey(const Key('search_pending_registration_banner')), findsOneWidget);

      // Simula logout da conta do usuário A
      authNotifier.emit(const Unauthenticated());
      await tester.pumpAndSettle();

      // O estado pendente foi limpo: banner não deve mais aparecer
      expect(find.byKey(const Key('search_pending_registration_banner')), findsNothing);

      // Simula login de outro usuário (Usuário B)
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-b-456', handle: 'user_b', email: 'b@test.com', displayName: 'User B'),
          tokens: AuthTokens(accessToken: 'token-b', refreshToken: 'ref-b'),
        ),
      );
      await tester.pumpAndSettle();

      // Usuário B não vê resíduo do usuário anterior
      expect(find.byKey(const Key('search_pending_registration_banner')), findsNothing);
    });

    testWidgets('10. A foto é sempre enviada com o placeId canônico do local criado', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                onPressed: () async {
                  await ManualPlaceRegistrationSheet.show(
                    context,
                    placeRepository: repo,
                    initialName: 'Sapataria Estrela',
                    onPickPhoto: (source) async => testPhotoFile1,
                  );
                },
                child: const Text('Abrir'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Abrir'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua dos Calçados, 88');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 1);
      expect(repo.uploadArguments.first['placeId'], 'place-uuid-1');
    });

    testWidgets('11. MB-1a: SearchScreen descarta resultado retornado se a sessão mudar para outro usuário durante o await da modal', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha temporária');
      final authNotifier = _FakeAuthNotifier();
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-a-111', handle: 'user_a', email: 'a@test.com', displayName: 'User A'),
          tokens: AuthTokens(accessToken: 'token-a', refreshToken: 'ref-a'),
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SearchScreen(
            searchRepository: _StubSearchRepository(),
            placeRepository: repo,
            authNotifier: authNotifier,
            pendingRegistrationStore: store,
            onPickManualPlacePhoto: (source) async => testPhotoFile1,
          ),
        ),
      );

      // Usuário A abre a modal para cadastrar 'Café do Ponto'
      await tester.enterText(find.byType(TextFormField), 'Café do Ponto');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('search_register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua das Flores, 10');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      // Durante a exibição da modal (antes do close ser consumido pelo caller), a autenticação muda para Usuário B
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-b-222', handle: 'user_b', email: 'b@test.com', displayName: 'User B'),
          tokens: AuthTokens(accessToken: 'token-b', refreshToken: 'ref-b'),
        ),
      );

      // Agora o usuário fecha a modal
      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      // MB-1a: Como a sessão mudou durante o await, o resultado não é aceito para a conta B
      expect(find.byKey(const Key('search_pending_registration_banner')), findsNothing);
    });

    testWidgets('12. MB-1b: TargetSearchSelectorWidget não injeta foto pendente de local anterior em novo cadastro', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha temporária');

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TargetSearchSelectorWidget(
              searchRepository: _StubSearchRepository(),
              placeRepository: repo,
              targetIndex: 0,
              onTargetSelected: (_) {},
              onPickManualPlacePhoto: (source) async => testPhotoFile1,
            ),
          ),
        ),
      );

      // 1. Cadastra 'Padaria Central' e upload falha -> pendência salva
      await tester.enterText(find.byType(TextFormField).first, 'Padaria Central');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Av. Central, 100');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      // Banner de pendência exibido para 'Padaria Central'
      expect(find.byKey(const Key('target_pending_registration_banner')), findsOneWidget);
      expect(find.text('Envio de fotografia pendente para "Padaria Central".'), findsOneWidget);

      // 2. Usuário pesquisa por outro estabelecimento: 'Livraria Moderna'
      await tester.enterText(find.byType(TextFormField).first, 'Livraria Moderna');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // 3. Usuário clica em cadastrar manualmente para o novo termo
      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      // MB-1b: A modal NÃO deve herdar 'Padaria Central' nem a foto pendente anterior!
      expect(find.descendant(of: find.byType(ManualPlaceRegistrationSheet), matching: find.text('Livraria Moderna')), findsOneWidget);
      expect(find.text('Padaria Central'), findsNothing);
      expect(find.byKey(const Key('manual_place_partial_created_banner')), findsNothing);
      expect(find.byKey(const Key('manual_place_photo_preview')), findsNothing);
    });

    testWidgets('13. MB-1b: TargetSearchSelectorWidget retoma pendência explicitamente via botão Retomar', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha temporária');

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TargetSearchSelectorWidget(
              searchRepository: _StubSearchRepository(),
              placeRepository: repo,
              targetIndex: 0,
              onTargetSelected: (_) {},
              onPickManualPlacePhoto: (source) async => testPhotoFile1,
            ),
          ),
        ),
      );

      // Cadastra 'Padaria Central' e upload falha
      await tester.enterText(find.byType(TextFormField).first, 'Padaria Central');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Av. Central, 100');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('target_pending_registration_banner')), findsOneWidget);

      // Recupera o storage
      repo.uploadPhotoException = null;

      // Clica explicitamente em Retomar no banner
      await tester.tap(find.byKey(const Key('target_resume_pending_registration_button')));
      await tester.pumpAndSettle();

      // Modal abre no modo de retomada da 'Padaria Central' com a foto original preservada
      expect(find.byKey(const Key('manual_place_partial_created_banner')), findsOneWidget);
      expect(find.text('Reenviar Fotografia e Concluir'), findsOneWidget);
      expect(find.byKey(const Key('manual_place_photo_preview')), findsOneWidget);

      // Conclui o envio
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      // Local não foi duplicado (idempotente) e banner foi limpo
      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 2);
      expect(find.byKey(const Key('target_pending_registration_banner')), findsNothing);
    });

    testWidgets('14. MB-1a: TargetSearchSelectorWidget limpa estado pendente no logout ou troca de conta', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha temporária');
      final authNotifier = _FakeAuthNotifier();
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-x-123', handle: 'user_x', email: 'x@test.com', displayName: 'User X'),
          tokens: AuthTokens(accessToken: 'token-x', refreshToken: 'ref-x'),
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TargetSearchSelectorWidget(
              searchRepository: _StubSearchRepository(),
              placeRepository: repo,
              targetIndex: 0,
              authNotifier: authNotifier,
              pendingRegistrationStore: store,
              onTargetSelected: (_) {},
              onPickManualPlacePhoto: (source) async => testPhotoFile1,
            ),
          ),
        ),
      );

      // Usuário X registra e falha upload
      await tester.enterText(find.byType(TextFormField).first, 'Loja X');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua X, 5');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('target_pending_registration_banner')), findsOneWidget);

      // Simula logout
      authNotifier.emit(const Unauthenticated());
      await tester.pumpAndSettle();

      // Banner não deve mais estar visível
      expect(find.byKey(const Key('target_pending_registration_banner')), findsNothing);
    });

    testWidgets('15. MB-1c: TargetItemInputWidget repassa authNotifier e currentUserId para TargetSearchSelectorWidget', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha no upload');
      final authNotifier = _FakeAuthNotifier();
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-caller-123', handle: 'caller', email: 'c@test.com', displayName: 'Caller'),
          tokens: AuthTokens(accessToken: 'token-c', refreshToken: 'ref-c'),
        ),
      );

      const targetInput = CreateReviewTargetInput(
        rateableTargetId: '',
        rating: 4.0,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: TargetItemInputWidget(
                index: 0,
                target: targetInput,
                canRemove: false,
                searchRepository: _StubSearchRepository(),
                placeRepository: repo,
                authNotifier: authNotifier,
                pendingRegistrationStore: store,
                currentUserId: 'user-caller-123',
                onTargetIdChanged: (_) {},
                onRatingChanged: (_) {},
                onSpecificCommentChanged: (_) {},
                onRemove: () {},
                onPickManualPlacePhoto: (source) async => testPhotoFile1,
              ),
            ),
          ),
        ),
      );

      // Inicia cadastro manual de local pelo caminho de TargetItemInputWidget
      await tester.enterText(find.byType(TextFormField).first, 'Mercado Caller');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua Caller, 10');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      // Banner é exibido e salvo na store indexado pelo usuário caller
      expect(find.byKey(const Key('target_pending_registration_banner')), findsOneWidget);
      final stored = (await store.loadLatest('user-caller-123'));
      expect(stored, isNotNull);
      expect(stored?.place.name, 'Mercado Caller');
    });

    testWidgets('16. MB-1d: Troca de conta impede que usuário B veja ou retome a operação de A', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha no upload');
      final authNotifier = _FakeAuthNotifier();
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-a-1', handle: 'user_a', email: 'a@test.com', displayName: 'User A'),
          tokens: AuthTokens(accessToken: 'token-a', refreshToken: 'ref-a'),
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TargetSearchSelectorWidget(
              searchRepository: _StubSearchRepository(),
              placeRepository: repo,
              targetIndex: 0,
              authNotifier: authNotifier,
              pendingRegistrationStore: store,
              onTargetSelected: (_) {},
              onPickManualPlacePhoto: (source) async => testPhotoFile1,
            ),
          ),
        ),
      );

      // Usuário A cadastra e upload falha
      await tester.enterText(find.byType(TextFormField).first, 'Café de A');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua A, 1');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('target_pending_registration_banner')), findsOneWidget);

      // Usuário B faz login
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-b-2', handle: 'user_b', email: 'b@test.com', displayName: 'User B'),
          tokens: AuthTokens(accessToken: 'token-b', refreshToken: 'ref-b'),
        ),
      );
      await tester.pumpAndSettle();

      // Usuário B NÃO vê banner de pendência do usuário A
      expect(find.byKey(const Key('target_pending_registration_banner')), findsNothing);
      expect((await store.loadLatest('user-b-2')), isNull);
      expect((await store.loadLatest('user-a-1')), isNotNull);
    });

    testWidgets('17. MB-1d: Usuário A ao retornar recupera a operação com mesmo local e mesmo UUID de upload', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha no upload');
      final authNotifier = _FakeAuthNotifier();
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-a-persisted', handle: 'user_a', email: 'a@test.com', displayName: 'User A'),
          tokens: AuthTokens(accessToken: 'token-a', refreshToken: 'ref-a'),
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TargetSearchSelectorWidget(
              searchRepository: _StubSearchRepository(),
              placeRepository: repo,
              targetIndex: 0,
              authNotifier: authNotifier,
              pendingRegistrationStore: store,
              onTargetSelected: (_) {},
              onPickManualPlacePhoto: (source) async => testPhotoFile1,
            ),
          ),
        ),
      );

      await tester.enterText(find.byType(TextFormField).first, 'Pizzaria A');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua A, 2');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      final firstPending = (await store.loadLatest('user-a-persisted'));
      final originalIdempotencyKey = firstPending?.uploadIdempotencyKey;
      expect(originalIdempotencyKey, isNotNull);

      // Simula usuário saindo da tela (desmontando widget) e voltando a montar
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TargetSearchSelectorWidget(
              searchRepository: _StubSearchRepository(),
              placeRepository: repo,
              targetIndex: 0,
              authNotifier: authNotifier,
              pendingRegistrationStore: store,
              onTargetSelected: (_) {},
              onPickManualPlacePhoto: (source) async => testPhotoFile1,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Banner é automaticamente restaurado para o usuário A
      expect(find.byKey(const Key('target_pending_registration_banner')), findsOneWidget);
      expect(find.byKey(const Key('target_resume_pending_registration_button')), findsOneWidget);

      // Clica em retomar e resolve o upload com sucesso
      repo.uploadPhotoException = null;
      await tester.tap(find.byKey(const Key('target_resume_pending_registration_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      // Local criado não foi duplicado (apenas 1 criação) e a chave UUID original foi preservada
      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadArguments.last['idempotencyKey'], originalIdempotencyKey);
      expect((await store.loadLatest('user-a-persisted')), isNull);
    });

    testWidgets('18. MB-1d: Fotografia local excluída gera aviso explicito e permite nova foto sem duplicar local', (tester) async {
      repo.uploadPhotoException = _makeApiException(500, 'STORAGE_ERR', 'Falha no upload');
      final authNotifier = _FakeAuthNotifier();
      authNotifier.emit(
        const Authenticated(
          user: AuthUserDto(id: 'user-missing-file', handle: 'missing_file', email: 'm@test.com', displayName: 'M'),
          tokens: AuthTokens(accessToken: 'token-m', refreshToken: 'ref-m'),
        ),
      );

      // Cria um arquivo temporário exclusivo que será deletado
      final fileToBeDeleted = File('${tempDir.path}/temp_deleted_photo.jpg');
      fileToBeDeleted.writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0]));

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TargetSearchSelectorWidget(
              searchRepository: _StubSearchRepository(),
              placeRepository: repo,
              targetIndex: 0,
              authNotifier: authNotifier,
              pendingRegistrationStore: store,
              onTargetSelected: (_) {},
              onPickManualPlacePhoto: (source) async => fileToBeDeleted,
            ),
          ),
        ),
      );

      await tester.enterText(find.byType(TextFormField).first, 'Bistrô Ficheiro');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('register_manual_place_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua Ficheiro, 1');
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_place_close_button')));
      await tester.pumpAndSettle();

      // Deleta fisicamente o arquivo do disco para simular limpeza de cache do OS
      fileToBeDeleted.deleteSync();

      // Clica em retomar
      await tester.tap(find.byKey(const Key('target_resume_pending_registration_button')));
      await tester.pumpAndSettle();

      // Permite selecionar nova foto mantendo o local já criado mas sem a foto anterior apagada
      expect(find.text('Bistrô Ficheiro'), findsWidgets);
      expect(find.byKey(const Key('manual_place_partial_created_banner')), findsOneWidget);
      expect(find.byKey(const Key('manual_place_photo_preview')), findsNothing);
    });
  });
}
