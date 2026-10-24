import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/place_discovery/data/repositories/place_discovery_repository_impl.dart';
import 'package:rewit_mobile/features/place_discovery/domain/entities/external_place_candidate.dart';

class _MockHttpBaseClient extends http.BaseClient {
  final List<http.Request> requests = [];
  http.Response? responseToReturn;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    requests.add(request as http.Request);
    final resp = responseToReturn ?? http.Response('[]', 200);
    return http.StreamedResponse(
      Stream.value(resp.bodyBytes),
      resp.statusCode,
      headers: {'content-type': 'application/json; charset=utf-8', ...resp.headers},
    );
  }
}

http.Response _json(Object body, [int status = 200]) => http.Response.bytes(utf8.encode(jsonEncode(body)), status);

const _geoCandidate = ExternalPlaceCandidate(
  provider: 'GEOAPIFY',
  externalId: '51c3f2a8d1e4-padaria',
  displayName: 'Padaria Estrela',
  formattedAddress: 'Padaria Estrela, Rua XV de Novembro, 100, Ponta Grossa - PR, Brasil',
  latitude: -25.0945,
  longitude: -50.1633,
  city: 'Ponta Grossa',
  state: 'PR',
  country: 'BR',
);

void main() {
  late _MockHttpBaseClient mock;
  late PlaceDiscoveryRepositoryImpl repository;

  setUp(() {
    mock = _MockHttpBaseClient();
    repository = PlaceDiscoveryRepositoryImpl(
      httpClient: RewitHttpClient(baseUrl: 'https://api.rewit.test', client: mock),
    );
  });

  test('search consulta o endpoint do backend e mapeia candidatos, partes do endereço e atribuições', () async {
    mock.responseToReturn = _json([
      {
        'provider': 'GEOAPIFY',
        'externalId': '51c3f2a8d1e4-padaria',
        'displayName': 'Padaria Estrela',
        'formattedAddress': 'Rua XV de Novembro, 100, Ponta Grossa - PR',
        'latitude': -25.0945,
        'longitude': -50.1633,
        'types': ['commercial.food_and_drink.bakery'],
        'attributions': [
          {'text': 'Powered by Geoapify', 'url': 'https://www.geoapify.com/'},
          {'text': '© OpenStreetMap contributors', 'url': 'https://www.openstreetmap.org/copyright'},
        ],
        'city': 'Ponta Grossa',
        'state': 'PR',
        'country': 'BR',
      },
      {'provider': 'GEOAPIFY', 'externalId': ' ', 'displayName': 'Sem id'},
      {'provider': 'GEOAPIFY', 'externalId': 'sem-endereco', 'displayName': 'Quiosque', 'formattedAddress': ''},
    ]);

    final results = await repository.search(query: 'padaria em Ponta Grossa');

    final uri = mock.requests.single.url;
    expect(uri.path, '/api/v1/places/discovery/search');
    expect(uri.queryParameters['query'], 'padaria em Ponta Grossa');
    expect(uri.queryParameters['limit'], '10');
    expect(uri.queryParameters.containsKey('latitude'), isFalse);
    // Nenhuma credencial de provedor sai do app: só a API Rewit é chamada
    expect(uri.host, 'api.rewit.test');
    expect(uri.queryParameters.keys, isNot(contains('apiKey')));

    expect(results, hasLength(2));
    final first = results.first;
    expect(first.provider, 'GEOAPIFY');
    expect(first.displayName, 'Padaria Estrela');
    expect(first.city, 'Ponta Grossa');
    expect(first.state, 'PR');
    expect(first.country, 'BR');
    expect(first.types, ['commercial.food_and_drink.bakery']);
    expect(first.attributions.map((a) => a.text), ['Powered by Geoapify', '© OpenStreetMap contributors']);
    expect(first.isAdoptable, isTrue);
    expect(first.providerLabel, 'Geoapify / OpenStreetMap');
    expect(results[1].isAdoptable, isFalse, reason: 'sem endereço, cidade e coordenadas não é adotável');
  });

  test('search envia localização e raio só quando ambas as coordenadas existem', () async {
    await repository.search(query: 'cafe', latitude: -25.09, longitude: -50.16, radiusMeters: 3000, limit: 5);
    final params = mock.requests.single.url.queryParameters;
    expect(params['latitude'], '-25.09');
    expect(params['longitude'], '-50.16');
    expect(params['radius'], '3000.0');
    expect(params['limit'], '5');
  });

  test('search sem provedor configurado propaga o ProblemDetail 503 com o código do backend', () async {
    mock.responseToReturn = _json({
      'type': 'about:blank',
      'title': 'Service Unavailable',
      'status': 503,
      'detail': 'Serviço de descoberta não configurado',
      'code': 'EXTERNAL_SERVICE_NOT_CONFIGURED',
    }, 503);

    await expectLater(
      repository.search(query: 'padaria'),
      throwsA(isA<ApiException>()
          .having((e) => e.statusCode, 'status', 503)
          .having((e) => e.errorCode, 'code', 'EXTERNAL_SERVICE_NOT_CONFIGURED')),
    );
  });

  test('adopt usa o fluxo oficial com referência externa e devolve o local do catálogo', () async {
    mock.responseToReturn = _json({
      'id': '11111111-2222-3333-4444-555555555555',
      'name': 'Padaria Estrela',
      'slug': 'padaria-estrela',
      'category': 'GERAL',
      'addressText': 'Rua XV de Novembro, 100',
      'city': 'Ponta Grossa',
      'state': 'PR',
      'status': 'ACTIVE',
      'origin': 'EXTERNAL',
    }, 201);

    final place = await repository.adopt(_geoCandidate);

    final request = mock.requests.single;
    expect(request.method, 'POST');
    expect(request.url.path, '/api/v1/places/adopt');
    final body = jsonDecode(request.body) as Map<String, dynamic>;
    expect(body['name'], 'Padaria Estrela');
    expect(body['addressText'], _geoCandidate.formattedAddress);
    expect(body['city'], 'Ponta Grossa');
    expect(body['state'], 'PR');
    expect(body['country'], 'BR');
    expect(body['latitude'], -25.0945);
    expect(body['longitude'], -50.1633);
    expect(body['externalReference'], {'provider': 'GEOAPIFY', 'externalId': '51c3f2a8d1e4-padaria'});
    // Nota, horário e avaliações do provedor não existem no candidato e não são enviados
    expect(body.keys, isNot(contains('rating')));
    expect(place.id, '11111111-2222-3333-4444-555555555555');
  });

  test('adopt de candidato sem endereço completo falha sem chamar o backend', () async {
    const incomplete = ExternalPlaceCandidate(
      provider: 'GEOAPIFY',
      externalId: 'sem-cidade',
      displayName: 'Quiosque',
      formattedAddress: 'Praça Central',
      latitude: -25.0,
      longitude: -50.0,
    );
    await expectLater(repository.adopt(incomplete), throwsA(isA<FormatException>()));
    expect(mock.requests, isEmpty);
  });
}
