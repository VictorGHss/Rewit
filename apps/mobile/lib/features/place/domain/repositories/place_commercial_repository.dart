import '../entities/place_commercial_entities.dart';
import '../entities/place_detail.dart';

/// Contrato de repositório para acesso aos dados comerciais de estabelecimentos (F2.1, F2.2, F2.3, F2.4).
abstract class PlaceCommercialRepository {
  /// Obtém a grade de horários semanais de funcionamento (F2.1).
  Future<PlaceOpeningHours> getOpeningHours(String placeId);

  /// Atualiza ou define a grade semanal de horários de funcionamento (F2.1).
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  });

  /// Lista o catálogo de comodidades controladas pelo sistema (F2.2).
  Future<List<PlaceAmenityItem>> getAmenityOptions();

  /// Obtém as comodidades oferecidas pelo estabelecimento (F2.2).
  Future<PlaceAmenities> getAmenities(String placeId);

  /// Atualiza integralmente as comodidades oferecidas pelo estabelecimento (F2.2).
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
  });

  /// Obtém a listagem paginada de ofertas oficiais de produtos comercializados pelo local (F2.3).
  Future<PlaceOffersPage> getOffers(
    String placeId, {
    int page = 0,
    int size = 50,
  });
  /// Publica ou atualiza os termos comerciais da oferta de um produto no estabelecimento (F2.3).
  Future<PlaceProductOffer> upsertOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
    required String availability,
    double? price,
    String currency = 'BRL',
  });

  /// Retira da vitrine a oferta comercial de um produto no estabelecimento (F2.3).
  Future<void> deleteOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
  });

  /// Obtém o perfil comercial e os contatos públicos do estabelecimento (F2.4).
  Future<PlaceDetail> getBusinessProfile(String placeId);

  /// Atualiza o perfil comercial e os contatos públicos do estabelecimento (F2.4).
  Future<PlaceDetail> updateBusinessProfile({
    required String placeId,
    required String businessAccountId,
    String? description,
    String? phone,
    String? websiteUrl,
    String? instagramUrl,
    String? facebookUrl,
  });
}
