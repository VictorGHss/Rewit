import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/scanner/presentation/screens/scanner_screen.dart';
import 'package:rewit_mobile/features/scanner/presentation/state/scanner_notifier.dart';

class _FakeProductRepository implements ProductRepository {
  ProductDetail? productToReturn;
  Exception? exceptionToThrow;

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
  Future<ProductDetail> getProductById(String id) async => throw UnimplementedError();

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

class _StubAuthRepo implements AuthRepository {
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

    // Redefine com recurso interno
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

  testWidgets('HomeScreen possui botão de scanner na AppBar e navega para /scanner', (tester) async {
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

    // Localiza o botão do scanner na AppBar
    final scannerButton = find.byTooltip('Escanear Código');
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
}
