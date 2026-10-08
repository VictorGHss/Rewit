import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/features/scanner/domain/entities/scanned_code.dart';
import 'package:rewit_mobile/features/scanner/domain/services/barcode_normalizer.dart';

void main() {
  group('BarcodeNormalizer - Barcodes Comerciais', () {
    test('normaliza EAN-13 válido limpando espaços em branco e quebras de linha', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: '  7891234567890 \r\n',
        format: BarcodeFormat.ean13,
      );

      expect(code.category, equals(ScannedCodeCategory.barcode));
      expect(code.normalizedValue, equals('7891234567890'));
      expect(code.barcodeType, equals('EAN'));
      expect(code.isBarcode, isTrue);
    });

    test('normaliza EAN-8 válido', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: '12345670',
        format: BarcodeFormat.ean8,
      );

      expect(code.category, equals(ScannedCodeCategory.barcode));
      expect(code.normalizedValue, equals('12345670'));
      expect(code.barcodeType, equals('EAN'));
      expect(code.isBarcode, isTrue);
    });

    test('normaliza UPC-A (12 dígitos) como UPC', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: '012345678905',
        format: BarcodeFormat.upcA,
      );

      expect(code.category, equals(ScannedCodeCategory.barcode));
      expect(code.normalizedValue, equals('012345678905'));
      expect(code.barcodeType, equals('UPC'));
      expect(code.isBarcode, isTrue);
    });

    test('normaliza código de 14 dígitos como GTIN', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: '17891234567897',
      );

      expect(code.category, equals(ScannedCodeCategory.barcode));
      expect(code.normalizedValue, equals('17891234567897'));
      expect(code.barcodeType, equals('GTIN'));
      expect(code.isBarcode, isTrue);
    });

    test('infere tipo de identificador baseado no tamanho quando format não é fornecido', () {
      final ean13 = BarcodeNormalizer.normalize(rawValue: '7891234567890');
      expect(ean13.barcodeType, equals('EAN'));

      final ean8 = BarcodeNormalizer.normalize(rawValue: '12345678');
      expect(ean8.barcodeType, equals('EAN'));

      final upc = BarcodeNormalizer.normalize(rawValue: '012345678901');
      expect(upc.barcodeType, equals('UPC'));
    });

    test('rejeita barcode com caracteres não numéricos', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: '7891234A67890',
        format: BarcodeFormat.ean13,
      );

      expect(code.category, equals(ScannedCodeCategory.unsupported));
      expect(code.isUnsupported, isTrue);
    });

    test('rejeita barcode numérico com tamanho inválido para padrões GS1', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: '123456789', // 9 dígitos
        format: BarcodeFormat.unknown,
      );

      expect(code.category, equals(ScannedCodeCategory.unsupported));
      expect(code.isUnsupported, isTrue);
    });

    test('classifica formatos não suportados de código de barras', () {
      final code128 = BarcodeNormalizer.normalize(
        rawValue: 'INV-12345',
        format: BarcodeFormat.code128,
      );
      expect(code128.category, equals(ScannedCodeCategory.unsupported));
      expect(code128.unsupportedReason, contains('code128'));

      final pdf417 = BarcodeNormalizer.normalize(
        rawValue: 'PDF417-DATA',
        format: BarcodeFormat.pdf417,
      );
      expect(pdf417.category, equals(ScannedCodeCategory.unsupported));
      expect(pdf417.unsupportedReason, contains('pdf417'));
    });

    test('rejeita entrada vazia ou nula', () {
      final empty = BarcodeNormalizer.normalize(rawValue: '   ');
      expect(empty.category, equals(ScannedCodeCategory.unsupported));
      expect(empty.isUnsupported, isTrue);
    });
  });

  group('BarcodeNormalizer - QR Codes', () {
    test('identifica QR Code interno rewit://places/{id}', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'rewit://places/place-uuid-123',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrInternal));
      expect(code.internalResource, isNotNull);
      expect(code.internalResource!.type, equals(InternalResourceType.place));
      expect(code.internalResource!.id, equals('place-uuid-123'));
    });

    test('identifica QR Code interno rewit://products/{id}', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'rewit://products/prod-uuid-456',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrInternal));
      expect(code.internalResource, isNotNull);
      expect(code.internalResource!.type, equals(InternalResourceType.product));
      expect(code.internalResource!.id, equals('prod-uuid-456'));
    });

    test('identifica QR Code interno rewit://reviews/{id}', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'rewit://reviews/rev-uuid-789',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrInternal));
      expect(code.internalResource, isNotNull);
      expect(code.internalResource!.type, equals(InternalResourceType.review));
      expect(code.internalResource!.id, equals('rev-uuid-789'));
    });

    test('identifica QR Code interno de lookup por identificador', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'rewit://products/identifiers/EAN/7891234567890',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrInternal));
      expect(code.internalResource, isNotNull);
      expect(code.internalResource!.type, equals(InternalResourceType.productLookup));
      expect(code.internalResource!.lookupType, equals('EAN'));
      expect(code.internalResource!.lookupValue, equals('7891234567890'));
    });

    test('identifica QR Code HTTPS de domínio oficial rewit.app', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'https://rewit.app/places/place-abc',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrInternal));
      expect(code.internalResource?.type, equals(InternalResourceType.place));
      expect(code.internalResource?.id, equals('place-abc'));
    });

    test('identifica QR Code HTTPS de subdomínio app.rewit.com', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'https://app.rewit.com/products/prod-def',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrInternal));
      expect(code.internalResource?.type, equals(InternalResourceType.product));
      expect(code.internalResource?.id, equals('prod-def'));
    });

    test('classifica URL externa como qrExternal para proteção do usuário', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'https://malicious-site.com/phishing',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrExternal));
      expect(code.internalResource, isNull);
      expect(code.normalizedValue, equals('https://malicious-site.com/phishing'));
    });

    test('classifica texto arbitrário sem esquema como qrExternal', () {
      final code = BarcodeNormalizer.normalize(
        rawValue: 'WIFI:S:MinhaRede;T:WPA;P:senha123;;',
        format: BarcodeFormat.qrCode,
      );

      expect(code.category, equals(ScannedCodeCategory.qrExternal));
      expect(code.internalResource, isNull);
    });

    test('normaliza a partir de objeto Barcode do plugin mobile_scanner', () {
      const barcode = Barcode(
        rawValue: '7891234567890',
        format: BarcodeFormat.ean13,
      );

      final code = BarcodeNormalizer.normalizeFromBarcode(barcode);
      expect(code.category, equals(ScannedCodeCategory.barcode));
      expect(code.normalizedValue, equals('7891234567890'));
      expect(code.barcodeType, equals('EAN'));
    });
  });
}
