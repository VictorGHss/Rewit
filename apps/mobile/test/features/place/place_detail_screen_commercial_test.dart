import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_detail_screen.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_commercial_notifier.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_detail_notifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'dart:typed_data';

class _FakePlaceRepository implements PlaceRepository {
  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) async =>
      throw UnimplementedError();

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    return const PlaceDetail(
      id: 'place-comm-1',
      name: 'Café & Bistrô Central',
      slug: 'cafe-bistro-central',
      category: 'RESTAURANTE',
      description: 'Cafés especiais e refeições artesanais.',
      addressText: 'Avenida Sete de Setembro',
      streetNumber: '1000',
      neighborhood: 'Centro',
      city: 'Curitiba',
      state: 'PR',
      latitude: -25.43,
      longitude: -49.27,
      validationRadiusMeters: 50,
      origin: 'USER',
      isVerified: true,
      status: 'ACTIVE',
    );
  }

  @override
  Future<TargetStats> getTargetStats(String id) async {
    return const TargetStats(
      targetId: 'place-comm-1',
      averageRating: 4.9,
      reviewsCount: 30,
    );
  }

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async {
    return const TargetReviewsPage(
      reviews: [],
      pageNumber: 0,
      pageSize: 10,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }
}

class _FakeProductRepository implements ProductRepository {
  @override
  Future<ProductsInPlacePage> getProductsInPlace(
    String placeId, {
    int page = 0,
    int size = 20,
  }) async {
    return const ProductsInPlacePage(
      products: [
        ProductDetail(
          id: 'prod-communitario-1',
          name: 'Pão de Queijo Mineiro',
          brand: 'Padaria Mineira',
          category: 'PADARIA',
          status: 'ACTIVE',
        ),
      ],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 1,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<ProductDetail> getProductById(String id) async => throw UnimplementedError();
  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) async => throw UnimplementedError();
  @override
  Future<ProductDetail> getProductByIdentifier({required String type, required String value}) async => throw UnimplementedError();
  @override
  Future<TargetStats> getProductStats(String id) async => throw UnimplementedError();
  @override
  Future<TargetReviewsPage> getProductReviews(String productId, {int page = 0, int size = 10, String sort = 'newest', bool verifiedOnly = false}) async => throw UnimplementedError();
  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async => throw UnimplementedError();
}

class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceOpeningHours? openingHours;
  PlaceAmenities? amenities;
  PlaceOffersPage? offersPage;
  bool throwOpeningHoursError = false;
  bool throwAmenitiesError = false;
  bool throwOffersError = false;

  int loadOffersCalls = 0;

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async {
    if (throwOpeningHoursError) throw Exception('Falha ao carregar horários');
    return openingHours ??
        const PlaceOpeningHours(
          placeId: 'place-comm-1',
          configured: true,
          days: [
            DaySchedule(
              dayOfWeek: 1,
              dayName: 'MONDAY',
              closed: false,
              intervals: [
                TimeInterval(openTime: '08:00', closeTime: '12:00'),
                TimeInterval(openTime: '13:30', closeTime: '18:00'),
              ],
            ),
            DaySchedule(
              dayOfWeek: 2,
              dayName: 'TUESDAY',
              closed: false,
              intervals: [TimeInterval(openTime: '08:00', closeTime: '18:00')],
            ),
            DaySchedule(
              dayOfWeek: 3,
              dayName: 'WEDNESDAY',
              closed: false,
              intervals: [TimeInterval(openTime: '08:00', closeTime: '18:00')],
            ),
            DaySchedule(
              dayOfWeek: 4,
              dayName: 'THURSDAY',
              closed: false,
              intervals: [TimeInterval(openTime: '08:00', closeTime: '18:00')],
            ),
            DaySchedule(
              dayOfWeek: 5,
              dayName: 'FRIDAY',
              closed: false,
              intervals: [TimeInterval(openTime: '08:00', closeTime: '18:00')],
            ),
            DaySchedule(
              dayOfWeek: 6,
              dayName: 'SATURDAY',
              closed: false,
              intervals: [TimeInterval(openTime: '09:00', closeTime: '13:00')],
            ),
            DaySchedule(
              dayOfWeek: 7,
              dayName: 'SUNDAY',
              closed: true,
              intervals: [],
            ),
          ],
        );
  }

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async {
    if (throwAmenitiesError) throw Exception('Falha ao carregar comodidades');
    return amenities ??
        const PlaceAmenities(
          placeId: 'place-comm-1',
          configured: true,
          amenities: [
            PlaceAmenityItem(code: 'WIFI', label: 'Wi-Fi para clientes'),
            PlaceAmenityItem(code: 'AIR_CONDITIONING', label: 'Ambiente climatizado'),
            PlaceAmenityItem(code: 'PET_FRIENDLY', label: 'Aceita animais'),
          ],
        );
  }

