import 'dart:async';
import 'package:geolocator/geolocator.dart';
import '../../domain/entities/device_location.dart';
import '../../domain/services/location_service.dart';

/// Implementação de [LocationService] utilizando a biblioteca federada `geolocator` (C5.10).
/// Realiza chamadas pontuais sob demanda sem tracking contínuo ou persistência.
class GeolocatorLocationService implements LocationService {
  const GeolocatorLocationService();

  @override
  Future<LocationResult> getCurrentLocation({
    Duration timeout = const Duration(seconds: 10),
  }) async {
    try {
      final isServiceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!isServiceEnabled) {
        return const LocationFailure(
          reason: LocationFailureReason.serviceDisabled,
          message: 'O serviço de localização está desativado no aparelho.',
        );
      }

      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          return const LocationFailure(
            reason: LocationFailureReason.permissionDenied,
            message: 'Permissão de localização negada.',
          );
        }
      }

      if (permission == LocationPermission.deniedForever) {
        return const LocationFailure(
          reason: LocationFailureReason.permissionDeniedForever,
          message: 'Permissão de localização permanentemente negada. Ative nas configurações do dispositivo.',
        );
      }

      // 1. Tenta obter a última localização conhecida do cache do sistema operacional.
      // Esta operação é praticamente instantânea (milissegundos), não aciona o hardware de satélites
      // nem listeners NMEA síncronos, prevenindo travamento da UI thread (ANR) no Android.
      try {
        final lastKnown = await Geolocator.getLastKnownPosition();
        if (lastKnown != null && _isUsablePosition(lastKnown)) {
          return LocationSuccess(
            DeviceLocation(
              latitude: lastKnown.latitude,
              longitude: lastKnown.longitude,
              accuracyMeters: lastKnown.accuracy,
            ),
          );
        }
      } catch (_) {
        // Fallback para obtenção ativa se o cache não estiver disponível
      }

      // 2. Se não houver posição em cache, solicita a posição atual com precisão equilibrada (medium)
      // e tempo limite seguro (máximo 5s) para evitar bloqueio e timeout da UI thread.
      final effectiveTimeout = timeout > const Duration(seconds: 5)
          ? const Duration(seconds: 5)
          : timeout;

      final position = await Geolocator.getCurrentPosition(
        locationSettings: LocationSettings(
          accuracy: LocationAccuracy.medium,
          timeLimit: effectiveTimeout,
        ),
      );

      if (!_isUsablePosition(position)) {
        return const LocationFailure(
          reason: LocationFailureReason.error,
          message: 'A localização obtida é inválida.',
        );
      }

      return LocationSuccess(
        DeviceLocation(
          latitude: position.latitude,
          longitude: position.longitude,
          accuracyMeters: position.accuracy,
        ),
      );
    } on TimeoutException {
      return const LocationFailure(
        reason: LocationFailureReason.timeout,
        message: 'Não foi possível obter sua localização agora.',
      );
    } catch (_) {
      return const LocationFailure(
        reason: LocationFailureReason.error,
        message: 'Não foi possível obter sua localização agora.',
      );
    }
  }

  static bool _isUsablePosition(Position position) {
    final lat = position.latitude;
    final lng = position.longitude;
    return lat.isFinite &&
        lng.isFinite &&
        lat >= -90.0 &&
        lat <= 90.0 &&
        lng >= -180.0 &&
        lng <= 180.0 &&
        !(lat == 0.0 && lng == 0.0);
  }

  @override
  Future<bool> openAppSettings() async {
    try {
      return await Geolocator.openAppSettings();
    } catch (_) {
      return false;
    }
  }
}
