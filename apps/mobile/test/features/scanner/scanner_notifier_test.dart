import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/scanner/domain/entities/scanned_code.dart';
import 'package:rewit_mobile/features/scanner/domain/entities/scanner_state.dart';
import 'package:rewit_mobile/features/scanner/presentation/state/scanner_notifier.dart';

class _FakeProductRepository implements ProductRepository {
  ProductDetail? productToReturn;
  Exception? exceptionToThrow;
  int lookupCallCount = 0;
  String? lastLookupType;
  String? lastLookupValue;

  @override
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  }) async {
    lookupCallCount++;
    lastLookupType = type;
    lastLookupValue = value;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    return productToReturn ??
        const ProductDetail(
          id: 'prod-uuid-1',
          name: 'Café Especial Arábica',
          brand: 'Fazenda Santa Maria',
          model: '250g Grãos',
          category: 'BEBIDA',
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
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async =>
      throw UnimplementedError();

  @override
  Future<TargetStats> getProductStats(String id) async => throw UnimplementedError();

  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async => Uint8List.fromList([]);
}

void main() {
  late _FakeProductRepository fakeRepository;
  late ScannerNotifier notifier;

  setUp(() {
    fakeRepository = _FakeProductRepository();
    notifier = ScannerNotifier(productRepository: fakeRepository);
  });

  tearDown(() {
    notifier.dispose();
  });

  group('ScannerNotifier - Ciclo de vida básico e permissões', () {
    test('inicia no estado ScannerInitial e sem processamento', () {
      expect(notifier.state, isA<ScannerInitial>());
      expect(notifier.isProcessing, isFalse);
    });

    test('startScanning transiciona para ScannerScanning', () {
      notifier.startScanning();
      expect(notifier.state, isA<ScannerScanning>());
      expect(notifier.isProcessing, isFalse);
    });

    test('resumeScanning redefine o estado para ScannerScanning e limpa último código', () async {
      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(notifier.state, isA<ScannerFound>());

      notifier.resumeScanning();
      expect(notifier.state, isA<ScannerScanning>());
      expect(notifier.isProcessing, isFalse);
    });

    test('onPermissionDenied transiciona para ScannerPermissionDenied', () {
      notifier.onPermissionDenied(permanentlyDenied: false);
      expect(notifier.state, isA<ScannerPermissionDenied>());
      expect((notifier.state as ScannerPermissionDenied).permanentlyDenied, isFalse);

      notifier.onPermissionDenied(permanentlyDenied: true);
      expect(notifier.state, isA<ScannerPermissionDenied>());
      expect((notifier.state as ScannerPermissionDenied).permanentlyDenied, isTrue);
    });
  });

  group('ScannerNotifier - Detecção de Barcodes e Consulta ao Catálogo', () {
    test('consulta produto existente com EAN-13 e transiciona para ScannerFound', () async {
      await notifier.onCodeDetected(' 7891234567890 ', format: BarcodeFormat.ean13);

      expect(fakeRepository.lookupCallCount, equals(1));
      expect(fakeRepository.lastLookupType, equals('EAN'));
      expect(fakeRepository.lastLookupValue, equals('7891234567890'));
      expect(notifier.state, isA<ScannerFound>());

      final found = notifier.state as ScannerFound;
      expect(found.product.id, equals('prod-uuid-1'));
      expect(found.product.name, equals('Café Especial Arábica'));
      expect(found.scannedCode.normalizedValue, equals('7891234567890'));
      expect(notifier.isProcessing, isFalse);
    });

    test('produto não encontrado (404) transiciona para ScannerNotFound', () async {
      fakeRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'Produto não cadastrado para o identificador fornecido.',
          code: 'PRODUCT_NOT_FOUND',
        ),
      );

      await notifier.onCodeDetected('7890000000000', format: BarcodeFormat.ean13);

      expect(fakeRepository.lookupCallCount, equals(1));
      expect(notifier.state, isA<ScannerNotFound>());
      final notFound = notifier.state as ScannerNotFound;
      expect(notFound.scannedCode.normalizedValue, equals('7890000000000'));
      expect(notFound.message, contains('não cadastrado'));
    });

    test('rate limit (429) transiciona para ScannerError com flags de limitação', () async {
      fakeRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Too Many Requests',
          status: 429,
          detail: 'Limite de requisições excedido.',
          code: 'RATE_LIMIT_EXCEEDED',
        ),
        retryAfterSeconds: 45,
      );

      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);

      expect(notifier.state, isA<ScannerError>());
      final error = notifier.state as ScannerError;
      expect(error.isRateLimit, isTrue);
      expect(error.retryAfterSeconds, equals(45));
    });

    test('requisição inválida (400) transiciona para ScannerError com mensagem apropriada', () async {
      fakeRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Bad Request',
          status: 400,
          detail: 'Identificador mal formatado.',
          code: 'INVALID_IDENTIFIER',
        ),
      );

      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);

      expect(notifier.state, isA<ScannerError>());
      final error = notifier.state as ScannerError;
      expect(error.message, contains('Identificador inválido'));
    });

    test('sessão expirada (401) transiciona para ScannerError', () async {
      fakeRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: 'Sessão inválida.',
          code: 'UNAUTHORIZED',
        ),
      );

      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);

      expect(notifier.state, isA<ScannerError>());
      final error = notifier.state as ScannerError;
      expect(error.message, contains('Sessão expirada'));
    });

    test('falha de conexão de rede transiciona para ScannerError', () async {
      fakeRepository.exceptionToThrow = const NetworkException('Sem conexão com a internet');

      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);

      expect(notifier.state, isA<ScannerError>());
      final error = notifier.state as ScannerError;
      expect(error.message, contains('Sem conexão'));
    });

    test('exceção genérica inesperada transiciona para ScannerError amigável', () async {
      fakeRepository.exceptionToThrow = Exception('Erro interno no servidor');

      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);

      expect(notifier.state, isA<ScannerError>());
      final error = notifier.state as ScannerError;
      expect(error.message, contains('erro inesperado'));
    });

    test('avisa se repositório não estiver configurado', () async {
      final unconfiguredNotifier = ScannerNotifier(productRepository: null);

      await unconfiguredNotifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);

      expect(unconfiguredNotifier.state, isA<ScannerError>());
      final error = unconfiguredNotifier.state as ScannerError;
      expect(error.message, contains('não configurado'));

      unconfiguredNotifier.dispose();
    });
  });

  group('ScannerNotifier - Desduplicação e Trava Concorrente', () {
    test('ignora leituras repetidas enquanto exibe resultado de leitura anterior', () async {
      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(fakeRepository.lookupCallCount, equals(1));

      // Mesma leitura imediata sem resumeScanning: deve ser ignorada
      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(fakeRepository.lookupCallCount, equals(1));

      // Após retomar o escaneamento, deve permitir nova leitura
      notifier.resumeScanning();
      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(fakeRepository.lookupCallCount, equals(2));
    });
  });

  group('ScannerNotifier - Tratamento de Formatos Especiais e QR Codes', () {
    test('formato não suportado transiciona para ScannerUnsupported sem chamar repositório', () async {
      await notifier.onCodeDetected('CODE128-VAL', format: BarcodeFormat.code128);

      expect(fakeRepository.lookupCallCount, equals(0));
      expect(notifier.state, isA<ScannerUnsupported>());
      final state = notifier.state as ScannerUnsupported;
      expect(state.message, contains('code128'));
    });

    test('QR Code externo transiciona para ScannerQrExternal sem chamar repositório', () async {
      await notifier.onCodeDetected('https://external-promo.com', format: BarcodeFormat.qrCode);

      expect(fakeRepository.lookupCallCount, equals(0));
      expect(notifier.state, isA<ScannerQrExternal>());
      final state = notifier.state as ScannerQrExternal;
      expect(state.scannedCode.normalizedValue, equals('https://external-promo.com'));
    });

    test('QR Code interno para local transiciona para ScannerQrInternal com dados do recurso', () async {
      await notifier.onCodeDetected('rewit://places/place-xyz-100', format: BarcodeFormat.qrCode);

      expect(fakeRepository.lookupCallCount, equals(0));
      expect(notifier.state, isA<ScannerQrInternal>());
      final state = notifier.state as ScannerQrInternal;
      expect(state.resource.type, equals(InternalResourceType.place));
      expect(state.resource.id, equals('place-xyz-100'));
    });

    test('QR Code interno para lookup de produto dispara consulta ao repositório', () async {
      await notifier.onCodeDetected(
        'rewit://products/identifiers/EAN/7891234567890',
        format: BarcodeFormat.qrCode,
      );

      expect(fakeRepository.lookupCallCount, equals(1));
      expect(fakeRepository.lastLookupType, equals('EAN'));
      expect(fakeRepository.lastLookupValue, equals('7891234567890'));
      expect(notifier.state, isA<ScannerFound>());
    });
  });
}
