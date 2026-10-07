/// Estatísticas factuais de atividade do usuário no Rewit (Step 18.0).
class UserStats {
  final int totalReviews;
  final int verifiedReviewsCount;
  final int followersCount;
  final int followingCount;
  final int helpfulVotesReceived;

  const UserStats({
    this.totalReviews = 0,
    this.verifiedReviewsCount = 0,
    this.followersCount = 0,
    this.followingCount = 0,
    this.helpfulVotesReceived = 0,
  });

  UserStats copyWith({
    int? totalReviews,
    int? verifiedReviewsCount,
    int? followersCount,
    int? followingCount,
    int? helpfulVotesReceived,
  }) {
    return UserStats(
      totalReviews: totalReviews ?? this.totalReviews,
      verifiedReviewsCount: verifiedReviewsCount ?? this.verifiedReviewsCount,
      followersCount: followersCount ?? this.followersCount,
      followingCount: followingCount ?? this.followingCount,
      helpfulVotesReceived: helpfulVotesReceived ?? this.helpfulVotesReceived,
    );
  }
}

/// Perfil público factual de um usuário no Rewit.
class UserProfile {
  final String id;
  final String handle;
  final String displayName;
  final String? bio;
  final String? avatarUrl;
  final UserStats stats;
  final bool isFollowing;

  const UserProfile({
    required this.id,
    required this.handle,
    required this.displayName,
    this.bio,
    this.avatarUrl,
    required this.stats,
    this.isFollowing = false,
  });

  UserProfile copyWith({
    String? id,
    String? handle,
    String? displayName,
    String? bio,
    String? avatarUrl,
    UserStats? stats,
    bool? isFollowing,
  }) {
    return UserProfile(
      id: id ?? this.id,
      handle: handle ?? this.handle,
      displayName: displayName ?? this.displayName,
      bio: bio ?? this.bio,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      stats: stats ?? this.stats,
      isFollowing: isFollowing ?? this.isFollowing,
    );
  }
}
