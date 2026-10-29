import '../entities/check_in_entities.dart';

/// Contrato do check-in no app: locais próximos para escolher onde avaliar e o histórico do próprio usuário.
abstract class CheckInRepository {
  /// Locais cadastrados no Rewit próximos de [latitude]/[longitude], do mais próximo ao mais distante.
  Future<List<NearbyPlace>> getNearbyPlaces({required double latitude, required double longitude});

  /// Check-ins verificados do usuário autenticado, do mais recente ao mais antigo.
  Future<MyCheckInsPage> getMyCheckIns({int page = 0, int size = 20});
}
