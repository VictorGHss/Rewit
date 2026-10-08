/// Representação estrita e efêmera de localização do dispositivo para check-in no Rewit.
/// Não armazena histórico, timestamp, altitude, rumo, velocidade nem endereço.
class DeviceLocation {
  final double latitude;
  final double longitude;
  final double accuracyMeters;

  const DeviceLocation({
    required this.latitude,
    required this.longitude,
    required this.accuracyMeters,
  });

  /// Indica se a precisão é considerada aproximada (por exemplo, > 100m no Android 12+).
  bool get isApproximate => accuracyMeters > 100.0;
}
