/// Representação estrita e efêmera de localização do dispositivo para check-in no Rewit.
/// Não armazena histórico, timestamp, altitude, rumo, velocidade nem endereço.
class DeviceLocation {
  final double latitude;
  final double longitude;
  final double accuracyMeters;

  /// Indica se o sistema operacional concedeu apenas permissão de localização aproximada
  /// (ex.: apenas `ACCESS_COARSE_LOCATION` no Android 12+).
  final bool isPermissionApproximate;

  /// Limiar de precisão em metros acima do qual a localização é considerada aproximada
  /// (ex.: permissão de localização aproximada do Android 12+ ou leitura grosseira).
  static const double approximateAccuracyThresholdMeters = 100.0;

  /// Predicado de domínio para determinar se uma precisão em metros é considerada aproximada.
  static bool isAccuracyApproximate(double? accuracyMeters) =>
      accuracyMeters != null && accuracyMeters > approximateAccuracyThresholdMeters;

  /// Valida se latitude e longitude são finitas, estão nos limites geográficos e não são (0, 0).
  static bool areCoordinatesValid(double latitude, double longitude) =>
      latitude.isFinite &&
      longitude.isFinite &&
      latitude >= -90.0 &&
      latitude <= 90.0 &&
      longitude >= -180.0 &&
      longitude <= 180.0 &&
      !(latitude == 0.0 && longitude == 0.0);

  const DeviceLocation({
    required this.latitude,
    required this.longitude,
    required this.accuracyMeters,
    this.isPermissionApproximate = false,
  });

  /// Indica se as coordenadas e a acurácia informada são numericamente utilizáveis.
  bool get hasValidCoordinates =>
      areCoordinatesValid(latitude, longitude) &&
      accuracyMeters.isFinite &&
      accuracyMeters >= 0.0;

  /// Indica se a posição é aproximada (permissão reduzida ou precisão > 100 m).
  bool get isApproximate =>
      isPermissionApproximate || isAccuracyApproximate(accuracyMeters);
}
