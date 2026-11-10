import 'package:flutter/foundation.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import '../../domain/entities/scanned_code.dart';
import '../../domain/entities/scanner_state.dart';
import '../../domain/services/barcode_normalizer.dart';

/// Gerenciador de estado reativo do Scanner MVP (ChangeNotifier) (C5.6).
class ScannerNotifier extends ChangeNotifier {
  final ProductRepository? _productRepository;
  final PlaceRepository? _placeRepository;
  final bool _selectionMode;

  ScannerState _state = const ScannerInitial();
  bool _isProcessing = false;
  String? _lastScannedValue;
  bool _isDisposed = false;
  int _lookupSequence = 0;

  ScannerNotifier({
    ProductRepository? productRepository,
    PlaceRepository? placeRepository,
    bool selectionMode = false,
  })  : _productRepository = productRepository,
        _placeRepository = placeRepository,
        _selectionMode = selectionMode;

  ScannerState get state => _state;
  bool get isProcessing => _isProcessing;
  bool get isDisposed => _isDisposed;
  bool get selectionMode => _selectionMode;

  /// Retorna se o scanner está em modo de captura ativa da câmera.
  bool get isScanning => _state is ScannerScanning || _state is ScannerInitial;

  @override
  void dispose() {
    _lookupSequence++;
    _isDisposed = true;
    super.dispose();
  }

  void _safeNotifyListeners() {
    if (!_isDisposed) {
      notifyListeners();
    }
  }

  /// Inicia o modo de escaneamento ativo da câmera.
  void startScanning() {
    if (_isDisposed) return;
    _lookupSequence++;
    _isProcessing = false;
    _state = const ScannerScanning();
    _safeNotifyListeners();
  }

  /// Retoma o escaneamento ativo limpando a trava do último código lido.
  void resumeScanning() {
    if (_isDisposed) return;
    _lookupSequence++;
    _isProcessing = false;
    _lastScannedValue = null;
    _state = const ScannerScanning();
    _safeNotifyListeners();
  }

  /// Registra que a permissão de câmera foi negada.
  void onPermissionDenied({bool permanentlyDenied = false}) {
    if (_isDisposed) return;
    _lookupSequence++;
    _isProcessing = false;
    _state = ScannerPermissionDenied(permanentlyDenied: permanentlyDenied);
    _safeNotifyListeners();
  }

  /// Processa a detecção de um código garantindo proteção contra leituras duplicadas
  /// e impedindo que novos frames da câmera sobrescrevam silenciosamente o resultado visível.
  Future<void> onCodeDetected(String rawValue, {BarcodeFormat? format}) async {
    if (_isDisposed) return;

    // Proteção contra novas detecções enquanto um resultado ativo estiver sendo exibido
    // ou enquanto uma consulta estiver em processamento. O usuário deve tocar explicitamente
    // em "Escanear novamente" para reativar o modo de leitura.
    if (_isProcessing || !isScanning) return;

    final trimmed = rawValue.trim();
    if (_lastScannedValue == trimmed) return;

    _isProcessing = true;
    _lastScannedValue = trimmed;

    final code = BarcodeNormalizer.normalize(rawValue: rawValue, format: format);

    if (_isDisposed) return;

    // 1. Formato não suportado
    if (code.isUnsupported) {
      _state = ScannerUnsupported(
        scannedCode: code,
        message: code.unsupportedReason ?? 'Formato de código não suportado.',
      );
      _isProcessing = false;
      _safeNotifyListeners();
      return;
    }

    // 2. QR Code externo / não reconhecido (nunca enviar ao backend como produto)
    if (code.isQrExternal) {
      _state = ScannerQrExternal(scannedCode: code);
      _isProcessing = false;
      _safeNotifyListeners();
      return;
    }

    // 3. QR Code interno do Rewit
    if (code.isQrInternal) {
      final resource = code.internalResource!;
      if (resource.type == InternalResourceType.productLookup &&
          resource.lookupType != null &&
          resource.lookupValue != null) {
        // Tratar lookup de produto via QR específico diretamente
        await _performProductLookup(
          code: code,
          type: resource.lookupType!,
          value: resource.lookupValue!,
        );
      } else if (_selectionMode) {
        await _handleInternalQrSelectionMode(code: code, resource: resource);
      } else {
        _state = ScannerQrInternal(scannedCode: code, resource: resource);
        _isProcessing = false;
        _safeNotifyListeners();
      }
      return;
    }

    // 4. Código de barras comercial de produto (EAN, UPC, GTIN)
    if (code.isBarcode) {
      await _performProductLookup(
        code: code,
        type: code.barcodeType!,
        value: code.normalizedValue!,
      );
    }
  }

