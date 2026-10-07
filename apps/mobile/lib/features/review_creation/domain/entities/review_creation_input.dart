/// Entidades de domínio para entrada de dados de criação de avaliação.
class CreateReviewTargetInput {
  final String rateableTargetId;
  final double rating;
  final String? specificComment;

  const CreateReviewTargetInput({
    required this.rateableTargetId,
    required this.rating,
    this.specificComment,
  });

  CreateReviewTargetInput copyWith({
    String? rateableTargetId,
    double? rating,
    String? specificComment,
  }) {
    return CreateReviewTargetInput(
      rateableTargetId: rateableTargetId ?? this.rateableTargetId,
      rating: rating ?? this.rating,
      specificComment: specificComment ?? this.specificComment,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'rateableTargetId': rateableTargetId,
      'rating': rating,
      if (specificComment != null && specificComment!.trim().isNotEmpty)
        'specificComment': specificComment!.trim(),
    };
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CreateReviewTargetInput &&
          runtimeType == other.runtimeType &&
          rateableTargetId == other.rateableTargetId &&
          rating == other.rating &&
          specificComment == other.specificComment;

  @override
  int get hashCode => Object.hash(rateableTargetId, rating, specificComment);
}

/// Dados de entrada consolidados para criar uma avaliação no backend (POST /api/v1/reviews).
class CreateReviewInput {
  final String? contextPlaceId;
  final String? experienceText;
  final bool isAnonymous;
  final String visibility;
  final double? userLatitude;
  final double? userLongitude;
  final double? locationAccuracyMeters;
  final List<CreateReviewTargetInput> targets;

  const CreateReviewInput({
    this.contextPlaceId,
    this.experienceText,
    this.isAnonymous = false,
    this.visibility = 'PUBLIC',
    this.userLatitude,
    this.userLongitude,
    this.locationAccuracyMeters,
    required this.targets,
  });

  Map<String, dynamic> toJson() {
    return {
      if (contextPlaceId != null && contextPlaceId!.trim().isNotEmpty)
        'contextPlaceId': contextPlaceId!.trim(),
      if (experienceText != null && experienceText!.trim().isNotEmpty)
        'experienceText': experienceText!.trim(),
      'isAnonymous': isAnonymous,
      'visibility': visibility,
      if (userLatitude != null) 'userLatitude': userLatitude,
      if (userLongitude != null) 'userLongitude': userLongitude,
      if (locationAccuracyMeters != null)
        'locationAccuracyMeters': locationAccuracyMeters,
      'targets': targets.map((t) => t.toJson()).toList(),
    };
  }

  CreateReviewInput copyWith({
    String? contextPlaceId,
    String? experienceText,
    bool? isAnonymous,
    String? visibility,
    double? userLatitude,
    double? userLongitude,
    double? locationAccuracyMeters,
    List<CreateReviewTargetInput>? targets,
  }) {
    return CreateReviewInput(
      contextPlaceId: contextPlaceId ?? this.contextPlaceId,
      experienceText: experienceText ?? this.experienceText,
      isAnonymous: isAnonymous ?? this.isAnonymous,
      visibility: visibility ?? this.visibility,
      userLatitude: userLatitude ?? this.userLatitude,
      userLongitude: userLongitude ?? this.userLongitude,
      locationAccuracyMeters:
          locationAccuracyMeters ?? this.locationAccuracyMeters,
      targets: targets ?? this.targets,
    );
  }
}
