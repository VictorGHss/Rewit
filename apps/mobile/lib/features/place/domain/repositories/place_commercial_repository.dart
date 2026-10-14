import '../entities/place_commercial_entities.dart';

/// Contrato de repositório para acesso aos dados comerciais de estabelecimentos (F2.1, F2.2, F2.3).
abstract class PlaceCommercialRepository {
  /// Obtém a grade de horários semanais de funcionamento (F2.1).
  Future<PlaceOpeningHours> getOpeningHours(String placeId);

  /// Obtém as comodidades oferecidas pelo estabelecimento (F2.2).
  Future<PlaceAmenities> getAmenities(String placeId);

  /// Obtém a listagem paginada de ofertas oficiais de produtos comercializados pelo local (F2.3).
  Future<PlaceOffersPage> getOffers(
    String placeId, {
    int page = 0,
    int size = 50,
  });
}
