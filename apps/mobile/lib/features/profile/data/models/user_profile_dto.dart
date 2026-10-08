import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';

/// DTO para deserialização das estatísticas factuais do usuário (/api/v1/users/{id}).
class UserStatsDto {
  final int totalReviews;
  final int verifiedReviewsCount;
  final int followersCount;
  final int followingCount;
  final int helpfulVotesReceived;

  const UserStatsDto({
    required this.totalReviews,
    required this.verifiedReviewsCount,
    required this.followersCount,
    required this.followingCount,
    required this.helpfulVotesReceived,
  });

  factory UserStatsDto.fromJson(Map<String, dynamic> json) {
    return UserStatsDto(
      totalReviews: (json['totalReviews'] as num?)?.toInt() ?? 0,
      verifiedReviewsCount: (json['verifiedReviewsCount'] as num?)?.toInt() ?? 0,
      followersCount: (json['followersCount'] as num?)?.toInt() ?? 0,
      followingCount: (json['followingCount'] as num?)?.toInt() ?? 0,
      helpfulVotesReceived: (json['helpfulVotesReceived'] as num?)?.toInt() ?? 0,
    );
  }

  UserStats toEntity() => UserStats(
        totalReviews: totalReviews,
        verifiedReviewsCount: verifiedReviewsCount,
        followersCount: followersCount,
        followingCount: followingCount,
        helpfulVotesReceived: helpfulVotesReceived,
      );
}

/// DTO para deserialização do perfil público do usuário (/api/v1/users/{id} e /api/v1/me/profile).
class UserProfileDto {
  final String id;
  final String handle;
  final String displayName;
  final String? bio;
  final String? avatarUrl;
  final UserStatsDto stats;
  final bool isFollowing;
  final bool isAnonymousDefault;

  const UserProfileDto({
    required this.id,
    required this.handle,
    required this.displayName,
    this.bio,
    this.avatarUrl,
    required this.stats,
    required this.isFollowing,
    this.isAnonymousDefault = false,
  });

  factory UserProfileDto.fromJson(Map<String, dynamic> json) {
    return UserProfileDto(
      id: json['id'] as String? ?? '',
      handle: json['handle'] as String? ?? '',
      displayName: json['displayName'] as String? ?? '',
      bio: json['bio'] as String?,
      avatarUrl: json['avatarUrl'] as String?,
      stats: json['stats'] is Map<String, dynamic>
          ? UserStatsDto.fromJson(json['stats'] as Map<String, dynamic>)
          : const UserStatsDto(
              totalReviews: 0,
              verifiedReviewsCount: 0,
              followersCount: 0,
              followingCount: 0,
              helpfulVotesReceived: 0,
            ),
      isFollowing: json['isFollowing'] as bool? ?? false,
      isAnonymousDefault: json['isAnonymousDefault'] as bool? ?? false,
    );
  }

  UserProfile toEntity() => UserProfile(
        id: id,
        handle: handle,
        displayName: displayName,
        bio: bio,
        avatarUrl: avatarUrl,
        stats: stats.toEntity(),
        isFollowing: isFollowing,
        isAnonymousDefault: isAnonymousDefault,
      );
}
