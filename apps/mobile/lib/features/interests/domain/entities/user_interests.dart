/// Situação do questionário inicial de interesses (GET /api/v1/me/interests).
enum InterestOnboardingStatus {
  /// Nenhuma resposta gravada no backend.
  notStarted,

  /// Respostas parciais gravadas; o questionário é retomado de onde parou.
  inProgress,

  /// Questionário confirmado; não volta a ser apresentado.
  completed;

  static InterestOnboardingStatus fromApi(String? value) {
    switch (value) {
      case 'NOT_STARTED':
        return InterestOnboardingStatus.notStarted;
      case 'IN_PROGRESS':
        return InterestOnboardingStatus.inProgress;
      case 'COMPLETED':
        return InterestOnboardingStatus.completed;
    }
    throw FormatException('Situação do questionário desconhecida: $value');
  }
}

/// Interesse do catálogo controlado do backend. [code] é o identificador canônico; [label] só é exibido.
class InterestOption {
  final String code;
  final String label;

  const InterestOption({required this.code, required this.label});
}

/// Pergunta do questionário e as opções que ela agrupa, na ordem do catálogo.
class InterestQuestion {
  final String code;
  final String title;
  final String question;
  final List<InterestOption> options;

  const InterestQuestion({
    required this.code,
    required this.title,
    required this.question,
    required this.options,
  });
}

/// Catálogo do questionário (GET /api/v1/interests/catalog).
class InterestCatalog {
  final int minInterestsToComplete;
  final List<InterestQuestion> questions;

  const InterestCatalog({required this.minInterestsToComplete, required this.questions});

  /// Todas as opções, na ordem das perguntas e do catálogo.
  List<InterestOption> get options => [for (final question in questions) ...question.options];

  bool contains(String code) => questions.any((q) => q.options.any((o) => o.code == code));
}

/// Interesses gravados no backend e a situação do questionário.
class UserInterests {
  final InterestOnboardingStatus status;
  final DateTime? completedAt;
  final List<InterestOption> interests;

  const UserInterests({required this.status, this.completedAt, required this.interests});

  bool get isCompleted => status == InterestOnboardingStatus.completed;

  Set<String> get codes => interests.map((interest) => interest.code).toSet();
}
