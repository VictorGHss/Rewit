/// Dados para atualização parcial de uma avaliação existente (Step 25.3 / C5.7).
class UpdateReviewInput {
  final String? experienceText;
  final Map<String, double>? targetRatings;
  final bool? isAnonymous;
  final String? visibility;

  const UpdateReviewInput({
    this.experienceText,
    this.targetRatings,
    this.isAnonymous,
    this.visibility,
  });

  Map<String, dynamic> toJson() {
    return {
      if (experienceText != null) 'experienceText': experienceText,
      if (targetRatings != null) 'targetRatings': targetRatings,
      if (isAnonymous != null) 'isAnonymous': isAnonymous,
      if (visibility != null) 'visibility': visibility,
    };
  }
}
