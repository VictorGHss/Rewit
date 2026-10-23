import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_media_gallery_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';

class _FakePlaceRepository implements PlaceRepository {
  List<PlaceMediaItem> mediaToReturn = [];
  Exception? errorToThrow;
  Exception? uploadErrorToThrow;
  PlaceMediaItem? uploadItemToReturn;

  bool wasSetPrimaryCalled = false;
  bool wasDeleteCalled = false;
  bool wasUploadCalled = false;
  int uploadCallCount = 0;
  String? lastUploadIdempotencyKey;

  @override
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId) async {
    if (errorToThrow != null) throw errorToThrow!;
    return List.of(mediaToReturn);
  }

  @override
  Future<PlaceMediaItem> setPrimaryPlaceMedia({
    required String placeId,
    required String mediaId,
  }) async {
    wasSetPrimaryCalled = true;
    if (errorToThrow != null) throw errorToThrow!;
    final item = mediaToReturn.firstWhere((m) => m.id == mediaId);
    return item.copyWith(isPrimary: true);
  }

  @override
  Future<void> deletePlaceMedia({
    required String placeId,
    required String mediaId,
  }) async {
    wasDeleteCalled = true;
    if (errorToThrow != null) throw errorToThrow!;
    final wasPrimary = mediaToReturn.any((m) => m.id == mediaId && m.isPrimary);
    mediaToReturn.removeWhere((m) => m.id == mediaId);
    if (wasPrimary && mediaToReturn.isNotEmpty) {
      mediaToReturn[0] = mediaToReturn[0].copyWith(isPrimary: true);
    }
  }

  @override
  Future<PlaceMediaItem> uploadPlaceMedia({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? mimeType,
    String? idempotencyKey,
  }) async {
    wasUploadCalled = true;
    uploadCallCount++;
    lastUploadIdempotencyKey = idempotencyKey;

    if (uploadErrorToThrow != null) throw uploadErrorToThrow!;
    if (errorToThrow != null) throw errorToThrow!;

    if (uploadItemToReturn != null) {
      return uploadItemToReturn!;
    }

    final created = PlaceMediaItem(
      id: 'media-uploaded-1',
      placeId: placeId,
      url: '/api/v1/places/$placeId/media/media-uploaded-1',
      mediaType: 'PHOTO',
      mimeType: mimeType ?? 'image/jpeg',
      sizeBytes: fileBytes.lengthInBytes,
      status: 'ACTIVE',
      isPrimary: mediaToReturn.isEmpty,
      createdAt: DateTime.now(),
    );
    mediaToReturn.add(created);
    return created;
  }

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) => throw UnimplementedError();

  @override
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? idempotencyKey,
  }) =>
      throw UnimplementedError();

  @override
  Future<PlaceDetail> getPlaceById(String id) => throw UnimplementedError();

  @override
  Future<TargetStats> getTargetStats(String id) => throw UnimplementedError();

  @override
  Future<TargetReviewsPage> getTargetReviews(String targetId, {int page = 0, int size = 10, String sort = 'newest', bool verifiedOnly = false}) =>
      throw UnimplementedError();
}

class _FakeReviewMediaRepository implements ReviewMediaRepository {
  static final Uint8List _valid1x1PngBytes = Uint8List.fromList([
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR chunk
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // 1x1
    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, // 8-bit RGBA
    0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, // IDAT chunk
    0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
    0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, // IEND chunk
    0x42, 0x60, 0x82,
  ]);

  @override
  Future<Uint8List> getMediaBytes(String pathOrUrl) async {
    return _valid1x1PngBytes;
  }

  @override
  Future<void> deleteMedia({required String reviewId, required String mediaId}) => throw UnimplementedError();

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) => throw UnimplementedError();

  @override
  Future<ReviewMediaItem> uploadMedia({required String reviewId, required Uint8List bytes, required String filename, String? mimeType}) =>
      throw UnimplementedError();
}

