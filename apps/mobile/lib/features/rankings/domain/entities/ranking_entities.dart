/// Rankings V1: três classificações independentes, sem campeão geral nem nota composta.
enum RankingMetric {
  checkIns(
    title: 'Check-ins verificados',
    scoreLabelSingular: 'check-in verificado',
    scoreLabelPlural: 'check-ins verificados',
  ),
  placesExplored(
    title: 'Locais explorados',
    scoreLabelSingular: 'local distinto explorado',
    scoreLabelPlural: 'locais distintos explorados',
  ),
  helpfulVotes(
    title: 'Votos úteis',
    scoreLabelSingular: 'voto útil recebido',
    scoreLabelPlural: 'votos úteis recebidos',
  );

  const RankingMetric({
    required this.title,
    required this.scoreLabelSingular,
    required this.scoreLabelPlural,
  });

  final String title;
  final String scoreLabelSingular;
  final String scoreLabelPlural;

  String scoreLabel(int score) => '$score ${score == 1 ? scoreLabelSingular : scoreLabelPlural}';
}

enum RankingPeriod {
  week('WEEK', 'Semana'),
  month('MONTH', 'Mês');

  const RankingPeriod(this.apiValue, this.label);

  final String apiValue;
  final String label;

  static RankingPeriod? fromApi(Object? value) {
    for (final period in values) {
      if (period.apiValue == value) return period;
    }
    return null;
  }
}

/// Filtros de um ranking. O estado (UF) é obrigatório; cidade e categoria são opcionais. Sem bairro.
class RankingFilters {
  final RankingPeriod period;
  final String? state;
  final String? city;

  /// Código canônico de `InterestCategory`, vindo do catálogo de interesses do backend.
  final String? category;

  const RankingFilters({this.period = RankingPeriod.week, this.state, this.city, this.category});

  bool get hasState => state != null && state!.isNotEmpty;

  RankingFilters copyWith({
    RankingPeriod? period,
    String? Function()? state,
    String? Function()? city,
    String? Function()? category,
  }) {
    return RankingFilters(
      period: period ?? this.period,
      state: state != null ? state() : this.state,
      city: city != null ? city() : this.city,
      category: category != null ? category() : this.category,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is RankingFilters &&
      other.period == period &&
      other.state == state &&
      other.city == city &&
      other.category == category;

  @override
  int get hashCode => Object.hash(period, state, city, category);
}

/// Perfil público exibido no ranking. Nunca traz e-mail, coordenadas ou dados internos.
class RankingUser {
  final String? id;
  final String? handle;
  final String displayName;
  final String? avatarUrl;

  const RankingUser({this.id, this.handle, required this.displayName, this.avatarUrl});
}

/// Uma posição do ranking. [rankPosition] vem do backend e se repete em empates; não é o índice da lista.
class RankingEntry {
  final int rankPosition;
  final RankingUser user;

  /// Valor inteiro da métrica do ranking consultado, e só dela.
  final int score;
  final DateTime? lastContributedAt;
  final bool isCurrentUser;

  const RankingEntry({
    required this.rankPosition,
    required this.user,
    required this.score,
    this.lastContributedAt,
    this.isCurrentUser = false,
  });
}

/// Página de um ranking, com os metadados do período efetivamente considerado.
class RankingPage {
  final List<RankingEntry> entries;
  final int pageNumber;
  final int totalElements;
  final bool isLast;

  /// Início efetivo da contagem (pode ser posterior ao início do período, quando a funcionalidade começou depois).
  final DateTime? periodStart;

  /// Fim exclusivo do período.
  final DateTime? periodEndExclusive;

  /// O período ainda não cobre o intervalo completo (ex.: primeira semana após a ativação dos rankings).
  final bool partialPeriod;

  const RankingPage({
    required this.entries,
    required this.pageNumber,
    required this.totalElements,
    required this.isLast,
    this.periodStart,
    this.periodEndExclusive,
    this.partialPeriod = false,
  });
}
