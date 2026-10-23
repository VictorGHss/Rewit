import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/services/url_launcher_service.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/business/domain/entities/business_entities.dart';
import 'package:rewit_mobile/features/business/domain/repositories/business_repository.dart';
import 'package:rewit_mobile/features/place/data/models/place_dtos.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_amenities_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_business_profile_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_detail_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_offers_management_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_opening_hours_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/place_amenities_section.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/place_commercial_offers_section.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/place_opening_hours_section.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _StubAuthRepo implements AuthRepository {
  @override
  Future<bool> hasStoredSession() async => true;
  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'user-1',
        email: 'user@rewit.com',
        handle: 'usuario',
        displayName: 'Usuário Gestor',
        isVerified: true,
        reputationScore: 100,
      );
  @override
  Future<Authenticated> login({required String email, required String password}) async =>
      throw UnimplementedError();
  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();
  @override
  Future<void> logout() async {}
  @override
  Future<Authenticated> reactivate({required String email, required String password}) async =>
      throw UnimplementedError();
  @override
  Future<void> deactivateAccount() async {}
  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {}
}

class _E2ECommercialRepository implements PlaceCommercialRepository {
  PlaceOpeningHours? currentHours;
  List<PlaceAmenityItem>? amenityCatalog;
  PlaceAmenities? currentAmenities;
  List<PlaceProductOffer>? currentOffers;
  PlaceDetail? currentProfile;
  Exception? errorToThrow;

  Map<String, dynamic>? lastUpdateProfilePayload;
  List<DaySchedule>? lastUpdatedDays;
  List<String>? lastUpdatedAmenityCodes;
  String? lastUpsertOfferPlaceId;
  String? lastUpsertOfferProductId;
  double? lastUpsertOfferPrice;
  String? lastUpsertOfferAvailability;

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async {
    if (errorToThrow != null) throw errorToThrow!;
    return currentHours ??
        PlaceOpeningHours(
          placeId: placeId,
          configured: true,
          days: const [
            DaySchedule(
              dayOfWeek: 1,
              dayName: 'MONDAY',
              closed: false,
              intervals: [TimeInterval(openTime: '08:00', closeTime: '18:00')],
            ),
            DaySchedule(
              dayOfWeek: 2,
              dayName: 'TUESDAY',
              closed: true,
              intervals: [],
            ),
          ],
        );
  }

