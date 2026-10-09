import '../entities/business_entities.dart';

abstract class BusinessRepository {
  /// Retorna as contas comerciais do usuário autenticado.
  Future<List<BusinessAccount>> getMyBusinessAccounts();

  /// Cria uma nova conta comercial vinculada ao usuário autenticado.
  Future<BusinessAccount> createBusinessAccount({
    required String corporateName,
    required String taxId,
  });

  /// Solicita a reivindicação de um local em nome de uma conta comercial.
  Future<PlaceClaim> requestPlaceClaim({
    required String businessAccountId,
    required String placeId,
    required String evidenceDescription,
  });

  /// Retorna as solicitações de reivindicação de uma conta comercial administrada pelo usuário.
  Future<List<PlaceClaim>> getBusinessPlaceClaims(
    String businessAccountId, {
    int page = 0,
    int size = 20,
    String? status,
  });
}
