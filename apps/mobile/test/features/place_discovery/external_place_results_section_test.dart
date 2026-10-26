import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place_discovery/domain/entities/discovery_region.dart';
import 'package:rewit_mobile/features/place_discovery/domain/entities/external_place_candidate.dart';
import 'package:rewit_mobile/features/place_discovery/domain/repositories/place_discovery_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/device_location.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/target_search_selector_widget.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _FakeSearchRepository implements SearchRepository {
  int calls = 0;
  List<SearchResultItem> items = const [];

  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async {
    calls++;
    return SearchPage(
      items: items,
      pageNumber: 0,
      pageSize: 20,
      totalElements: items.length,
      totalPages: 1,
      isLast: true,
    );
  }
}

class _FakeDiscoveryRepository implements PlaceDiscoveryRepository {
  int searchCalls = 0;
  String? lastQuery;
  DiscoveryRegion? lastRegion;
  List<ExternalPlaceCandidate> results = const [];
  Exception? searchError;
  Completer<List<ExternalPlaceCandidate>>? searchCompleter;
  final List<ExternalPlaceCandidate> adopted = [];
  String adoptedPlaceId = '99999999-9999-9999-9999-999999999999';

  @override
  Future<List<ExternalPlaceCandidate>> search({
    required String query,
    DiscoveryRegion region = const UnrestrictedRegion(),
    int limit = 10,
  }) async {
    searchCalls++;
    lastQuery = query;
    lastRegion = region;
    if (searchCompleter != null) return searchCompleter!.future;
    if (searchError != null) throw searchError!;
    return results;
  }

  @override
  Future<PlaceDetail> adopt(ExternalPlaceCandidate candidate) async {
    adopted.add(candidate);
    return PlaceDetail(
      id: adoptedPlaceId,
      name: candidate.displayName,
      slug: 'padaria-estrela',
      category: 'GERAL',
      addressText: candidate.formattedAddress,
      city: candidate.city,
      state: candidate.state,
      validationRadiusMeters: 50,
      origin: 'EXTERNAL',
      isVerified: false,
      status: 'ACTIVE',
    );
  }
}

class _FakeLocationService implements LocationService {
  int calls = 0;
  LocationResult result = const LocationSuccess(
    DeviceLocation(latitude: -25.09, longitude: -50.16, accuracyMeters: 20),
  );
  Completer<LocationResult>? locationCompleter;

  @override
  Future<LocationResult> getCurrentLocation({Duration timeout = const Duration(seconds: 10)}) async {
    calls++;
    if (locationCompleter != null) return locationCompleter!.future;
    return result;
  }

  @override
  Future<bool> openAppSettings() async => true;
}

const _attributions = [
  ExternalPlaceAttribution(text: 'Powered by Geoapify', url: 'https://www.geoapify.com/'),
  ExternalPlaceAttribution(text: '© OpenStreetMap contributors', url: 'https://www.openstreetmap.org/copyright'),
];

const _adoptable = ExternalPlaceCandidate(
  provider: 'GEOAPIFY',
  externalId: 'geo-padaria',
  displayName: 'Padaria Junior Raikoski',
  formattedAddress: 'Rua Conrado Schiffer, Ponta Grossa - PR',
  latitude: -25.108,
  longitude: -50.169,
  attributions: _attributions,
  city: 'Ponta Grossa',
  state: 'PR',
  country: 'BR',
);

/// Como chega do backend um local cujo estado veio só como região ("Sul"): UF nula.
const _withoutState = ExternalPlaceCandidate(
  provider: 'GEOAPIFY',
  externalId: 'geo-super-pao',
  displayName: 'Padaria Super Pão',
  formattedAddress: 'Avenida Brasil, Cambará - Sul',
  latitude: -23.043,
  longitude: -50.073,
  attributions: _attributions,
  city: 'Cambará',
  country: 'BR',
);