  Future<void> _handleInternalQrSelectionMode({
    required ScannedCode code,
    required InternalResource resource,
  }) async {
    switch (resource.type) {
      case InternalResourceType.product:
        await _performProductByIdLookup(code: code, productId: resource.id);
      case InternalResourceType.place:
        await _performPlaceByIdLookup(code: code, placeId: resource.id);
      case InternalResourceType.review:
        _state = ScannerInvalidSelectionTarget(
          scannedCode: code,
          resource: resource,
          message:
              'QR Codes de avaliações não podem ser selecionados como alvo. Escaneie um produto, local ou código de barras.',
        );
        _isProcessing = false;
        _safeNotifyListeners();
      case InternalResourceType.productLookup:
        // Fallback defensivo caso lookupType/lookupValue estejam ausentes.
        _state = const ScannerError(
          message: 'QR Code de consulta de produto incompleto.',
        );
        _isProcessing = false;
        _safeNotifyListeners();
    }
  }

  Future<void> _performProductLookup({
    required ScannedCode code,
    required String type,
    required String value,
  }) async {
    if (_isDisposed) return;

    final normalizedType = type.trim().toUpperCase();
    if (!BarcodeNormalizer.supportedIdentifierTypes.contains(normalizedType)) {
      _isProcessing = false;
      _state = ScannerError(
        message: 'Tipo de identificador não suportado pelo catálogo: $type',
      );
      _safeNotifyListeners();
      return;
    }

    if (_productRepository == null) {
      _isProcessing = false;
      _state = const ScannerError(
        message: 'Repositório de produtos não configurado para consulta.',
      );
      _safeNotifyListeners();
      return;
    }

    final trimmedValue = value.trim();
    await _executeLookup(
      code: code,
      genericErrorMessage: 'Ocorreu um erro inesperado ao consultar o produto.',
      runLookup: (isActive) async {
        try {
          final product = await _productRepository.getProductByIdentifier(
            type: normalizedType,
            value: trimmedValue,
          );
          return _buildProductFoundState(product, code);
        } on ApiException catch (e) {
          if (e.statusCode == 404 &&
              _shouldFallbackToIsbn(normalizedType, trimmedValue)) {
            if (!isActive()) rethrow;
            final isbnProduct = await _productRepository.getProductByIdentifier(
              type: 'ISBN',
              value: trimmedValue,
            );
            return _buildProductFoundState(isbnProduct, code);
          }
          rethrow;
        }
      },
    );
  }

  Future<void> _performProductByIdLookup({
    required ScannedCode code,
    required String productId,
  }) async {
    if (_isDisposed) return;

    final trimmedId = productId.trim();
    if (trimmedId.isEmpty) {
      _isProcessing = false;
      _state = const ScannerError(
        message: 'Identificador de produto inválido para consulta.',
      );
      _safeNotifyListeners();
      return;
    }

    if (_productRepository == null) {
      _isProcessing = false;
      _state = const ScannerError(
        message: 'Repositório de produtos não configurado para consulta.',
      );
      _safeNotifyListeners();
      return;
    }

    await _executeLookup(
      code: code,
      genericErrorMessage: 'Ocorreu um erro inesperado ao consultar o produto.',
      runLookup: (_) async {
        final product = await _productRepository.getProductById(trimmedId);
        return _buildProductFoundState(product, code);
      },
    );
  }

