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
  Exception? getMediaErrorToThrow;
  PlaceMediaItem? uploadItemToReturn;

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
  int uploadCallCount = 0;

  @override
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId) async {
    lastGetPlaceId = placeId;
    if (getMediaErrorToThrow != null) throw getMediaErrorToThrow!;
    if (errorToThrow != null) throw errorToThrow!;
    return List.of(mediaToReturn);
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
    uploadCallCount++;
    lastUploadPlaceId = placeId;
    lastUploadBytes = fileBytes;
    lastUploadFilename = filename;
    lastUploadMimeType = mimeType;
    lastUploadIdempotencyKey = idempotencyKey;
    if (errorToThrow != null) throw errorToThrow!;

    if (uploadItemToReturn != null) {
      return uploadItemToReturn!;
    }

    final created = PlaceMediaItem(
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

void main() {
  group('PlaceMediaNotifier', () {
    late _FakePlaceMediaRepository repository;
    late PlaceMediaNotifier notifier;
    int keyCounter = 0;

    setUp(() {
      repository = _FakePlaceMediaRepository();
      keyCounter = 0;
      notifier = PlaceMediaNotifier(
        repository: repository,
        uuidGenerator: () {
          keyCounter++;
          return 'generated-uuid-key-$keyCounter';
        },
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

    group('Correção A: Exclusão da foto principal e consistência da capa', () {
      test('1. Excluir a foto principal quando há outras fotografias ativas promove a mais antiga', () async {
        final t1 = DateTime(2026, 10, 20, 10, 0);
        final t2 = DateTime(2026, 10, 21, 10, 0);

        repository.mediaToReturn = [
          PlaceMediaItem(
            id: 'media-capa-velha',
            placeId: 'place-1',
            url: '/url-capa',
            mediaType: 'PHOTO',
            mimeType: 'image/jpeg',
            sizeBytes: 1024,
            status: 'ACTIVE',
            isPrimary: true,
            createdAt: t1,
          ),
          PlaceMediaItem(
            id: 'media-restante',
            placeId: 'place-1',
            url: '/url-restante',
            mediaType: 'PHOTO',
            mimeType: 'image/jpeg',
            sizeBytes: 1024,
            status: 'ACTIVE',
            isPrimary: false,
            createdAt: t2,
          ),
        ];
        await notifier.loadMedia('place-1');

        final success = await notifier.deleteMedia(placeId: 'place-1', mediaId: 'media-capa-velha');

        expect(success, isTrue);
        expect(notifier.mediaList.length, 1);
        expect(notifier.mediaList.first.id, 'media-restante');
        expect(notifier.mediaList.first.isPrimary, isTrue);
        expect(notifier.primaryMedia?.id, 'media-restante');
      });

      test('2. Próxima capa reconcilia com o estado real retornado pelo servidor', () async {
        final t1 = DateTime(2026, 10, 20, 10, 0);
        final t2 = DateTime(2026, 10, 21, 10, 0);

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
            createdAt: t1,
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
            createdAt: t2,
          ),
        ];
        await notifier.loadMedia('place-1');

        // Simulando que o servidor atualizou a lista e media-2 é primary
        await notifier.deleteMedia(placeId: 'place-1', mediaId: 'media-1');

        expect(notifier.mediaList.length, 1);
        expect(notifier.primaryMedia?.id, 'media-2');
        expect(notifier.primaryMedia?.isPrimary, isTrue);
      });

      test('3. Excluir a única fotografia deixa a galeria vazia e sem capa', () async {
        repository.mediaToReturn = [
          PlaceMediaItem(
            id: 'media-unica',
            placeId: 'place-1',
            url: '/url-unica',
            mediaType: 'PHOTO',
            mimeType: 'image/jpeg',
            sizeBytes: 1024,
            status: 'ACTIVE',
            isPrimary: true,
            createdAt: DateTime.now(),
          ),
        ];
        await notifier.loadMedia('place-1');

        final success = await notifier.deleteMedia(placeId: 'place-1', mediaId: 'media-unica');

        expect(success, isTrue);
        expect(notifier.mediaList, isEmpty);
        expect(notifier.primaryMedia, isNull);
        expect(notifier.operatingMediaId, isNull);
      });

      test('4. Falha ao recarregar a lista após exclusão preserva a remoção confirmada', () async {
        final t1 = DateTime(2026, 10, 20, 10, 0);
        final t2 = DateTime(2026, 10, 21, 10, 0);

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
            createdAt: t1,
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
            createdAt: t2,
          ),
        ];
        await notifier.loadMedia('place-1');

        // Configura falha apenas no getPlaceMedia subsequente
        repository.getMediaErrorToThrow = const ApiException(
          ProblemDetail(type: 'about:blank', title: 'Erro', status: 500, detail: 'Falha de rede momentânea'),
        );

        final success = await notifier.deleteMedia(placeId: 'place-1', mediaId: 'media-1');

        expect(success, isTrue);
        expect(notifier.mediaList.length, 1);
        expect(notifier.mediaList.first.id, 'media-2');
        expect(notifier.mediaList.first.isPrimary, isTrue);
        expect(notifier.operatingMediaId, isNull);
      });

      test('Falha na requisição de exclusão não remove a foto do estado local', () async {
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
    });

    group('Correção B: Idempotência estável durante retentativas', () {
      test('5. Retentativa da mesma operação reutiliza a chave de idempotência original', () async {
        final fakeBytes = Uint8List.fromList([1, 2, 3, 4]);

        // 1ª tentativa falha por erro de rede
        repository.errorToThrow = Exception('Network timeout');
        final firstAttempt = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: fakeBytes,
          filename: 'foto.jpg',
        );

        expect(firstAttempt, isFalse);
        expect(repository.uploadCallCount, 1);
        final originalKey = repository.lastUploadIdempotencyKey;
        expect(originalKey, 'generated-uuid-key-1');
        expect(notifier.hasPendingRetry, isTrue);
        expect(notifier.pendingUpload?.idempotencyKey, originalKey);

        // 2ª tentativa (retentativa explícita) tem sucesso
        repository.errorToThrow = null;
        final secondAttempt = await notifier.retryUpload();

        expect(secondAttempt, isTrue);
        expect(repository.uploadCallCount, 2);
        expect(repository.lastUploadIdempotencyKey, originalKey); // Mesma chave!
        expect(notifier.hasPendingRetry, isFalse);
      });

      test('6. Uma nova fotografia recebe uma chave diferente', () async {
        final bytesA = Uint8List.fromList([10, 20]);
        final bytesB = Uint8List.fromList([30, 40]);

        await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: bytesA,
          filename: 'fotoA.jpg',
        );
        final keyA = repository.lastUploadIdempotencyKey;
        expect(keyA, 'generated-uuid-key-1');

        await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: bytesB,
          filename: 'fotoB.jpg',
        );
        final keyB = repository.lastUploadIdempotencyKey;
        expect(keyB, 'generated-uuid-key-2');
        expect(keyA, isNot(equals(keyB)));
      });

      test('7. Sucesso encerra a operação anterior', () async {
        final success = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: Uint8List.fromList([1, 2]),
          filename: 'foto.jpg',
        );

        expect(success, isTrue);
        expect(notifier.hasPendingRetry, isFalse);
        expect(notifier.pendingUpload, isNull);
      });

      test('8. Falha de transporte não cria nova chave automaticamente', () async {
        repository.errorToThrow = Exception('Transport broken');

        await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: Uint8List.fromList([1, 2]),
          filename: 'foto.jpg',
        );

        expect(notifier.hasPendingRetry, isTrue);
        expect(notifier.pendingUpload?.idempotencyKey, 'generated-uuid-key-1');
      });

      test('9. Reenvio continua protegido contra ações simultâneas', () async {
        repository.errorToThrow = Exception('Network fail');
        await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: Uint8List.fromList([1, 2]),
          filename: 'foto.jpg',
        );

        expect(notifier.hasPendingRetry, isTrue);

        // Dispara retry
        repository.errorToThrow = null;
        final retryFuture = notifier.retryUpload();
        // Ação concorrente enquanto retryUpload está em curso
        final simultaneousDelete = notifier.deleteMedia(placeId: 'place-1', mediaId: 'm-x');

        final rDelete = await simultaneousDelete;
        final rRetry = await retryFuture;

        expect(rDelete, isFalse);
        expect(rRetry, isTrue);
      });

      test('Tentativa de iniciar outro upload após falha não substitui operação pendente nem envia requisição', () async {
        final bytesA = Uint8List.fromList([1, 2, 3, 4]);
        final bytesB = Uint8List.fromList([9, 8, 7, 6]);

        // 1. Falha no primeiro envio da fotoA
        repository.errorToThrow = Exception('Network error');
        final firstAttempt = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: bytesA,
          filename: 'fotoA.jpg',
          mimeType: 'image/jpeg',
        );

        expect(firstAttempt, isFalse);
        expect(repository.uploadCallCount, 1);
        final originalKey = repository.lastUploadIdempotencyKey;
        expect(originalKey, 'generated-uuid-key-1');
        expect(notifier.hasPendingRetry, isTrue);
        expect(notifier.pendingUpload?.filename, 'fotoA.jpg');
        expect(notifier.pendingUpload?.bytes, bytesA);
        expect(notifier.pendingUpload?.idempotencyKey, originalKey);

        // 2. Tentativa de iniciar outro upload com fotoB enquanto fotoA está pendente
        final secondAttempt = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: bytesB,
          filename: 'fotoB.jpg',
          mimeType: 'image/jpeg',
        );

        expect(secondAttempt, isFalse);
        expect(
          notifier.errorMessage,
          'Existe um envio pendente. Tente novamente ou descarte a operação antes de adicionar outra foto.',
        );
        // Nenhuma segunda requisição foi enviada
        expect(repository.uploadCallCount, 1);
        // Chave e bytes originais continuam intactos
        expect(notifier.hasPendingRetry, isTrue);
        expect(notifier.pendingUpload?.filename, 'fotoA.jpg');
        expect(notifier.pendingUpload?.bytes, bytesA);
        expect(notifier.pendingUpload?.idempotencyKey, originalKey);
        expect(notifier.pendingUpload?.placeId, 'place-1');
        expect(notifier.pendingUpload?.mimeType, 'image/jpeg');

        // 3. Retentar operação original continua usando a mesma chave e os mesmos bytes
        repository.errorToThrow = null;
        final retrySuccess = await notifier.retryUpload();

        expect(retrySuccess, isTrue);
        expect(repository.uploadCallCount, 2);
        expect(repository.lastUploadIdempotencyKey, originalKey);
        expect(notifier.hasPendingRetry, isFalse);
        expect(notifier.pendingUpload, isNull);

        // 4. Sucesso da retentativa encerra estado pendente e permite novo upload
        final newUploadSuccess = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: bytesB,
          filename: 'fotoB.jpg',
        );

        expect(newUploadSuccess, isTrue);
        expect(repository.uploadCallCount, 3);
        expect(repository.lastUploadIdempotencyKey, 'generated-uuid-key-2');
      });

      test('Descartar a operação pendente libera novo upload que recebe uma nova chave', () async {
        final bytesA = Uint8List.fromList([1, 2]);
        final bytesB = Uint8List.fromList([3, 4]);

        repository.errorToThrow = Exception('Fail');
        await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: bytesA,
          filename: 'fotoA.jpg',
        );

        expect(notifier.hasPendingRetry, isTrue);
        expect(notifier.pendingUpload?.filename, 'fotoA.jpg');

        // Descarte explícito
        notifier.cancelPendingUpload();
        expect(notifier.hasPendingRetry, isFalse);
        expect(notifier.pendingUpload, isNull);

        // Novo upload agora é aceito e recebe nova chave
        repository.errorToThrow = null;
        final success = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: bytesB,
          filename: 'fotoB.jpg',
        );

        expect(success, isTrue);
        expect(repository.uploadCallCount, 2);
        expect(repository.lastUploadIdempotencyKey, 'generated-uuid-key-2');
      });
    });

    group('Correção C: Replay com mídia REMOVED', () {
      test('10. Resposta REMOVED nunca é adicionada à galeria como foto ativa', () async {
        repository.uploadItemToReturn = PlaceMediaItem(
          id: 'media-removed-replay',
          placeId: 'place-1',
          url: '/url-removed',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'REMOVED',
          isPrimary: false,
          createdAt: DateTime.now(),
        );

        final success = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: Uint8List.fromList([1, 2, 3]),
          filename: 'foto.jpg',
        );

        expect(success, isFalse);
        expect(notifier.mediaList.any((m) => m.id == 'media-removed-replay'), isFalse);
        expect(notifier.hasPendingRetry, isFalse); // Encerrou a operação esgotada
      });

      test('11. Reconcilia o estado com o servidor e fornece feedback verdadeiro', () async {
        repository.uploadItemToReturn = PlaceMediaItem(
          id: 'media-removed-replay',
          placeId: 'place-1',
          url: '/url-removed',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'REMOVED',
          isPrimary: false,
          createdAt: DateTime.now(),
        );

        await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: Uint8List.fromList([1, 2, 3]),
          filename: 'foto.jpg',
        );

        expect(notifier.errorMessage, contains('não está mais ativa'));
        expect(repository.lastGetPlaceId, 'place-1'); // Reconciliou chamando getPlaceMedia
      });

      test('12. Nova operação explicitamente iniciada usa nova chave após resposta REMOVED', () async {
        repository.uploadItemToReturn = PlaceMediaItem(
          id: 'media-removed-replay',
          placeId: 'place-1',
          url: '/url-removed',
          mediaType: 'PHOTO',
          mimeType: 'image/jpeg',
          sizeBytes: 1024,
          status: 'REMOVED',
          isPrimary: false,
          createdAt: DateTime.now(),
        );

        await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: Uint8List.fromList([1, 2, 3]),
          filename: 'foto1.jpg',
        );
        expect(repository.lastUploadIdempotencyKey, 'generated-uuid-key-1');

        // Usuário escolhe outra foto e inicia novo upload
        repository.uploadItemToReturn = null;
        final secondUpload = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: Uint8List.fromList([4, 5, 6]),
          filename: 'foto2.jpg',
        );

        expect(secondUpload, isTrue);
        expect(repository.lastUploadIdempotencyKey, 'generated-uuid-key-2');
        expect(notifier.mediaList.length, 1);
        expect(notifier.mediaList.first.id, 'media-new-99');
      });
    });

    group('Limites de mídia', () {
      test('impede upload quando limite de 5 fotografias é atingido', () async {
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
      });

      test('impede upload quando arquivo excede limite de 10 MB', () async {
        await notifier.loadMedia('place-1');

        final oversizedBytes = Uint8List((10 * 1024 * 1024) + 1);

        final success = await notifier.uploadMedia(
          placeId: 'place-1',
          bytes: oversizedBytes,
          filename: 'foto_gigante.jpg',
        );

        expect(success, isFalse);
        expect(notifier.errorMessage, contains('excede o limite máximo permitido de 10 MB'));
      });
    });
  });
}