/// Estado inválido vindo de qualquer origem: o app também não aceita, mesmo que o backend falhe em limpar.
const _invalidState = ExternalPlaceCandidate(
  provider: 'GEOAPIFY',
  externalId: 'geo-regiao',
  displayName: 'Padaria Região',
  formattedAddress: 'Rua Y, Ponta Grossa',
  latitude: -25.1,
  longitude: -50.1,
  attributions: _attributions,
  city: 'Ponta Grossa',
  state: 'Sul',
  country: 'BR',
);

const _localPlace = SearchResultItem(
  id: '11111111-1111-1111-1111-111111111111',
  name: 'Padaria do Catálogo',
  targetType: TargetType.place,
  rawTargetType: 'PLACE',
  status: 'ACTIVE',
);

void main() {
  late _FakeSearchRepository search;
  late _FakeDiscoveryRepository discovery;
  late _FakeLocationService location;
  late List<SearchResultItem> selected;

  setUp(() {
    search = _FakeSearchRepository();
    discovery = _FakeDiscoveryRepository();
    location = _FakeLocationService();
    selected = [];
  });

  Future<void> pumpSubject(
    WidgetTester tester, {
    bool Function(String)? isTargetSelectedElsewhere,
    bool withDiscovery = true,
    bool withLocation = true,
  }) async {
    // Tela alta: a lista de UFs do dropdown cabe inteira
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: TargetSearchSelectorWidget(
            searchRepository: search,
            placeDiscoveryRepository: withDiscovery ? discovery : null,
            locationService: withLocation ? location : null,
            targetIndex: 0,
            onTargetSelected: selected.add,
            isTargetSelectedElsewhere: isTargetSelectedElsewhere,
          ),
        ),
      ),
    ));
  }

  Future<void> typeQuery(WidgetTester tester, String query) async {
    // O campo da busca de alvos é o primeiro TextFormField; o de cidade é um TextField
    await tester.enterText(find.byType(TextFormField).first, query);
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
  }

  Future<void> chooseCity(WidgetTester tester, String city, String uf) async {
    await tester.enterText(find.byKey(const Key('external_region_city_field')), city);
    await tester.tap(find.byKey(const Key('external_region_state_field')));
    await tester.pumpAndSettle();
    await tester.tap(find.text(uf).last);
    await tester.pumpAndSettle();
  }

  Future<void> tapExternalSearch(WidgetTester tester) async {
    await tester.ensureVisible(find.byKey(const Key('external_place_search_button')));
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pumpAndSettle();
  }

  bool searchButtonEnabled(WidgetTester tester) =>
      tester.widget<OutlinedButton>(find.byKey(const Key('external_place_search_button'))).onPressed != null;

  testWidgets('catálogo local continua normal e a busca externa por cidade só parte do toque explícito',
      (tester) async {
    search.items = const [_localPlace];
    discovery.results = const [_adoptable];
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');

    // 1. Catálogo local
    expect(search.calls, 1);
    expect(find.text('Padaria do Catálogo'), findsOneWidget);
    // 2. Externa separada e sem chamada automática
    expect(find.byKey(const Key('external_place_results_section')), findsOneWidget);
    expect(discovery.searchCalls, 0, reason: 'digitar não gasta cota do provedor externo');
    expect(searchButtonEnabled(tester), isFalse, reason: 'cidade e UF são obrigatórias no modo Cidade');

    await chooseCity(tester, 'Ponta Grossa', 'PR');
    expect(searchButtonEnabled(tester), isTrue);
    expect(discovery.searchCalls, 0);
    await tapExternalSearch(tester);

    expect(discovery.searchCalls, 1);
    expect(discovery.lastQuery, 'padaria');
    final region = discovery.lastRegion as CityRegion;
    expect(region.city, 'Ponta Grossa');
    expect(region.state, 'PR');
    expect(location.calls, 0, reason: 'cidade escolhida não usa o GPS');
    // 3. Região informada
    expect(find.text('Região: Ponta Grossa – PR'), findsOneWidget);
    // 4. Separados do catálogo
    expect(find.text('Resultados externos • Geoapify / OpenStreetMap'), findsOneWidget);
    expect(find.text('Padaria Junior Raikoski'), findsOneWidget);
    expect(find.text('Padaria do Catálogo'), findsOneWidget);
    // 5. Atribuições
    expect(find.text('Powered by Geoapify'), findsOneWidget);
    expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
    // Pesquisa não adota nada
    expect(discovery.adopted, isEmpty);
    expect(selected, isEmpty);
  });

  testWidgets('6. "Adotar e selecionar" adota pelo fluxo oficial e seleciona o local do catálogo como PLACE',
      (tester) async {
    discovery.results = const [_adoptable];
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tapExternalSearch(tester);

    final adoptButton = find.byKey(const Key('adopt_external_candidate_geo-padaria'));
    await tester.ensureVisible(adoptButton);
    await tester.tap(adoptButton);
    await tester.pumpAndSettle();

    expect(discovery.adopted.single.externalId, 'geo-padaria');
    expect(selected.single.id, '99999999-9999-9999-9999-999999999999');
    expect(selected.single.targetType, TargetType.place);
    expect(selected.single.rawTargetType, 'PLACE');
  });

  testWidgets('7. candidatos sem UF ou com UF inválida não oferecem adoção', (tester) async {
    discovery.results = const [_adoptable, _withoutState, _invalidState];
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tapExternalSearch(tester);

    expect(find.byKey(const Key('adopt_external_candidate_geo-padaria')), findsOneWidget);
    expect(find.byKey(const Key('adopt_external_candidate_geo-super-pao')), findsNothing);
    expect(find.byKey(const Key('adopt_external_candidate_geo-regiao')), findsNothing);
    expect(find.textContaining('estado (UF) não informado'), findsOneWidget);
    expect(find.textContaining('UF inválida'), findsOneWidget);
    expect(discovery.adopted, isEmpty);
  });

  testWidgets('local adotado que já está em outro alvo não é selecionado de novo', (tester) async {
    discovery.results = const [_adoptable];
    await pumpSubject(tester, isTargetSelectedElsewhere: (id) => id == discovery.adoptedPlaceId);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tapExternalSearch(tester);
    await tester.ensureVisible(find.byKey(const Key('adopt_external_candidate_geo-padaria')));
    await tester.tap(find.byKey(const Key('adopt_external_candidate_geo-padaria')));
    await tester.pumpAndSettle();

    expect(selected, isEmpty);
    expect(find.text('O local "Padaria Junior Raikoski" já foi adicionado a esta avaliação.'), findsOneWidget);
  });

  Future<void> selectNearby(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('external_region_mode_nearby')));
    await tester.pumpAndSettle();
  }

  testWidgets('"Perto de mim" com termo: pede a localização ao escolher e faz uma única busca automática',
      (tester) async {
    discovery.results = const [_adoptable];
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    expect(location.calls, 0, reason: 'nada de localização antes da escolha');

    await selectNearby(tester);

    expect(location.calls, 1);
    expect(discovery.searchCalls, 1, reason: 'busca automática, sem segundo toque');
    final region = discovery.lastRegion as NearbyRegion;
    expect(region.latitude, -25.09);
    expect(region.longitude, -50.16);
    expect(region.radiusMeters, 10000);
    expect(find.text('Região: até 10 km da sua localização atual'), findsOneWidget);
    expect(find.textContaining('-25.09'), findsNothing, reason: 'coordenadas não são exibidas');
    expect(find.text('Padaria Junior Raikoski'), findsOneWidget);

    // Sem loop: mais quadros não geram novas chamadas, e o mesmo pedido fica indisponível
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();
    expect(location.calls, 1);
    expect(discovery.searchCalls, 1);
    expect(searchButtonEnabled(tester), isFalse);
    expect(find.text('Busca feita para este termo e região'), findsOneWidget);
  });

  testWidgets('novo termo depois da busca automática não busca sozinho; o toque reaproveita a posição desta escolha',
      (tester) async {
    discovery.results = const [_adoptable];
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await selectNearby(tester);
    expect(discovery.searchCalls, 1);

    await typeQuery(tester, 'farmácia');
    expect(discovery.searchCalls, 1, reason: 'digitar não busca');
    expect(searchButtonEnabled(tester), isTrue);
    await tapExternalSearch(tester);

    expect(discovery.searchCalls, 2);
    expect(discovery.lastQuery, 'farmácia');
    expect(location.calls, 1, reason: 'a posição desta escolha é reaproveitada, sem novo pedido');
  });

  testWidgets('sair de "Perto de mim" descarta a posição; voltar pede de novo', (tester) async {
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await selectNearby(tester);
    await tester.tap(find.byKey(const Key('external_region_mode_city')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('external_region_label')), findsNothing);
    await selectNearby(tester);

    expect(location.calls, 2);
    expect(discovery.searchCalls, 2);
  });

  testWidgets('mesmo termo na mesma cidade não é buscado de novo; outra cidade libera a busca', (tester) async {
    discovery.results = const [_adoptable];
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tapExternalSearch(tester);
    expect(searchButtonEnabled(tester), isFalse);
    await tester.tap(find.byKey(const Key('external_place_search_button')), warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(discovery.searchCalls, 1);

    await chooseCity(tester, 'Curitiba', 'PR');
    expect(searchButtonEnabled(tester), isTrue);
    await tapExternalSearch(tester);
    expect(discovery.searchCalls, 2);
  });

  testWidgets('permissão negada: mensagem própria, sem busca, e "Tentar novamente" pede de novo', (tester) async {
    location.result = const LocationFailure(
      reason: LocationFailureReason.permissionDenied,
      message: 'Permissão de localização negada.',
    );
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await selectNearby(tester);

    expect(discovery.searchCalls, 0);
    expect(find.textContaining('Permissão de localização negada'), findsOneWidget);
    expect(find.byKey(const Key('external_location_settings_button')), findsNothing);

    location.result = const LocationSuccess(
      DeviceLocation(latitude: -25.09, longitude: -50.16, accuracyMeters: 20),
    );
    await tester.tap(find.byKey(const Key('external_location_retry_button')));
    await tester.pumpAndSettle();
    expect(location.calls, 2);
    expect(discovery.searchCalls, 1, reason: 'com a posição, a busca é feita uma vez');
    expect(find.byKey(const Key('external_location_issue')), findsNothing);
  });

  testWidgets('falhas de localização têm mensagens distintas; bloqueio oferece as configurações', (tester) async {
    final cases = <LocationResult, String>{
      const LocationFailure(reason: LocationFailureReason.permissionDeniedForever, message: ''):
          'bloqueada para o Rewit',
      const LocationFailure(reason: LocationFailureReason.serviceDisabled, message: ''): 'GPS',
      const LocationFailure(reason: LocationFailureReason.timeout, message: ''): 'não ficou disponível a tempo',
      const LocationSuccess(DeviceLocation(latitude: 0, longitude: 0, accuracyMeters: 1)): 'recebida é inválida',
    };
    for (final entry in cases.entries) {
      location.result = entry.key;
      await pumpSubject(tester);
      await typeQuery(tester, 'padaria');
      // O estado do widget sobrevive ao novo pump: passar por "Cidade" reinicia a escolha
      await tester.tap(find.byKey(const Key('external_region_mode_city')));
      await tester.pumpAndSettle();
      await selectNearby(tester);
      expect(find.textContaining(entry.value), findsOneWidget, reason: entry.value);
      final permanent = entry.key is LocationFailure &&
          (entry.key as LocationFailure).reason == LocationFailureReason.permissionDeniedForever;
      expect(find.byKey(const Key('external_location_settings_button')), permanent ? findsOneWidget : findsNothing);
      expect(find.byKey(const Key('external_location_emulator_hint')), permanent ? findsNothing : findsOneWidget);
    }
    expect(discovery.searchCalls, 0, reason: 'sem posição válida nenhuma busca é feita');
  });

  testWidgets('"Perto de mim" sem resultados explica a posição simulada do emulador', (tester) async {
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await selectNearby(tester);

    expect(discovery.searchCalls, 1);
    expect(find.textContaining('posição é a simulada'), findsOneWidget);
  });

  testWidgets('erro de rede na busca mostra o motivo e "Tentar novamente" refaz a mesma busca', (tester) async {
    discovery.searchError = const NetworkException('Sem conexão com o servidor.');
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await selectNearby(tester);
    expect(find.text('Sem conexão com o servidor.'), findsOneWidget);

    discovery.searchError = null;
    discovery.results = const [_adoptable];
    await tester.tap(find.byKey(const Key('external_search_retry_button')));
    await tester.pumpAndSettle();
    expect(discovery.searchCalls, 2);
    expect(location.calls, 1, reason: 'a posição já obtida é reaproveitada');
    expect(find.text('Padaria Junior Raikoski'), findsOneWidget);
  });

  testWidgets('cota excedida e chave recusada no backend aparecem como mensagens controladas', (tester) async {
    discovery.searchError = const ApiException(
      ProblemDetail(type: 'about:blank', title: 'Too Many Requests', status: 429, detail: 'limite'),
      retryAfterSeconds: 30,
    );
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await selectNearby(tester);
    expect(find.text('Muitas buscas seguidas. Tente novamente em 30 segundos.'), findsOneWidget);

    discovery.searchError = const ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Bad Gateway',
      status: 502,
      detail: 'Falha na comunicação com o serviço externo de lugares',
      code: 'EXTERNAL_SERVICE_ERROR',
    ));
    await tester.tap(find.byKey(const Key('external_search_retry_button')));
    await tester.pumpAndSettle();
    expect(find.text('A fonte externa não respondeu agora. Tente novamente mais tarde.'), findsOneWidget);
  });

  testWidgets('"Qualquer lugar" avisa que a busca não tem restrição geográfica', (tester) async {
    discovery.results = const [_adoptable];
    await pumpSubject(tester, withLocation: false);
    await typeQuery(tester, 'padaria');

    expect(find.byKey(const Key('external_region_mode_nearby')), findsNothing,
        reason: 'sem serviço de localização a opção não aparece');
    await tester.tap(find.byKey(const Key('external_region_mode_anywhere')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('external_region_anywhere_hint')), findsOneWidget);
    await tapExternalSearch(tester);

    expect(discovery.lastRegion, isA<UnrestrictedRegion>());
    expect(find.text('Sem restrição geográfica: os resultados podem ser de qualquer cidade'), findsOneWidget);
  });

  testWidgets('erros controlados: provedor não configurado e cidade não encontrada', (tester) async {
    search.items = const [_localPlace];
    discovery.searchError = const ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Service Unavailable',
      status: 503,
      detail: 'Integração não configurada',
      code: 'EXTERNAL_SERVICE_NOT_CONFIGURED',
    ));
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tapExternalSearch(tester);
    expect(find.text('A busca em fontes externas não está disponível neste ambiente.'), findsOneWidget);
    expect(find.text('Padaria do Catálogo'), findsOneWidget);

    discovery.searchError = const ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Unprocessable Entity',
      status: 422,
      detail: 'Cidade não encontrada',
      code: 'REGION_NOT_FOUND',
    ));
    await tapExternalSearch(tester);
    expect(find.text('Cidade não encontrada nessa UF. Confira o nome da cidade e a UF.'), findsOneWidget);
  });

  testWidgets('seletor de região e resultados não transbordam em 320 dp', (tester) async {
    discovery.results = const [_adoptable, _withoutState];
    await pumpSubject(tester);
    tester.view.physicalSize = const Size(320, 2400);
    await tester.pumpAndSettle();
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'São José dos Pinhais', 'PR');
    await tapExternalSearch(tester);

    expect(tester.takeException(), isNull);
    expect(find.text('Região: São José dos Pinhais – PR'), findsOneWidget);
  });

  testWidgets('filtro Produtos e ausência do repositório escondem a seção externa', (tester) async {
    await pumpSubject(tester, withDiscovery: false);
    await typeQuery(tester, 'padaria');
    expect(find.byKey(const Key('external_place_results_section')), findsNothing);

    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    expect(find.byKey(const Key('external_place_results_section')), findsOneWidget);
    await tester.tap(find.text('Produtos'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('external_place_results_section')), findsNothing);
  });

  testWidgets('tempo limite (timeout) na busca externa exibe mensagem explicativa e permite tentar novamente', (tester) async {
    discovery.searchError = TimeoutException('Tempo esgotado');
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tapExternalSearch(tester);

    expect(find.text('Tempo limite esgotado ao consultar a fonte externa. Tente novamente.'), findsOneWidget);
    expect(find.byKey(const Key('external_search_retry_button')), findsOneWidget);

    discovery.searchError = null;
    discovery.results = const [_adoptable];
    await tester.tap(find.byKey(const Key('external_search_retry_button')));
    await tester.pumpAndSettle();

    expect(find.text('Padaria Junior Raikoski'), findsOneWidget);
    expect(find.byKey(const Key('external_search_retry_button')), findsNothing);
  });

  testWidgets('bloqueio HTML externo (WAF / 403) é sanitizado para mensagem segura sem vazar markup', (tester) async {
    discovery.searchError = const ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Forbidden',
      status: 403,
      detail: '<!DOCTYPE html><html><body>Attention Required! Cloudflare</body></html>',
    ));
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tapExternalSearch(tester);

    expect(find.text('Não foi possível consultar a fonte externa neste momento. Tente novamente mais tarde.'), findsOneWidget);
    expect(find.textContaining('<!DOCTYPE'), findsNothing);
  });

  testWidgets('descarte da tela durante a leitura de localização não causa exceção nem erro de dispose', (tester) async {
    final completer = Completer<LocationResult>();
    location.locationCompleter = completer;

    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await tester.tap(find.byKey(const Key('external_region_mode_nearby')));
    await tester.pump();

    expect(find.byKey(const Key('external_locating')), findsOneWidget);

    // Desmonta a árvore antes da localização resolver
    await tester.pumpWidget(const SizedBox());

    completer.complete(const LocationSuccess(DeviceLocation(latitude: -25.09, longitude: -50.16, accuracyMeters: 10.0)));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
  });

  testWidgets('respostas fora de ordem: resposta atrasada da busca antiga não sobrescreve o resultado atual', (tester) async {
    final completer1 = Completer<List<ExternalPlaceCandidate>>();
    discovery.searchCompleter = completer1;

    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await chooseCity(tester, 'Ponta Grossa', 'PR');
    await tester.ensureVisible(find.byKey(const Key('external_place_search_button')));
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pump();
    expect(discovery.searchCalls, 1);

    // Altera a query: isso reseta o estado de carregamento e invalida a busca anterior via _sequence
    discovery.searchCompleter = null;
    discovery.results = const [_adoptable];
    await tester.enterText(find.byType(TextFormField).first, 'confeitaria');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pump();

    // Dispara a nova busca para a query atual
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pumpAndSettle();

    expect(find.text('Padaria Junior Raikoski'), findsOneWidget);

    // Agora a busca antiga (padaria) completa com outro resultado atrasado
    completer1.complete(const [_withoutState]);
    await tester.pumpAndSettle();

    // Deve continuar exibindo o resultado atual da busca mais recente e não o da busca atrasada
    expect(find.text('Padaria Junior Raikoski'), findsOneWidget);
    expect(find.text('Padaria Super Pão'), findsNothing);
  });
}
