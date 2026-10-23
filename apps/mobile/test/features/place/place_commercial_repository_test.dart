import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/place/data/repositories/place_commercial_repository_impl.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';

class _FakeHttpClient implements RewitHttpClient {
  String? requestedPath;
  Map<String, dynamic>? requestedQueryParameters;
  String? requestedPutPath;
  Object? requestedPutBody;
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
  Future<http.Response> put(
    String path, {
    Object? body,
    Map<String, String>? headers,
    Map<String, dynamic>? queryParameters,
    bool requiresAuth = true,
  }) async {
    requestedPutPath = path;
    requestedPutBody = body;
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

    test('updateOpeningHours envia PUT com payload correto e retorna horários atualizados', () async {
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
                {'openTime': '09:00', 'closeTime': '17:00'},
              ],
            },
            {
              'dayOfWeek': 2,
              'dayName': 'TUESDAY',
              'closed': true,
              'intervals': [],
            },
          ],
        }),
        200,
      );

      final days = [
        const DaySchedule(
          dayOfWeek: 1,
          dayName: 'MONDAY',
          closed: false,
          intervals: [TimeInterval(openTime: '09:00', closeTime: '17:00')],
        ),
        const DaySchedule(
          dayOfWeek: 2,
          dayName: 'TUESDAY',
          closed: true,
          intervals: [TimeInterval(openTime: '10:00', closeTime: '12:00')], // intervalo deve ser zerado ao enviar
        ),
      ];

      final result = await repository.updateOpeningHours(
        placeId: 'p-1',
        businessAccountId: 'biz-1',
        days: days,
      );

      expect(httpClient.requestedPutPath, ApiEndpoints.placeOpeningHours('p-1'));
      expect(result.placeId, 'p-1');
      expect(result.configured, isTrue);
      expect(result.days.length, 2);

      final sentPayload = jsonDecode(httpClient.requestedPutBody as String) as Map<String, dynamic>;
      expect(sentPayload['businessAccountId'], 'biz-1');
      final sentDays = sentPayload['days'] as List<dynamic>;
      expect(sentDays.length, 2);
      expect(sentDays[0]['closed'], isFalse);
      expect(sentDays[0]['intervals'], [
        {'openTime': '09:00', 'closeTime': '17:00'},
      ]);
      expect(sentDays[1]['closed'], isTrue);
      expect(sentDays[1]['intervals'], isEmpty);
    });

    test('getAmenityOptions consulta catálogo público e retorna lista de opções', () async {
      httpClient.responseToReturn = http.Response(
        jsonEncode({
          'options': [
            {'code': 'WIFI', 'label': 'Wi-Fi para clientes'},
            {'code': 'PARKING', 'label': 'Estacionamento próprio'},
          ],
        }),
        200,
      );

      final result = await repository.getAmenityOptions();
      expect(httpClient.requestedPath, ApiEndpoints.placeAmenityOptions);
      expect(result.length, 2);
      expect(result[0].code, 'WIFI');
      expect(result[0].label, 'Wi-Fi para clientes');
      expect(result[1].code, 'PARKING');
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

    test('updateAmenities envia PUT com payload e retorna comodidades atualizadas', () async {
      httpClient.responseToReturn = http.Response(
        jsonEncode({
          'placeId': 'p-1',
          'configured': true,
          'amenities': [
            {'code': 'WIFI', 'label': 'Wi-Fi para clientes'},
            {'code': 'PARKING', 'label': 'Estacionamento próprio'},
          ],
        }),
        200,
      );

      final result = await repository.updateAmenities(
        placeId: 'p-1',
        businessAccountId: 'biz-1',
        amenityCodes: ['WIFI', 'PARKING'],
      );

      expect(httpClient.requestedPutPath, ApiEndpoints.placeAmenities('p-1'));
      expect(result.placeId, 'p-1');
      expect(result.configured, isTrue);
      expect(result.amenities.length, 2);

      final sentPayload = jsonDecode(httpClient.requestedPutBody as String) as Map<String, dynamic>;
      expect(sentPayload['businessAccountId'], 'biz-1');
      expect(sentPayload['amenities'], ['WIFI', 'PARKING']);
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

      expect(
        () => repository.getAmenityOptions(),
        throwsA(isA<FormatException>()),
      );

      expect(
        () => repository.updateOpeningHours(
          placeId: 'p-1',
          businessAccountId: 'biz-1',
          days: [],
        ),
        throwsA(isA<FormatException>()),
      );

      expect(
        () => repository.updateAmenities(
          placeId: 'p-1',
          businessAccountId: 'biz-1',
          amenityCodes: [],
        ),
        throwsA(isA<FormatException>()),
      );
    });
  });
}
