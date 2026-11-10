import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/product/domain/entities/create_product_input.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/scanner/domain/entities/scanner_state.dart';
import 'package:rewit_mobile/features/scanner/presentation/screens/scanner_screen.dart';
import 'package:rewit_mobile/features/scanner/presentation/state/scanner_notifier.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _FakeSearchRepository implements SearchRepository {
  @override
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
    return const SearchPage(
      items: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }
}

class _FakeProductRepository implements ProductRepository {
  @override
  Future<ProductDetail> createProduct(CreateProductInput input) async => throw UnimplementedError();

  ProductDetail? productToReturn;
  Exception? exceptionToThrow;
  ProductDetail? productByIdToReturn;
  Exception? productByIdException;

  @override
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  }) async {
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    return productToReturn ??
        const ProductDetail(
          id: 'prod-uuid-123',
          name: 'Café Arábica Especial',
          brand: 'Fazenda Terra Roxa',
          model: '500g',
          category: 'BEBIDAS',
          status: 'ACTIVE',
        );
  }

  @override
  Future<ProductDetail> getProductById(String id) async {
    if (productByIdException != null) {
      throw productByIdException!;
    }
    return productByIdToReturn ??
        ProductDetail(
          id: id,
          name: 'Mouse Sem Fio Pro',
          brand: 'LogiTest',
          category: 'PERIFÉRICOS',
          status: 'ACTIVE',
        );
  }

  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) async => throw UnimplementedError();

  @override
  Future<ProductsInPlacePage> getProductsInPlace(String placeId, {int page = 0, int size = 20}) async =>
      throw UnimplementedError();

  @override
  Future<TargetStats> getProductStats(String id) async => throw UnimplementedError();

  @override
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async =>
      throw UnimplementedError();

  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async => Uint8List.fromList([]);
}

class _FakePlaceRepository implements PlaceRepository {
  PlaceDetail? placeToReturn;
  Exception? exceptionToThrow;

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    return placeToReturn ??
        PlaceDetail(
          id: id,
          name: 'Padaria Boa Vista',
          slug: 'padaria-boa-vista',
          category: 'PADARIA',
          addressText: 'Rua Augusta, 400',
          validationRadiusMeters: 100,
          origin: 'MANUAL',
          isVerified: true,
          status: 'ACTIVE',
        );
  }

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) async => throw UnimplementedError();

  @override
  Future<void> deletePlaceMedia({required String placeId, required String mediaId}) async =>
      throw UnimplementedError();

  @override
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId) async => throw UnimplementedError();

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async =>
      throw UnimplementedError();

  @override
  Future<TargetStats> getTargetStats(String id) async => throw UnimplementedError();

  @override
  Future<PlaceMediaItem> setPrimaryPlaceMedia({required String placeId, required String mediaId}) async =>
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
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? idempotencyKey,
  }) async =>
      throw UnimplementedError();
}

class _StubAuthRepo implements AuthRepository {
  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<String> requestSocialNonce() => throw UnimplementedError();

