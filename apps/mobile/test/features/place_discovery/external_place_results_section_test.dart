import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place_discovery/domain/entities/external_place_candidate.dart';
import 'package:rewit_mobile/features/place_discovery/domain/repositories/place_discovery_repository.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/target_search_selector_widget.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _FakeSearchRepository implements SearchRepository {
  List<SearchResultItem> items = const [];

  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async => SearchPage(
        items: items,
        pageNumber: 0,
        pageSize: 20,
        totalElements: items.length,
        totalPages: 1,
        isLast: true,
      );
}

class _FakeDiscoveryRepository implements PlaceDiscoveryRepository {
  int searchCalls = 0;
  String? lastQuery;
  List<ExternalPlaceCandidate> results = const [];
  Exception? searchError;
  final List<ExternalPlaceCandidate> adopted = [];
  String adoptedPlaceId = '99999999-9999-9999-9999-999999999999';

  @override
  Future<List<ExternalPlaceCandidate>> search({
    required String query,
    double? latitude,
    double? longitude,
    double? radiusMeters,
    int limit = 10,
  }) async {
    searchCalls++;
    lastQuery = query;
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

const _attributions = [
  ExternalPlaceAttribution(text: 'Powered by Geoapify', url: 'https://www.geoapify.com/'),
  ExternalPlaceAttribution(text: '© OpenStreetMap contributors', url: 'https://www.openstreetmap.org/copyright'),
];

const _adoptable = ExternalPlaceCandidate(
  provider: 'GEOAPIFY',
  externalId: 'geo-padaria',
  displayName: 'Padaria Estrela Externa',
  formattedAddress: 'Rua XV de Novembro, 100, Ponta Grossa - PR',
  latitude: -25.0945,
  longitude: -50.1633,
  attributions: _attributions,
  city: 'Ponta Grossa',
  state: 'PR',
  country: 'BR',
);

const _incomplete = ExternalPlaceCandidate(
  provider: 'GEOAPIFY',
  externalId: 'geo-quiosque',
  displayName: 'Quiosque Sem Cidade',
  formattedAddress: 'Praça Central',
  latitude: -25.0,
  longitude: -50.0,
  attributions: _attributions,
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
  late List<SearchResultItem> selected;

  setUp(() {
    search = _FakeSearchRepository();
    discovery = _FakeDiscoveryRepository();
    selected = [];
  });

  Widget buildSubject({bool Function(String)? isTargetSelectedElsewhere, bool withDiscovery = true}) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: TargetSearchSelectorWidget(
            searchRepository: search,
            placeDiscoveryRepository: withDiscovery ? discovery : null,
            targetIndex: 0,
            onTargetSelected: selected.add,
            isTargetSelectedElsewhere: isTargetSelectedElsewhere,
          ),
        ),
      ),
    );
  }

  Future<void> typeQuery(WidgetTester tester, String query) async {
    await tester.enterText(find.byType(TextFormField), query);
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
  }

  testWidgets('seção externa aparece separada do catálogo e só consulta o provedor após toque explícito',
      (tester) async {
    search.items = const [_localPlace];
    discovery.results = const [_adoptable];
    await tester.pumpWidget(buildSubject());
    await typeQuery(tester, 'padaria');

    expect(find.text('Padaria do Catálogo'), findsOneWidget);
    expect(find.byKey(const Key('external_place_results_section')), findsOneWidget);
    expect(discovery.searchCalls, 0, reason: 'digitar não gasta cota do provedor externo');

    await tester.ensureVisible(find.byKey(const Key('external_place_search_button')));
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pumpAndSettle();

    expect(discovery.searchCalls, 1);
    expect(discovery.lastQuery, 'padaria');
    expect(find.text('Resultados externos • Geoapify / OpenStreetMap'), findsOneWidget);
    expect(find.text('Padaria Estrela Externa'), findsOneWidget);
    // Atribuições exigidas pelas fontes, junto dos resultados
    expect(find.text('Powered by Geoapify'), findsOneWidget);
    expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
    // Nada foi adotado nem selecionado só por exibir
    expect(discovery.adopted, isEmpty);
    expect(selected, isEmpty);
  });

  testWidgets('"Adotar e selecionar" adota pelo fluxo oficial e seleciona o local do catálogo como PLACE',
      (tester) async {
    discovery.results = const [_adoptable];
    await tester.pumpWidget(buildSubject());
    await typeQuery(tester, 'padaria');
    await tester.ensureVisible(find.byKey(const Key('external_place_search_button')));
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pumpAndSettle();

    final adoptButton = find.byKey(const Key('adopt_external_candidate_geo-padaria'));
    await tester.ensureVisible(adoptButton);
    await tester.tap(adoptButton);
    await tester.pumpAndSettle();

    expect(discovery.adopted.single.externalId, 'geo-padaria');
    expect(selected.single.id, '99999999-9999-9999-9999-999999999999');
    expect(selected.single.targetType, TargetType.place);
    expect(selected.single.rawTargetType, 'PLACE');
  });

  testWidgets('local adotado que já está em outro alvo não é selecionado de novo', (tester) async {
    discovery.results = const [_adoptable];
    await tester.pumpWidget(buildSubject(isTargetSelectedElsewhere: (id) => id == discovery.adoptedPlaceId));
    await typeQuery(tester, 'padaria');
    await tester.ensureVisible(find.byKey(const Key('external_place_search_button')));
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('adopt_external_candidate_geo-padaria')));
    await tester.tap(find.byKey(const Key('adopt_external_candidate_geo-padaria')));
    await tester.pumpAndSettle();

    expect(selected, isEmpty);
    expect(find.text('O local "Padaria Estrela Externa" já foi adicionado a esta avaliação.'), findsOneWidget);
  });

  testWidgets('candidato sem endereço completo não oferece adoção', (tester) async {
    discovery.results = const [_incomplete];
    await tester.pumpWidget(buildSubject());
    await typeQuery(tester, 'quiosque');
    await tester.ensureVisible(find.byKey(const Key('external_place_search_button')));
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pumpAndSettle();

    expect(find.text('Quiosque Sem Cidade'), findsOneWidget);
    expect(find.byKey(const Key('adopt_external_candidate_geo-quiosque')), findsNothing);
    expect(find.textContaining('endereço incompleto'), findsOneWidget);
  });

  testWidgets('provedor não configurado no backend mostra aviso controlado e mantém a busca local',
      (tester) async {
    search.items = const [_localPlace];
    discovery.searchError = const ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Service Unavailable',
      status: 503,
      detail: 'Serviço de descoberta não configurado',
      code: 'EXTERNAL_SERVICE_NOT_CONFIGURED',
    ));
    await tester.pumpWidget(buildSubject());
    await typeQuery(tester, 'padaria');
    await tester.ensureVisible(find.byKey(const Key('external_place_search_button')));
    await tester.tap(find.byKey(const Key('external_place_search_button')));
    await tester.pumpAndSettle();

    expect(find.text('A busca em fontes externas não está disponível neste ambiente.'), findsOneWidget);
    expect(find.text('Padaria do Catálogo'), findsOneWidget);
  });

  testWidgets('filtro Produtos e ausência do repositório escondem a seção externa', (tester) async {
    await tester.pumpWidget(buildSubject(withDiscovery: false));
    await typeQuery(tester, 'padaria');
    expect(find.byKey(const Key('external_place_results_section')), findsNothing);

    await tester.pumpWidget(buildSubject());
    await typeQuery(tester, 'padaria');
    expect(find.byKey(const Key('external_place_results_section')), findsOneWidget);
    await tester.tap(find.text('Produtos'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('external_place_results_section')), findsNothing);
  });
}
