import 'dart:async';
import 'package:geolocator/geolocator.dart';
import '../../domain/entities/device_location.dart';
import '../../domain/services/location_service.dart';

/// Política explícita de captura de localização por finalidade.
class LocationCapturePolicy {
  final LocationPurpose purpose;
  final bool allowCachedPosition;
  final Duration maxLocationAge;
  final LocationAccuracy accuracy;
  final Duration defaultTimeout;
  final Duration minTimeout;
  final Duration maxTimeout;
  final bool retryOnceOnApproximateAccuracy;
  final Duration retryTimeout;

  const LocationCapturePolicy({
    required this.purpose,
    required this.allowCachedPosition,
    required this.maxLocationAge,
    required this.accuracy,
    required this.defaultTimeout,
    required this.minTimeout,
    required this.maxTimeout,
    required this.retryOnceOnApproximateAccuracy,
    required this.retryTimeout,
  });

  /// Política para descoberta externa de estabelecimentos (raio de 10 km):
  /// prioriza velocidade, aceita cache de até 5 minutos e precisão média com teto de 5s.
  static const discovery = LocationCapturePolicy(
    purpose: LocationPurpose.discovery,
    allowCachedPosition: true,
    maxLocationAge: Duration(minutes: 5),
    accuracy: LocationAccuracy.medium,
    defaultTimeout: Duration(seconds: 5),
    minTimeout: Duration(milliseconds: 1),
    maxTimeout: Duration(seconds: 5),
    retryOnceOnApproximateAccuracy: false,
    retryTimeout: Duration.zero,
  );

  /// Política para verificação de presença / check-in:
  /// ignora cache antigo, exige alta precisão, timeout de 10s a 15s para GPS frio,
  /// timestamp recente (<= 1 min) e no máximo uma tentativa adicional se acurácia > 100 m.
  static const presenceVerification = LocationCapturePolicy(
    purpose: LocationPurpose.presenceVerification,
    allowCachedPosition: false,
    maxLocationAge: Duration(minutes: 1),
    accuracy: LocationAccuracy.high,
    defaultTimeout: Duration(seconds: 12),
    minTimeout: Duration(seconds: 10),
    maxTimeout: Duration(seconds: 15),
    retryOnceOnApproximateAccuracy: true,
    retryTimeout: Duration(seconds: 5),
  );

  factory LocationCapturePolicy.forPurpose(LocationPurpose purpose) => switch (purpose) {
        LocationPurpose.discovery => discovery,
        LocationPurpose.presenceVerification => presenceVerification,
      };

  Duration resolveTimeout(Duration? requested) {
    final candidate = requested ?? defaultTimeout;
    if (candidate < minTimeout) return minTimeout;
    if (candidate > maxTimeout) return maxTimeout;
    return candidate;
  }
}

/// Implementação de [LocationService] utilizando a biblioteca federada `geolocator` (C5.10).
/// Realiza chamadas pontuais sob demanda com política explícita por finalidade,
/// sem tracking contínuo em segundo plano nem bloqueio de coordenadas simuladas.
class GeolocatorLocationService implements LocationService {
  final DateTime Function() _clock;

  const GeolocatorLocationService({
    DateTime Function() clock = _defaultClock,
  }) : _clock = clock;

  static DateTime _defaultClock() => DateTime.now();

  /// Idade máxima permitida para a localização em cache na descoberta externa (5 minutos).
  static const Duration maxCachedLocationAge = Duration(minutes: 5);

  /// Idade máxima permitida para uma leitura de verificação de presença / check-in (1 minuto).
  static const Duration maxPresenceLocationAge = Duration(minutes: 1);

  /// Tolerância máxima para divergência de relógio (skew) no futuro (1 minuto).
  static const Duration maxFutureClockSkew = Duration(minutes: 1);

