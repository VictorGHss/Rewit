import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/manual_place_registration_sheet.dart';

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

  group('ManualPlaceRegistrationSheet - Idempotência e Retries de Fotos (H-4)', () {
    late _RecordingPlaceRepository repo;
    late Directory tempDir;
    late File testPhotoFile1;

    setUp(() {
      repo = _RecordingPlaceRepository();
      tempDir = Directory.systemTemp.createTempSync('rewit_test_');
      testPhotoFile1 = File('${tempDir.path}/mock_photo_1.jpg');
      testPhotoFile1.writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46]));
    });

    tearDown(() {
      try {
        tempDir.deleteSync(recursive: true);
      } catch (_) {}
    });

    Widget buildSheet({
      required PlaceRepository repository,
      File? initialPhoto,
    }) {
      File? photoToPick = initialPhoto ?? testPhotoFile1;
      return MaterialApp(
        home: Scaffold(
          body: ManualPlaceRegistrationSheet(
            placeRepository: repository,
            initialName: 'Café Teste',
            initialLatitude: -25.42,
            initialLongitude: -49.27,
            onPickPhoto: (source) async => photoToPick,
          ),
        ),
      );
    }

    testWidgets('1. Uma nova operação lógica de upload recebe uma chave de idempotência', (tester) async {
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
      expect(key.startsWith('m-'), isTrue);
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

    testWidgets('3 e 4. Remover e trocar a fotografia limpa a operação e permite nova chave', (tester) async {
      await tester.pumpWidget(buildSheet(repository: repo));

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua das Palmeiras, 100',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('manual_place_photo_preview')), findsOneWidget);

      // Usuário remove a fotografia atual
      await tester.tap(find.byKey(const Key('manual_place_remove_photo_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('manual_place_photo_preview')), findsNothing);
    });

    testWidgets('5 e 6. Se o local já foi criado e o upload falhar, o retry não recria o local e não reinicia a chave', (tester) async {
      repo.uploadPhotoException = _makeApiException(504, 'TIMEOUT', 'Timeout no storage');

      await tester.pumpWidget(buildSheet(repository: repo));

      await tester.enterText(
        find.byKey(const Key('manual_place_address_field')),
        'Rua das Palmeiras, 100',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      // Primeira tentativa falha no upload
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1);
      expect(repo.uploadCalls, 1);
      final firstKey = repo.uploadArguments[0]['idempotencyKey'] as String?;

      // Interface exibe aviso de que o local foi criado mas a foto falhou
      expect(find.byKey(const Key('manual_place_error_banner')), findsOneWidget);
      expect(find.text('Reenviar Fotografia e Concluir'), findsOneWidget);

      // Segunda tentativa de envio
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(repo.createPlaceCalls, 1, reason: 'Não deve criar outro local no retry');
      expect(repo.uploadCalls, 2);
      expect(repo.uploadArguments[1]['idempotencyKey'], equals(firstKey));
    });

    testWidgets('7. O resultado de um retry idempotente atualiza o estado da interface coerentemente', (tester) async {
      repo.uploadPhotoException = _makeApiException(503, 'TEMPORARY_ERROR', 'Tente novamente');

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
                    initialName: 'Pastelaria Central',
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
        'Av. Central, 50',
      );
      await tester.tap(find.text('Câmera'));
      await tester.pumpAndSettle();

      // Primeira tentativa (falha)
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      expect(result, isNull);
      expect(find.text('Reenviar Fotografia e Concluir'), findsOneWidget);

      // Sucesso na segunda tentativa
      repo.uploadPhotoException = null;
      await tester.tap(find.byKey(const Key('manual_place_submit_button')));
      await tester.pumpAndSettle();

      // Sheet fechou e retornou resultado completo com photoUrl
      expect(result, isNotNull);
      expect(result!.place.photoUrl, contains('/media/photo-2'));
      expect(result!.place.name, 'Pastelaria Central');
    });
  });
}

