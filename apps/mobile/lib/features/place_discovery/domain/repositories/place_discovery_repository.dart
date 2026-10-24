import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import '../entities/external_place_candidate.dart';

/// Descoberta de locais em fonte externa pelo backend. A credencial do provedor fica só no servidor:
/// o app conversa apenas com a API Rewit.
abstract class PlaceDiscoveryRepository {
  /// Busca candidatos externos (`GET /api/v1/places/discovery/search`). Nada é gravado no catálogo.
  Future<List<ExternalPlaceCandidate>> search({
    required String query,
    double? latitude,
    double? longitude,
    double? radiusMeters,
    int limit = 10,
  });

  /// Adota o candidato no catálogo (`POST /api/v1/places/adopt`). A mesma referência externa devolve o local
  /// já existente, sem duplicar.
  Future<PlaceDetail> adopt(ExternalPlaceCandidate candidate);
}
