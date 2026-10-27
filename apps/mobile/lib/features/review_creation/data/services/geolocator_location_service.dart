import 'dart:async';
import 'package:geolocator/geolocator.dart';
import '../../domain/entities/device_location.dart';
import '../../domain/services/location_service.dart';

/// Implementação de [LocationService] utilizando a biblioteca federada `geolocator` (C5.10).
/// Realiza chamadas pontuais sob demanda sem tracking contínuo ou persistência.
class GeolocatorLocationService implements LocationService {
  final DateTime Function() _clock;

  const GeolocatorLocationService({
    DateTime Function() clock = _defaultClock,
  }) : _clock = clock;

  static DateTime _defaultClock() => DateTime.now();

  /// Idade máxima permitida para a localização em cache do sistema operacional (5 minutos).
  ///
  /// Limite estabelecido para o fluxo de descoberta de estabelecimentos próximos (raio padrão de 10 km):
  /// - Até 5 minutos: o deslocamento pedestre típico (< 400 m) é desprezível em relação ao raio de 10 km,
  ///   permitindo retorno instantâneo em milissegundos sem acionar satélites GNSS ou listeners NMEA síncronos.
  /// - Acima de 5 minutos: o usuário pode ter se deslocado de carro, metrô ou trocado de bairro/cidade.
  ///   O cache é considerado expirado e o serviço recorre à obtenção ativa.
  static const Duration maxCachedLocationAge = Duration(minutes: 5);

  /// Tolerância máxima para divergência de relógio (skew) no futuro (1 minuto).
  ///
  /// Timestamps à frente do horário atual além deste limiar indicam incoerência temporal
  /// (relógio do dispositivo incorreto, anomalia de driver ou registro corrompido).
  static const Duration maxFutureClockSkew = Duration(minutes: 1);

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
      // Rejeita posições ausentes, coordenadas inválidas, caches expirados (> 5 min) ou timestamps incoerentes.
      try {
        final now = _clock();
        final lastKnown = await Geolocator.getLastKnownPosition();
        if (lastKnown != null && _isFreshAndCoherentCache(lastKnown, now)) {
          return LocationSuccess(
            DeviceLocation(
              latitude: lastKnown.latitude,
              longitude: lastKnown.longitude,
              accuracyMeters: lastKnown.accuracy,
            ),
          );
        }
      } catch (_) {
        // Fallback para obtenção ativa se o cache não estiver disponível ou falhar
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

  static bool _isFreshAndCoherentCache(Position position, DateTime now) {
    if (!_isUsablePosition(position)) {
      return false;
    }

    final ts = position.timestamp;

    // Timestamp incoerente no futuro (além da margem de tolerância de relógio do aparelho)
    if (ts.isAfter(now.add(maxFutureClockSkew))) {
      return false;
    }

    // Timestamp incoerente no passado remoto (ex.: epoch zero 1970 ou data anterior ao app)
    if (ts.year < 2020) {
      return false;
    }

    // Posição expirada (idade superior ao limite máximo estipulado de 5 minutos)
    final age = now.difference(ts);
    if (age > maxCachedLocationAge) {
      return false;
    }

    return true;
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
