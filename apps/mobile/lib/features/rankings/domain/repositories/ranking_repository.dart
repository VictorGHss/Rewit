import '../entities/ranking_entities.dart';

/// Rankings da Região e a preferência de participação. A conta é sempre a da sessão: nenhum id de usuário é enviado.
abstract class RankingRepository {
  /// Uma página de [metric] com os [filters] informados; [filters] precisa ter o estado (UF).
  Future<RankingPage> getRanking(RankingMetric metric, RankingFilters filters, {int page = 0, int size = 20});

  /// Se o usuário autenticado aparece nos rankings (`GET /api/v1/me/ranking-preferences`).
  Future<bool> getParticipation();

  /// Grava a participação (`PUT /api/v1/me/ranking-preferences`) e devolve o valor confirmado pelo backend.
  Future<bool> updateParticipation(bool showInRankings);
}