  @override
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  }) async {
    if (errorToThrow != null) throw errorToThrow!;
    lastUpdatedDays = days;
    currentHours = PlaceOpeningHours(
      placeId: placeId,
      configured: true,
      days: days,
    );
    return currentHours!;
  }

  @override
  Future<List<PlaceAmenityItem>> getAmenityOptions() async {
    if (errorToThrow != null) throw errorToThrow!;
    return amenityCatalog ??
        const [
          PlaceAmenityItem(code: 'WIFI', label: 'Wi-Fi gratuito'),
          PlaceAmenityItem(code: 'PARKING', label: 'Estacionamento próprio'),
          PlaceAmenityItem(code: 'PET_FRIENDLY', label: 'Aceita animais'),
        ];
  }

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async {
    if (errorToThrow != null) throw errorToThrow!;
    return currentAmenities ??
        PlaceAmenities(
          placeId: placeId,
          configured: true,
          amenities: const [
            PlaceAmenityItem(code: 'WIFI', label: 'Wi-Fi gratuito'),
          ],
        );
  }

  @override
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
  }) async {
    if (errorToThrow != null) throw errorToThrow!;
    lastUpdatedAmenityCodes = amenityCodes;
    final catalog = await getAmenityOptions();
    final selectedItems = catalog.where((opt) => amenityCodes.contains(opt.code)).toList();
    currentAmenities = PlaceAmenities(
      placeId: placeId,
      configured: true,
      amenities: selectedItems,
    );
    return currentAmenities!;
  }

  @override
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 50}) async {
    if (errorToThrow != null) throw errorToThrow!;
    final offers = currentOffers ??
        [
          const PlaceProductOffer(
            product: OfferedProductSummary(
              id: 'prod-1',
              name: 'Pão Francês Artesanal',
              category: 'PADARIA',
            ),
            availability: 'DISPONIVEL',
            price: 10.00,
          ),
        ];
    return PlaceOffersPage(
      content: offers,
      pageNumber: 0,
      pageSize: size,
      totalElements: offers.length,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<PlaceProductOffer> upsertOffer({
    required String placeId,
    required String businessAccountId,
    required String productId,
    required String availability,
    double? price,
    String currency = 'BRL',
  }) async {
    if (errorToThrow != null) throw errorToThrow!;
    lastUpsertOfferPlaceId = placeId;
    lastUpsertOfferProductId = productId;
    lastUpsertOfferAvailability = availability;
    lastUpsertOfferPrice = price;

    final updatedList = List<PlaceProductOffer>.from(currentOffers ?? []);
    final existingIndex = updatedList.indexWhere((o) => o.product.id == productId);
    final newOffer = PlaceProductOffer(
      product: existingIndex >= 0
          ? updatedList[existingIndex].product
          : OfferedProductSummary(id: productId, name: 'Produto Teste', category: 'CAT'),
      availability: availability,
      price: price,
      currency: currency,
    );
    if (existingIndex >= 0) {
      updatedList[existingIndex] = newOffer;
    } else {
      updatedList.add(newOffer);
    }
    currentOffers = updatedList;
    return newOffer;
  }

  @override
  Future<void> deleteOffer({
    required String placeId,
    required String businessAccountId,
    required String productId,
  }) async {
    if (errorToThrow != null) throw errorToThrow!;
    currentOffers = (currentOffers ?? []).where((o) => o.product.id != productId).toList();
  }

  @override
  Future<PlaceDetail> getBusinessProfile(String placeId) async {
    if (errorToThrow != null) throw errorToThrow!;
    return currentProfile ??
        PlaceDetail(
          id: placeId,
          name: 'Padaria Central Centro',
          slug: 'padaria-central-centro',
          category: 'PADARIA',
          description: 'Padaria artesanal tradicional',
          addressText: 'Rua das Flores, 100',
          city: 'São Paulo',
          state: 'SP',
          validationRadiusMeters: 50,
          origin: 'CLAIMED',
          isVerified: true,
          status: 'ACTIVE',
          phone: '+55 (11) 98765-4321',
          websiteUrl: 'https://padariacentral.com.br',
          instagramUrl: 'https://instagram.com/padariacentral',
          facebookUrl: 'https://facebook.com/padariacentral',
        );
  }

  @override
  Future<PlaceDetail> updateBusinessProfile({
    required String placeId,
    required String businessAccountId,
    String? description,
    String? phone,
    String? websiteUrl,
    String? instagramUrl,
    String? facebookUrl,
  }) async {
    lastUpdateProfilePayload = UpdatePlaceBusinessProfileRequestDto(
      businessAccountId: businessAccountId,
      description: description,
      phone: phone,
      websiteUrl: websiteUrl,
      instagramUrl: instagramUrl,
      facebookUrl: facebookUrl,
    ).toJson();

    if (errorToThrow != null) throw errorToThrow!;

    final existing = await getBusinessProfile(placeId);
    currentProfile = PlaceDetail(
      id: placeId,
      name: existing.name,
      slug: existing.slug,
      category: existing.category,
      description: (description != null && description.trim().isNotEmpty) ? description.trim() : null,
      addressText: existing.addressText,
      city: existing.city,
      state: existing.state,
      validationRadiusMeters: existing.validationRadiusMeters,
      origin: existing.origin,
      isVerified: existing.isVerified,
      status: existing.status,
      phone: (phone != null && phone.trim().isNotEmpty) ? phone.trim() : null,
      websiteUrl: (websiteUrl != null && websiteUrl.trim().isNotEmpty) ? websiteUrl.trim() : null,
      instagramUrl: (instagramUrl != null && instagramUrl.trim().isNotEmpty) ? instagramUrl.trim() : null,
      facebookUrl: (facebookUrl != null && facebookUrl.trim().isNotEmpty) ? facebookUrl.trim() : null,
    );
    return currentProfile!;
  }
}

class _E2EPlaceRepository implements PlaceRepository {
  PlaceDetail? placeToReturn;

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    return placeToReturn ??
        PlaceDetail(
          id: id,
          name: 'Padaria Central Centro',
          slug: 'padaria-central-centro',
          category: 'PADARIA',
          description: 'Padaria artesanal tradicional',
          addressText: 'Rua das Flores, 100',
          city: 'São Paulo',
          state: 'SP',
          validationRadiusMeters: 50,
          origin: 'CLAIMED',
          isVerified: true,
          status: 'ACTIVE',
          phone: '+55 (11) 98765-4321',
          websiteUrl: 'https://padariacentral.com.br',
          instagramUrl: 'https://instagram.com/padariacentral',
          facebookUrl: 'https://facebook.com/padariacentral',
        );
  }

  @override
  Future<TargetStats> getTargetStats(String id) async {
    return const TargetStats(
      targetId: 'place-1',
      averageRating: 4.8,
      reviewsCount: 15,
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

  @override
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId) async => [];

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) async => throw UnimplementedError();

  @override
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? idempotencyKey,
  }) async =>
      throw UnimplementedError();

  @override
  Future<PlaceMediaItem> uploadPlaceMedia({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? mimeType,
    String? idempotencyKey,
  }) async =>
      throw UnimplementedError();

  @override
  Future<PlaceMediaItem> setPrimaryPlaceMedia({
    required String placeId,
    required String mediaId,
  }) async =>
      throw UnimplementedError();

  @override
  Future<void> deletePlaceMedia({
    required String placeId,
    required String mediaId,
  }) async {}
}