void main() {
  const args = PlaceMediaGalleryArgs(
    placeId: 'place-test-1',
    businessAccountId: 'acc-test-1',
    placeName: 'Restaurante Central',
  );

  group('PlaceMediaGalleryScreen Widget Tests', () {
    late _FakePlaceRepository repository;
    late _FakeReviewMediaRepository reviewMediaRepo;

    setUp(() {
      repository = _FakePlaceRepository();
      reviewMediaRepo = _FakeReviewMediaRepository();
    });

    testWidgets('exibe estado vazio amigável quando não há fotografias', (tester) async {
      repository.mediaToReturn = [];

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Nenhuma fotografia cadastrada'), findsOneWidget);
      expect(find.byKey(const Key('empty_add_photo_button')), findsOneWidget);
      expect(find.byKey(const Key('add_photo_fab')), findsOneWidget);
    });

    testWidgets('exibe grade de fotos destacando a foto de capa (primária)', (tester) async {
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'photo-1',
          placeId: 'place-test-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: DateTime.now(),
        ),
        PlaceMediaItem(
          id: 'photo-2',
          placeId: 'place-test-1',
          url: '/url-2',
          mediaType: 'PHOTO',
          mimeType: 'image/png',
          sizeBytes: 2048,
          status: 'ACTIVE',
          isPrimary: false,
          createdAt: DateTime.now(),
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Fotos ativas (2 de 5)'), findsOneWidget);
      expect(find.byKey(const Key('primary_badge_photo-1')), findsOneWidget);
      expect(find.byKey(const Key('primary_badge_photo-2')), findsNothing);
      expect(find.byKey(const Key('set_primary_button_photo-2')), findsOneWidget);
    });

    testWidgets('toque em "Capa" define a fotografia indicada como principal', (tester) async {
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'photo-1',
          placeId: 'place-test-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: DateTime.now(),
        ),
        PlaceMediaItem(
          id: 'photo-2',
          placeId: 'place-test-1',
          url: '/url-2',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: false,
          createdAt: DateTime.now(),
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('set_primary_button_photo-2')));
      await tester.pumpAndSettle();

      expect(repository.wasSetPrimaryCalled, isTrue);
      expect(find.text('Foto de capa atualizada com sucesso!'), findsOneWidget);
      expect(find.byKey(const Key('primary_badge_photo-2')), findsOneWidget);
    });

    testWidgets('exclusão exige confirmação em diálogo antes de chamar o backend', (tester) async {
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'photo-1',
          placeId: 'place-test-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: DateTime.now(),
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Clica em excluir
      await tester.tap(find.byKey(const Key('delete_media_button_photo-1')));
      await tester.pumpAndSettle();

      expect(find.text('Excluir fotografia'), findsOneWidget);
      expect(find.byKey(const Key('cancel_delete_button')), findsOneWidget);

      // Cancela
      await tester.tap(find.byKey(const Key('cancel_delete_button')));
      await tester.pumpAndSettle();
      expect(repository.wasDeleteCalled, isFalse);

      // Abre diálogo e confirma
      await tester.tap(find.byKey(const Key('delete_media_button_photo-1')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('confirm_delete_button_photo-1')));
      await tester.pumpAndSettle();

      expect(repository.wasDeleteCalled, isTrue);
      expect(find.text('Fotografia removida com sucesso!'), findsOneWidget);
      expect(find.text('Nenhuma fotografia cadastrada'), findsOneWidget);
    });

    testWidgets('Correção A: excluir foto principal com múltiplas fotos promove a próxima como capa na UI', (tester) async {
      final t1 = DateTime(2026, 10, 20, 10, 0);
      final t2 = DateTime(2026, 10, 21, 10, 0);

      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'photo-1',
          placeId: 'place-test-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: t1,
        ),
        PlaceMediaItem(
          id: 'photo-2',
          placeId: 'place-test-1',
          url: '/url-2',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: false,
          createdAt: t2,
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('primary_badge_photo-1')), findsOneWidget);

      // Exclui photo-1 (capa)
      await tester.tap(find.byKey(const Key('delete_media_button_photo-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm_delete_button_photo-1')));
      await tester.pumpAndSettle();

      expect(repository.wasDeleteCalled, isTrue);
      expect(find.text('Fotografia removida com sucesso!'), findsOneWidget);
      // photo-2 agora é a capa visual!
      expect(find.byKey(const Key('primary_badge_photo-2')), findsOneWidget);
    });

    testWidgets('Correção B: falha de upload exibe banner e permite tentar novamente com sucesso', (tester) async {
      repository.mediaToReturn = [];
      repository.uploadErrorToThrow = Exception('Network error');

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
            onPickPhoto: (source) async => SelectedMediaItem(
              id: 'item-1',
              name: 'fachada.jpg',
              sizeBytes: 1024,
              mimeType: 'image/jpeg',
              bytes: Uint8List.fromList([1, 2, 3]),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Inicia upload
      await tester.tap(find.byKey(const Key('add_photo_fab')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('pick_gallery_option')));
      await tester.pumpAndSettle();

      expect(repository.uploadCallCount, 1);
      final firstKey = repository.lastUploadIdempotencyKey;
      expect(find.byKey(const Key('pending_upload_banner')), findsOneWidget);
      expect(find.textContaining('fachada.jpg'), findsWidgets);

      // Recupera rede e tenta novamente
      repository.uploadErrorToThrow = null;
      await tester.tap(find.byKey(const Key('retry_upload_button')));
      await tester.pumpAndSettle();

      expect(repository.uploadCallCount, 2);
      expect(repository.lastUploadIdempotencyKey, firstKey); // Mesma chave!
      expect(find.byKey(const Key('pending_upload_banner')), findsNothing);
      expect(find.text('Fotografia adicionada com sucesso!'), findsOneWidget);
    });

    testWidgets('Correção B: descarte do banner limpa operação pendente', (tester) async {
      repository.mediaToReturn = [];
      repository.uploadErrorToThrow = Exception('Network error');

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
            onPickPhoto: (source) async => SelectedMediaItem(
              id: 'item-1',
              name: 'fachada.jpg',
              sizeBytes: 1024,
              mimeType: 'image/jpeg',
              bytes: Uint8List.fromList([1, 2, 3]),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('add_photo_fab')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('pick_gallery_option')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('pending_upload_banner')), findsOneWidget);

      // Descarta
      await tester.tap(find.byKey(const Key('cancel_retry_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('pending_upload_banner')), findsNothing);
    });

    testWidgets('Correção C: resposta de replay com status REMOVED não entra na galeria', (tester) async {
      repository.mediaToReturn = [];
      repository.uploadItemToReturn = PlaceMediaItem(
        id: 'media-removed-1',
        placeId: 'place-test-1',
        url: '/url-removed',
        mediaType: 'PHOTO',
        mimeType: 'image/jpeg',
        sizeBytes: 1024,
        status: 'REMOVED',
        isPrimary: false,
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
            onPickPhoto: (source) async => SelectedMediaItem(
              id: 'item-1',
              name: 'foto.jpg',
              sizeBytes: 1024,
              mimeType: 'image/jpeg',
              bytes: Uint8List.fromList([1, 2, 3]),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('add_photo_fab')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('pick_gallery_option')));
      await tester.pumpAndSettle();

      expect(find.text('Nenhuma fotografia cadastrada'), findsOneWidget);
      expect(find.textContaining('não está mais ativa'), findsOneWidget);
      expect(find.byKey(const Key('pending_upload_banner')), findsNothing);
    });

    testWidgets('adiciona foto utilizando seletor e realiza upload com sucesso', (tester) async {
      repository.mediaToReturn = [];

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
            onPickPhoto: (source) async => SelectedMediaItem(
              id: 'item-1',
              name: 'fachada_restaurante.jpg',
              sizeBytes: 2048,
              mimeType: 'image/jpeg',
              bytes: Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0]),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Abre opções de fonte
      await tester.tap(find.byKey(const Key('add_photo_fab')));
      await tester.pumpAndSettle();

      expect(find.text('Adicionar Fotografia'), findsOneWidget);
      expect(find.byKey(const Key('pick_gallery_option')), findsOneWidget);

      // Escolhe galeria
      await tester.tap(find.byKey(const Key('pick_gallery_option')));
      await tester.pumpAndSettle();

      expect(repository.wasUploadCalled, isTrue);
      expect(find.text('Fotografia adicionada com sucesso!'), findsOneWidget);
      expect(find.text('Fotos ativas (1 de 5)'), findsOneWidget);
    });

    testWidgets('quando limite de 5 fotos é atingido, exibe aviso e bloqueia novo envio', (tester) async {
      repository.mediaToReturn = List.generate(
        5,
        (i) => PlaceMediaItem(
          id: 'photo-$i',
          placeId: 'place-test-1',
          url: '/url-$i',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: i == 0,
          createdAt: DateTime.now(),
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Fotos ativas (5 de 5)'), findsOneWidget);
      expect(find.text('Limite atingido'), findsOneWidget);

      await tester.tap(find.byKey(const Key('add_photo_fab')));
      await tester.pumpAndSettle();

      expect(find.text('Limite de 5 fotografias já foi atingido.'), findsOneWidget);
      expect(find.text('Adicionar Fotografia'), findsNothing); // Modal não abriu
    });

    testWidgets('falha no carregamento exibe ErrorView e permite tentar novamente', (tester) async {
      repository.errorToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Erro de Rede',
          status: 503,
          code: 'UNAVAILABLE',
          detail: 'Servidor temporariamente indisponível',
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceMediaGalleryScreen(
            args: args,
            placeRepository: repository,
            reviewMediaRepository: reviewMediaRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Erro ao carregar fotografias'), findsOneWidget);
      expect(find.text('Servidor temporariamente indisponível'), findsOneWidget);

      // Corrige erro e tenta novamente
      repository.errorToThrow = null;
      repository.mediaToReturn = [];

      await tester.tap(find.text('Tentar novamente'));
      await tester.pumpAndSettle();

      expect(find.text('Nenhuma fotografia cadastrada'), findsOneWidget);
    });
  });
}