  @override
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 50}) async {
    loadOffersCalls++;
    if (throwOffersError) throw Exception('Falha ao carregar ofertas');
    if (page == 1) {
      return const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(
              id: 'prod-paginada-2',
              name: 'Capuccino Especial Italiano',
              category: 'BEBIDA',
            ),
            availability: 'AVAILABLE',
            price: 14.50,
          ),
        ],
        pageNumber: 1,
        pageSize: 50,
        totalElements: 4,
        totalPages: 2,
        isLast: true,
      );
    }

    return offersPage ??
        const PlaceOffersPage(
          content: [
            PlaceProductOffer(
              product: OfferedProductSummary(
                id: 'prod-oficial-1',
                name: 'Espresso Blend Supremo',
                brand: 'Bistrô',
                model: '2026',
                category: 'BEBIDA',
              ),
              availability: 'AVAILABLE',
              price: 9.90,
            ),
            PlaceProductOffer(
              product: OfferedProductSummary(
                id: 'prod-oficial-2',
                name: 'Degustação de Água Filtrada',
                category: 'BEBIDA',
              ),
              availability: 'AVAILABLE',
              price: 0.0,
            ),
            PlaceProductOffer(
              product: OfferedProductSummary(
                id: 'prod-oficial-3',
                name: 'Torta Holandesa Fatia',
                category: 'SOBREMESA',
              ),
              availability: 'OUT_OF_STOCK',
              price: null,
            ),
          ],
          pageNumber: 0,
          pageSize: 50,
          totalElements: 4,
          totalPages: 2,
          isLast: false,
        );
  }
}