  @override
  Future<Authenticated> signInWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'user-1',
        email: 'ana@rewit.com',
        handle: 'anapaula',
        displayName: 'Ana Paula',
        isVerified: true,
        reputationScore: 120,
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

void main() {
  late _FakeProductRepository fakeRepository;

  setUp(() {
    fakeRepository = _FakeProductRepository();
  });

  Widget createScannerApp({
    ScannerNotifier? notifier,
    void Function(BarcodeCapture)? onCaptureCallback,
    Map<String, WidgetBuilder>? additionalRoutes,
  }) {
    return MaterialApp(
      routes: {
        '/': (context) => ScannerScreen(
              productRepository: fakeRepository,
              notifier: notifier,
              cameraBuilder: (ctx, onDetect) {
                if (onCaptureCallback != null) {
                  onCaptureCallback = onDetect;
                }
                return Container(
                  key: const Key('mock_camera_preview'),
                  color: Colors.black,
                  child: Center(
                    child: ElevatedButton(
                      key: const Key('btn_simulate_detect'),
                      onPressed: () {
                        onDetect(
                          const BarcodeCapture(
                            barcodes: [
                              Barcode(
                                rawValue: '7891234567890',
                                format: BarcodeFormat.ean13,
                              ),
                            ],
                          ),
                        );
                      },
                      child: const Text('Simular Leitura'),
                    ),
                  ),
                );
              },
            ),
        AppRouter.productDetail: (context) {
          final args = ModalRoute.of(context)?.settings.arguments;
          return Scaffold(
            body: Center(child: Text('Tela de Detalhes do Produto: $args')),
          );
        },
        AppRouter.placeDetail: (context) {
          final args = ModalRoute.of(context)?.settings.arguments;
          return Scaffold(
            body: Center(child: Text('Tela de Detalhes do Local: $args')),
          );
        },
        AppRouter.search: (context) => const Scaffold(
              body: Center(child: Text('Tela de Busca')),
            ),
        ...?additionalRoutes,
      },
    );
  }

  testWidgets('renderiza elementos básicos da interface do ScannerScreen', (tester) async {
    await tester.pumpWidget(createScannerApp());
    await tester.pumpAndSettle();

    expect(find.text('Escanear Código'), findsOneWidget);
    expect(find.byIcon(Icons.flash_off), findsOneWidget);
    expect(find.byIcon(Icons.flip_camera_ios), findsOneWidget);
    expect(find.byKey(const Key('mock_camera_preview')), findsOneWidget);
    expect(find.text('Aponte para um código de barras ou QR Code'), findsOneWidget);
    expect(find.text('escaneie aqui'), findsOneWidget);
  });

  testWidgets('exibe card de produto encontrado e permite navegar para o detalhe', (tester) async {
    await tester.pumpWidget(createScannerApp());
    await tester.pumpAndSettle();

    // Simula detecção de código de barras
    await tester.tap(find.byKey(const Key('btn_simulate_detect')));
    await tester.pumpAndSettle();

    expect(find.text('Produto encontrado'), findsOneWidget);
    expect(find.text('Fazenda Terra Roxa Café Arábica Especial'), findsOneWidget);
    expect(find.text('Escanear novamente'), findsOneWidget);
    expect(find.text('Ver produto'), findsOneWidget);

    // Clica para ver produto
    await tester.tap(find.text('Ver produto'));
    await tester.pumpAndSettle();

    expect(find.text('Tela de Detalhes do Produto: prod-uuid-123'), findsOneWidget);
  });

  testWidgets('exibe card de produto não encontrado (404) e permite buscar pelo nome', (tester) async {
    fakeRepository.exceptionToThrow = const ApiException(
      ProblemDetail(
        type: 'about:blank',
        title: 'Not Found',
        status: 404,
        detail: 'Produto não cadastrado.',
        code: 'PRODUCT_NOT_FOUND',
      ),
    );

    await tester.pumpWidget(createScannerApp());
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('btn_simulate_detect')));
    await tester.pumpAndSettle();

    expect(find.text('Produto não encontrado'), findsOneWidget);
    expect(find.text('Buscar pelo nome'), findsOneWidget);
    expect(find.text('Escanear novamente'), findsOneWidget);

    // Clica para buscar pelo nome
    await tester.tap(find.text('Buscar pelo nome'));
    await tester.pumpAndSettle();

    expect(find.text('Tela de Busca'), findsOneWidget);
  });

  testWidgets('exibe card informativo para formato de código não suportado', (tester) async {
    final notifier = ScannerNotifier(productRepository: fakeRepository);

    await tester.pumpWidget(
      MaterialApp(
        home: ScannerScreen(
          notifier: notifier,
          cameraBuilder: (ctx, onDetect) => ElevatedButton(
            key: const Key('btn_unsupported'),
            onPressed: () {
              onDetect(
                const BarcodeCapture(
                  barcodes: [
                    Barcode(
                      rawValue: 'ABC-12345',
                      format: BarcodeFormat.code128,
                    ),
                  ],
                ),
              );
            },
            child: const Text('Simular Não Suportado'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('btn_unsupported')));
    await tester.pumpAndSettle();

    expect(find.text('Formato não suportado'), findsOneWidget);
    expect(find.text('Escanear novamente'), findsOneWidget);

    notifier.dispose();
  });

  testWidgets('exibe aviso de segurança ao escanear QR Code externo e não o executa automaticamente', (tester) async {
    final notifier = ScannerNotifier(productRepository: fakeRepository);

    await tester.pumpWidget(
      MaterialApp(
        home: ScannerScreen(
          notifier: notifier,
          cameraBuilder: (ctx, onDetect) => ElevatedButton(
            key: const Key('btn_external_qr'),
            onPressed: () {
              onDetect(
                const BarcodeCapture(
                  barcodes: [
                    Barcode(
                      rawValue: 'https://exemplo-externo.com/promo',
                      format: BarcodeFormat.qrCode,
                    ),
                  ],
                ),
              );
            },
            child: const Text('Simular QR Externo'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('btn_external_qr')));
    await tester.pumpAndSettle();

    expect(find.text('QR Code detectado'), findsOneWidget);
    expect(find.text('Conteúdo não reconhecido pelo Rewit.'), findsOneWidget);
    expect(find.text('Escanear novamente'), findsOneWidget);
    expect(find.text('Voltar'), findsOneWidget);

    notifier.dispose();
  });

  testWidgets('exibe confirmação de recurso interno e permite abrir local', (tester) async {
    final notifier = ScannerNotifier(productRepository: fakeRepository);

    await tester.pumpWidget(
      createScannerApp(
        notifier: notifier,
        additionalRoutes: {
          '/scanner_test': (context) => ScannerScreen(
                notifier: notifier,
                cameraBuilder: (ctx, onDetect) => ElevatedButton(
                  key: const Key('btn_internal_qr'),
                  onPressed: () {
                    onDetect(
                      const BarcodeCapture(
                        barcodes: [
                          Barcode(
                            rawValue: 'rewit://places/place-cafe-central',
                            format: BarcodeFormat.qrCode,
                          ),
                        ],
                      ),
                    );
                  },
                  child: const Text('Simular QR Local'),
                ),
              ),
        },
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('btn_simulate_detect')));
    await tester.pumpAndSettle();

    // Toca para escanear novamente para permitir nova leitura
    await tester.tap(find.text('Escanear novamente'));
    await tester.pumpAndSettle();

    // Detecta recurso interno
    await notifier.onCodeDetected('rewit://places/place-cafe-central', format: BarcodeFormat.qrCode);
    await tester.pumpAndSettle();

    expect(find.text('Recurso Rewit Reconhecido'), findsOneWidget);
    expect(find.text('Local Físico identificado: place-cafe-central'), findsOneWidget);
    expect(find.text('Abrir recurso'), findsOneWidget);

    await tester.tap(find.text('Abrir recurso'));
    await tester.pumpAndSettle();

    expect(find.text('Tela de Detalhes do Local: place-cafe-central'), findsOneWidget);

    notifier.dispose();
  });

  testWidgets('exibe tela de aviso quando permissão de câmera é negada', (tester) async {
    final notifier = ScannerNotifier(productRepository: fakeRepository);
    notifier.onPermissionDenied();

    await tester.pumpWidget(
      MaterialApp(
        home: ScannerScreen(
          notifier: notifier,
          cameraBuilder: (ctx, onDetect) => const SizedBox.shrink(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Acesso à câmera necessário'), findsOneWidget);
    expect(find.text('Tentar novamente'), findsOneWidget);

    notifier.dispose();
  });

  testWidgets('ScannerScreen instancia MobileScanner com formatos incluindo itf14', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: ScannerScreen(
          productRepository: fakeRepository,
          autoStartCamera: false,
        ),
      ),
    );

    final mobileScannerFinder = find.byType(MobileScanner);
    expect(mobileScannerFinder, findsOneWidget);
    final mobileScannerWidget = tester.widget<MobileScanner>(mobileScannerFinder);
    expect(mobileScannerWidget.controller?.formats, contains(BarcodeFormat.itf14));
    expect(mobileScannerWidget.controller?.formats, contains(BarcodeFormat.ean13));
    expect(mobileScannerWidget.controller?.formats, contains(BarcodeFormat.qrCode));
  });

  testWidgets('errorBuilder de MobileScanner com permissionDenied atualiza notifier sem permanentemente negado e permite tentar novamente', (tester) async {
    final notifier = ScannerNotifier(productRepository: fakeRepository);

    await tester.pumpWidget(
      MaterialApp(
        home: ScannerScreen(
          notifier: notifier,
          autoStartCamera: false,
        ),
      ),
    );

    final mobileScannerFinder = find.byType(MobileScanner);
    expect(mobileScannerFinder, findsOneWidget);
    final mobileScannerWidget = tester.widget<MobileScanner>(mobileScannerFinder);

    mobileScannerWidget.errorBuilder!(
      tester.element(mobileScannerFinder),
      const MobileScannerException(
        errorCode: MobileScannerErrorCode.permissionDenied,
      ),
    );

    await tester.pump();
    await tester.pumpAndSettle();

    expect(find.text('Acesso à câmera necessário'), findsOneWidget);
    expect(
      find.text('Para escanear códigos de barras e QR Codes de produtos, o Rewit precisa de permissão de acesso à câmera.'),
      findsOneWidget,
    );
    expect(find.text('Tentar novamente'), findsOneWidget);
    expect(notifier.state, isA<ScannerPermissionDenied>());
    expect((notifier.state as ScannerPermissionDenied).permanentlyDenied, isFalse);

    notifier.dispose();
  });

  testWidgets('exibe tela de aviso com instrucao de configuracoes quando permissao e permanentemente negada', (tester) async {
    final notifier = ScannerNotifier(productRepository: fakeRepository);
    notifier.onPermissionDenied(permanentlyDenied: true);

    await tester.pumpWidget(
      MaterialApp(
        home: ScannerScreen(
          notifier: notifier,
          cameraBuilder: (ctx, onDetect) => const SizedBox.shrink(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Acesso à câmera necessário'), findsOneWidget);
    expect(
      find.text('A permissão de acesso à câmera foi negada permanentemente. Para escanear códigos, habilite a câmera nas configurações do dispositivo.'),
      findsOneWidget,
    );
    expect(find.text('Tentar novamente'), findsNothing);
    expect(find.text('Voltar'), findsOneWidget);

    notifier.dispose();
  });

  testWidgets('exibe aviso de rate limit (429) com tempo de espera', (tester) async {
    fakeRepository.exceptionToThrow = const ApiException(
      ProblemDetail(
        type: 'about:blank',
        title: 'Too Many Requests',
        status: 429,
        detail: 'Muitas consultas.',
        code: 'RATE_LIMIT_EXCEEDED',
      ),
      retryAfterSeconds: 60,
    );

    await tester.pumpWidget(createScannerApp());
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('btn_simulate_detect')));
    await tester.pumpAndSettle();

    expect(find.text('Limite de Consultas'), findsOneWidget);
    expect(find.text('Tente novamente em 60 segundos.'), findsOneWidget);
  });

  testWidgets('Explorar na HomeScreen possui botão de scanner e navega para /scanner', (tester) async {
    final repo = _StubAuthRepo();
    final authNotifier = AuthNotifier(authRepository: repo);
    await authNotifier.checkAuthStatus();

    await tester.pumpWidget(
      MaterialApp(
        routes: {
          '/': (context) => HomeScreen(
                authNotifier: authNotifier,
              ),
          AppRouter.scanner: (context) => const Scaffold(
                body: Center(child: Text('Tela Scanner Rota')),
              ),
        },
      ),
    );
    await tester.pumpAndSettle();

    // Na Home V2, o scanner fica acessível na busca da aba Explorar
    await tester.tap(find.text('Explorar'));
    await tester.pumpAndSettle();

    final scannerButton = find.byKey(const Key('search_scanner_button'));
    expect(scannerButton, findsOneWidget);

    // Clica no botão e valida navegação
    await tester.tap(scannerButton);
    await tester.pumpAndSettle();

    expect(find.text('Tela Scanner Rota'), findsOneWidget);

    authNotifier.dispose();
  });

  testWidgets('AppRouter gera rota de scanner corretamente', (tester) async {
    final repo = _StubAuthRepo();
    final authNotifier = AuthNotifier(authRepository: repo);
    final router = AppRouter(
      authNotifier: authNotifier,
      productRepository: fakeRepository,
    );

    final route = router.onGenerateRoute(const RouteSettings(name: AppRouter.scanner));
    expect(route, isA<MaterialPageRoute>());

    final pageRoute = route as MaterialPageRoute;
    expect(pageRoute.settings.name, equals(AppRouter.scanner));

    authNotifier.dispose();
  });

  group('ScannerScreen - Modo Seleção (selectionMode)', () {
    late _FakePlaceRepository fakePlaceRepository;

    setUp(() {
      fakePlaceRepository = _FakePlaceRepository();
    });

    Future<void> pumpSelectionFlow(
      WidgetTester tester, {
      required void Function(SearchResultItem? result, bool popped) onPopped,
      void Function(void Function(BarcodeCapture) onDetect)? onCameraReady,
      ScannerNotifier? customNotifier,
    }) async {
      await tester.pumpWidget(
        MaterialApp(
          routes: {
            '/': (context) => Scaffold(
                  body: Center(
                    child: ElevatedButton(
                      key: const Key('btn_open_scanner'),
                      onPressed: () async {
                        final res = await Navigator.of(context).push<SearchResultItem?>(
                          MaterialPageRoute<SearchResultItem?>(
                            builder: (_) => ScannerScreen(
                              productRepository: fakeRepository,
                              placeRepository: fakePlaceRepository,
                              selectionMode: true,
                              notifier: customNotifier,
                              cameraBuilder: (ctx, onDetect) {
                                onCameraReady?.call(onDetect);
                                return const SizedBox.expand(
                                  key: Key('selection_mock_camera'),
                                );
                              },
                            ),
                          ),
                        );
                        onPopped(res, true);
                      },
                      child: const Text('Abrir Scanner'),
                    ),
                  ),
                ),
            AppRouter.search: (context) => const Scaffold(
                  body: Center(child: Text('Tela de Busca')),
                ),
            AppRouter.productDetail: (context) => const Scaffold(
                  body: Center(child: Text('Detalhe Produto')),
                ),
            AppRouter.placeDetail: (context) => const Scaffold(
                  body: Center(child: Text('Detalhe Local')),
                ),
          },
        ),
      );

      await tester.tap(find.byKey(const Key('btn_open_scanner')));
      await tester.pumpAndSettle();
    }

    testWidgets('barcode de produto exibe Selecionar produto e retorna SearchResultItem', (tester) async {
      SearchResultItem? selectedItem;
      var didPop = false;
      late void Function(BarcodeCapture) capture;

      await pumpSelectionFlow(
        tester,
        onCameraReady: (onDetect) => capture = onDetect,
        onPopped: (res, popped) {
          selectedItem = res;
          didPop = popped;
        },
      );

      capture(
        const BarcodeCapture(
          barcodes: [
            Barcode(rawValue: '7891234567890', format: BarcodeFormat.ean13),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Produto encontrado'), findsOneWidget);
      expect(find.text('Selecionar produto'), findsOneWidget);
      expect(find.text('Ver produto'), findsNothing);

      await tester.tap(find.text('Selecionar produto'));
      await tester.pumpAndSettle();

      expect(didPop, isTrue);
      expect(selectedItem, isNotNull);
      expect(selectedItem!.id, equals('prod-uuid-123'));
      expect(selectedItem!.name, equals('Café Arábica Especial'));
      expect(selectedItem!.category, equals('BEBIDAS'));
      expect(selectedItem!.targetType, equals(TargetType.product));
      expect(selectedItem!.rawTargetType, equals('PRODUCT'));
      expect(selectedItem!.status, equals('ACTIVE'));
      expect(find.text('Detalhe Produto'), findsNothing);
    });

    testWidgets('QR de lugar exibe Local encontrado e retorna SearchResultItem de Place', (tester) async {
      SearchResultItem? selectedItem;
      var didPop = false;
      late void Function(BarcodeCapture) capture;

      await pumpSelectionFlow(
        tester,
        onCameraReady: (onDetect) => capture = onDetect,
        onPopped: (res, popped) {
          selectedItem = res;
          didPop = popped;
        },
      );

      capture(
        const BarcodeCapture(
          barcodes: [
            Barcode(rawValue: 'rewit://places/place-456', format: BarcodeFormat.qrCode),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Local encontrado'), findsOneWidget);
      expect(find.text('Padaria Boa Vista'), findsOneWidget);
      expect(find.text('Selecionar local'), findsOneWidget);

      await tester.tap(find.text('Selecionar local'));
      await tester.pumpAndSettle();

      expect(didPop, isTrue);
      expect(selectedItem, isNotNull);
      expect(selectedItem!.id, equals('place-456'));
      expect(selectedItem!.name, equals('Padaria Boa Vista'));
      expect(selectedItem!.slug, equals('padaria-boa-vista'));
      expect(selectedItem!.targetType, equals(TargetType.place));
      expect(selectedItem!.rawTargetType, equals('PLACE'));
    });

    testWidgets('QR de avaliação em modo seleção exibe explicação e botão Escanear novamente', (tester) async {
      late void Function(BarcodeCapture) capture;
      final notifier = ScannerNotifier(
        productRepository: fakeRepository,
        placeRepository: fakePlaceRepository,
        selectionMode: true,
      );
      addTearDown(notifier.dispose);

      await pumpSelectionFlow(
        tester,
        customNotifier: notifier,
        onCameraReady: (onDetect) => capture = onDetect,
        onPopped: (_, __) {},
      );

      capture(
        const BarcodeCapture(
          barcodes: [
            Barcode(rawValue: 'rewit://reviews/rev-999', format: BarcodeFormat.qrCode),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Alvo não selecionável'), findsOneWidget);
      expect(find.textContaining('avaliações não podem ser selecionados como alvo'), findsOneWidget);
      expect(find.text('Escanear novamente'), findsOneWidget);
      expect(find.text('Selecionar produto'), findsNothing);
      expect(find.text('Selecionar local'), findsNothing);

      await tester.tap(find.text('Escanear novamente'));
      await tester.pumpAndSettle();

      expect(notifier.state, isA<ScannerScanning>());
    });

    testWidgets('código não encontrado em modo seleção oferece Voltar para busca e Escanear novamente sem empilhar SearchScreen', (tester) async {
      fakeRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'Not Found',
          code: 'PRODUCT_NOT_FOUND',
        ),
      );

      SearchResultItem? selectedItem;
      var didPop = false;
      late void Function(BarcodeCapture) capture;

      await pumpSelectionFlow(
        tester,
        onCameraReady: (onDetect) => capture = onDetect,
        onPopped: (res, popped) {
          selectedItem = res;
          didPop = popped;
        },
      );

      capture(
        const BarcodeCapture(
          barcodes: [
            Barcode(rawValue: '7890000000000', format: BarcodeFormat.ean13),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Produto não encontrado'), findsOneWidget);
      expect(find.text('Voltar para busca'), findsOneWidget);
      expect(find.text('Escanear novamente'), findsOneWidget);
      expect(find.text('Buscar pelo nome'), findsNothing);

      await tester.tap(find.text('Voltar para busca'));
      await tester.pumpAndSettle();

      expect(didPop, isTrue);
      expect(selectedItem, isNull);
      expect(find.text('Tela de Busca'), findsNothing);
    });

    testWidgets('QR de produto por ID exibe nome real (nunca ID) e permite selecionar produto', (tester) async {
      fakeRepository.productByIdToReturn = const ProductDetail(
        id: 'prod-qr-uuid-88',
        name: 'Teclado Mecânico Compacto',
        brand: 'KeyPro',
        category: 'PERIFÉRICOS',
        status: 'ACTIVE',
      );

      SearchResultItem? selectedItem;
      var didPop = false;
      late void Function(BarcodeCapture) capture;

      await pumpSelectionFlow(
        tester,
        onCameraReady: (onDetect) => capture = onDetect,
        onPopped: (res, popped) {
          selectedItem = res;
          didPop = popped;
        },
      );

      capture(
        const BarcodeCapture(
          barcodes: [
            Barcode(rawValue: 'rewit://products/prod-qr-uuid-88', format: BarcodeFormat.qrCode),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Produto encontrado'), findsOneWidget);
      expect(find.text('KeyPro Teclado Mecânico Compacto'), findsOneWidget);
      expect(find.text('prod-qr-uuid-88'), findsNothing);
      expect(find.text('Selecionar produto'), findsOneWidget);

      await tester.tap(find.text('Selecionar produto'));
      await tester.pumpAndSettle();

      expect(didPop, isTrue);
      expect(selectedItem, isNotNull);
      expect(selectedItem!.id, equals('prod-qr-uuid-88'));
      expect(selectedItem!.name, equals('Teclado Mecânico Compacto'));
      expect(selectedItem!.category, equals('PERIFÉRICOS'));
      expect(selectedItem!.targetType, equals(TargetType.product));
      expect(selectedItem!.rawTargetType, equals('PRODUCT'));
    });

    testWidgets('QR de lugar inexistente em modo seleção exibe Local não encontrado e Voltar para busca', (tester) async {
      fakePlaceRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'Local não encontrado',
          code: 'PLACE_NOT_FOUND',
        ),
      );

      SearchResultItem? selectedItem;
      var didPop = false;
      late void Function(BarcodeCapture) capture;

      await pumpSelectionFlow(
        tester,
        onCameraReady: (onDetect) => capture = onDetect,
        onPopped: (res, popped) {
          selectedItem = res;
          didPop = popped;
        },
      );

      capture(
        const BarcodeCapture(
          barcodes: [
            Barcode(rawValue: 'rewit://places/place-404', format: BarcodeFormat.qrCode),
          ],
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Local não encontrado'), findsOneWidget);
      expect(find.text('Voltar para busca'), findsOneWidget);
      expect(find.text('Escanear novamente'), findsOneWidget);
      expect(find.text('Buscar pelo nome'), findsNothing);

      await tester.tap(find.text('Voltar para busca'));
      await tester.pumpAndSettle();

      expect(didPop, isTrue);
      expect(selectedItem, isNull);
    });
  });

  group('Integração AppRouter: ReviewCreateScreen <-> ScannerScreen e Explorar', () {
    late _FakePlaceRepository fakePlaceRepository;

    setUp(() {
      fakePlaceRepository = _FakePlaceRepository();
    });

    testWidgets(
      'AppRouter.reviewCreate abre Scanner em selectionMode com PlaceRepository injetado e retorna SearchResultItem',
      (tester) async {
        tester.view.physicalSize = const Size(800, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        final repo = _StubAuthRepo();
        final authNotifier = AuthNotifier(authRepository: repo);
        addTearDown(authNotifier.dispose);
        await authNotifier.checkAuthStatus();

        fakePlaceRepository.placeToReturn = const PlaceDetail(
          id: 'place-router-uuid-1',
          name: 'Cafeteria Central Router',
          slug: 'cafeteria-central-router',
          category: 'CAFETERIA',
          addressText: 'Av. Paulista, 1000',
          validationRadiusMeters: 100,
          origin: 'MANUAL',
          isVerified: true,
          status: 'ACTIVE',
        );

        void Function(BarcodeCapture)? activeCapture;

        final router = AppRouter(
          authNotifier: authNotifier,
          searchRepository: _FakeSearchRepository(),
          productRepository: fakeRepository,
          placeRepository: fakePlaceRepository,
          scannerCameraBuilder: (context, onDetect) {
            activeCapture = onDetect;
            return const SizedBox.expand(key: Key('router_mock_camera'));
          },
        );

        await tester.pumpWidget(
          MaterialApp(
            initialRoute: AppRouter.reviewCreate,
            onGenerateRoute: router.onGenerateRoute,
          ),
        );
        await tester.pumpAndSettle();

        final targetScannerBtn = find.byKey(const Key('target_scanner_button_0'));
        expect(targetScannerBtn, findsOneWidget);

        await tester.tap(targetScannerBtn);
        await tester.pumpAndSettle();

        final scannerScreenFinder = find.byType(ScannerScreen);
        expect(scannerScreenFinder, findsOneWidget);

        final scannerWidget = tester.widget<ScannerScreen>(scannerScreenFinder);
        expect(scannerWidget.selectionMode, isTrue);
        expect(scannerWidget.placeRepository, same(fakePlaceRepository));
        expect(scannerWidget.productRepository, same(fakeRepository));
        expect(activeCapture, isNotNull);

        // Simula leitura de QR Code de local físico via PlaceRepository injetado
        activeCapture!(
          const BarcodeCapture(
            barcodes: [
              Barcode(
                rawValue: 'rewit://places/place-router-uuid-1',
                format: BarcodeFormat.qrCode,
              ),
            ],
          ),
        );
        await tester.pumpAndSettle();

        expect(find.text('Local encontrado'), findsOneWidget);
        expect(find.text('Cafeteria Central Router'), findsOneWidget);
        expect(find.text('Selecionar local'), findsOneWidget);

        await tester.tap(find.text('Selecionar local'));
        await tester.pumpAndSettle();

        // Verifica retorno à ReviewCreateScreen com o alvo selecionado
        expect(find.byType(ScannerScreen), findsNothing);
        expect(find.text('Cafeteria Central Router'), findsOneWidget);
        expect(
          find.text('Estabelecimento vinculado: Cafeteria Central Router'),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      'cancelar Scanner aberto a partir de AppRouter.reviewCreate retorna null sem alterar o alvo',
      (tester) async {
        tester.view.physicalSize = const Size(800, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        final repo = _StubAuthRepo();
        final authNotifier = AuthNotifier(authRepository: repo);
        addTearDown(authNotifier.dispose);
        await authNotifier.checkAuthStatus();

        final router = AppRouter(
          authNotifier: authNotifier,
          searchRepository: _FakeSearchRepository(),
          productRepository: fakeRepository,
          placeRepository: fakePlaceRepository,
          scannerCameraBuilder: (context, onDetect) {
            return const SizedBox.expand(key: Key('router_mock_camera'));
          },
        );

        await tester.pumpWidget(
          MaterialApp(
            initialRoute: AppRouter.reviewCreate,
            onGenerateRoute: router.onGenerateRoute,
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key('target_scanner_button_0')));
        await tester.pumpAndSettle();

        expect(find.byType(ScannerScreen), findsOneWidget);

        // Cancela voltando sem selecionar nada
        final NavigatorState navigator = tester.state(find.byType(Navigator));
        navigator.pop();
        await tester.pumpAndSettle();

        expect(find.byType(ScannerScreen), findsNothing);
        expect(find.byKey(const Key('target_scanner_button_0')), findsOneWidget);
        expect(find.text('O que você quer avaliar? *'), findsOneWidget);
      },
    );

    testWidgets(
      'abrir Scanner pelo Explorar via AppRouter opera em modo normal (selectionMode == false)',
      (tester) async {
        final repo = _StubAuthRepo();
        final authNotifier = AuthNotifier(authRepository: repo);
        addTearDown(authNotifier.dispose);
        await authNotifier.checkAuthStatus();

        void Function(BarcodeCapture)? activeCapture;

        final router = AppRouter(
          authNotifier: authNotifier,
          productRepository: fakeRepository,
          placeRepository: fakePlaceRepository,
          scannerCameraBuilder: (context, onDetect) {
            activeCapture = onDetect;
            return const SizedBox.expand(key: Key('explore_router_camera'));
          },
        );

        await tester.pumpWidget(
          MaterialApp(
            initialRoute: AppRouter.root,
            onGenerateRoute: router.onGenerateRoute,
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.text('Explorar'));
        await tester.pumpAndSettle();

        final scannerButton = find.byKey(const Key('search_scanner_button'));
        expect(scannerButton, findsOneWidget);

        await tester.tap(scannerButton);
        await tester.pumpAndSettle();

        final scannerScreenFinder = find.byType(ScannerScreen);
        expect(scannerScreenFinder, findsOneWidget);

        final scannerWidget = tester.widget<ScannerScreen>(scannerScreenFinder);
        expect(scannerWidget.selectionMode, isFalse);
        expect(activeCapture, isNotNull);

        activeCapture!(
          const BarcodeCapture(
            barcodes: [
              Barcode(rawValue: '7894900011517', format: BarcodeFormat.ean13),
            ],
          ),
        );
        await tester.pumpAndSettle();

        expect(find.text('Produto encontrado'), findsOneWidget);
        expect(find.text('Ver produto'), findsOneWidget);
        expect(find.text('Selecionar produto'), findsNothing);
      },
    );
  });
}
