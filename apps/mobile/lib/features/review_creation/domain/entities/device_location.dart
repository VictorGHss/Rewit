/// Representação estrita e efêmera de localização do dispositivo para check-in no Rewit.
/// Não armazena histórico, timestamp, altitude, rumo, velocidade nem endereço.
class DeviceLocation {
  final double latitude;
  final double longitude;
  final double accuracyMeters;

  /// Limiar de precisão em metros acima do qual a localização é considerada aproximada
  /// (ex.: permissão de localização aproximada do Android 12+).
  static const double approximateAccuracyThresholdMeters = 100.0;

  /// Predicado de domínio para determinar se uma precisão em metros é considerada aproximada.
  static bool isAccuracyApproximate(double? accuracyMeters) =>
      accuracyMeters != null && accuracyMeters > approximateAccuracyThresholdMeters;

  const DeviceLocation({
    required this.latitude,
    required this.longitude,
    required this.accuracyMeters,
  });

  /// Indica se a precisão é considerada aproximada (por exemplo, > 100m no Android 12+).
  bool get isApproximate => isAccuracyApproximate(accuracyMeters);
}
