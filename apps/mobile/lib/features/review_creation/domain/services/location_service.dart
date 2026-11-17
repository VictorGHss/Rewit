import '../entities/device_location.dart';

/// Finalidade da captura de localização, separando descoberta rápida de verificação de presença.
enum LocationPurpose {
  /// Descoberta de estabelecimentos próximos: prioriza agilidade e aceita cache recente com precisão média.
  discovery,

  /// Verificação de presença para avaliação/check-in: ignora cache, exige posição atual em alta precisão e timestamp recente.
  presenceVerification,
}

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

/// Estado atual de prontidão do dispositivo para captura de localização, sem solicitar permissão nativa.
sealed class LocationReadiness {
  const LocationReadiness();
}

/// Serviço ativo e permissão concedida para iniciar uma captura de localização.
class LocationReady extends LocationReadiness {
  final bool isPermissionApproximate;

  const LocationReady({this.isPermissionApproximate = false});
}

/// Serviço desativado ou permissão ausente/bloqueada no momento da revalidação.
class LocationNotReady extends LocationReadiness {
  final LocationFailureReason reason;
  final String message;

  const LocationNotReady({
    required this.reason,
    required this.message,
  });
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
  /// Revalida serviço de localização e permissão atual sem abrir prompt nativo de permissão.
  Future<LocationReadiness> checkReadiness({
    LocationPurpose purpose = LocationPurpose.discovery,
  });

  /// Captura a localização atual do dispositivo conforme a finalidade [purpose].
  Future<LocationResult> getCurrentLocation({
    Duration? timeout,
    LocationPurpose purpose = LocationPurpose.discovery,
  });

  /// Abre as configurações do aplicativo para ajuste manual de permissões.
  Future<bool> openAppSettings();

  /// Abre as configurações de localização do sistema operacional (GPS/serviço de localização).
  Future<bool> openLocationSettings();
}