class _E2EBusinessRepository implements BusinessRepository {
  List<BusinessAccount> accounts = [];
  Map<String, List<PlaceClaim>> claims = {};

  @override
  Future<List<BusinessAccount>> getMyBusinessAccounts() async => accounts;

  @override
  Future<List<PlaceClaim>> getBusinessPlaceClaims(
    String businessAccountId, {
    int page = 0,
    int size = 20,
    String? status,
  }) async =>
      claims[businessAccountId] ?? [];

  @override
  Future<BusinessAccount> createBusinessAccount({
    required String corporateName,
    required String taxId,
  }) async =>
      throw UnimplementedError();

  @override
  Future<PlaceClaim> requestPlaceClaim({
    required String businessAccountId,
    required String placeId,
    required String evidenceDescription,
  }) async =>
      throw UnimplementedError();
}

class _E2ESearchRepository implements SearchRepository {
  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async {
    return const SearchPage(items: [], pageNumber: 0, pageSize: 20, totalElements: 0, totalPages: 0, isLast: true);
  }
}

class _E2EProductRepository implements ProductRepository {
  @override
  Future<ProductsInPlacePage> getProductsInPlace(String placeId, {int page = 0, int size = 20}) async {
    return const ProductsInPlacePage(
      products: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
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
  Future<Uint8List> getProductImageBytes(String imageUrl) async => Uint8List(0);
  @override
  Future<TargetReviewsPage> getProductReviews(String targetId, {int page = 0, int size = 10, String sort = 'newest', bool verifiedOnly = false}) async => const TargetReviewsPage(reviews: [], pageNumber: 0, pageSize: 10, totalElements: 0, totalPages: 0, isLast: true);
  @override
  Future<TargetStats> getProductStats(String id) async => const TargetStats(targetId: 'prod-1', averageRating: 4.5, reviewsCount: 10);
}

class _E2EUrlLauncherService implements UrlLauncherService {
  String? lastLaunchedWebUrl;
  String? lastLaunchedPhone;
  LaunchResult webUrlResult = LaunchResult.success;
  LaunchResult phoneResult = LaunchResult.success;

  @override
  Future<LaunchResult> launchWebUrl(String url) async {
    lastLaunchedWebUrl = url;
    return webUrlResult;
  }

  @override
  Future<LaunchResult> launchPhone(String phone) async {
    lastLaunchedPhone = phone;
    return phoneResult;
  }
}

void main() {
  late _E2ECommercialRepository commercialRepo;
  late _E2EPlaceRepository placeRepo;
  late _E2EBusinessRepository businessRepo;
  late _E2ESearchRepository searchRepo;
  late _E2EProductRepository productRepo;
  late _E2EUrlLauncherService urlLauncherService;
  late AuthNotifier authNotifier;

  setUp(() {
    commercialRepo = _E2ECommercialRepository();
    placeRepo = _E2EPlaceRepository();
    businessRepo = _E2EBusinessRepository();
    searchRepo = _E2ESearchRepository();
    productRepo = _E2EProductRepository();
    urlLauncherService = _E2EUrlLauncherService();
    authNotifier = AuthNotifier(authRepository: _StubAuthRepo());

    businessRepo.accounts = [
      BusinessAccount(
        id: 'acc-padaria-1',
        corporateName: 'Padaria Central Ltda',
        taxId: '12345678000190',
        verificationStatus: 'APPROVED',
        planTier: 'FREE',
        createdAt: DateTime.now(),
        updatedAt: DateTime.now(),
      ),
    ];

    businessRepo.claims['acc-padaria-1'] = [
      PlaceClaim(
        id: 'claim-1',
        businessAccountId: 'acc-padaria-1',
        corporateName: 'Padaria Central Ltda',
        taxId: '12345678000190',
        placeId: 'place-padaria-1',
        placeName: 'Padaria Central Centro',
        city: 'São Paulo',
        state: 'SP',
        status: 'APPROVED',
        evidenceDescription: 'Contrato social e conta de energia comercial.',
        createdAt: DateTime.now(),
        decidedAt: DateTime.now(),
      ),
    ];
  });

  Widget buildApp({String initialRoute = AppRouter.businessAccounts}) {
    final router = AppRouter(
      authNotifier: authNotifier,
      placeRepository: placeRepo,
      placeCommercialRepository: commercialRepo,
      searchRepository: searchRepo,
      businessRepository: businessRepo,
      productRepository: productRepo,
    );

    return MaterialApp(
      initialRoute: initialRoute,
      onGenerateRoute: router.onGenerateRoute,
    );
  }

  group('QA2: Jornada Integrada Mobile F2.1-F2.5', () {
    testWidgets('1 e 2. Navegação completa de Contas Comerciais -> Dashboard -> Subtelas com argumentos válidos',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();

      // 1. Contas comerciais exibe botão de gerenciar estabelecimento aprovado
      expect(find.text('Padaria Central Centro'), findsOneWidget);
      final manageButton = find.byKey(const Key('manage_place_button_place-padaria-1'));
      expect(manageButton, findsOneWidget);

      await tester.tap(manageButton);
      await tester.pumpAndSettle();

      // Transicionado para PlaceManagementDashboardScreen
      expect(find.text('Gestão do Estabelecimento'), findsOneWidget);
      expect(find.text('Padaria Central Centro'), findsOneWidget);
      expect(find.text('São Paulo / SP'), findsOneWidget);

      // 2. Abertura e retorno de cada opção
      // 2.a Horários
      await tester.tap(find.byKey(const Key('manage_opening_hours_option')));
      await tester.pumpAndSettle();
      expect(find.text('Horários de Funcionamento'), findsOneWidget);
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();

      // 2.b Comodidades
      await tester.tap(find.byKey(const Key('manage_amenities_option')));
      await tester.pumpAndSettle();
      expect(find.text('Comodidades'), findsOneWidget);
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();

      // 2.c Ofertas
      await tester.tap(find.byKey(const Key('manage_offers_option')));
      await tester.pumpAndSettle();
      expect(find.text('Ofertas comerciais'), findsOneWidget);
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();

      // 2.d Perfil comercial
      await tester.tap(find.byKey(const Key('manage_business_profile_option')));
      await tester.pumpAndSettle();
      expect(find.text('Perfil Comercial'), findsOneWidget);
      await tester.tap(find.byTooltip('Back'));
      await tester.pumpAndSettle();

      // 2.e Ver perfil público
      await tester.tap(find.byKey(const Key('view_public_profile_option')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('place_public_contacts_section')), findsOneWidget);
      expect(find.text('Contatos e Redes Sociais'), findsOneWidget);
    });

    testWidgets('3. Ciclo completo de edição, salvamento, retorno e reabertura consistente em Horários (F2.1)',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      const args = PlaceOpeningHoursEditArgs(
        placeId: 'place-padaria-1',
        businessAccountId: 'acc-padaria-1',
        placeName: 'Padaria Central Centro',
        isApproved: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceOpeningHoursEditScreen(
                args: args,
                commercialRepository: commercialRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      // Inicialmente segunda-feira (day_switch_1) está aberta (true)
      final mondaySwitch = find.byKey(const Key('day_switch_1'));
      expect(mondaySwitch, findsOneWidget);

      // Clica no switch para fechar a segunda-feira
      await tester.tap(mondaySwitch);
      await tester.pumpAndSettle();

      // Salva
      await tester.tap(find.byKey(const Key('save_opening_hours_button')));
      await tester.pumpAndSettle();

      expect(find.text('Horários de funcionamento atualizados com sucesso.'), findsOneWidget);
      expect(commercialRepo.lastUpdatedDays, isNotNull);
      final mondaySaved = commercialRepo.lastUpdatedDays!.firstWhere((d) => d.dayOfWeek == 1);
      expect(mondaySaved.closed, isTrue);

      // Reabre a tela com novo widget (simulando retorno do usuário)
      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceOpeningHoursEditScreen(
                args: args,
                commercialRepository: commercialRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      // Confirma que agora segunda-feira carrega como fechada
      final reloadedSwitch = tester.widget<Switch>(find.byKey(const Key('day_switch_1')));
      expect(reloadedSwitch.value, isFalse);
    });

    testWidgets('3. Ciclo completo de edição, salvamento, retorno e reabertura consistente em Comodidades (F2.2)',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      const args = PlaceAmenitiesEditArgs(
        placeId: 'place-padaria-1',
        businessAccountId: 'acc-padaria-1',
        placeName: 'Padaria Central Centro',
        isApproved: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceAmenitiesEditScreen(
                args: args,
                commercialRepository: commercialRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      // Seleciona Estacionamento (PARKING)
      final parkingCheckbox = find.byKey(const Key('amenity_checkbox_PARKING'));
      expect(parkingCheckbox, findsOneWidget);
      await tester.tap(parkingCheckbox);
      await tester.pumpAndSettle();

      // Salva
      await tester.tap(find.byKey(const Key('save_amenities_button')));
      await tester.pumpAndSettle();

      expect(find.text('Comodidades atualizadas com sucesso.'), findsOneWidget);
      expect(commercialRepo.lastUpdatedAmenityCodes, contains('PARKING'));
      expect(commercialRepo.lastUpdatedAmenityCodes, contains('WIFI'));

      // Reabre a tela e verifica persistência
      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceAmenitiesEditScreen(
                args: args,
                commercialRepository: commercialRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      final reloadedParking = tester.widget<CheckboxListTile>(find.byKey(const Key('amenity_checkbox_PARKING')));
      expect(reloadedParking.value, isTrue);
    });

    testWidgets('3. Ciclo completo de edição, salvamento, retorno e reabertura consistente em Ofertas (F2.3)',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      const args = PlaceOffersManagementArgs(
        placeId: 'place-padaria-1',
        businessAccountId: 'acc-padaria-1',
        placeName: 'Padaria Central Centro',
        isApproved: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceOffersManagementScreen(
                args: args,
                commercialRepository: commercialRepo,
                searchRepository: searchRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Pão Francês Artesanal'), findsOneWidget);
      expect(find.text('R\$ 10,00'), findsOneWidget);

      // Clica em editar a oferta existente
      await tester.tap(find.byKey(const Key('edit_offer_button_prod-1')));
      await tester.pumpAndSettle();

      // Altera preço para 14.50
      final priceInput = find.byKey(const Key('offer_price_input'));
      await tester.enterText(priceInput, '14,50');
      await tester.pumpAndSettle();

      // Salva a oferta no bottom sheet
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(find.text('Oferta atualizada com sucesso.'), findsOneWidget);
      expect(commercialRepo.lastUpsertOfferPrice, 14.50);

      // Reabre e confirma novo preço
      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceOffersManagementScreen(
                args: args,
                commercialRepository: commercialRepo,
                searchRepository: searchRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('R\$ 14,50'), findsOneWidget);
    });

    testWidgets('3 e 4. Ciclo de perfil comercial: preservação de campos não editados e limpeza intencional',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-padaria-1',
        businessAccountId: 'acc-padaria-1',
        placeName: 'Padaria Central Centro',
        isApproved: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceBusinessProfileEditScreen(
                args: args,
                commercialRepository: commercialRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      // 4.a: Usuário altera APENAS o telefone
      final phoneInput = find.byKey(const Key('business_profile_phone_input'));
      await tester.enterText(phoneInput, '+55 (11) 91111-2222');
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('save_business_profile_button')));
      await tester.pumpAndSettle();

      expect(find.text('Perfil comercial atualizado com sucesso.'), findsOneWidget);

      // Invariante essencial do contrato: o payload PUT mantém todos os campos preenchidos
      final payload1 = commercialRepo.lastUpdateProfilePayload!;
      expect(payload1['phone'], '+55 (11) 91111-2222');
      expect(payload1['description'], 'Padaria artesanal tradicional');
      expect(payload1['websiteUrl'], 'https://padariacentral.com.br');
      expect(payload1['instagramUrl'], 'https://instagram.com/padariacentral');
      expect(payload1['facebookUrl'], 'https://facebook.com/padariacentral');

      // 4.b: Limpeza intencional: usuário apaga o Facebook
      final facebookInput = find.byKey(const Key('business_profile_facebook_input'));
      await tester.enterText(facebookInput, '');
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('save_business_profile_button')));
      await tester.pumpAndSettle();

      final payload2 = commercialRepo.lastUpdateProfilePayload!;
      expect(payload2['facebookUrl'], isNull); // enviado como null explicitamente para remover
      expect(payload2['phone'], '+55 (11) 91111-2222'); // mantido
      expect(payload2['websiteUrl'], 'https://padariacentral.com.br'); // mantido
    });

    testWidgets('5. Resiliência a falhas HTTP (400, 403, 500) sem perda de formulário nem vazamento interno',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      const args = PlaceBusinessProfileEditArgs(
        placeId: 'place-padaria-1',
        businessAccountId: 'acc-padaria-1',
        placeName: 'Padaria Central Centro',
        isApproved: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          onGenerateRoute: (settings) {
            return MaterialPageRoute(
              builder: (ctx) => PlaceBusinessProfileEditScreen(
                args: args,
                commercialRepository: commercialRepo,
              ),
            );
          },
        ),
      );
      await tester.pumpAndSettle();

      // Usuário digita novos dados
      final phoneInput = find.byKey(const Key('business_profile_phone_input'));
      await tester.enterText(phoneInput, '+55 (11) 93333-4444');
      await tester.pumpAndSettle();

      // 5.a Simula 400 Bad Request com RFC 7807
      commercialRepo.errorToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 400,
          title: 'Bad Request',
          detail: 'Número de telefone em formato rejeitado pela operadora.',
        ),
      );

      await tester.tap(find.byKey(const Key('save_business_profile_button')));
      await tester.pumpAndSettle();

      // Erro amigável na interface (banner / snackbar)
      expect(find.text('Número de telefone em formato rejeitado pela operadora.'), findsAtLeastNWidgets(1));
      // Formulário não foi resetado
      expect(find.text('+55 (11) 93333-4444'), findsOneWidget);

      // 5.b Simula 403 Forbidden
      commercialRepo.errorToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 403,
          title: 'Forbidden',
          detail: 'Access denied',
        ),
      );

