import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';

/// Conversão dos contratos de `docs/api/me.md` §5 para as entidades do app. Campo obrigatório ausente ou com tipo
/// inesperado lança [FormatException], tratada pela tela como falha de carregamento.
class InterestDtos {
  const InterestDtos._();

  static InterestOption option(Object? json) {
    final map = _map(json);
    return InterestOption(code: _string(map, 'code'), label: _string(map, 'label'));
  }

  static InterestCatalog catalog(Object? json) {
    final map = _map(json);
    final min = map['minInterestsToComplete'];
    if (min is! int || min < 0) {
      throw const FormatException('Catálogo de interesses sem mínimo válido.');
    }
    return InterestCatalog(
      minInterestsToComplete: min,
      questions: _list(map, 'questions').map((raw) {
        final question = _map(raw);
        return InterestQuestion(
          code: _string(question, 'code'),
          title: _string(question, 'title'),
          question: _string(question, 'question'),
          options: _list(question, 'options').map(option).toList(growable: false),
        );
      }).toList(growable: false),
    );
  }

  static UserInterests userInterests(Object? json) {
    final map = _map(json);
    final completedAt = map['onboardingCompletedAt'];
    return UserInterests(
      status: InterestOnboardingStatus.fromApi(map['onboardingStatus'] as String?),
      completedAt: completedAt is String ? DateTime.tryParse(completedAt) : null,
      interests: _list(map, 'interests').map(option).toList(growable: false),
    );
  }

  static Map<String, dynamic> saveRequest(List<String> codes, bool completeOnboarding) => {
        'interests': codes,
        'completeOnboarding': completeOnboarding,
      };

  static Map<String, dynamic> _map(Object? json) {
    if (json is Map<String, dynamic>) return json;
    throw const FormatException('Resposta inválida do servidor ao carregar interesses.');
  }

  static List<Object?> _list(Map<String, dynamic> map, String key) {
    final value = map[key];
    if (value is List) return value;
    throw FormatException('Campo "$key" ausente na resposta de interesses.');
  }

  static String _string(Map<String, dynamic> map, String key) {
    final value = map[key];
    if (value is String && value.isNotEmpty) return value;
    throw FormatException('Campo "$key" ausente na resposta de interesses.');
  }
}
