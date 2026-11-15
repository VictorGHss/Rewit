import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
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
import 'package:rewit_mobile/features/scanner/domain/entities/scanned_code.dart';
import 'package:rewit_mobile/features/scanner/domain/entities/scanner_state.dart';
import 'package:rewit_mobile/features/scanner/domain/services/camera_permission_service.dart';
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

class _ForcedStateScannerNotifier extends ScannerNotifier {
  ScannerState _forcedState;

  _ForcedStateScannerNotifier(this._forcedState) : super(selectionMode: true);

  @override
  ScannerState get state => _forcedState;

  void setForcedState(ScannerState nextState) {
    _forcedState = nextState;
    notifyListeners();
  }

  @override
  void startScanning() {}
}

class _RecordingScannerNotifier extends ScannerNotifier {
  int onCodeDetectedCalls = 0;
  int resumeScanningCalls = 0;
  int startScanningCalls = 0;

  _RecordingScannerNotifier({
    super.productRepository,
    super.placeRepository,
    super.selectionMode = false,
  });

  @override
  Future<void> onCodeDetected(String rawValue, {BarcodeFormat? format}) {
    onCodeDetectedCalls++;
    return super.onCodeDetected(rawValue, format: format);
  }

  @override
  void resumeScanning() {
    resumeScanningCalls++;
    super.resumeScanning();
  }

  @override
  void startScanning() {
    startScanningCalls++;
    super.startScanning();
  }
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

