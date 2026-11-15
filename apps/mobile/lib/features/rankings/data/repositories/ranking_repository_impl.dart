import 'dart:convert';

import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';

import '../../domain/entities/ranking_entities.dart';
import '../../domain/repositories/ranking_repository.dart';

/// Implementação de [RankingRepository] sobre a API REST do Rewit.
///
/// O essencial do contrato é estrito: sem `content`, `rankPosition`, `score` inteiros ou `user.displayName`, a resposta
/// é inválida e vira [FormatException]. O mesmo vale para `metadata`, que o backend sempre devolve com
/// `effectiveStart` (início efetivo), `periodEnd` (fim exclusivo), ambos em ISO-8601, e `isPartialPeriod`.
class RankingRepositoryImpl implements RankingRepository {
  final RewitHttpClient httpClient;

  const RankingRepositoryImpl({required this.httpClient});

  @override
  Future<RankingPage> getRanking(RankingMetric metric, RankingFilters filters, {int page = 0, int size = 20}) async {
    if (!filters.hasState) {
      throw ArgumentError('O estado (UF) é obrigatório para consultar rankings.');
    }
    final city = filters.city?.trim();
    final path = switch (metric) {
      RankingMetric.checkIns => ApiEndpoints.rankingCheckIns,
      RankingMetric.placesExplored => ApiEndpoints.rankingPlacesExplored,
      RankingMetric.helpfulVotes => ApiEndpoints.rankingHelpfulVotes,
    };
    final response = await httpClient.get(
      path,
      queryParameters: {
        'period': filters.period.apiValue,
        'state': filters.state,
        if (city != null && city.isNotEmpty) 'city': city,
        if (filters.category != null) 'category': filters.category,
        'page': page,
        'size': size,
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao carregar o ranking.');
    }
    return parsePage(decoded, requestedPage: page);
  }

  @override
  Future<bool> getParticipation() async {
    final response = await httpClient.get(ApiEndpoints.myRankingPreferences);
    return _parsePreference(jsonDecode(response.body));
  }

  @override
  Future<bool> updateParticipation(bool showInRankings) async {
    final response = await httpClient.put(
      ApiEndpoints.myRankingPreferences,
      body: {'showInRankings': showInRankings},
    );
    return _parsePreference(jsonDecode(response.body));
  }

  static bool _parsePreference(Object? decoded) {
    final value = decoded is Map<String, dynamic> ? decoded['showInRankings'] : null;
    if (value is! bool) {
      throw const FormatException('Resposta inválida do servidor sobre a participação nos rankings.');
    }
    return value;
  }

  static RankingPage parsePage(Map<String, dynamic> json, {required int requestedPage}) {
    final content = json['content'];
    if (content is! List) {
      throw const FormatException('Resposta inválida do servidor ao carregar o ranking.');
    }
    final metadata = json['metadata'];
    if (metadata is! Map<String, dynamic>) {
      throw const FormatException('Resposta do ranking sem os metadados do período.');
    }
    final effectiveStart = _requiredInstant(metadata['effectiveStart']);
    final periodEnd = _requiredInstant(metadata['periodEnd']);
    final isPartialPeriod = metadata['isPartialPeriod'];
    if (isPartialPeriod is! bool) {
      throw const FormatException('Metadados do período inválidos na resposta do ranking.');
    }
    // Janela inativa (antes da ativação) vem com início efetivo igual ao fim; o início nunca passa do fim
    if (effectiveStart.isAfter(periodEnd)) {
      throw const FormatException('Metadados do período inválidos na resposta do ranking.');
    }
    return RankingPage(
      entries: [for (final item in content) _entry(item)],
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? requestedPage,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? content.length,
      isLast: json['isLast'] as bool? ?? true,
      periodStart: effectiveStart,
      periodEndExclusive: periodEnd,
      partialPeriod: isPartialPeriod,
    );
  }

  /// Data obrigatória dos metadados: ausente ou fora de ISO-8601 é resposta inválida, nunca um valor padrão.
  static DateTime _requiredInstant(Object? value) {
    final parsed = value is String ? DateTime.tryParse(value) : null;
    if (parsed == null) {
      throw const FormatException('Metadados do período inválidos na resposta do ranking.');
    }
    return parsed;
  }

  static RankingEntry _entry(Object? item) {
    if (item is! Map<String, dynamic>) {
      throw const FormatException('Posição inválida na resposta do ranking.');
    }
    final position = item['rankPosition'];
    final score = item['score'];
    final user = item['user'];
    final displayName = user is Map<String, dynamic> ? user['displayName'] : null;
    if (position is! int || position < 1 || score is! int || score < 0 || displayName is! String) {
      throw const FormatException('Posição inválida na resposta do ranking.');
    }
    final userJson = user as Map<String, dynamic>;
    return RankingEntry(
      rankPosition: position,
      user: RankingUser(
        id: userJson['id'] as String?,
        handle: userJson['handle'] as String?,
        displayName: displayName,
        avatarUrl: userJson['avatarUrl'] as String?,
      ),
      score: score,
      lastContributedAt: _date(item['lastContributedAt']),
      isCurrentUser: item['isCurrentUser'] == true,
    );
  }

  static DateTime? _date(Object? value) => value is String ? DateTime.tryParse(value) : null;
}