  Future<void> _performPlaceByIdLookup({
    required ScannedCode code,
    required String placeId,
  }) async {
    if (_isDisposed) return;

    final trimmedId = placeId.trim();
    if (trimmedId.isEmpty) {
      _isProcessing = false;
      _state = const ScannerError(
        message: 'Identificador de local inválido para consulta.',
      );
      _safeNotifyListeners();
      return;
    }

    if (_placeRepository == null) {
      _isProcessing = false;
      _state = const ScannerError(
        message: 'Repositório de locais não configurado para consulta.',
      );
      _safeNotifyListeners();
      return;
    }

    await _executeLookup(
      code: code,
      isPlace: true,
      genericErrorMessage: 'Ocorreu um erro inesperado ao consultar o local.',
      runLookup: (_) async {
        final place = await _placeRepository.getPlaceById(trimmedId);
        return _buildPlaceFoundState(place, code);
      },
    );
  }

  bool _shouldFallbackToIsbn(String normalizedType, String value) {
    // Códigos ISBN-13 físicos são reportados pela câmera como EAN-13 iniciando em 978 ou 979.
    if (normalizedType != 'EAN') return false;
    if (!RegExp(r'^\d{13}$').hasMatch(value)) return false;
    return value.startsWith('978') || value.startsWith('979');
  }

  ScannerState _buildProductFoundState(
    ProductDetail product,
    ScannedCode code,
  ) {
    if (product.id.trim().isEmpty) {
      return const ScannerError(
        message: 'Produto retornado sem identificador válido no catálogo.',
      );
    }
    if (product.name.trim().isEmpty) {
      return const ScannerError(
        message: 'Produto retornado sem nome válido no catálogo.',
      );
    }
    return ScannerFound(product: product, scannedCode: code);
  }

  ScannerState _buildPlaceFoundState(PlaceDetail place, ScannedCode code) {
    if (place.id.trim().isEmpty) {
      return const ScannerError(
        message: 'Local retornado sem identificador válido no catálogo.',
      );
    }
    if (place.name.trim().isEmpty) {
      return const ScannerError(
        message: 'Local retornado sem nome válido no catálogo.',
      );
    }
    return ScannerPlaceFound(place: place, scannedCode: code);
  }

  Future<void> _executeLookup({
    required ScannedCode code,
    required String genericErrorMessage,
    required Future<ScannerState> Function(bool Function() isActive) runLookup,
    bool isPlace = false,
  }) async {
    final seq = ++_lookupSequence;
    bool isActive() => !_isDisposed && seq == _lookupSequence;

    _state = ScannerProcessing(scannedCode: code);
    _safeNotifyListeners();

    try {
      final nextState = await runLookup(isActive);
      if (!isActive()) return;
      _state = nextState;
    } on ApiException catch (e) {
      if (!isActive()) return;
      _state = _mapApiExceptionToState(e, code: code, isPlace: isPlace);
    } on NetworkException catch (e) {
      if (!isActive()) return;
      _state = ScannerError(message: e.message);
    } catch (_) {
      if (!isActive()) return;
      _state = ScannerError(message: genericErrorMessage);
    } finally {
      if (isActive()) {
        _isProcessing = false;
        _safeNotifyListeners();
      }
    }
  }

  ScannerState _mapApiExceptionToState(
    ApiException e, {
    required ScannedCode code,
    required bool isPlace,
  }) {
    if (e.statusCode == 404) {
      return ScannerNotFound(
        scannedCode: code,
        message: e.detail,
        isPlace: isPlace,
      );
    }
    if (e.statusCode == 429) {
      return ScannerError(
        message: 'Limite de consultas atingido. Aguarde alguns instantes.',
        isRateLimit: true,
        retryAfterSeconds: e.retryAfterSeconds,
        errorCode: e.errorCode,
      );
    }
    if (e.statusCode == 400) {
      return ScannerError(
        message: 'Identificador inválido para consulta no catálogo.',
        errorCode: e.errorCode,
      );
    }
    if (e.statusCode == 401) {
      return const ScannerError(
        message: 'Sessão expirada. Faça login novamente para consultar o catálogo.',
      );
    }
    return ScannerError(
      message: e.detail,
      errorCode: e.errorCode,
    );
  }
}