    testWidgets('L-3: duplo acionamento rapido ao selecionar produto ou local nao fecha a tela subjacente', (tester) async {
      void Function(BarcodeCapture)? activeCapture;
      var returnedItems = <SearchResultItem?>[];

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (baseContext) => Scaffold(
              body: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('Tela Base Raiz'),
                    ElevatedButton(
                      key: const Key('btn_go_intermediate'),
                      onPressed: () {
                        Navigator.of(baseContext).push<void>(
                          MaterialPageRoute<void>(
                            builder: (interContext) => Scaffold(
                              body: Center(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Text('Tela Intermediária Avaliação'),
                                    ElevatedButton(
                                      key: const Key('btn_open_scanner_l3'),
                                      onPressed: () async {
                                        final res = await Navigator.of(interContext)
                                            .push<SearchResultItem?>(
                                          MaterialPageRoute<SearchResultItem?>(
                                            builder: (_) => ScannerScreen(
                                              productRepository: fakeRepository,
                                              placeRepository: fakePlaceRepository,
                                              selectionMode: true,
                                              cameraBuilder: (_, onDetect) {
                                                activeCapture = onDetect;
                                                return const SizedBox.expand(
                                                  key: Key('l3_camera'),
                                                );
                                              },
                                            ),
                                          ),
                                        );
                                        returnedItems.add(res);
                                      },
                                      child: const Text('Abrir Scanner L3'),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                      child: const Text('Ir para Intermediária'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.byKey(const Key('btn_go_intermediate')));
      await tester.pumpAndSettle();
      expect(find.text('Tela Intermediária Avaliação'), findsOneWidget);

      // 1) Produto: duplo acionamento de "Selecionar produto"
      await tester.tap(find.byKey(const Key('btn_open_scanner_l3')));
      await tester.pumpAndSettle();
      activeCapture!(
        const BarcodeCapture(
          barcodes: [Barcode(rawValue: '7891234567890', format: BarcodeFormat.ean13)],
        ),
      );
      await tester.pumpAndSettle();

      final selectProdFinder = find.widgetWithText(ElevatedButton, 'Selecionar produto');
      expect(selectProdFinder, findsOneWidget);
      final selectProdOnPressed = tester.widget<ElevatedButton>(selectProdFinder).onPressed!;
      selectProdOnPressed();
      selectProdOnPressed(); // segundo disparo rápido durante fechamento
      await tester.pumpAndSettle();

      expect(find.text('Tela Intermediária Avaliação'), findsOneWidget);
      expect(find.text('Tela Base Raiz'), findsNothing);
      expect(returnedItems.length, equals(1));
      expect(returnedItems.last?.id, equals('prod-uuid-123'));

      // 2) Local: duplo acionamento de "Selecionar local"
      await tester.tap(find.byKey(const Key('btn_open_scanner_l3')));
      await tester.pumpAndSettle();
      activeCapture!(
        const BarcodeCapture(
          barcodes: [Barcode(rawValue: 'rewit://places/place-789', format: BarcodeFormat.qrCode)],
        ),
      );
      await tester.pumpAndSettle();

      final selectPlaceFinder = find.widgetWithText(ElevatedButton, 'Selecionar local');
      expect(selectPlaceFinder, findsOneWidget);
      final selectPlaceOnPressed = tester.widget<ElevatedButton>(selectPlaceFinder).onPressed!;
      selectPlaceOnPressed();
      selectPlaceOnPressed();
      await tester.pumpAndSettle();

      expect(find.text('Tela Intermediária Avaliação'), findsOneWidget);
      expect(find.text('Tela Base Raiz'), findsNothing);
      expect(returnedItems.length, equals(2));
      expect(returnedItems.last?.id, equals('place-789'));
    });

    testWidgets('L-3: duplo acionamento rapido em Voltar para busca, Voltar apos erro e BackButton nao fecha tela subjacente', (tester) async {
      void Function(BarcodeCapture)? activeCapture;
      var returnedItems = <SearchResultItem?>[];

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (baseContext) => Scaffold(
              body: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('Tela Base Raiz'),
                    ElevatedButton(
                      key: const Key('btn_go_intermediate'),
                      onPressed: () {
                        Navigator.of(baseContext).push<void>(
                          MaterialPageRoute<void>(
                            builder: (interContext) => Scaffold(
                              body: Center(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Text('Tela Intermediária Avaliação'),
                                    ElevatedButton(
                                      key: const Key('btn_open_scanner_l3'),
                                      onPressed: () async {
                                        final res = await Navigator.of(interContext)
                                            .push<SearchResultItem?>(
                                          MaterialPageRoute<SearchResultItem?>(
                                            builder: (_) => ScannerScreen(
                                              productRepository: fakeRepository,
                                              placeRepository: fakePlaceRepository,
                                              selectionMode: true,
                                              cameraBuilder: (_, onDetect) {
                                                activeCapture = onDetect;
                                                return const SizedBox.expand();
                                              },
                                            ),
                                          ),
                                        );
                                        returnedItems.add(res);
                                      },
                                      child: const Text('Abrir Scanner L3'),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                      child: const Text('Ir para Intermediária'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.byKey(const Key('btn_go_intermediate')));
      await tester.pumpAndSettle();

      // 1) Voltar para busca (404 em selectionMode)
      fakeRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'Not Found',
          code: 'PRODUCT_NOT_FOUND',
        ),
      );
      await tester.tap(find.byKey(const Key('btn_open_scanner_l3')));
      await tester.pumpAndSettle();
      activeCapture!(
        const BarcodeCapture(
          barcodes: [Barcode(rawValue: '7890000000000', format: BarcodeFormat.ean13)],
        ),
      );
      await tester.pumpAndSettle();

      final backToSearchFinder = find.widgetWithText(ElevatedButton, 'Voltar para busca');
      final backToSearchOnPressed = tester.widget<ElevatedButton>(backToSearchFinder).onPressed!;
      backToSearchOnPressed();
      backToSearchOnPressed();
      await tester.pumpAndSettle();

      expect(find.text('Tela Intermediária Avaliação'), findsOneWidget);
      expect(find.text('Tela Base Raiz'), findsNothing);
      expect(returnedItems.single, isNull);

      // 2) Voltar após erro (500)
      fakeRepository.exceptionToThrow = const NetworkException('Sem conexão');
      await tester.tap(find.byKey(const Key('btn_open_scanner_l3')));
      await tester.pumpAndSettle();
      activeCapture!(
        const BarcodeCapture(
          barcodes: [Barcode(rawValue: '7891234567890', format: BarcodeFormat.ean13)],
        ),
      );
      await tester.pumpAndSettle();

      final errorBackFinder = find.widgetWithText(TextButton, 'Voltar');
      final errorBackOnPressed = tester.widget<TextButton>(errorBackFinder).onPressed!;
      errorBackOnPressed();
      errorBackOnPressed();
      await tester.pumpAndSettle();

      expect(find.text('Tela Intermediária Avaliação'), findsOneWidget);
      expect(find.text('Tela Base Raiz'), findsNothing);
      expect(returnedItems.length, equals(2));
      expect(returnedItems.last, isNull);

      // 3) Cancelamento via BackButton da AppBar (duplo disparo)
      await tester.tap(find.byKey(const Key('btn_open_scanner_l3')));
      await tester.pumpAndSettle();
      final appBarBackFinder = find.byType(BackButton);
      expect(appBarBackFinder, findsOneWidget);
      final appBarBackOnPressed = tester.widget<BackButton>(appBarBackFinder).onPressed!;
      appBarBackOnPressed();
      appBarBackOnPressed();
      await tester.pumpAndSettle();

      expect(find.text('Tela Intermediária Avaliação'), findsOneWidget);
      expect(find.text('Tela Base Raiz'), findsNothing);
      expect(returnedItems.length, equals(3));
      expect(returnedItems.last, isNull);
    });

    testWidgets('L-3: ScannerScreen recusa criar SearchResultItem inválido se produto ou local vier com id ou nome vazio', (tester) async {
      fakeRepository.productToReturn = const ProductDetail(
        id: '   ',
        name: 'Produto Sem ID',
        brand: 'Marca',
        category: 'CAT',
        status: 'ACTIVE',
      );

      var popped = false;
      SearchResultItem? selectedItem;
      late void Function(BarcodeCapture) capture;

      await pumpSelectionFlow(
        tester,
        onCameraReady: (onDetect) => capture = onDetect,
        onPopped: (res, didPop) {
          selectedItem = res;
          popped = didPop;
        },
      );

      capture(
        const BarcodeCapture(
          barcodes: [Barcode(rawValue: '7891234567890', format: BarcodeFormat.ean13)],
        ),
      );
      await tester.pumpAndSettle();

      // Notifier rejeita antes mesmo de oferecer botão de seleção
      expect(find.text('Falha na Consulta'), findsOneWidget);
      expect(find.text('Selecionar produto'), findsNothing);
      expect(popped, isFalse);
      expect(selectedItem, isNull);
    });

    testWidgets('L-3: protecao defensiva em _selectProduct e _selectPlace impede pop com SearchResultItem invalido mesmo com notifier externo', (tester) async {
      final forcedNotifier = _ForcedStateScannerNotifier(
        const ScannerFound(
          product: ProductDetail(
            id: '   ',
            name: 'Produto Inválido',
            brand: 'Marca',
            category: 'CAT',
            status: 'ACTIVE',
          ),
          scannedCode: ScannedCode(
            rawValue: '7891234567890',
            format: BarcodeFormat.ean13,
            category: ScannedCodeCategory.barcode,
            barcodeType: 'EAN',
            normalizedValue: '7891234567890',
          ),
        ),
      );
      addTearDown(forcedNotifier.dispose);

      var popped = false;
      SearchResultItem? result;
      await pumpSelectionFlow(
        tester,
        customNotifier: forcedNotifier,
        onPopped: (res, didPop) {
          result = res;
          popped = didPop;
        },
      );

      expect(find.text('Selecionar produto'), findsOneWidget);
      await tester.tap(find.text('Selecionar produto'));
      await tester.pump();

      expect(popped, isFalse);
      expect(find.text('Produto com dados inválidos para seleção.'), findsOneWidget);
      expect(find.byType(ScannerScreen), findsOneWidget);

      // Valida também _selectPlace com PlaceDetail sem nome válido no mesmo fluxo
      forcedNotifier.setForcedState(
        const ScannerPlaceFound(
          place: PlaceDetail(
            id: 'place-123',
            name: '   ',
            slug: 'slug',
            category: 'PADARIA',
            addressText: 'Rua A',
            validationRadiusMeters: 100,
            origin: 'MANUAL',
            isVerified: true,
            status: 'ACTIVE',
          ),
          scannedCode: ScannedCode(
            rawValue: 'rewit://places/place-123',
            format: BarcodeFormat.qrCode,
            category: ScannedCodeCategory.qrInternal,
          ),
        ),
      );
      await tester.pump();

      expect(find.text('Selecionar local'), findsOneWidget);
      await tester.tap(find.text('Selecionar local'));
      await tester.pumpAndSettle();

      expect(popped, isFalse);
      expect(find.text('Local com dados inválidos para seleção.'), findsOneWidget);
      expect(find.byType(ScannerScreen), findsOneWidget);

      // Cancelamento manual segue fechando normalmente com null
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(popped, isTrue);
      expect(result, isNull);
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

    testWidgets(
      'H-1: pushNamed<SearchResultItem?> na rota AppRouter.scanner nao lanca TypeError ao selecionar ou cancelar',
      (tester) async {
        final repo = _StubAuthRepo();
        final authNotifier = AuthNotifier(authRepository: repo);
        addTearDown(authNotifier.dispose);
        await authNotifier.checkAuthStatus();

        void Function(BarcodeCapture)? activeCapture;
        SearchResultItem? typedResult;
        var calls = 0;

        final router = AppRouter(
          authNotifier: authNotifier,
          productRepository: fakeRepository,
          placeRepository: fakePlaceRepository,
          scannerCameraBuilder: (context, onDetect) {
            activeCapture = onDetect;
            return const SizedBox.expand(key: Key('h1_typed_camera'));
          },
        );

        await tester.pumpWidget(
          MaterialApp(
            onGenerateRoute: router.onGenerateRoute,
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: ElevatedButton(
                    key: const Key('btn_push_typed_scanner'),
                    onPressed: () async {
                      calls++;
                      typedResult = await Navigator.of(context)
                          .pushNamed<SearchResultItem?>(
                        AppRouter.scanner,
                        arguments: ScannerRouteArgs(
                          selectionMode: true,
                          productRepository: fakeRepository,
                          placeRepository: fakePlaceRepository,
                        ),
                      );
                    },
                    child: const Text('Abrir Tipado'),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();

        // 1. Cancelamento tipado retorna null sem TypeError
        await tester.tap(find.byKey(const Key('btn_push_typed_scanner')));
        await tester.pumpAndSettle();
        expect(find.byType(ScannerScreen), findsOneWidget);

        await tester.tap(find.byType(BackButton));
        await tester.pumpAndSettle();
        expect(calls, equals(1));
        expect(typedResult, isNull);

        // 2. Seleção tipada retorna SearchResultItem sem TypeError
        await tester.tap(find.byKey(const Key('btn_push_typed_scanner')));
        await tester.pumpAndSettle();

        activeCapture!(
          const BarcodeCapture(
            barcodes: [
              Barcode(rawValue: '7891234567890', format: BarcodeFormat.ean13),
            ],
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.text('Selecionar produto'));
        await tester.pumpAndSettle();

        expect(calls, equals(2));
        expect(typedResult, isNotNull);
        expect(typedResult!.id, equals('prod-uuid-123'));
        expect(typedResult!.targetType, equals(TargetType.product));
      },
    );
  });

  group('ScannerScreen - Polish L-A e L-C (Fechamento com Animação e Eventos Tardios)', () {
    late _FakePlaceRepository fakePlaceRepository;

    setUp(() {
      fakePlaceRepository = _FakePlaceRepository();
    });

    testWidgets(
      'L-A: leitura tardia da câmera e Escanear novamente durante animação de fechamento são ignorados',
      (tester) async {
        final spyNotifier = _RecordingScannerNotifier(
          productRepository: fakeRepository,
          placeRepository: fakePlaceRepository,
          selectionMode: true,
        );
        addTearDown(spyNotifier.dispose);

        void Function(BarcodeCapture)? activeCapture;
        final returnedItems = <SearchResultItem?>[];

        await tester.pumpWidget(
          MaterialApp(
            home: Builder(
              builder: (baseContext) => Scaffold(
                body: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text('Tela Base Raiz'),
                      ElevatedButton(
                        key: const Key('btn_open_inter_la'),
                        onPressed: () {
                          Navigator.of(baseContext).push<void>(
                            MaterialPageRoute<void>(
                              builder: (interContext) => Scaffold(
                                body: Center(
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Text('Tela Intermediária L-A'),
                                      ElevatedButton(
                                        key: const Key('btn_open_scanner_la'),
                                        onPressed: () async {
                                          final res = await Navigator.of(interContext)
                                              .push<SearchResultItem?>(
                                            MaterialPageRoute<SearchResultItem?>(
                                              builder: (_) => ScannerScreen(
                                                notifier: spyNotifier,
                                                selectionMode: true,
                                                cameraBuilder: (_, onDetect) {
                                                  activeCapture = onDetect;
                                                  return const SizedBox.expand(
                                                    key: Key('la_mock_camera'),
                                                  );
                                                },
                                              ),
                                            ),
                                          );
                                          returnedItems.add(res);
                                        },
                                        child: const Text('Abrir Scanner L-A'),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          );
                        },
                        child: const Text('Ir Inter L-A'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );

        await tester.tap(find.byKey(const Key('btn_open_inter_la')));
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key('btn_open_scanner_la')));
        await tester.pumpAndSettle();

        activeCapture!(
          const BarcodeCapture(
            barcodes: [
              Barcode(rawValue: '7891234567890', format: BarcodeFormat.ean13),
            ],
          ),
        );
        await tester.pumpAndSettle();
        expect(spyNotifier.onCodeDetectedCalls, equals(1));
        expect(spyNotifier.resumeScanningCalls, equals(0));

        final resumeFinder = find.widgetWithText(OutlinedButton, 'Escanear novamente');
        final resumeOnPressed = tester.widget<OutlinedButton>(resumeFinder).onPressed!;

        // Inicia o fechamento selecionando o produto e avança apenas parte da animação
        await tester.tap(find.text('Selecionar produto'));
        await tester.pump(const Duration(milliseconds: 50));

        // Dispara leitura tardia da câmera e tentativa de retomada com _isClosing == true
        activeCapture!(
          const BarcodeCapture(
            barcodes: [
              Barcode(rawValue: '012345678905', format: BarcodeFormat.upcA),
            ],
          ),
        );
        resumeOnPressed();
        await tester.pumpAndSettle();

        expect(spyNotifier.onCodeDetectedCalls, equals(1));
        expect(spyNotifier.resumeScanningCalls, equals(0));
        expect(find.text('Tela Intermediária L-A'), findsOneWidget);
        expect(find.text('Tela Base Raiz'), findsNothing);
        expect(returnedItems.length, equals(1));
        expect(returnedItems.single?.id, equals('prod-uuid-123'));
      },
    );

    testWidgets(
      'L-C: duplo acionamento com intervalo na animação em CloseButton (fullscreenDialog), Selecionar produto e PopScope back preserva tela subjacente',
      (tester) async {
        void Function(BarcodeCapture)? activeCapture;
        final returnedItems = <SearchResultItem?>[];
        BuildContext? scannerContext;

        await tester.pumpWidget(
          MaterialApp(
            home: Builder(
              builder: (baseContext) => Scaffold(
                body: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text('Tela Base Raiz L-C'),
                      ElevatedButton(
                        key: const Key('btn_open_inter_lc'),
                        onPressed: () {
                          Navigator.of(baseContext).push<void>(
                            MaterialPageRoute<void>(
                              builder: (interContext) => Scaffold(
                                body: Center(
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Text('Tela Intermediária L-C'),
                                      ElevatedButton(
                                        key: const Key('btn_open_fullscreen_scanner'),
                                        onPressed: () async {
                                          final res = await Navigator.of(interContext)
                                              .push<SearchResultItem?>(
                                            MaterialPageRoute<SearchResultItem?>(
                                              fullscreenDialog: true,
                                              builder: (_) => ScannerScreen(
                                                productRepository: fakeRepository,
                                                placeRepository: fakePlaceRepository,
                                                selectionMode: true,
                                                cameraBuilder: (ctx, onDetect) {
                                                  scannerContext = ctx;
                                                  activeCapture = onDetect;
                                                  return const SizedBox.expand(
                                                    key: Key('lc_camera'),
                                                  );
                                                },
                                              ),
                                            ),
                                          );
                                          returnedItems.add(res);
                                        },
                                        child: const Text('Abrir Scanner Fullscreen'),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          );
                        },
                        child: const Text('Ir Inter L-C'),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );

        await tester.tap(find.byKey(const Key('btn_open_inter_lc')));
        await tester.pumpAndSettle();

        // 1) CloseButton em fullscreenDialog com segundo disparo durante animação (pump 50ms)
        await tester.tap(find.byKey(const Key('btn_open_fullscreen_scanner')));
        await tester.pumpAndSettle();

        final closeFinder = find.byType(CloseButton);
        expect(closeFinder, findsOneWidget);
        expect(find.byType(BackButton), findsNothing);
        final closeOnPressed = tester.widget<CloseButton>(closeFinder).onPressed!;

        await tester.tap(closeFinder);
        await tester.pump(const Duration(milliseconds: 50));
        closeOnPressed();
        await tester.pumpAndSettle();

        expect(find.text('Tela Intermediária L-C'), findsOneWidget);
        expect(find.text('Tela Base Raiz L-C'), findsNothing);
        expect(returnedItems.length, equals(1));
        expect(returnedItems.last, isNull);

        // 2) Selecionar produto com segundo disparo durante animação (pump 50ms)
        await tester.tap(find.byKey(const Key('btn_open_fullscreen_scanner')));
        await tester.pumpAndSettle();

        activeCapture!(
          const BarcodeCapture(
            barcodes: [
              Barcode(rawValue: '7891234567890', format: BarcodeFormat.ean13),
            ],
          ),
        );
        await tester.pumpAndSettle();

        final selectProdFinder = find.widgetWithText(ElevatedButton, 'Selecionar produto');
        final selectOnPressed = tester.widget<ElevatedButton>(selectProdFinder).onPressed!;
        await tester.tap(selectProdFinder);
        await tester.pump(const Duration(milliseconds: 50));
        selectOnPressed();
        await tester.pumpAndSettle();

        expect(find.text('Tela Intermediária L-C'), findsOneWidget);
        expect(find.text('Tela Base Raiz L-C'), findsNothing);
        expect(returnedItems.length, equals(2));
        expect(returnedItems.last?.id, equals('prod-uuid-123'));

        // 3) Back via PopScope (maybePop) seguido de acionamentos durante animação de saída
        await tester.tap(find.byKey(const Key('btn_open_fullscreen_scanner')));
        await tester.pumpAndSettle();

        activeCapture!(
          const BarcodeCapture(
            barcodes: [
              Barcode(rawValue: 'rewit://places/place-789', format: BarcodeFormat.qrCode),
            ],
          ),
        );
        await tester.pumpAndSettle();

        final selectPlaceFinder = find.widgetWithText(ElevatedButton, 'Selecionar local');
        final selectPlaceOnPressed = tester.widget<ElevatedButton>(selectPlaceFinder).onPressed!;
        final closeAfterPopOnPressed = tester.widget<CloseButton>(find.byType(CloseButton)).onPressed!;

        await Navigator.of(scannerContext!).maybePop();
        await tester.pump(const Duration(milliseconds: 50));
        selectPlaceOnPressed();
        closeAfterPopOnPressed();
        await tester.pumpAndSettle();

        expect(find.text('Tela Intermediária L-C'), findsOneWidget);
        expect(find.text('Tela Base Raiz L-C'), findsNothing);
        expect(returnedItems.length, equals(3));
        expect(returnedItems.last, isNull);
      },
    );
  });

  group('ScannerScreen - UX de Permissão de Câmera e Ciclo de Vida', () {
    late MobileScannerPlatform originalPlatform;
    late _SpyMobileScannerPlatform spyPlatform;

    setUp(() {
      originalPlatform = MobileScannerPlatform.instance;
      MobileScannerController.resetPlatformSessionOwner();
      spyPlatform = _SpyMobileScannerPlatform();
      MobileScannerPlatform.instance = spyPlatform;
    });

    tearDown(() async {
      await spyPlatform.disposeStreams();
      MobileScannerController.resetPlatformSessionOwner();
      MobileScannerPlatform.instance = originalPlatform;
    });

    testWidgets(
      'concessão inicial de permissão exibe explicação contextual antes do diálogo nativo e inicia câmera ao continuar',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.denied,
          requestResponses: [CameraPermissionStatus.granted],
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(fakePermission.checkCalls, equals(1));
        expect(fakePermission.hasRequestedBeforeCalls, equals(1));
        expect(fakePermission.requestCalls, equals(0));
        expect(spyPlatform.startCalls, equals(0));
        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsOneWidget);
        expect(find.text('Continuar'), findsOneWidget);
        expect(find.text('Agora não'), findsOneWidget);

        await tester.tap(find.byKey(const Key('scanner_rationale_continue_button')));
        await tester.pumpAndSettle();

        expect(fakePermission.requestCalls, equals(1));
        expect(spyPlatform.startCalls, equals(1));
        expect(find.byType(MobileScanner), findsOneWidget);
        expect(find.text('Acesso à câmera necessário'), findsNothing);
        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsNothing);
      },
    );

    testWidgets(
      'primeira negação após explicação contextual exibe negação temporária sem repetir explicação e concede na segunda tentativa',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.denied,
          requestResponses: [
            CameraPermissionStatus.denied,
            CameraPermissionStatus.granted,
          ],
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsOneWidget);
        await tester.tap(find.byKey(const Key('scanner_rationale_continue_button')));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsNothing);
        expect(find.text('Acesso à câmera necessário'), findsOneWidget);
        expect(find.text('Tentar novamente'), findsOneWidget);
        expect(find.text('Abrir configurações'), findsNothing);
        expect(find.text('Voltar'), findsOneWidget);
        expect(spyPlatform.startCalls, equals(0));

        await tester.tap(find.text('Tentar novamente'));
        await tester.pumpAndSettle();

        expect(fakePermission.requestCalls, equals(2));
        expect(spyPlatform.startCalls, equals(1));
        expect(find.byType(MobileScanner), findsOneWidget);
        expect(find.text('Tentar novamente'), findsNothing);
      },
    );

    testWidgets(
      'negação repetida evolui para negação permanente com Abrir configurações e sem Tentar novamente',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.denied,
          hasRequestedBefore: true,
          requestResponses: [
            CameraPermissionStatus.denied,
            CameraPermissionStatus.permanentlyDenied,
          ],
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsNothing);
        expect(find.text('Tentar novamente'), findsOneWidget);
        expect(find.text('Abrir configurações'), findsNothing);

        fakePermission.nextCheckStatus = CameraPermissionStatus.denied;
        await tester.tap(find.text('Tentar novamente'));
        await tester.pumpAndSettle();

        expect(find.text('Acesso à câmera necessário'), findsOneWidget);
        expect(
          find.text(
            'A permissão de acesso à câmera foi negada permanentemente. Para escanear códigos, habilite a câmera nas configurações do dispositivo.',
          ),
          findsOneWidget,
        );
        expect(find.text('Abrir configurações'), findsOneWidget);
        expect(find.text('Tentar novamente'), findsNothing);
        expect(find.text('Voltar'), findsOneWidget);
        expect(spyPlatform.startCalls, equals(0));
      },
    );

    testWidgets(
      'abertura com permissão já negada permanentemente entra direto na tela de configurações sem chamar start()',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.permanentlyDenied,
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.text('Abrir configurações'), findsOneWidget);
        expect(find.text('Tentar novamente'), findsNothing);
        expect(fakePermission.requestCalls, equals(0));
        expect(spyPlatform.startCalls, equals(0));
      },
    );

    testWidgets(
      'botão Abrir configurações aciona abertura nativa e exibe feedback discreto se falhar',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.permanentlyDenied,
          openSettingsResult: true,
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.text('Abrir configurações'));
        await tester.pumpAndSettle();
        expect(fakePermission.openSettingsCalls, equals(1));

        fakePermission.openSettingsResult = false;
        await tester.tap(find.text('Abrir configurações'));
        await tester.pumpAndSettle();
        expect(fakePermission.openSettingsCalls, equals(2));
        expect(
          find.text('Não foi possível abrir as configurações do dispositivo.'),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      'retornar das Configurações sem conceder mantém negação permanente e não inicia a câmera',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.permanentlyDenied,
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.text('Abrir configurações'));
        await tester.pumpAndSettle();

        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
        await tester.pump();
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pumpAndSettle();

        expect(find.text('Abrir configurações'), findsOneWidget);
        expect(spyPlatform.startCalls, equals(0));
      },
    );

    testWidgets(
      'retornar das Configurações com permissão concedida limpa negação e inicia câmera uma única vez',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.permanentlyDenied,
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.text('Abrir configurações'), findsOneWidget);
        expect(spyPlatform.startCalls, equals(0));

        await tester.tap(find.text('Abrir configurações'));
        await tester.pumpAndSettle();

        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
        await tester.pump();

        // Usuário habilitou a câmera nas Configurações do Android antes de voltar
        fakePermission.nextCheckStatus = CameraPermissionStatus.granted;
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pumpAndSettle();

        expect(find.text('Abrir configurações'), findsNothing);
        expect(find.byType(MobileScanner), findsOneWidget);
        expect(spyPlatform.startCalls, equals(1));

        // Em modo normal, leitura após retorno continua funcionando normalmente
        spyPlatform.emitBarcode('7891234567890', format: BarcodeFormat.ean13);
        await tester.pumpAndSettle();

        expect(find.text('Produto encontrado'), findsOneWidget);
        expect(find.text('Fazenda Terra Roxa Café Arábica Especial'), findsOneWidget);
        expect(find.text('Ver produto'), findsOneWidget);
      },
    );

    testWidgets(
      'recuperação via errorBuilder seguida de retorno com concessão não reutiliza erro obsoleto nem duplica start()',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.granted,
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(spyPlatform.startCalls, equals(1));

        // Simula falha tardia de permissão no próprio MobileScanner (ex.: revogação)
        final scannerElement = tester.element(find.byType(MobileScanner));
        final scannerWidget = tester.widget<MobileScanner>(find.byType(MobileScanner));
        scannerWidget.controller!.value = scannerWidget.controller!.value.copyWith(
          error: const MobileScannerException(
            errorCode: MobileScannerErrorCode.permissionDenied,
          ),
        );
        fakePermission.nextCheckStatus = CameraPermissionStatus.permanentlyDenied;
        scannerWidget.errorBuilder!(
          scannerElement,
          const MobileScannerException(
            errorCode: MobileScannerErrorCode.permissionDenied,
          ),
        );
        await tester.pumpAndSettle();

        expect(find.text('Abrir configurações'), findsOneWidget);
        expect(spyPlatform.startCalls, equals(1));

        // Retorna com permissão concedida
        fakePermission.nextCheckStatus = CameraPermissionStatus.granted;
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
        await tester.pump();
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pumpAndSettle();

        expect(find.byType(MobileScanner), findsOneWidget);
        expect(find.text('Abrir configurações'), findsNothing);
        // Exatamente +1 start após recuperação (total = 2, nunca 3)
        expect(spyPlatform.startCalls, equals(2));
      },
    );

    testWidgets(
      'fechamento da rota durante solicitação assíncrona de permissão ignora resposta tardia com segurança',
      (tester) async {
        final pendingRequest = Completer<CameraPermissionStatus>();
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.denied,
          hasRequestedBefore: true,
          pendingRequest: pendingRequest,
        );
        final notifier = ScannerNotifier(productRepository: fakeRepository);
        final poppedValues = <SearchResultItem?>[];

        await tester.pumpWidget(
          MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: ElevatedButton(
                    key: const Key('btn_push_scanner_perm'),
                    onPressed: () async {
                      final res = await Navigator.of(context).push<SearchResultItem?>(
                        MaterialPageRoute<SearchResultItem?>(
                          builder: (_) => ScannerScreen(
                            notifier: notifier,
                            selectionMode: true,
                            cameraPermissionService: fakePermission,
                          ),
                        ),
                      );
                      poppedValues.add(res);
                    },
                    child: const Text('Abrir Scanner'),
                  ),
                ),
              ),
            ),
          ),
        );

        await tester.tap(find.byKey(const Key('btn_push_scanner_perm')));
        await tester.pumpAndSettle();

        expect(fakePermission.requestCalls, equals(1));

        // Fecha a rota enquanto a caixa de permissão ainda está pendente
        await tester.tap(find.byType(BackButton));
        await tester.pumpAndSettle();

        expect(poppedValues, equals([null]));
        expect(notifier.state, isA<ScannerInitial>());

        // Resposta tardia chega após fechamento da tela
        pendingRequest.complete(CameraPermissionStatus.granted);
        await tester.pumpAndSettle();

        expect(notifier.state, isA<ScannerInitial>());
        expect(spyPlatform.startCalls, equals(0));
        expect(find.text('Abrir Scanner'), findsOneWidget);

        notifier.dispose();
      },
    );

    testWidgets(
      'Selection Mode com negação permanente permite Voltar com null e funciona ao retornar com concessão',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.permanentlyDenied,
        );
        final results = <SearchResultItem?>[];

        await tester.pumpWidget(
          MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: ElevatedButton(
                    key: const Key('btn_open_selection_perm'),
                    onPressed: () async {
                      final res = await Navigator.of(context).push<SearchResultItem?>(
                        MaterialPageRoute<SearchResultItem?>(
                          builder: (_) => ScannerScreen(
                            productRepository: fakeRepository,
                            placeRepository: _FakePlaceRepository(),
                            selectionMode: true,
                            cameraPermissionService: fakePermission,
                          ),
                        ),
                      );
                      results.add(res);
                    },
                    child: const Text('Escanear para avaliar'),
                  ),
                ),
              ),
            ),
          ),
        );

        // 1) Negação permanente + Voltar -> retorna null preservando rota anterior
        await tester.tap(find.byKey(const Key('btn_open_selection_perm')));
        await tester.pumpAndSettle();

        expect(find.text('Abrir configurações'), findsOneWidget);
        await tester.tap(find.text('Voltar'));
        await tester.pumpAndSettle();

        expect(results, equals([null]));
        expect(find.text('Escanear para avaliar'), findsOneWidget);
        expect(spyPlatform.startCalls, equals(0));

        // 2) Reabre em Selection Mode, vai às Configurações e retorna com concessão
        await tester.tap(find.byKey(const Key('btn_open_selection_perm')));
        await tester.pumpAndSettle();

        await tester.tap(find.text('Abrir configurações'));
        await tester.pumpAndSettle();

        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
        await tester.pump();
        fakePermission.nextCheckStatus = CameraPermissionStatus.granted;
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pumpAndSettle();

        expect(find.byType(MobileScanner), findsOneWidget);
        expect(spyPlatform.startCalls, equals(1));

        spyPlatform.emitBarcode('7891234567890', format: BarcodeFormat.ean13);
        await tester.pumpAndSettle();

        expect(find.text('Selecionar produto'), findsOneWidget);
        await tester.tap(find.text('Selecionar produto'));
        await tester.pumpAndSettle();

        expect(results.length, equals(2));
        expect(results.last, isNotNull);
        expect(results.last!.id, equals('prod-uuid-123'));
        expect(results.last!.targetType, equals(TargetType.product));
      },
    );

    testWidgets(
      'Bug 04: cancelar explicação prévia com Agora não fecha rota sem solicitar permissão e reabrir exibe explicação novamente',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.denied,
          requestResponses: [CameraPermissionStatus.granted],
        );
        final results = <SearchResultItem?>[];

        await tester.pumpWidget(
          MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: ElevatedButton(
                    key: const Key('btn_open_scanner_rationale'),
                    onPressed: () async {
                      final res = await Navigator.of(context).push<SearchResultItem?>(
                        MaterialPageRoute<SearchResultItem?>(
                          builder: (_) => ScannerScreen(
                            productRepository: fakeRepository,
                            selectionMode: true,
                            cameraPermissionService: fakePermission,
                          ),
                        ),
                      );
                      results.add(res);
                    },
                    child: const Text('Abrir Scanner'),
                  ),
                ),
              ),
            ),
          ),
        );

        await tester.tap(find.byKey(const Key('btn_open_scanner_rationale')));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsOneWidget);
        expect(fakePermission.requestCalls, equals(0));
        expect(fakePermission.hasRequestedBefore, isFalse);

        // Cancelamento explícito ("Agora não")
        await tester.tap(find.byKey(const Key('scanner_rationale_cancel_button')));
        await tester.pumpAndSettle();

        expect(results, equals([null]));
        expect(fakePermission.requestCalls, equals(0));
        expect(fakePermission.hasRequestedBefore, isFalse);
        expect(spyPlatform.startCalls, equals(0));

        // Reabertura sem histórico prévio volta a exibir explicação contextual
        await tester.tap(find.byKey(const Key('btn_open_scanner_rationale')));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsOneWidget);
        expect(fakePermission.requestCalls, equals(0));

        // Agora continua e concede
        await tester.tap(find.byKey(const Key('scanner_rationale_continue_button')));
        await tester.pumpAndSettle();
        expect(fakePermission.requestCalls, equals(1));
        expect(fakePermission.hasRequestedBefore, isTrue);
        expect(spyPlatform.startCalls, equals(1));
      },
    );

    testWidgets(
      'Bug 04: após recusa anterior, reabertura não repete explicação redundante e duplo toque em Continuar só solicita uma vez',
      (tester) async {
        final pendingRequest = Completer<CameraPermissionStatus>();
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.denied,
          pendingRequest: pendingRequest,
        );

        await tester.pumpWidget(
          MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: ElevatedButton(
                    key: const Key('btn_open_scanner_reopen'),
                    onPressed: () {
                      Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => ScannerScreen(
                            productRepository: fakeRepository,
                            cameraPermissionService: fakePermission,
                          ),
                        ),
                      );
                    },
                    child: const Text('Abrir Scanner'),
                  ),
                ),
              ),
            ),
          ),
        );

        await tester.tap(find.byKey(const Key('btn_open_scanner_reopen')));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsOneWidget);

        // Duplo toque rápido em Continuar enquanto diálogo nativo ainda pende
        await tester.tap(find.byKey(const Key('scanner_rationale_continue_button')));
        await tester.tap(find.byKey(const Key('scanner_rationale_continue_button')));
        await tester.pump();

        expect(fakePermission.requestCalls, equals(1));

        pendingRequest.complete(CameraPermissionStatus.denied);
        await tester.pumpAndSettle();

        expect(find.text('Acesso à câmera necessário'), findsOneWidget);
        expect(fakePermission.hasRequestedBefore, isTrue);

        // Volta e reabre: não repete mais a tela de explicação em loop
        await tester.tap(find.text('Voltar'));
        await tester.pumpAndSettle();

        fakePermission.nextCheckStatus = CameraPermissionStatus.denied;
        await tester.tap(find.byKey(const Key('btn_open_scanner_reopen')));
        await tester.pump();
        await tester.pump();

        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsNothing);
        expect(fakePermission.requestCalls, equals(2));
      },
    );

    testWidgets(
      'Bug 04: conceder permissão nas configurações do sistema enquanto na tela de explicação inicia a câmera ao retornar ao app',
      (tester) async {
        final fakePermission = _FakeCameraPermissionService(
          initialCheckStatus: CameraPermissionStatus.denied,
        );

        await tester.pumpWidget(
          MaterialApp(
            home: ScannerScreen(
              productRepository: fakeRepository,
              cameraPermissionService: fakePermission,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsOneWidget);
        expect(spyPlatform.startCalls, equals(0));

        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
        await tester.pump();
        fakePermission.nextCheckStatus = CameraPermissionStatus.granted;
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('scanner_permission_rationale_view')), findsNothing);
        expect(find.byType(MobileScanner), findsOneWidget);
        expect(spyPlatform.startCalls, equals(1));
      },
    );
  });

  group('Bug 02 — Contraste e legibilidade da AppBar do Scanner', () {
    for (final entry in <String, ThemeData>{
      'ThemeData Claro': AppTheme.lightTheme,
      'ThemeData Escuro': AppTheme.darkTheme,
    }.entries) {
      testWidgets(
        'exibe título e ícones da AppBar com cor clara sobre mediaScrim em ${entry.key} (incluindo flash ligado/desligado e Back/Close)',
        (tester) async {
          final spyPlatform = _SpyMobileScannerPlatform();
          MobileScannerPlatform.instance = spyPlatform;
          addTearDown(() async {
            await spyPlatform.disposeStreams();
          });

          Future<void> verifyAppBarContrast({required bool fullscreenDialog}) async {
            await tester.pumpWidget(
              MaterialApp(
                key: UniqueKey(),
                theme: entry.value,
                home: Builder(
                  builder: (context) => Scaffold(
                    body: TextButton(
                      key: Key('btn_open_${fullscreenDialog ? 'close' : 'back'}'),
                      onPressed: () {
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            fullscreenDialog: fullscreenDialog,
                            builder: (_) => ScannerScreen(
                              cameraPermissionService: _FakeCameraPermissionService(
                                initialCheckStatus: CameraPermissionStatus.granted,
                              ),
                            ),
                          ),
                        );
                      },
                      child: const Text('Abrir Scanner'),
                    ),
                  ),
                ),
              ),
            );

            await tester.tap(
              find.byKey(Key('btn_open_${fullscreenDialog ? 'close' : 'back'}')),
            );
            await tester.pumpAndSettle();

            final semantic = entry.value.extension<RewitSemanticColors>()!;
            final appBar = tester.widget<AppBar>(find.byType(AppBar));
            expect(appBar.backgroundColor, equals(semantic.mediaScrim));
            expect(appBar.foregroundColor, equals(semantic.onMediaScrim));
            expect(appBar.foregroundColor!.computeLuminance(), greaterThan(0.9));

            // Título efetivo pintado
            final titleRichText = tester.widget<RichText>(
              find.descendant(
                of: find.byType(AppBar),
                matching: find.byWidgetPredicate(
                  (w) => w is RichText && w.text.toPlainText() == 'Escanear Código',
                ),
              ),
            );
            expect(titleRichText.text.style?.color, equals(semantic.onMediaScrim));

            // Ícone leading (CloseButton ou BackButton) efetivo
            final expectedLeadingIcon =
                fullscreenDialog ? Icons.close : Icons.arrow_back;
            final leadingRichText = tester.widget<RichText>(
              find.descendant(
                of: find.byIcon(expectedLeadingIcon),
                matching: find.byType(RichText),
              ),
            );
            expect(
              leadingRichText.text.style?.color,
              equals(semantic.onMediaScrim),
            );

            // Ícone do flash (OFF -> ON) efetivo
            final flashOffRichText = tester.widget<RichText>(
              find.descendant(
                of: find.byIcon(Icons.flash_off),
                matching: find.byType(RichText),
              ),
            );
            expect(
              flashOffRichText.text.style?.color,
              equals(semantic.onMediaScrim),
            );

            // Ícone de alternar câmera efetivo
            final switchCameraRichText = tester.widget<RichText>(
              find.descendant(
                of: find.byIcon(Icons.flip_camera_ios),
                matching: find.byType(RichText),
              ),
            );
            expect(
              switchCameraRichText.text.style?.color,
              equals(semantic.onMediaScrim),
            );

            // Alterna flash para ON e valida que o novo ícone continua legível
            await tester.tap(find.byIcon(Icons.flash_off));
            await tester.pumpAndSettle();
            final flashOnRichText = tester.widget<RichText>(
              find.descendant(
                of: find.byIcon(Icons.flash_on),
                matching: find.byType(RichText),
              ),
            );
            expect(
              flashOnRichText.text.style?.color,
              equals(semantic.onMediaScrim),
            );
          }

          await verifyAppBarContrast(fullscreenDialog: false);
          await verifyAppBarContrast(fullscreenDialog: true);
        },
      );
    }
  });
}

