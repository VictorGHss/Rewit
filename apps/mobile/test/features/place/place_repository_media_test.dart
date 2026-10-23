import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/place/data/repositories/place_repository_impl.dart';

class _MockHttpBaseClient extends http.BaseClient {
  http.BaseRequest? capturedRequest;
  http.Response? responseToReturn;
  Exception? exceptionToThrow;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    capturedRequest = request;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    final resp = responseToReturn ??
        http.Response(
          jsonEncode({}),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
    return http.StreamedResponse(
      Stream.value(resp.bodyBytes),
      resp.statusCode,
      headers: resp.headers,
    );
  }
}

void main() {
  group('PlaceRepositoryImpl - Media Endpoints Tests', () {
    late _MockHttpBaseClient innerClient;
    late RewitHttpClient httpClient;
    late PlaceRepositoryImpl repository;

    setUp(() {
      innerClient = _MockHttpBaseClient();
      httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: innerClient,
      );
      repository = PlaceRepositoryImpl(httpClient: httpClient);
    });

    test('getPlaceMedia realiza GET /api/v1/places/{id}/media e mapeia lista de itens', () async {
      const placeId = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
      innerClient.responseToReturn = http.Response(
        jsonEncode([
          {
            'id': 'media-1',
            'placeId': placeId,
            'url': '/api/v1/places/$placeId/media/media-1',
            'mediaType': 'PHOTO',
            'mimeType': 'image/jpeg',
            'sizeBytes': 2048,
            'status': 'ACTIVE',
            'isPrimary': true,
            'createdAt': '2026-10-19T12:00:00Z',
          },
          {
            'id': 'media-2',
            'placeId': placeId,
            'url': '/api/v1/places/$placeId/media/media-2',
            'mediaType': 'PHOTO',
            'mimeType': 'image/png',
            'sizeBytes': 4096,
            'status': 'ACTIVE',
            'isPrimary': false,
            'createdAt': '2026-10-19T13:00:00Z',
          },
        ]),
        200,
        headers: {'content-type': 'application/json'},
      );

      final mediaList = await repository.getPlaceMedia(placeId);

      expect(innerClient.capturedRequest?.method, 'GET');
      expect(innerClient.capturedRequest?.url.path, '/api/v1/places/$placeId/media');
      expect(mediaList.length, 2);
      expect(mediaList[0].id, 'media-1');
      expect(mediaList[0].isPrimary, isTrue);
      expect(mediaList[1].id, 'media-2');
      expect(mediaList[1].isPrimary, isFalse);
    });

    test('uploadPlaceMedia realiza POST multipart /api/v1/places/{id}/media com Idempotency-Key', () async {
      const placeId = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'id': 'media-new-1',
          'placeId': placeId,
          'url': '/api/v1/places/$placeId/media/media-new-1',
          'mediaType': 'PHOTO',
          'mimeType': 'image/jpeg',
          'sizeBytes': 1024,
          'status': 'ACTIVE',
          'isPrimary': false,
          'createdAt': '2026-10-23T10:00:00Z',
        }),
        201,
        headers: {'content-type': 'application/json'},
      );

      final result = await repository.uploadPlaceMedia(
        placeId: placeId,
        fileBytes: Uint8List.fromList([10, 20, 30]),
        filename: 'foto_fachada.jpg',
        mimeType: 'image/jpeg',
        idempotencyKey: 'idemp-uuid-v4-test',
      );

      expect(innerClient.capturedRequest?.method, 'POST');
      expect(innerClient.capturedRequest?.url.path, '/api/v1/places/$placeId/media');
      expect(innerClient.capturedRequest?.headers['Idempotency-Key'], 'idemp-uuid-v4-test');
      expect(result.id, 'media-new-1');
      expect(result.isPrimary, isFalse);
    });

    test('setPrimaryPlaceMedia realiza PUT /api/v1/places/{id}/media/{mediaId}/primary', () async {
      const placeId = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
      const mediaId = 'media-target-9';
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'id': mediaId,
          'placeId': placeId,
          'url': '/api/v1/places/$placeId/media/$mediaId',
          'mediaType': 'PHOTO',
          'mimeType': 'image/jpeg',
          'sizeBytes': 512,
          'status': 'ACTIVE',
          'isPrimary': true,
          'createdAt': '2026-10-23T11:00:00Z',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final result = await repository.setPrimaryPlaceMedia(
        placeId: placeId,
        mediaId: mediaId,
      );

      expect(innerClient.capturedRequest?.method, 'PUT');
      expect(innerClient.capturedRequest?.url.path, '/api/v1/places/$placeId/media/$mediaId/primary');
      expect(result.id, mediaId);
      expect(result.isPrimary, isTrue);
    });

    test('deletePlaceMedia realiza DELETE /api/v1/places/{id}/media/{mediaId}', () async {
      const placeId = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
      const mediaId = 'media-delete-1';
      innerClient.responseToReturn = http.Response('', 204);

      await repository.deletePlaceMedia(
        placeId: placeId,
        mediaId: mediaId,
      );

      expect(innerClient.capturedRequest?.method, 'DELETE');
      expect(innerClient.capturedRequest?.url.path, '/api/v1/places/$placeId/media/$mediaId');
    });

    test('deletePlaceMedia propaga ApiException quando backend retorna 403 Forbidden', () async {
      const placeId = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
      const mediaId = 'media-delete-1';
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.com/errors/forbidden',
          'title': 'Forbidden',
          'status': 403,
          'detail': 'Usuário não tem permissão para gerenciar fotos deste estabelecimento',
        }),
        403,
        headers: {'content-type': 'application/problem+json'},
      );

      expect(
        () => repository.deletePlaceMedia(
          placeId: placeId,
          mediaId: mediaId,
        ),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 403)),
      );
    });
  });
}

