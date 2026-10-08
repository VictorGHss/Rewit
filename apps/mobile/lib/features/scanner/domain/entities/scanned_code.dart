import 'package:mobile_scanner/mobile_scanner.dart';

/// Categoria do código identificado pelo leitor.
enum ScannedCodeCategory {
  /// Código de barras comercial de produto (EAN-13, EAN-8, UPC-A, GTIN-14).
  barcode,

  /// QR Code interno do ecossistema Rewit com recurso reconhecido e seguro.
  qrInternal,

  /// QR Code externo ou de conteúdo desconhecido (não deve ser executado automaticamente).
  qrExternal,

  /// Formato não suportado ou ruído ilegível.
  unsupported,
}

/// Tipos de recursos internos do Rewit que podem ser apontados por um QR Code.
enum InternalResourceType {
  place,
  product,
  productLookup,
  review,
}

/// Representação de um recurso interno validado do Rewit.
class InternalResource {
  final InternalResourceType type;
  final String id;
  final String? lookupType;
  final String? lookupValue;

  const InternalResource({
    required this.type,
    required this.id,
    this.lookupType,
    this.lookupValue,
  });

  @override
  String toString() => 'InternalResource(type: $type, id: $id, lookupType: $lookupType, lookupValue: $lookupValue)';
}

/// Entidade de domínio para representar o resultado normalizado de um código escaneado (C5.6).
class ScannedCode {
  final String rawValue;
  final BarcodeFormat? format;
  final ScannedCodeCategory category;
  final String? barcodeType; // 'EAN', 'UPC', 'GTIN'
  final String? normalizedValue;
  final InternalResource? internalResource;
  final String? unsupportedReason;

  const ScannedCode({
    required this.rawValue,
    this.format,
    required this.category,
    this.barcodeType,
    this.normalizedValue,
    this.internalResource,
    this.unsupportedReason,
  });

  bool get isBarcode => category == ScannedCodeCategory.barcode;
  bool get isQrInternal => category == ScannedCodeCategory.qrInternal;
  bool get isQrExternal => category == ScannedCodeCategory.qrExternal;
  bool get isUnsupported => category == ScannedCodeCategory.unsupported;

  @override
  String toString() {
    return 'ScannedCode(category: $category, barcodeType: $barcodeType, value: $normalizedValue, reason: $unsupportedReason)';
  }
}
