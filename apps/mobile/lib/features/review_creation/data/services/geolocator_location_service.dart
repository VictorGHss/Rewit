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

      final position = await Geolocator.getCurrentPosition(
        locationSettings: LocationSettings(
          accuracy: LocationAccuracy.high,
          timeLimit: timeout,
        ),
      );

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

  @override
  Future<bool> openAppSettings() async {
    try {
      return await Geolocator.openAppSettings();
    } catch (_) {
      return false;
    }
  }
}