class _FakeCameraPermissionService implements CameraPermissionService {
  CameraPermissionStatus nextCheckStatus;
  bool hasRequestedBefore;
  final List<CameraPermissionStatus> _requestResponses;
  final Completer<CameraPermissionStatus>? pendingRequest;
  bool openSettingsResult;

  int checkCalls = 0;
  int hasRequestedBeforeCalls = 0;
  int requestCalls = 0;
  int openSettingsCalls = 0;

  _FakeCameraPermissionService({
    required CameraPermissionStatus initialCheckStatus,
    this.hasRequestedBefore = false,
    List<CameraPermissionStatus> requestResponses = const [],
    this.pendingRequest,
    this.openSettingsResult = true,
  })  : nextCheckStatus = initialCheckStatus,
        _requestResponses = List<CameraPermissionStatus>.from(requestResponses);

  @override
  Future<CameraPermissionStatus> checkPermissionStatus({
    bool afterRequest = false,
  }) async {
    checkCalls++;
    return nextCheckStatus;
  }

  @override
  Future<bool> hasRequestedPermissionBefore() async {
    hasRequestedBeforeCalls++;
    return hasRequestedBefore;
  }

  @override
  Future<CameraPermissionStatus> requestPermission() async {
    requestCalls++;
    if (pendingRequest != null) {
      final result = await pendingRequest!.future;
      hasRequestedBefore = true;
      nextCheckStatus = result;
      return result;
    }
    hasRequestedBefore = true;
    if (_requestResponses.isNotEmpty) {
      final result = _requestResponses.removeAt(0);
      nextCheckStatus = result;
      return result;
    }
    return nextCheckStatus;
  }