  @override
  Future<LocationResult> getCurrentLocation({
    Duration? timeout,
    LocationPurpose purpose = LocationPurpose.discovery,
  }) async {
    final policy = LocationCapturePolicy.forPurpose(purpose);
    final effectiveTimeout = policy.resolveTimeout(timeout);

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
          message:
              'Permissão de localização permanentemente negada. Ative nas configurações do dispositivo.',
        );
      }

      final permissionReduced = policy.purpose == LocationPurpose.presenceVerification
          ? await _isPermissionReduced()
          : false;

      // 1. Apenas a descoberta externa aproveita a última localização em cache do SO.
      // Para verificação de presença / check-in o cache é sempre ignorado.
      if (policy.allowCachedPosition) {
        try {
          final now = _clock();
          final lastKnown = await Geolocator.getLastKnownPosition();
          if (lastKnown != null &&
              _isFreshAndCoherentPosition(
                lastKnown,
                now,
                maxAge: policy.maxLocationAge,
              )) {
            return LocationSuccess(
              DeviceLocation(
                latitude: lastKnown.latitude,
                longitude: lastKnown.longitude,
                accuracyMeters: lastKnown.accuracy,
                isPermissionApproximate: permissionReduced,
              ),
            );
          }
        } catch (_) {
          // Fallback para obtenção ativa se o cache não estiver disponível ou falhar
        }
      }

      // 2. Obtenção ativa com precisão e timeout definidos pela política da finalidade.
      final firstPosition = await Geolocator.getCurrentPosition(
        locationSettings: LocationSettings(
          accuracy: policy.accuracy,
          timeLimit: effectiveTimeout,
        ),
      );

      if (!_isUsablePosition(firstPosition)) {
        return const LocationFailure(
          reason: LocationFailureReason.error,
          message: 'A localização obtida é inválida.',
        );
      }

      final captureNow = _clock();
      if (!_isCoherentAndFreshTimestamp(
        firstPosition.timestamp,
        captureNow,
        maxAge: policy.maxLocationAge,
      )) {
        return const LocationFailure(
          reason: LocationFailureReason.error,
          message: 'A localização obtida está desatualizada ou inválida. Tente novamente.',
        );
      }

      var chosenPosition = firstPosition;

      // 3. Em verificação de presença com permissão exata, se a primeira leitura vier
      // acima do limite de 100 m (ex.: fix inicial por rede/Wi-Fi antes do lock GNSS),
      // faz no máximo UMA tentativa curta adicional para tentar refinar sem entrar em loop.
      if (policy.retryOnceOnApproximateAccuracy &&
          !permissionReduced &&
          firstPosition.accuracy > DeviceLocation.approximateAccuracyThresholdMeters) {
        chosenPosition = await _trySinglePrecisionRefinement(
          initialPosition: firstPosition,
          policy: policy,
        );
      }

      return LocationSuccess(
        DeviceLocation(
          latitude: chosenPosition.latitude,
          longitude: chosenPosition.longitude,
          accuracyMeters: chosenPosition.accuracy,
          isPermissionApproximate: permissionReduced,
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

  Future<Position> _trySinglePrecisionRefinement({
    required Position initialPosition,
    required LocationCapturePolicy policy,
  }) async {
    try {
      final refined = await Geolocator.getCurrentPosition(
        locationSettings: LocationSettings(
          accuracy: policy.accuracy,
          timeLimit: policy.retryTimeout,
        ),
      );
      final retryNow = _clock();
      if (_isFreshAndCoherentPosition(
            refined,
            retryNow,
            maxAge: policy.maxLocationAge,
          ) &&
          refined.accuracy < initialPosition.accuracy) {
        return refined;
      }
    } catch (_) {
      // Falha ou timeout na tentativa extra preserva a leitura válida original.
    }
    return initialPosition;
  }

  Future<bool> _isPermissionReduced() async {
    try {
      final status = await Geolocator.getLocationAccuracy();
      return status == LocationAccuracyStatus.reduced;
    } catch (_) {
      return false;
    }
  }

  static bool _isFreshAndCoherentPosition(
    Position position,
    DateTime now, {
    required Duration maxAge,
  }) {
    if (!_isUsablePosition(position)) {
      return false;
    }
    return _isCoherentAndFreshTimestamp(
      position.timestamp,
      now,
      maxAge: maxAge,
    );
  }

  static bool _isCoherentAndFreshTimestamp(
    DateTime timestamp,
    DateTime now, {
    required Duration maxAge,
  }) {
    final tsUtc = timestamp.toUtc();
    final nowUtc = now.toUtc();

    // Timestamp incoerente no futuro (além da margem de tolerância de relógio do aparelho)
    if (tsUtc.isAfter(nowUtc.add(maxFutureClockSkew))) {
      return false;
    }

    // Timestamp incoerente no passado remoto (ex.: epoch zero 1970 ou data anterior ao app)
    if (tsUtc.year < 2020) {
      return false;
    }

    // Posição expirada para a política ativa
    final age = nowUtc.difference(tsUtc);
    if (age > maxAge) {
      return false;
    }

    return true;
  }

  static bool _isUsablePosition(Position position) {
    return DeviceLocation.areCoordinatesValid(position.latitude, position.longitude) &&
        position.accuracy.isFinite &&
        position.accuracy >= 0.0;
  }

  @override
  Future<bool> openAppSettings() async {
    try {
      return await Geolocator.openAppSettings();
    } catch (_) {
      return false;
    }
  }

  @override
  Future<bool> openLocationSettings() async {
    try {
      return await Geolocator.openLocationSettings();
    } catch (_) {
      return false;
    }
  }
}
