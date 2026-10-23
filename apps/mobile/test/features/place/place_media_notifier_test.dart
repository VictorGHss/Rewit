import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_media_notifier.dart';

class _FakePlaceMediaRepository implements PlaceRepository {
  List<PlaceMediaItem> mediaToReturn = [];
  Exception? errorToThrow;

  String? lastGetPlaceId;
  String? lastPrimaryPlaceId;
  String? lastPrimaryMediaId;
  String? lastDeletePlaceId;
  String? lastDeleteMediaId;
  String? lastUploadPlaceId;
  Uint8List? lastUploadBytes;
  String? lastUploadFilename;
  String? lastUploadMimeType;
  String? lastUploadIdempotencyKey;

  @override
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId) async {
    lastGetPlaceId = placeId;
    if (errorToThrow != null) throw errorToThrow!;
    return mediaToReturn;
  }

  @override
  Future<PlaceMediaItem> setPrimaryPlaceMedia({
    required String placeId,
    required String mediaId,
  }) async {
    lastPrimaryPlaceId = placeId;
    lastPrimaryMediaId = mediaId;
    if (errorToThrow != null) throw errorToThrow!;
    final existing = mediaToReturn.firstWhere((m) => m.id == mediaId);
    return existing.copyWith(isPrimary: true);
  }

  @override
  Future<void> deletePlaceMedia({
    required String placeId,
    required String mediaId,
  }) async {
    lastDeletePlaceId = placeId;
    lastDeleteMediaId = mediaId;
    if (errorToThrow != null) throw errorToThrow!;
    mediaToReturn.removeWhere((m) => m.id == mediaId);
  }

  @override
  Future<PlaceMediaItem> uploadPlaceMedia({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? mimeType,
    String? idempotencyKey,
  }) async {
    lastUploadPlaceId = placeId;
    lastUploadBytes = fileBytes;
    lastUploadFilename = filename;
    lastUploadMimeType = mimeType;
    lastUploadIdempotencyKey = idempotencyKey;
    if (errorToThrow != null) throw errorToThrow!;

    return PlaceMediaItem(
      id: 'media-new-99',
      placeId: placeId,
      url: '/api/v1/places/$placeId/media/media-new-99',
      mediaType: 'PHOTO',
      mimeType: mimeType ?? 'image/jpeg',
      sizeBytes: fileBytes.lengthInBytes,
      status: 'ACTIVE',
      isPrimary: mediaToReturn.isEmpty,
      createdAt: DateTime.now(),
    );
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

void main() {
  group('PlaceMediaNotifier', () {
    late _FakePlaceMediaRepository repository;
    late PlaceMediaNotifier notifier;

    setUp(() {
      repository = _FakePlaceMediaRepository();
      notifier = PlaceMediaNotifier(
        repository: repository,
        uuidGenerator: () => 'fixed-test-uuid-v4',
      );
    });

    test('carrega fotografias ativas do local com sucesso', () async {
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'media-1',
          placeId: 'place-1',
          url: '/api/v1/places/place-1/media/media-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: DateTime.now(),
        ),
        PlaceMediaItem(
          id: 'media-2',
          placeId: 'place-1',
          url: '/api/v1/places/place-1/media/media-2',
          mediaType: 'PHOTO',
          mimeType: 'image/png',
          sizeBytes: 2048,
          status: 'ACTIVE',
          isPrimary: false,
          createdAt: DateTime.now(),
        ),
      ];

      await notifier.loadMedia('place-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.errorMessage, isNull);
      expect(notifier.mediaList.length, 2);
      expect(notifier.primaryMedia?.id, 'media-1');
      expect(repository.lastGetPlaceId, 'place-1');
    });

    test('trata falha no carregamento sem prender o estado', () async {
      repository.errorToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Erro',
          status: 500,
          code: 'SERVER_ERROR',
          detail: 'Falha interna ao buscar mídias',
        ),
      );

      await notifier.loadMedia('place-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.errorMessage, 'Falha interna ao buscar mídias');
      expect(notifier.mediaList, isEmpty);

      // Nova tentativa após limpar erro
      repository.errorToThrow = null;
      repository.mediaToReturn = [];
      await notifier.loadMedia('place-1');

      expect(notifier.errorMessage, isNull);
      expect(notifier.isLoading, isFalse);
    });

    test('define fotografia como principal e atualiza a capa visual', () async {
      notifier = PlaceMediaNotifier(repository: repository);
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'media-1',
          placeId: 'place-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: DateTime.now(),
        ),
        PlaceMediaItem(
          id: 'media-2',
          placeId: 'place-1',
          url: '/url-2',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: false,
          createdAt: DateTime.now(),
        ),
      ];
      await notifier.loadMedia('place-1');

      final success = await notifier.setPrimaryMedia(placeId: 'place-1', mediaId: 'media-2');

      expect(success, isTrue);
      expect(repository.lastPrimaryPlaceId, 'place-1');
      expect(repository.lastPrimaryMediaId, 'media-2');
      expect(notifier.primaryMedia?.id, 'media-2');
      expect(notifier.mediaList.firstWhere((m) => m.id == 'media-1').isPrimary, isFalse);
      expect(notifier.mediaList.firstWhere((m) => m.id == 'media-2').isPrimary, isTrue);
      expect(notifier.successMessage, 'Foto de capa atualizada com sucesso!');
      expect(notifier.operatingMediaId, isNull);
    });

    test('exclui fotografia com sucesso apenas após confirmação do backend', () async {
      notifier = PlaceMediaNotifier(repository: repository);
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'media-1',
          placeId: 'place-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: DateTime.now(),
        ),
      ];
      await notifier.loadMedia('place-1');

      final success = await notifier.deleteMedia(placeId: 'place-1', mediaId: 'media-1');

      expect(success, isTrue);
      expect(repository.lastDeletePlaceId, 'place-1');
      expect(repository.lastDeleteMediaId, 'media-1');
      expect(notifier.mediaList, isEmpty);
      expect(notifier.successMessage, 'Fotografia removida com sucesso!');
      expect(notifier.operatingMediaId, isNull);
    });

    test('falha na exclusão não remove a foto do estado local', () async {
      notifier = PlaceMediaNotifier(repository: repository);
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'media-1',
          placeId: 'place-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: true,
          createdAt: DateTime.now(),
        ),
      ];
      await notifier.loadMedia('place-1');

      repository.errorToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Proibido',
          status: 403,
          code: 'FORBIDDEN',
          detail: 'Você não tem permissão para remover esta foto',
        ),
      );

      final success = await notifier.deleteMedia(placeId: 'place-1', mediaId: 'media-1');

      expect(success, isFalse);
      expect(notifier.mediaList.length, 1);
      expect(notifier.errorMessage, 'Você não tem permissão para remover esta foto');
      expect(notifier.operatingMediaId, isNull);
    });

    test('upload envia Idempotency-Key UUID v4 e atualiza a lista', () async {
      notifier = PlaceMediaNotifier(
        repository: repository,
        uuidGenerator: () => 'custom-uuid-key-v4',
      );
      repository.mediaToReturn = [];
      await notifier.loadMedia('place-1');

      final fakeBytes = Uint8List.fromList([0xFF, 0xD8, 0xFF, 0x00]);
      final success = await notifier.uploadMedia(
        placeId: 'place-1',
        bytes: fakeBytes,
        filename: 'foto_fachada.jpg',
        mimeType: 'image/jpeg',
      );

      expect(success, isTrue);
      expect(repository.lastUploadPlaceId, 'place-1');
      expect(repository.lastUploadBytes, fakeBytes);
      expect(repository.lastUploadFilename, 'foto_fachada.jpg');
      expect(repository.lastUploadMimeType, 'image/jpeg');
      expect(repository.lastUploadIdempotencyKey, 'custom-uuid-key-v4');
      expect(notifier.mediaList.length, 1);
      expect(notifier.isUploading, isFalse);
      expect(notifier.successMessage, 'Fotografia adicionada com sucesso!');
    });

    test('impede upload quando limite de 5 fotografias é atingido', () async {
      notifier = PlaceMediaNotifier(repository: repository);
      repository.mediaToReturn = List.generate(
        5,
        (i) => PlaceMediaItem(
          id: 'media-$i',
          placeId: 'place-1',
          url: '/url-$i',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: i == 0,
          createdAt: DateTime.now(),
        ),
      );
      await notifier.loadMedia('place-1');

      expect(notifier.isLimitReached, isTrue);

      final success = await notifier.uploadMedia(
        placeId: 'place-1',
        bytes: Uint8List(100),
        filename: 'foto_extra.jpg',
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, contains('Limite máximo de 5 fotografias'));
      expect(repository.lastUploadPlaceId, isNull); // Nenhuma requisição enviada
    });

    test('impede upload quando arquivo excede limite de 10 MB', () async {
      notifier = PlaceMediaNotifier(repository: repository);
      repository.mediaToReturn = [];
      await notifier.loadMedia('place-1');

      // 10 MB + 1 byte
      final oversizedBytes = Uint8List((10 * 1024 * 1024) + 1);

      final success = await notifier.uploadMedia(
        placeId: 'place-1',
        bytes: oversizedBytes,
        filename: 'foto_gigante.jpg',
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, contains('excede o limite máximo permitido de 10 MB'));
      expect(repository.lastUploadPlaceId, isNull);
    });

    test('impede operações simultâneas conflitantes na mesma fotografia', () async {
      notifier = PlaceMediaNotifier(repository: repository);
      repository.mediaToReturn = [
        PlaceMediaItem(
          id: 'media-1',
          placeId: 'place-1',
          url: '/url-1',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'ACTIVE',
          isPrimary: false,
          createdAt: DateTime.now(),
        ),
      ];
      await notifier.loadMedia('place-1');

      // Dispara primeira operação
      final op1 = notifier.setPrimaryMedia(placeId: 'place-1', mediaId: 'media-1');
      // Segunda operação simultânea enquanto op1 está em curso
      final op2 = notifier.deleteMedia(placeId: 'place-1', mediaId: 'media-1');

      final r2 = await op2;
      final r1 = await op1;

      expect(r1, isTrue);
      expect(r2, isFalse); // Rejeitada pois já havia operação em andamento
    });
  });
}

