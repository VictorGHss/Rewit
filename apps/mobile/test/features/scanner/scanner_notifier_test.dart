import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
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
import 'package:rewit_mobile/features/scanner/presentation/state/scanner_notifier.dart';

class _FakeProductRepository implements ProductRepository {
  @override
  Future<ProductDetail> createProduct(CreateProductInput input) async => throw UnimplementedError();

  ProductDetail? productToReturn;
  Exception? exceptionToThrow;
  Future<ProductDetail> Function(String type, String value)? onGetProductByIdentifier;
  int lookupCallCount = 0;
  String? lastLookupType;
  String? lastLookupValue;
  final List<String> lookupCalls = [];

  ProductDetail? productByIdToReturn;
  Exception? productByIdException;
  int getProductByIdCallCount = 0;
  String? lastProductId;

  @override
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  }) async {
    lookupCallCount++;
    lastLookupType = type;
    lastLookupValue = value;
    lookupCalls.add('$type:$value');
    if (onGetProductByIdentifier != null) {
      return onGetProductByIdentifier!(type, value);
    }
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
  Future<ProductDetail> getProductById(String id) async {
    getProductByIdCallCount++;
    lastProductId = id;
    if (productByIdException != null) {
      throw productByIdException!;
    }
    return productByIdToReturn ??
        ProductDetail(
          id: id,
          name: 'Notebook Pro 14',
          brand: 'TechCo',
          category: 'ELETRÔNICOS',
          status: 'ACTIVE',
        );
  }

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

class _FakePlaceRepository implements PlaceRepository {
  PlaceDetail? placeToReturn;
  Exception? exceptionToThrow;
  int getPlaceByIdCallCount = 0;
  String? lastPlaceId;

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    getPlaceByIdCallCount++;
    lastPlaceId = id;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    return placeToReturn ??
        PlaceDetail(
          id: id,
          name: 'Café Central',
          slug: 'cafe-central',
          category: 'CAFETERIA',
          addressText: 'Rua das Flores, 123',
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

    test('dispose durante chamada assincrona nao dispara notifyListeners e nao lanca erro', () async {
      final customRepo = _FakeProductRepository();
      final customNotifier = ScannerNotifier(productRepository: customRepo);

      // Inicia chamada assíncrona
      final future = customNotifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      // Descarta imediatamente enquanto requisição está em trânsito
      customNotifier.dispose();

      expect(customNotifier.isDisposed, isTrue);
      await expectLater(future, completes);
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
    test('consulta GTIN-14 com tipo GTIN e 14 digitos', () async {
      await notifier.onCodeDetected('17891234567897', format: BarcodeFormat.itf14);

      expect(fakeRepository.lookupCallCount, equals(1));
      expect(fakeRepository.lastLookupType, equals('GTIN'));
      expect(fakeRepository.lastLookupValue, equals('17891234567897'));
      expect(notifier.state, isA<ScannerFound>());
    });

    test('rejeita lookup de produto se tipo nao estiver na whitelist do catalogo', () async {
      await notifier.onCodeDetected(
        'rewit://products/identifiers/CUSTOM_CODE/12345',
        format: BarcodeFormat.qrCode,
      );

      // Não deve chamar o repositório pois tipo é rejeitado antes da consulta
      expect(fakeRepository.lookupCallCount, equals(0));
      expect(notifier.state, isA<ScannerQrExternal>());
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

    test('impede que novo frame da camera sobrescreva resultado visivel ate resumeScanning ser acionado', () async {
      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(fakeRepository.lookupCallCount, equals(1));
      expect(notifier.state, isA<ScannerFound>());
      expect((notifier.state as ScannerFound).scannedCode.normalizedValue, equals('7891234567890'));

      // Código DIFERENTE detectado enquanto o resultado anterior é exibido
      await notifier.onCodeDetected('012345678905', format: BarcodeFormat.upcA);
      // Deve ser ignorado: repositório não é chamado e estado não é sobrescrito
      expect(fakeRepository.lookupCallCount, equals(1));
      expect((notifier.state as ScannerFound).scannedCode.normalizedValue, equals('7891234567890'));

      // Usuário toca em 'Escanear novamente'
      notifier.resumeScanning();
      expect(notifier.state, isA<ScannerScanning>());

      // Agora a leitura do novo código é processada
      await notifier.onCodeDetected('012345678905', format: BarcodeFormat.upcA);
      expect(fakeRepository.lookupCallCount, equals(2));
      expect(fakeRepository.lastLookupType, equals('UPC'));
      expect((notifier.state as ScannerFound).scannedCode.normalizedValue, equals('012345678905'));
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

    test('modo normal preserva QR codes internos de produto e review em ScannerQrInternal sem consultar repos', () async {
      await notifier.onCodeDetected('rewit://products/prod-normal-1', format: BarcodeFormat.qrCode);
      expect(notifier.state, isA<ScannerQrInternal>());
      expect(fakeRepository.getProductByIdCallCount, equals(0));

      notifier.resumeScanning();
      await notifier.onCodeDetected('rewit://reviews/rev-normal-1', format: BarcodeFormat.qrCode);
      expect(notifier.state, isA<ScannerQrInternal>());
    });
  });

  group('ScannerNotifier - Modo Seleção e Fallback ISBN-13', () {
    late _FakePlaceRepository fakePlaceRepository;
    late ScannerNotifier selectionNotifier;

    setUp(() {
      fakePlaceRepository = _FakePlaceRepository();
      selectionNotifier = ScannerNotifier(
        productRepository: fakeRepository,
        placeRepository: fakePlaceRepository,
        selectionMode: true,
      );
    });

    tearDown(() {
      selectionNotifier.dispose();
    });

    test('EAN, UPC e GTIN permanecem selecionáveis em selectionMode', () async {
      await selectionNotifier.onCodeDetected('012345678905', format: BarcodeFormat.upcA);
      expect(selectionNotifier.state, isA<ScannerFound>());
      expect(fakeRepository.lastLookupType, equals('UPC'));

      selectionNotifier.resumeScanning();
      await selectionNotifier.onCodeDetected('07891234567890', format: BarcodeFormat.itf14);
      expect(selectionNotifier.state, isA<ScannerFound>());
      expect(fakeRepository.lastLookupType, equals('GTIN'));
    });

    test('executa fallback para ISBN após EAN 404 em código 978 de 13 dígitos', () async {
      fakeRepository.onGetProductByIdentifier = (type, value) async {
        if (type == 'EAN') {
          throw const ApiException(
            ProblemDetail(
              type: 'about:blank',
              title: 'Not Found',
              status: 404,
              detail: 'Not found',
              code: 'PRODUCT_NOT_FOUND',
            ),
          );
        }
        return const ProductDetail(
          id: 'book-978',
          name: 'Código Limpo',
          brand: 'Alta Books',
          category: 'LIVROS',
          status: 'ACTIVE',
        );
      };

      await selectionNotifier.onCodeDetected('9788576082675', format: BarcodeFormat.ean13);

      expect(fakeRepository.lookupCalls, equals(['EAN:9788576082675', 'ISBN:9788576082675']));
      expect(selectionNotifier.state, isA<ScannerFound>());
      expect((selectionNotifier.state as ScannerFound).product.name, equals('Código Limpo'));
    });

    test('executa fallback para ISBN após EAN 404 em código 979 de 13 dígitos', () async {
      fakeRepository.onGetProductByIdentifier = (type, value) async {
        if (type == 'EAN') {
          throw const ApiException(
            ProblemDetail(
              type: 'about:blank',
              title: 'Not Found',
              status: 404,
              detail: 'Not found',
              code: 'PRODUCT_NOT_FOUND',
            ),
          );
        }
        return const ProductDetail(
          id: 'book-979',
          name: 'Arquitetura Limpa',
          brand: 'Editora Exemplo',
          category: 'LIVROS',
          status: 'ACTIVE',
        );
      };

      await selectionNotifier.onCodeDetected('9791090636071', format: BarcodeFormat.ean13);

      expect(fakeRepository.lookupCalls, equals(['EAN:9791090636071', 'ISBN:9791090636071']));
      expect(selectionNotifier.state, isA<ScannerFound>());
    });

    test('não executa fallback ISBN após erro de rede ou timeout', () async {
      fakeRepository.onGetProductByIdentifier = (_, __) async {
        throw const NetworkException('Tempo esgotado.');
      };

      await selectionNotifier.onCodeDetected('9788576082675', format: BarcodeFormat.ean13);

      expect(fakeRepository.lookupCalls, equals(['EAN:9788576082675']));
      expect(selectionNotifier.state, isA<ScannerError>());
    });

    test('não executa fallback ISBN após erro HTTP 500/503 do servidor', () async {
      fakeRepository.onGetProductByIdentifier = (_, __) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Service Unavailable',
            status: 503,
            detail: 'Indisponível',
            code: 'SERVICE_UNAVAILABLE',
          ),
        );
      };

      await selectionNotifier.onCodeDetected('9788576082675', format: BarcodeFormat.ean13);

      expect(fakeRepository.lookupCalls, equals(['EAN:9788576082675']));
      expect(selectionNotifier.state, isA<ScannerError>());
    });

    test('não executa fallback ISBN para EAN comum que não começa com 978 ou 979', () async {
      fakeRepository.onGetProductByIdentifier = (_, __) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            detail: 'Not found',
            code: 'PRODUCT_NOT_FOUND',
          ),
        );
      };

      await selectionNotifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);

      expect(fakeRepository.lookupCalls, equals(['EAN:7891234567890']));
      expect(selectionNotifier.state, isA<ScannerNotFound>());
    });

    test('QR de produto por ID consulta getProductById e obtém metadados reais', () async {
      fakeRepository.productByIdToReturn = const ProductDetail(
        id: 'prod-abc-123',
        name: 'Fone Bluetooth XYZ',
        brand: 'AudioMax',
        category: 'ÁUDIO',
        status: 'ACTIVE',
      );

      await selectionNotifier.onCodeDetected('rewit://products/prod-abc-123', format: BarcodeFormat.qrCode);

      expect(fakeRepository.getProductByIdCallCount, equals(1));
      expect(fakeRepository.lastProductId, equals('prod-abc-123'));
      expect(selectionNotifier.state, isA<ScannerFound>());
      final state = selectionNotifier.state as ScannerFound;
      expect(state.product.name, equals('Fone Bluetooth XYZ'));
      expect(state.product.category, equals('ÁUDIO'));
      expect(state.product.status, equals('ACTIVE'));
    });

    test('QR de produto por ID rejeita resposta sem nome real sem usar ID como substituto', () async {
      fakeRepository.productByIdToReturn = const ProductDetail(
        id: 'prod-sem-nome',
        name: '   ',
        brand: 'Marca',
        category: 'OUTROS',
        status: 'ACTIVE',
      );

      await selectionNotifier.onCodeDetected('rewit://products/prod-sem-nome', format: BarcodeFormat.qrCode);

      expect(selectionNotifier.state, isA<ScannerError>());
    });

    test('QR de lugar por ID consulta getPlaceById e emite ScannerPlaceFound', () async {
      fakePlaceRepository.placeToReturn = const PlaceDetail(
        id: 'place-uuid-9',
        name: 'Livraria Cultura',
        slug: 'livraria-cultura',
        category: 'LIVRARIA',
        addressText: 'Av. Paulista, 2073',
        validationRadiusMeters: 100,
        origin: 'MANUAL',
        isVerified: true,
        status: 'ACTIVE',
      );

      await selectionNotifier.onCodeDetected('rewit://places/place-uuid-9', format: BarcodeFormat.qrCode);

      expect(fakePlaceRepository.getPlaceByIdCallCount, equals(1));
      expect(fakePlaceRepository.lastPlaceId, equals('place-uuid-9'));
      expect(selectionNotifier.state, isA<ScannerPlaceFound>());
      final state = selectionNotifier.state as ScannerPlaceFound;
      expect(state.place.name, equals('Livraria Cultura'));
      expect(state.place.category, equals('LIVRARIA'));
    });

    test('QR de avaliação em selectionMode é recusado com ScannerInvalidSelectionTarget', () async {
      await selectionNotifier.onCodeDetected('rewit://reviews/rev-uuid-77', format: BarcodeFormat.qrCode);

      expect(selectionNotifier.state, isA<ScannerInvalidSelectionTarget>());
      final state = selectionNotifier.state as ScannerInvalidSelectionTarget;
      expect(state.resource.type, equals(InternalResourceType.review));
      expect(state.message, contains('avaliações'));
    });

    test('produto e lugar inexistentes por ID transicionam para ScannerNotFound', () async {
      fakeRepository.productByIdException = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'Produto não encontrado.',
          code: 'PRODUCT_NOT_FOUND',
        ),
      );
      await selectionNotifier.onCodeDetected('rewit://products/inexistente', format: BarcodeFormat.qrCode);
      expect(selectionNotifier.state, isA<ScannerNotFound>());
      expect((selectionNotifier.state as ScannerNotFound).isPlace, isFalse);

      selectionNotifier.resumeScanning();
      fakePlaceRepository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'Local não encontrado.',
          code: 'PLACE_NOT_FOUND',
        ),
      );
      await selectionNotifier.onCodeDetected('rewit://places/inexistente', format: BarcodeFormat.qrCode);
      expect(selectionNotifier.state, isA<ScannerNotFound>());
      expect((selectionNotifier.state as ScannerNotFound).isPlace, isTrue);
    });
  });
}
