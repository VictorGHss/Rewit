import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/place/data/repositories/place_commercial_repository_impl.dart';

class _FakeHttpClient implements RewitHttpClient {
  String? requestedPath;
  Map<String, dynamic>? requestedQueryParameters;
  http.Response? responseToReturn;
  Exception? exceptionToThrow;

  @override
  Future<http.Response> get(
    String path, {
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) async {
    requestedPath = path;
    requestedQueryParameters = queryParameters;
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return responseToReturn ?? http.Response('{}', 200);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  group('PlaceCommercialRepositoryImpl Tests', () {
    late _FakeHttpClient httpClient;
    late PlaceCommercialRepositoryImpl repository;

    setUp(() {
      httpClient = _FakeHttpClient();
      repository = PlaceCommercialRepositoryImpl(httpClient: httpClient);
    });

    test('getOpeningHours consulta o endpoint correto e retorna entidade', () async {
      httpClient.responseToReturn = http.Response(
        jsonEncode({
          'placeId': 'p-1',
          'configured': true,
          'days': [
            {
              'dayOfWeek': 1,
              'dayName': 'MONDAY',
              'closed': false,
              'intervals': [
                {'openTime': '08:00', 'closeTime': '18:00'},
              ],
            },
          ],
        }),
        200,
      );

      final result = await repository.getOpeningHours('p-1');
      expect(httpClient.requestedPath, ApiEndpoints.placeOpeningHours('p-1'));
      expect(result.placeId, 'p-1');
      expect(result.configured, isTrue);
      expect(result.days.length, 1);
    });

    test('getAmenities consulta o endpoint correto e retorna comodidades', () async {
      httpClient.responseToReturn = http.Response(
        jsonEncode({
          'placeId': 'p-1',
          'configured': true,
          'amenities': [
            {'code': 'WIFI', 'label': 'Wi-Fi'},
          ],
        }),
        200,
      );

      final result = await repository.getAmenities('p-1');
      expect(httpClient.requestedPath, ApiEndpoints.placeAmenities('p-1'));
      expect(result.configured, isTrue);
      expect(result.amenities.first.code, 'WIFI');
    });

    test('getOffers repassa page e size e retorna página de ofertas', () async {
      httpClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {
              'product': {
                'id': 'prod-1',
                'name': 'Suco de Laranja',
                'category': 'BEBIDA',
              },
              'availability': 'AVAILABLE',
              'price': 10.0,
              'currency': 'BRL',
            },
          ],
          'pageNumber': 1,
          'pageSize': 20,
          'totalElements': 15,
          'totalPages': 1,
          'isLast': false,
        }),
        200,
      );

      final result = await repository.getOffers('p-1', page: 1, size: 20);
      expect(httpClient.requestedPath, ApiEndpoints.placeOffers('p-1', page: 1, size: 20));
      expect(result.pageNumber, 1);
      expect(result.pageSize, 20);
      expect(result.isLast, isFalse);
      expect(result.content.first.product.name, 'Suco de Laranja');
    });

    test('repassa ApiException e falhas de formato', () async {
      httpClient.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 404,
          title: 'Not Found',
          detail: 'Local não encontrado',
          code: 'PLACE_NOT_FOUND',
        ),
      );

      expect(
        () => repository.getOpeningHours('unknown'),
        throwsA(isA<ApiException>()),
      );

      httpClient.exceptionToThrow = null;
      httpClient.responseToReturn = http.Response('[]', 200); // Lista em vez de Map

      expect(
        () => repository.getAmenities('p-1'),
        throwsA(isA<FormatException>()),
      );
    });
  });
}
