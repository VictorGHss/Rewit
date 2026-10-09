import 'package:mobile_scanner/mobile_scanner.dart';
import '../entities/scanned_code.dart';

/// Serviço responsável por analisar, classificar e normalizar códigos lidos pelo scanner (C5.6).
///
/// Implementa regras estritas de segurança:
/// - Preserva dígitos significativos sem inventar zeros ou alterar semântica;
/// - Distingue rigorosamente códigos de barra comerciais de QR Codes;
/// - Valida e classifica QR Codes internos do Rewit de forma sanitizada;
/// - Rejeita formatos não comerciais para evitar chamadas desnecessárias ao backend.
class BarcodeNormalizer {
  static const Set<String> _validRewitHosts = {
    'rewit.app',
    'www.rewit.app',
    'rewit.com',
    'www.rewit.com',
    'api.rewit.com',
  };

  /// Whitelist fechada de tipos de identificador comercial suportados pelo catálogo Rewit.
  static const Set<String> supportedIdentifierTypes = {
    'EAN',
    'UPC',
    'GTIN',
    'ISBN',
  };

  static final RegExp _validIdPattern = RegExp(r'^[a-zA-Z0-9\-_]+$');
  static final RegExp _digitsOnlyPattern = RegExp(r'^\d+$');

  /// Atalho de conveniência para normalizar diretamente um [Barcode] da biblioteca mobile_scanner.
  static ScannedCode normalizeFromBarcode(Barcode barcode) {
    return normalize(
      rawValue: barcode.rawValue ?? '',
      format: barcode.format,
    );
  }

  /// Normaliza e categoriza um código bruto capturado pelo scanner.
  static ScannedCode normalize({
    required String rawValue,
    BarcodeFormat? format,
  }) {
    final trimmed = rawValue.trim();

    if (trimmed.isEmpty) {
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.unsupported,
        unsupportedReason: 'Código vazio ou ilegível.',
      );
    }

    // 1. Detecção e classificação de QR Code
    final isQr = format == BarcodeFormat.qrCode ||
        _looksLikeQrContent(trimmed, format);

    if (isQr) {
      return _classifyQrCode(rawValue, trimmed, format);
    }

