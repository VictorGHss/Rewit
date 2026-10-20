import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
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

    setUp(() {
      repo = _RecordingPlaceRepository();
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
  });
}
