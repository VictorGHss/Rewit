import '../entities/device_location.dart';

/// Razões de falha possíveis na captura de localização sob demanda.
enum LocationFailureReason {
  serviceDisabled,
  permissionDenied,
  permissionDeniedForever,
  timeout,
  error,
}

/// Resultado da tentativa de captura de localização pontual do dispositivo.
sealed class LocationResult {
  const LocationResult();
}

/// Captura bem-sucedida contendo a localização efêmera do dispositivo.
class LocationSuccess extends LocationResult {
  final DeviceLocation location;

  const LocationSuccess(this.location);
}

/// Falha na captura com motivo estruturado e mensagem segura para o usuário.
class LocationFailure extends LocationResult {
  final LocationFailureReason reason;
  final String message;

  const LocationFailure({
    required this.reason,
    required this.message,
  });
}

/// Contrato de serviço para obtenção de localização única sob demanda (C5.10).
abstract class LocationService {
  /// Captura a localização atual do dispositivo através de uma chamada pontual.
  Future<LocationResult> getCurrentLocation({
    Duration timeout = const Duration(seconds: 10),
  });

  /// Abre as configurações do sistema para ajuste manual de permissões se necessário.
  Future<bool> openAppSettings();
}