    // 2. Verificação de formatos de código de barras expressamente não suportados para produtos
    if (format != null && _isUnsupportedBarcodeFormat(format)) {
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.unsupported,
        unsupportedReason: 'Formato ${format.name} não suportado pelo catálogo.',
      );
    }

    // 3. Validação de código de barras comercial (deve ser puramente numérico)
    if (!_digitsOnlyPattern.hasMatch(trimmed)) {
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.unsupported,
        unsupportedReason: 'Código de barras de produto deve conter apenas dígitos numéricos.',
      );
    }

    // 4. Mapeamento por comprimento do código comercial (EAN-13, EAN-8, UPC-A, GTIN-14)
    final length = trimmed.length;

    if (format == BarcodeFormat.itf14 && length != 14) {
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.unsupported,
        unsupportedReason: 'Código ITF-14 deve conter exatamente 14 dígitos numéricos.',
      );
    }

    if (length == 13) {
      // EAN-13 canônico
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.barcode,
        barcodeType: 'EAN',
        normalizedValue: trimmed,
      );
    } else if (length == 8) {
      // EAN-8
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.barcode,
        barcodeType: 'EAN',
        normalizedValue: trimmed,
      );
    } else if (length == 12) {
      // UPC-A (12 dígitos numéricos com preservação de zero à esquerda)
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.barcode,
        barcodeType: 'UPC',
        normalizedValue: trimmed,
      );
    } else if (length == 14) {
      // GTIN-14
      return ScannedCode(
        rawValue: rawValue,
        format: format,
        category: ScannedCodeCategory.barcode,
        barcodeType: 'GTIN',
        normalizedValue: trimmed,
      );
    }

    // Comprimentos não conformes com os padrões de catálogo do Rewit
    return ScannedCode(
      rawValue: rawValue,
      format: format,
      category: ScannedCodeCategory.unsupported,
      unsupportedReason: 'Comprimento de código de barras não suportado ($length dígitos).',
    );
  }

  static bool _looksLikeQrContent(String content, BarcodeFormat? format) {
    if (format == null) {
      return content.startsWith('http://') ||
          content.startsWith('https://') ||
          content.startsWith('rewit://');
    }
    return false;
  }

  static bool _isUnsupportedBarcodeFormat(BarcodeFormat format) {
    return format == BarcodeFormat.code128 ||
        format == BarcodeFormat.code39 ||
        format == BarcodeFormat.code93 ||
        format == BarcodeFormat.codabar ||
        format == BarcodeFormat.dataMatrix ||
        format == BarcodeFormat.pdf417 ||
        format == BarcodeFormat.aztec ||
        format == BarcodeFormat.itf2of5;
  }

  static bool _isRewitHost(String host) {
    final lower = host.toLowerCase();
    return _validRewitHosts.contains(lower) ||
        lower.endsWith('.rewit.app') ||
        lower.endsWith('.rewit.com');
  }

  static ScannedCode _classifyQrCode(
    String rawValue,
    String content,
    BarcodeFormat? format,
  ) {
    final uri = Uri.tryParse(content);

    if (uri != null) {
      // Rejeita explicitamente tentativas de path traversal em URLs do Rewit
      if (content.contains('..') || content.toLowerCase().contains('%2e')) {
        return ScannedCode(
          rawValue: rawValue,
          format: format,
          category: ScannedCodeCategory.qrExternal,
          normalizedValue: content,
        );
      }

      final isRewitScheme = uri.scheme.toLowerCase() == 'rewit';
      final isRewitHost = uri.hasAuthority && _isRewitHost(uri.host);

      if (isRewitScheme || isRewitHost) {
        final resource = _parseInternalResource(uri);
        if (resource != null) {
          return ScannedCode(
            rawValue: rawValue,
            format: format,
            category: ScannedCodeCategory.qrInternal,
            internalResource: resource,
            normalizedValue: content,
          );
        }
      }
    }

    // QR Code externo ou de conteúdo desconhecido
    return ScannedCode(
      rawValue: rawValue,
      format: format,
      category: ScannedCodeCategory.qrExternal,
      normalizedValue: content,
    );
  }

  static InternalResource? _parseInternalResource(Uri uri) {
    final List<String> segments;
    if (uri.scheme.toLowerCase() == 'rewit') {
      segments = [
        if (uri.host.isNotEmpty) uri.host,
        ...uri.pathSegments,
      ];
    } else {
      segments = uri.pathSegments;
    }

    // Caso 1: rewit://places/{id} ou https://rewit.app/places/{id}
    if (segments.length == 2) {
      final section = segments[0].toLowerCase();
      final id = segments[1];

      if (!_validIdPattern.hasMatch(id)) return null;

      if (section == 'places' || section == 'place') {
        return InternalResource(type: InternalResourceType.place, id: id);
      }
      if (section == 'products' || section == 'product') {
        return InternalResource(type: InternalResourceType.product, id: id);
      }
      if (section == 'reviews' || section == 'review') {
        return InternalResource(type: InternalResourceType.review, id: id);
      }
    }

    // Caso 2: lookup de produto estruturado: rewit://products/identifiers/{type}/{value}
    if (segments.length == 4) {
      final section = segments[0].toLowerCase();
      final sub = segments[1].toLowerCase();
      final type = segments[2].toUpperCase();
      final value = segments[3];

      if ((section == 'products' || section == 'product') &&
          sub == 'identifiers' &&
          supportedIdentifierTypes.contains(type) &&
          _validIdPattern.hasMatch(value)) {
        return InternalResource(
          type: InternalResourceType.productLookup,
          id: value,
          lookupType: type,
          lookupValue: value,
        );
      }
    }

    return null;
  }
}
