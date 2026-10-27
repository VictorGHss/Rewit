import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';

/// Interesses do usuário autenticado e catálogo do questionário inicial. O backend é a fonte de verdade: nada é
/// guardado só no aparelho, e a conta é sempre a do token da sessão.
abstract class InterestRepository {
  /// Perguntas, opções e mínimo para concluir (GET /api/v1/interests/catalog).
  Future<InterestCatalog> getCatalog();

  /// Interesses gravados e situação do questionário (GET /api/v1/me/interests).
  Future<UserInterests> getMyInterests();

  /// Substitui os interesses (PUT /api/v1/me/interests). [completeOnboarding] confirma o questionário; `false` grava
  /// um rascunho. Depois da conclusão, toda gravação exige o mínimo do catálogo.
  Future<UserInterests> saveMyInterests({
    required List<String> codes,
    required bool completeOnboarding,
  });
}
