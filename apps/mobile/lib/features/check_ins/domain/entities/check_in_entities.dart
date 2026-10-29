/// Check-in verificado do próprio usuário (`GET /api/v1/me/check-ins`). Sem coordenadas nem distância.
class MyCheckIn {
  final String id;
  final String reviewId;
  final String placeId;
  final String placeName;
  final String? placeCategory;
  final String? placeCity;
  final String? placeState;
  final DateTime verifiedAt;

  const MyCheckIn({
    required this.id,
    required this.reviewId,
    required this.placeId,
    required this.placeName,
    this.placeCategory,
    this.placeCity,
    this.placeState,
    required this.verifiedAt,
  });
}

/// Página do histórico de check-ins.
class MyCheckInsPage {
  final List<MyCheckIn> items;
  final int pageNumber;
  final int totalElements;
  final bool isLast;

  const MyCheckInsPage({
    required this.items,
    required this.pageNumber,
    required this.totalElements,
    required this.isLast,
  });
}

/// Local cadastrado próximo à posição informada (`GET /api/v1/places/nearby`).
class NearbyPlace {
  final String id;
  final String name;
  final String? category;
  final String? addressText;

  /// Distância até o local, calculada pelo backend; só informativa, quem decide a presença é o backend.
  final double distanceMeters;

  const NearbyPlace({
    required this.id,
    required this.name,
    this.category,
    this.addressText,
    required this.distanceMeters,
  });
}