  @override
  Future<bool> openAppSettings() async {
    openSettingsCalls++;
    return openSettingsResult;
  }
}

class _SpyMobileScannerPlatform extends MobileScannerPlatform {
  final StreamController<BarcodeCapture?> _barcodesController =
      StreamController<BarcodeCapture?>.broadcast();
  final StreamController<TorchState> _torchController =
      StreamController<TorchState>.broadcast();
  final StreamController<double> _zoomController =
      StreamController<double>.broadcast();

  int startCalls = 0;
  int stopCalls = 0;

  void emitBarcode(String rawValue, {BarcodeFormat format = BarcodeFormat.ean13}) {
    if (_barcodesController.isClosed) return;
    _barcodesController.add(
      BarcodeCapture(
        barcodes: [Barcode(rawValue: rawValue, format: format)],
      ),
    );
  }

  Future<void> disposeStreams() async {
    await _barcodesController.close();
    await _torchController.close();
    await _zoomController.close();
  }

  @override
  Stream<BarcodeCapture?> get barcodesStream => _barcodesController.stream;

  @override
  Stream<TorchState> get torchStateStream => _torchController.stream;

  @override
  Stream<double> get zoomScaleStateStream => _zoomController.stream;

  @override
  Widget buildCameraView() => const ColoredBox(color: Colors.black);

  @override
  Future<MobileScannerViewAttributes> start(StartOptions startOptions) async {
    startCalls++;
    return const MobileScannerViewAttributes(
      cameraDirection: CameraFacing.back,
      currentTorchMode: TorchState.off,
      size: Size(1080, 1920),
      numberOfCameras: 2,
    );
  }

  @override
  Future<void> stop() async {
    stopCalls++;
  }

  @override
  Future<void> pause() async {}

  @override
  Future<void> toggleTorch() async {}

  @override
  Future<void> dispose() async {}
}
