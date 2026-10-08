import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'scanned_code.dart';

/// Estados imutáveis e explícitos da máquina de estados do Scanner (C5.6).
sealed class ScannerState {
  const ScannerState();
}

/// Estado inicial enquanto o subsistema e câmera são preparados.
final class ScannerInitial extends ScannerState {
  const ScannerInitial();
}

/// Estado ativo de escaneamento pela câmera.
final class ScannerScanning extends ScannerState {
  const ScannerScanning();
}

/// Estado de processamento (normalização ou requisição de consulta em andamento).
final class ScannerProcessing extends ScannerState {
  final ScannedCode scannedCode;

  const ScannerProcessing({required this.scannedCode});
}

/// Produto comercial correspondente localizado no catálogo do Rewit.
final class ScannerFound extends ScannerState {
  final ProductDetail product;
  final ScannedCode scannedCode;

  const ScannerFound({
    required this.product,
    required this.scannedCode,
  });
}

/// O código foi lido com sucesso, mas o produto não existe no catálogo (404 PRODUCT_NOT_FOUND).
final class ScannerNotFound extends ScannerState {
  final ScannedCode scannedCode;
  final String message;

  const ScannerNotFound({
    required this.scannedCode,
    required this.message,
  });
}

/// Formato de código de barras não suportado ou ruído incompatível.
final class ScannerUnsupported extends ScannerState {
  final ScannedCode scannedCode;
  final String message;

  const ScannerUnsupported({
    required this.scannedCode,
    required this.message,
  });
}

/// QR Code externo ou com conteúdo não reconhecido pelo Rewit.
final class ScannerQrExternal extends ScannerState {
  final ScannedCode scannedCode;

  const ScannerQrExternal({required this.scannedCode});
}

/// QR Code interno do Rewit reconhecido apontando para recurso seguro.
final class ScannerQrInternal extends ScannerState {
  final ScannedCode scannedCode;
  final InternalResource resource;

  const ScannerQrInternal({
    required this.scannedCode,
    required this.resource,
  });
}

/// Permissão de câmera negada pelo usuário ou pelo sistema.
final class ScannerPermissionDenied extends ScannerState {
  final bool permanentlyDenied;

  const ScannerPermissionDenied({this.permanentlyDenied = false});
}

/// Erro de rede, rate limit (429), requisição inválida (400) ou falha de infraestrutura.
final class ScannerError extends ScannerState {
  final String message;
  final bool isRateLimit;
  final int? retryAfterSeconds;
  final String? errorCode;

  const ScannerError({
    required this.message,
    this.isRateLimit = false,
    this.retryAfterSeconds,
    this.errorCode,
  });
}
