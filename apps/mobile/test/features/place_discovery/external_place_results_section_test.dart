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

  @override
  Future<LocationResult> getCurrentLocation({Duration timeout = const Duration(seconds: 10)}) async {
    calls++;
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

  testWidgets('"Perto de mim" pede a localização só no toque de busca e envia coordenadas com raio', (tester) async {
    discovery.results = const [_adoptable];
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await tester.tap(find.byKey(const Key('external_region_mode_nearby')));
    await tester.pumpAndSettle();

    expect(location.calls, 0, reason: 'escolher a opção não lê a localização');
    expect(find.byKey(const Key('external_region_nearby_hint')), findsOneWidget);
    await tapExternalSearch(tester);

    expect(location.calls, 1);
    final region = discovery.lastRegion as NearbyRegion;
    expect(region.latitude, -25.09);
    expect(region.longitude, -50.16);
    expect(region.radiusMeters, 10000);
    expect(find.text('Região: até 10 km da sua localização atual'), findsOneWidget);
  });

  testWidgets('localização negada não busca e sugere escolher uma cidade', (tester) async {
    location.result = const LocationFailure(
      reason: LocationFailureReason.permissionDenied,
      message: 'Permissão de localização negada.',
    );
    await pumpSubject(tester);
    await typeQuery(tester, 'padaria');
    await tester.tap(find.byKey(const Key('external_region_mode_nearby')));
    await tester.pumpAndSettle();
    await tapExternalSearch(tester);

    expect(discovery.searchCalls, 0);
    expect(find.textContaining('Escolha uma cidade para buscar sem usar a localização.'), findsOneWidget);
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
}