      await tester.tap(find.byKey(const Key('save_business_profile_button')));
      await tester.pumpAndSettle();

      expect(find.text('Você não possui permissão para gerenciar este estabelecimento.'), findsAtLeastNWidgets(1));
      expect(find.text('+55 (11) 93333-4444'), findsOneWidget);

      // 5.c Simula erro genérico de rede (500 / Network)
      commercialRepo.errorToThrow = Exception('SocketException: Connection refused');

      await tester.tap(find.byKey(const Key('save_business_profile_button')));
      await tester.pumpAndSettle();

      expect(find.text('Falha de comunicação ao salvar o perfil comercial.'), findsAtLeastNWidgets(1));
      expect(find.text('+55 (11) 93333-4444'), findsOneWidget);
    });

    testWidgets('6. Visualização do detalhe público: exibição seletiva apenas dos contatos preenchidos',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      placeRepo.placeToReturn = const PlaceDetail(
        id: 'place-seletivo',
        name: 'Café Especial',
        slug: 'cafe-especial',
        category: 'CAFES',
        addressText: 'Rua Bela Cintra, 20',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: '+55 (11) 98765-1111',
        instagramUrl: 'https://instagram.com/cafeespecial',
        websiteUrl: null,
        facebookUrl: '',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceDetailScreen(
            placeId: 'place-seletivo',
            repository: placeRepo,
            commercialRepository: commercialRepo,
            urlLauncherService: urlLauncherService,
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Card de contatos visível
      expect(find.byKey(const Key('place_public_contacts_section')), findsOneWidget);
      expect(find.byKey(const Key('place_contact_phone_item')), findsOneWidget);
      expect(find.byKey(const Key('place_contact_instagram_item')), findsOneWidget);
      // Não exibe site nem facebook
      expect(find.byKey(const Key('place_contact_website_item')), findsNothing);
      expect(find.byKey(const Key('place_contact_facebook_item')), findsNothing);
    });

    testWidgets('7. Omissão limpa quando todos os contatos do estabelecimento estão ausentes ou em branco',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      placeRepo.placeToReturn = const PlaceDetail(
        id: 'place-vazio',
        name: 'Banca da Esquina',
        slug: 'banca-da-esquina',
        category: 'SERVICOS',
        addressText: 'Praça da Sé, 1',
        validationRadiusMeters: 50,
        origin: 'USER',
        isVerified: false,
        status: 'ACTIVE',
        phone: null,
        websiteUrl: '',
        instagramUrl: '   ',
        facebookUrl: null,
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceDetailScreen(
            placeId: 'place-vazio',
            repository: placeRepo,
            commercialRepository: commercialRepo,
            urlLauncherService: urlLauncherService,
          ),
        ),
      );
      await tester.pumpAndSettle();

      // A seção de contatos não deve existir na tela
      expect(find.byKey(const Key('place_public_contacts_section')), findsNothing);
      expect(find.text('Contatos e Redes Sociais'), findsNothing);
    });

    testWidgets('8. Ações de telefone, links externos, esquemas inválidos e falhas de abertura',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      placeRepo.placeToReturn = const PlaceDetail(
        id: 'place-links',
        name: 'Padaria Modelo',
        slug: 'padaria-modelo',
        category: 'PADARIA',
        addressText: 'Av Paulista, 1000',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: '+55 (11) 98765-4321',
        websiteUrl: 'https://padariamodelo.com.br',
        instagramUrl: 'https://instagram.com/padariamodelo',
        facebookUrl: 'https://facebook.com/padariamodelo',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceDetailScreen(
            placeId: 'place-links',
            repository: placeRepo,
            commercialRepository: commercialRepo,
            urlLauncherService: urlLauncherService,
          ),
        ),
      );
      await tester.pumpAndSettle();

      // 8.a Disparo de telefone com sucesso
      await tester.tap(find.byKey(const Key('place_contact_phone_item')));
      await tester.pumpAndSettle();
      expect(urlLauncherService.lastLaunchedPhone, '+55 (11) 98765-4321');

      // 8.b Disparo de site oficial com sucesso
      await tester.tap(find.byKey(const Key('place_contact_website_item')));
      await tester.pumpAndSettle();
      expect(urlLauncherService.lastLaunchedWebUrl, 'https://padariamodelo.com.br');

      // 8.c Esquema inválido (invalidScheme)
      urlLauncherService.webUrlResult = LaunchResult.invalidScheme;
      await tester.tap(find.byKey(const Key('place_contact_instagram_item')));
      await tester.pumpAndSettle();
      expect(find.text('Endereço inválido: apenas links http ou https são permitidos.'), findsOneWidget);

      // Aguarda dissipar a SnackBar anterior
      await tester.pump(const Duration(seconds: 4));

      // 8.d Falha ao abrir link no navegador (launchFailed)
      urlLauncherService.webUrlResult = LaunchResult.launchFailed;
      await tester.tap(find.byKey(const Key('place_contact_facebook_item')));
      await tester.pumpAndSettle();
      expect(find.text('Não foi possível abrir o link no navegador.'), findsOneWidget);

      // Aguarda dissipar a SnackBar anterior
      await tester.pump(const Duration(seconds: 4));

      // 8.e Falha ao iniciar chamada telefônica (launchFailed)
      urlLauncherService.phoneResult = LaunchResult.launchFailed;
      await tester.tap(find.byKey(const Key('place_contact_phone_item')));
      await tester.pumpAndSettle();
      expect(find.text('Não foi possível iniciar a chamada telefônica no dispositivo.'), findsOneWidget);
    });

    testWidgets('9. Pull-to-refresh na tela de detalhe atualiza a seção de contatos dinamicamente',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      // Começa com telefone antigo
      placeRepo.placeToReturn = const PlaceDetail(
        id: 'place-refresh',
        name: 'Restaurante Central',
        slug: 'restaurante-central',
        category: 'ALIMENTACAO',
        addressText: 'Rua do Ouvidor, 50',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: '+55 (11) 91111-0000',
        websiteUrl: 'https://restaurante.com.br',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceDetailScreen(
            placeId: 'place-refresh',
            repository: placeRepo,
            commercialRepository: commercialRepo,
            urlLauncherService: urlLauncherService,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('+55 (11) 91111-0000'), findsOneWidget);

      // Atualização remota no repositório
      placeRepo.placeToReturn = const PlaceDetail(
        id: 'place-refresh',
        name: 'Restaurante Central',
        slug: 'restaurante-central',
        category: 'ALIMENTACAO',
        addressText: 'Rua do Ouvidor, 50',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: '+55 (11) 99999-8888', // Novo telefone
        websiteUrl: 'https://restaurante.com.br',
      );

      // Ação de pull-to-refresh
      await tester.fling(find.byType(ListView), const Offset(0, 300), 1000);
      await tester.pumpAndSettle();

      // UI atualizada dinamicamente com o novo número
      expect(find.text('+55 (11) 99999-8888'), findsOneWidget);
    });

    testWidgets('10. Compatibilidade entre dados DTO, PlaceDetail.copyWith e entidade após mutação',
        (tester) async {
      final jsonSample = {
        'id': 'place-dto-1',
        'name': 'Confeitaria Real',
        'slug': 'confeitaria-real',
        'category': 'DOCES',
        'addressText': 'Rua Direita, 20',
        'city': 'Curitiba',
        'state': 'PR',
        'validationRadiusMeters': 60,
        'origin': 'CLAIMED',
        'isVerified': true,
        'status': 'ACTIVE',
        'phone': '  +55 (41) 98888-1111  ',
        'websiteUrl': 'https://confeitaria.com.br ',
        'instagramUrl': 'https://instagram.com/real',
        'facebookUrl': null,
      };

      final dto = PlaceDto.fromJson(jsonSample);
      final entity = dto.toEntity();

      expect(entity.phone, '+55 (41) 98888-1111');
      expect(entity.websiteUrl, 'https://confeitaria.com.br');
      expect(entity.instagramUrl, 'https://instagram.com/real');
      expect(entity.facebookUrl, isNull);
      expect(entity.hasPublicContacts, isTrue);

      // copyWith alterando apenas foto de capa preserva contatos comerciais
      final modified = entity.copyWith(photoUrl: 'https://storage/cover.jpg');
      expect(modified.photoUrl, 'https://storage/cover.jpg');
      expect(modified.phone, '+55 (41) 98888-1111');
      expect(modified.websiteUrl, 'https://confeitaria.com.br');
      expect(modified.instagramUrl, 'https://instagram.com/real');
      expect(modified.facebookUrl, isNull);
    });

    testWidgets('11. Coexistência harmônica de todas as seções na tela de detalhe público sem quebra ou overflow',
        (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      placeRepo.placeToReturn = const PlaceDetail(
        id: 'place-completo',
        name: 'Padaria e Café Completo',
        slug: 'padaria-completo',
        category: 'PADARIA',
        addressText: 'Av Central, 500',
        city: 'Campinas',
        state: 'SP',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: '+55 (19) 97777-6666',
        websiteUrl: 'https://completo.com.br',
        instagramUrl: 'https://instagram.com/completo',
        facebookUrl: 'https://facebook.com/completo',
      );

      await tester.pumpWidget(
        MaterialApp(
          home: PlaceDetailScreen(
            placeId: 'place-completo',
            repository: placeRepo,
            commercialRepository: commercialRepo,
            productRepository: productRepo,
            urlLauncherService: urlLauncherService,
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Todas as seções convivem perfeitamente
      expect(find.byKey(const Key('place_public_contacts_section')), findsOneWidget);
      expect(find.byType(PlaceOpeningHoursSection), findsOneWidget);
      expect(find.byType(PlaceAmenitiesSection), findsOneWidget);
      expect(find.byType(PlaceCommercialOffersSection), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
