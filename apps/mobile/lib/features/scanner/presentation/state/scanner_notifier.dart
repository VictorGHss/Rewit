import 'package:flutter/foundation.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import '../../domain/entities/scanned_code.dart';
import '../../domain/entities/scanner_state.dart';
import '../../domain/services/barcode_normalizer.dart';

/// Gerenciador de estado reativo do Scanner MVP (ChangeNotifier) (C5.6).
class ScannerNotifier extends ChangeNotifier {
  final ProductRepository? _productRepository;

  ScannerState _state = const ScannerInitial();
  bool _isProcessing = false;
  String? _lastScannedValue;
  bool _isDisposed = false;

  ScannerNotifier({ProductRepository? productRepository})
      : _productRepository = productRepository;

  ScannerState get state => _state;
  bool get isProcessing => _isProcessing;
  bool get isDisposed => _isDisposed;

  /// Retorna se o scanner está em modo de captura ativa da câmera.
  bool get isScanning => _state is ScannerScanning || _state is ScannerInitial;

  @override
  void dispose() {
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
    _isProcessing = false;
    _state = const ScannerScanning();
    _safeNotifyListeners();
  }

  /// Retoma o escaneamento ativo limpando a trava do último código lido.
  void resumeScanning() {
    if (_isDisposed) return;
    _isProcessing = false;
    _lastScannedValue = null;
    _state = const ScannerScanning();
    _safeNotifyListeners();
  }

  /// Registra que a permissão de câmera foi negada.
  void onPermissionDenied({bool permanentlyDenied = false}) {
    if (_isDisposed) return;
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

    _state = ScannerProcessing(scannedCode: code);
    _safeNotifyListeners();

    if (_productRepository == null) {
      _isProcessing = false;
      _state = const ScannerError(
        message: 'Repositório de produtos não configurado para consulta.',
      );
      _safeNotifyListeners();
      return;
    }

    try {
      final product = await _productRepository.getProductByIdentifier(
        type: normalizedType,
        value: value.trim(),
      );

      if (_isDisposed) return;
      _state = ScannerFound(product: product, scannedCode: code);
    } on ApiException catch (e) {
      if (_isDisposed) return;
      if (e.statusCode == 404) {
        _state = ScannerNotFound(
          scannedCode: code,
          message: e.detail,
        );
      } else if (e.statusCode == 429) {
        _state = ScannerError(
          message: 'Limite de consultas atingido. Aguarde alguns instantes.',
          isRateLimit: true,
          retryAfterSeconds: e.retryAfterSeconds,
          errorCode: e.errorCode,
        );
      } else if (e.statusCode == 400) {
        _state = ScannerError(
          message: 'Identificador inválido para consulta no catálogo.',
          errorCode: e.errorCode,
        );
      } else if (e.statusCode == 401) {
        _state = const ScannerError(
          message: 'Sessão expirada. Faça login novamente para consultar o catálogo.',
        );
      } else {
        _state = ScannerError(
          message: e.detail,
          errorCode: e.errorCode,
        );
      }
    } on NetworkException catch (e) {
      if (_isDisposed) return;
      _state = ScannerError(message: e.message);
    } catch (_) {
      if (_isDisposed) return;
      _state = const ScannerError(
        message: 'Ocorreu um erro inesperado ao consultar o produto.',
      );
    } finally {
      _isProcessing = false;
      if (!_isDisposed) {
        _safeNotifyListeners();
      }
    }
  }
}
