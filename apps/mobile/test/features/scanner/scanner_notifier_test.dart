import 'dart:async';
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

  group('ScannerNotifier - Hardening: Concorrência, Cancelamento ISBN e Dados Inválidos', () {
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

    test('L-1: resumeScanning durante EAN 978 suspenso cancela fallback ISBN quando 404 retorna depois', () async {
      final eanCompleter = Completer<ProductDetail>();
      fakeRepository.onGetProductByIdentifier = (type, value) {
        if (type == 'EAN') return eanCompleter.future;
        return Future.value(
          const ProductDetail(
            id: 'book-978',
            name: 'Não deveria buscar',
            brand: 'Editora',
            category: 'LIVROS',
            status: 'ACTIVE',
          ),
        );
      };

      final detectionFuture = selectionNotifier.onCodeDetected(
        '9788576082675',
        format: BarcodeFormat.ean13,
      );
      expect(selectionNotifier.isProcessing, isTrue);
      expect(selectionNotifier.state, isA<ScannerProcessing>());

      // Usuário cancela/retoma escaneamento antes da resposta 404 do lookup EAN.
      selectionNotifier.resumeScanning();
      expect(selectionNotifier.state, isA<ScannerScanning>());
      expect(selectionNotifier.isProcessing, isFalse);

      // Resposta tardia 404 do lookup EAN não pode iniciar o fallback ISBN.
      eanCompleter.completeError(
        const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            detail: 'Not Found',
            code: 'PRODUCT_NOT_FOUND',
          ),
        ),
      );
      await detectionFuture;

      expect(fakeRepository.lookupCalls, equals(['EAN:9788576082675']));
      expect(selectionNotifier.state, isA<ScannerScanning>());
      expect(selectionNotifier.isProcessing, isFalse);
    });

    test('L-1: dispose durante EAN 979 suspenso impede fallback ISBN após 404 tardio e nao notifica listeners', () async {
      final customRepo = _FakeProductRepository();
      final customNotifier = ScannerNotifier(
        productRepository: customRepo,
        selectionMode: true,
      );
      final eanCompleter = Completer<ProductDetail>();
      customRepo.onGetProductByIdentifier = (type, value) => eanCompleter.future;

      var listenerNotifications = 0;
      customNotifier.addListener(() => listenerNotifications++);

      final detectionFuture = customNotifier.onCodeDetected(
        '9791090636071',
        format: BarcodeFormat.ean13,
      );
      final notificationsBeforeDispose = listenerNotifications;

      customNotifier.dispose();
      eanCompleter.completeError(
        const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            detail: 'Not Found',
            code: 'PRODUCT_NOT_FOUND',
          ),
        ),
      );
      await expectLater(detectionFuture, completes);

      expect(customRepo.lookupCalls, equals(['EAN:9791090636071']));
      expect(listenerNotifications, equals(notificationsBeforeDispose));
    });

    test('L-1 + M-1: nova leitura apos resumeScanning invalida fallback ISBN e isProcessing da consulta antiga', () async {
      final completer1 = Completer<ProductDetail>();
      final completer2 = Completer<ProductDetail>();

      fakeRepository.onGetProductByIdentifier = (type, value) {
        if (value == '9788576082675' && type == 'EAN') return completer1.future;
        if (value == '7891234567890' && type == 'EAN') return completer2.future;
        throw StateError('Chamada inesperada: $type:$value');
      };

      final f1 = selectionNotifier.onCodeDetected('9788576082675', format: BarcodeFormat.ean13);
      selectionNotifier.resumeScanning();

      final f2 = selectionNotifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(selectionNotifier.isProcessing, isTrue);
      expect(selectionNotifier.state, isA<ScannerProcessing>());

      // Primeira consulta (antiga) retorna 404 enquanto a segunda ainda está em trânsito:
      // não dispara ISBN e NÃO pode limpar _isProcessing da segunda leitura.
      completer1.completeError(
        const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            detail: 'Not Found',
            code: 'PRODUCT_NOT_FOUND',
          ),
        ),
      );
      await f1;

      expect(fakeRepository.lookupCalls, equals(['EAN:9788576082675', 'EAN:7891234567890']));
      expect(selectionNotifier.isProcessing, isTrue);
      expect(selectionNotifier.state, isA<ScannerProcessing>());

      completer2.complete(
        const ProductDetail(
          id: 'prod-2',
          name: 'Produto Novo Válido',
          brand: 'Marca B',
          category: 'OUTROS',
          status: 'ACTIVE',
        ),
      );
      await f2;

      expect(selectionNotifier.isProcessing, isFalse);
      expect(selectionNotifier.state, isA<ScannerFound>());
      expect((selectionNotifier.state as ScannerFound).product.id, equals('prod-2'));
    });

    test('M-1: resposta antiga concluída após conclusão da segunda leitura não sobrescreve estado atual', () async {
      final completerOld = Completer<ProductDetail>();
      final completerNew = Completer<ProductDetail>();

      fakeRepository.onGetProductByIdentifier = (type, value) {
        if (value == '7890000000011') return completerOld.future;
        if (value == '7890000000022') return completerNew.future;
        throw StateError('Chamada inesperada: $type:$value');
      };

      // 1. Consulta EAN antiga suspensa
      final oldFuture = selectionNotifier.onCodeDetected('7890000000011', format: BarcodeFormat.ean13);
      expect(selectionNotifier.isProcessing, isTrue);

      // 2. resumeScanning durante a consulta
      selectionNotifier.resumeScanning();
      expect(selectionNotifier.isProcessing, isFalse);

      // 3. Nova leitura iniciada
      final newFuture = selectionNotifier.onCodeDetected('7890000000022', format: BarcodeFormat.ean13);
      expect(selectionNotifier.isProcessing, isTrue);
      expect(selectionNotifier.state, isA<ScannerProcessing>());

      // 4. Segunda consulta concluída primeiro
      completerNew.complete(
        const ProductDetail(
          id: 'prod-atual',
          name: 'Café Atual',
          brand: 'Marca Atual',
          category: 'BEBIDAS',
          status: 'ACTIVE',
        ),
      );
      await newFuture;

      expect(selectionNotifier.state, isA<ScannerFound>());
      expect((selectionNotifier.state as ScannerFound).product.id, equals('prod-atual'));
      expect(selectionNotifier.isProcessing, isFalse);

      // 5. Primeira consulta concluída posteriormente
      completerOld.complete(
        const ProductDetail(
          id: 'prod-antigo',
          name: 'Produto Obsoleto',
          brand: 'Marca Antiga',
          category: 'OUTROS',
          status: 'ACTIVE',
        ),
      );
      await oldFuture;

      // 6 & 7. Resposta antiga não altera estado atual nem corrompe isProcessing
      expect(selectionNotifier.state, isA<ScannerFound>());
      expect((selectionNotifier.state as ScannerFound).product.id, equals('prod-atual'));
      expect(selectionNotifier.isProcessing, isFalse);
    });

    test('M-1: dispose durante consulta bem-sucedida em trânsito com Completer nao dispara listeners', () async {
      final customRepo = _FakeProductRepository();
      final customNotifier = ScannerNotifier(productRepository: customRepo);
      final completer = Completer<ProductDetail>();
      customRepo.onGetProductByIdentifier = (_, __) => completer.future;

      var listenerCalls = 0;
      customNotifier.addListener(() => listenerCalls++);

      final future = customNotifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      final countBeforeDispose = listenerCalls;

      customNotifier.dispose();
      completer.complete(
        const ProductDetail(
          id: 'prod-late',
          name: 'Produto Tardio',
          brand: 'Marca',
          category: 'CAT',
          status: 'ACTIVE',
        ),
      );
      await expectLater(future, completes);

      expect(customNotifier.isDisposed, isTrue);
      expect(listenerCalls, equals(countBeforeDispose));
    });

    test('M-1: QR de lugar com placeRepository nulo em selectionMode retorna ScannerError', () async {
      final noPlaceRepoNotifier = ScannerNotifier(
        productRepository: fakeRepository,
        placeRepository: null,
        selectionMode: true,
      );
      addTearDown(noPlaceRepoNotifier.dispose);

      await noPlaceRepoNotifier.onCodeDetected('rewit://places/place-1', format: BarcodeFormat.qrCode);

      expect(noPlaceRepoNotifier.isProcessing, isFalse);
      expect(noPlaceRepoNotifier.state, isA<ScannerError>());
      expect(
        (noPlaceRepoNotifier.state as ScannerError).message,
        contains('locais não configurado'),
      );
    });

    test('M-1: QR de produto com productRepository nulo em selectionMode retorna ScannerError', () async {
      final noProductRepoNotifier = ScannerNotifier(
        productRepository: null,
        placeRepository: fakePlaceRepository,
        selectionMode: true,
      );
      addTearDown(noProductRepoNotifier.dispose);

      await noProductRepoNotifier.onCodeDetected('rewit://products/prod-1', format: BarcodeFormat.qrCode);

      expect(noProductRepoNotifier.isProcessing, isFalse);
      expect(noProductRepoNotifier.state, isA<ScannerError>());
      expect(
        (noProductRepoNotifier.state as ScannerError).message,
        contains('produtos não configurado'),
      );
    });

    test('L-3 + M-1: PlaceDetail com nome vazio ou id vazio é rejeitado com ScannerError', () async {
      fakePlaceRepository.placeToReturn = const PlaceDetail(
        id: 'place-ok',
        name: '   ',
        slug: 'sem-nome',
        category: 'PADARIA',
        addressText: 'Rua A',
        validationRadiusMeters: 100,
        origin: 'MANUAL',
        isVerified: true,
        status: 'ACTIVE',
      );
      await selectionNotifier.onCodeDetected('rewit://places/place-ok', format: BarcodeFormat.qrCode);
      expect(selectionNotifier.state, isA<ScannerError>());
      expect((selectionNotifier.state as ScannerError).message, contains('sem nome válido'));

      selectionNotifier.resumeScanning();
      fakePlaceRepository.placeToReturn = const PlaceDetail(
        id: '   ',
        name: 'Padaria com Nome Válido',
        slug: 'padaria',
        category: 'PADARIA',
        addressText: 'Rua A',
        validationRadiusMeters: 100,
        origin: 'MANUAL',
        isVerified: true,
        status: 'ACTIVE',
      );
      await selectionNotifier.onCodeDetected('rewit://places/place-ok', format: BarcodeFormat.qrCode);
      expect(selectionNotifier.state, isA<ScannerError>());
      expect((selectionNotifier.state as ScannerError).message, contains('sem identificador válido'));
    });

    test('L-3 + M-1: ProductDetail com nome vazio ou id vazio é rejeitado tanto em barcode quanto em getProductById', () async {
      fakeRepository.productToReturn = const ProductDetail(
        id: 'prod-1',
        name: '   ',
        brand: 'Marca',
        category: 'CAT',
        status: 'ACTIVE',
      );
      await selectionNotifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(selectionNotifier.state, isA<ScannerError>());
      expect((selectionNotifier.state as ScannerError).message, contains('sem nome válido'));

      selectionNotifier.resumeScanning();
      fakeRepository.productToReturn = const ProductDetail(
        id: '   ',
        name: 'Produto com Nome',
        brand: 'Marca',
        category: 'CAT',
        status: 'ACTIVE',
      );
      await selectionNotifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(selectionNotifier.state, isA<ScannerError>());
      expect((selectionNotifier.state as ScannerError).message, contains('sem identificador válido'));

      // Também valida quando retornado no modo normal via barcode
      notifier.resumeScanning();
      fakeRepository.productToReturn = const ProductDetail(
        id: '  ',
        name: 'Produto Inválido Modo Normal',
        brand: 'Marca',
        category: 'CAT',
        status: 'ACTIVE',
      );
      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(notifier.state, isA<ScannerError>());

      notifier.resumeScanning();
      fakeRepository.productToReturn = const ProductDetail(
        id: 'prod-valid-id',
        name: '  ',
        brand: 'Marca',
        category: 'CAT',
        status: 'ACTIVE',
      );
      await notifier.onCodeDetected('7891234567890', format: BarcodeFormat.ean13);
      expect(notifier.state, isA<ScannerError>());
    });
  });
}