void main() {
  Widget buildTestableWidget({
    required Widget child,
    Map<String, WidgetBuilder>? routes,
  }) {
    return MaterialApp(
      home: child,
      routes: routes ?? {},
    );
  }

  group('PlaceDetailScreen Commercial Information Integration Tests', () {
    late _FakePlaceRepository placeRepository;
    late _FakeProductRepository productRepository;
    late _FakeCommercialRepository commercialRepository;

    setUp(() {
      placeRepository = _FakePlaceRepository();
      productRepository = _FakeProductRepository();
      commercialRepository = _FakeCommercialRepository();
    });

    testWidgets('exibe grade semanal de horários, intervalos e dias fechados quando configurados', (tester) async {
      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      expect(find.text('Horários de Funcionamento'), findsOneWidget);
      expect(find.text('Segunda-feira'), findsOneWidget);
      expect(find.text('08:00 às 12:00, 13:30 às 18:00'), findsOneWidget);
      expect(find.text('Domingo'), findsOneWidget);
      expect(find.text('Fechado'), findsOneWidget);
    });

    testWidgets('exibe mensagem apropriada quando horários não estão configurados (configured=false)', (tester) async {
      commercialRepository.openingHours = const PlaceOpeningHours(
        placeId: 'place-comm-1',
        configured: false,
        days: [],
      );

      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      expect(find.text('Horários não informados pelo estabelecimento.'), findsOneWidget);
    });

    testWidgets('exibe comodidades configuradas com ícones e rótulos em português', (tester) async {
      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      expect(find.text('Comodidades'), findsOneWidget);
      expect(find.text('Wi-Fi para clientes'), findsOneWidget);
      expect(find.text('Ambiente climatizado'), findsOneWidget);
      expect(find.text('Aceita animais'), findsOneWidget);
      expect(find.byIcon(Icons.wifi), findsOneWidget);
      expect(find.byIcon(Icons.pets), findsOneWidget);
    });

    testWidgets('diferencia comodidades não configuradas de declaração explícita de nenhuma', (tester) async {
      // 1. Declaração explícita de nenhuma comodidade (configured=true, amenities=[])
      commercialRepository.amenities = const PlaceAmenities(
        placeId: 'place-comm-1',
        configured: true,
        amenities: [],
      );

      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      expect(
        find.text('O estabelecimento declarou não oferecer comodidades deste catálogo.'),
        findsOneWidget,
      );

      // 2. Não configuradas (configured=false)
      commercialRepository.amenities = const PlaceAmenities(
        placeId: 'place-comm-1',
        configured: false,
        amenities: [],
      );
      await commercialNotifier.loadAmenities();
      await tester.pumpAndSettle();

      expect(
        find.text('Comodidades não informadas pelo estabelecimento.'),
        findsOneWidget,
      );
    });

    testWidgets('exibe ofertas oficiais com preços (positivo, gratuito, ausente) e disponibilidade (Disponível, Sem estoque)', (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
            productRepository: productRepository,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      // Distinção visual: seção oficial vs comunitária
      expect(find.text('Ofertas Oficiais do Estabelecimento'), findsOneWidget);
      expect(find.text('Catálogo comercial publicado pelo estabelecimento'), findsOneWidget);

      // Rola até a seção comunitária para garantir renderização na lista
      await tester.scrollUntilVisible(find.text('Produtos neste local'), 200);
      await tester.pumpAndSettle();

      expect(find.text('Produtos neste local'), findsOneWidget); // Comunitário
      expect(find.textContaining('Pão de Queijo Mineiro'), findsOneWidget); // Comunitário

      // Rola de volta para conferir as ofertas comerciais detalhadas
      await tester.scrollUntilVisible(find.text('Espresso Blend Supremo'), -200);
      await tester.pumpAndSettle();

      // Oferta 1: Preço positivo e disponível
      expect(find.text('Espresso Blend Supremo'), findsOneWidget);
      expect(find.text('Bistrô - 2026'), findsOneWidget);
      expect(find.text('R\$ 9,90'), findsOneWidget);
      expect(find.text('Disponível'), findsNWidgets(2)); // Oferta 1 e 2

      // Oferta 2: Preço gratuito
      expect(find.text('Degustação de Água Filtrada'), findsOneWidget);
      expect(find.text('Gratuito'), findsOneWidget);

      // Oferta 3: Sem estoque e preço não informado
      expect(find.text('Torta Holandesa Fatia'), findsOneWidget);
      expect(find.text('Sem estoque'), findsOneWidget);
      expect(find.text('Preço não informado'), findsOneWidget);
    });

    testWidgets('paginação de ofertas comerciais carrega próxima página e botão desaparece no fim', (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await tester.pumpAndSettle();

      expect(commercialRepository.loadOffersCalls, 1);

      final loadMoreButton = find.textContaining('Carregar mais ofertas');
      await tester.scrollUntilVisible(loadMoreButton, 200);
      await tester.pumpAndSettle();
      expect(loadMoreButton, findsOneWidget);

      await tester.tap(loadMoreButton);
      await tester.pumpAndSettle();

      expect(commercialRepository.loadOffersCalls, 2);
      expect(find.text('Capuccino Especial Italiano'), findsOneWidget);
      expect(find.text('R\$ 14,50'), findsOneWidget);
      expect(find.textContaining('Carregar mais ofertas'), findsNothing, reason: 'última página oculta o botão');
    });

    testWidgets('toque na oferta oficial navega para detalhes do produto com contextPlace', (tester) async {
      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      Object? receivedArgs;

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
          ),
          routes: {
            AppRouter.productDetail: (context) {
              receivedArgs = ModalRoute.of(context)?.settings.arguments;
              return const Scaffold(body: Text('ProductDetail Target'));
            },
          },
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(find.text('Espresso Blend Supremo'), 200);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Espresso Blend Supremo'));
      await tester.pumpAndSettle();

      expect(find.text('ProductDetail Target'), findsOneWidget);
      expect(receivedArgs, isA<Map<String, dynamic>>());
      final map = receivedArgs as Map<String, dynamic>;
      expect(map['productId'], 'prod-oficial-1');
      expect(map['contextPlace'], isA<PlaceDetail>());
    });

    testWidgets('falha isolada em cada seção comercial exibe erro sutil sem travar a tela principal nem avaliações', (tester) async {
      commercialRepository.throwOpeningHoursError = true;
      commercialRepository.throwAmenitiesError = true;
      commercialRepository.throwOffersError = true;

      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      // Informações básicas e cabeçalho continuam visíveis normalmente
      expect(find.text('Café & Bistrô Central'), findsOneWidget);

      await tester.scrollUntilVisible(find.text('Avaliações da Comunidade'), 200);
      await tester.pumpAndSettle();
      expect(find.text('Avaliações da Comunidade'), findsOneWidget);

      // Erros específicos e botões de tentar novamente
      expect(find.text('Não foi possível carregar os horários de funcionamento.'), findsOneWidget);
      expect(find.text('Não foi possível carregar as comodidades.'), findsOneWidget);
      expect(find.text('Não foi possível carregar as ofertas comerciais.'), findsOneWidget);
      expect(find.text('Tentar novamente'), findsNWidgets(3));
    });

    testWidgets('pull-to-refresh atualiza tanto os dados básicos quanto os dados comerciais', (tester) async {
      final placeNotifier = PlaceDetailNotifier(
        repository: placeRepository,
        placeId: 'place-comm-1',
      );
      final commercialNotifier = PlaceCommercialNotifier(
        repository: commercialRepository,
        placeId: 'place-comm-1',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-comm-1',
            notifier: placeNotifier,
            commercialNotifier: commercialNotifier,
            productRepository: productRepository,
          ),
        ),
      );

      await placeNotifier.loadPlace();
      await commercialNotifier.loadAll();
      await tester.pumpAndSettle();

      final initialCalls = commercialRepository.loadOffersCalls;

      // Dispara pull-to-refresh puxando a lista para baixo
      await tester.drag(find.byType(ListView), const Offset(0, 300));
      await tester.pumpAndSettle();

      expect(commercialRepository.loadOffersCalls, initialCalls + 1, reason: 'pull-to-refresh deve recarregar os dados comerciais');
    });
  });
}
