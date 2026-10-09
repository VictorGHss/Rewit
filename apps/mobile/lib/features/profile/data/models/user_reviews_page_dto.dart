import 'package:rewit_mobile/features/feed/data/models/feed_models.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_reviews_page.dart';

/// DTO de serialização para resposta paginada de avaliações do usuário autenticado.
class UserReviewsPageDto {
  final List<FeedReviewDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const UserReviewsPageDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory UserReviewsPageDto.fromJson(Map<String, dynamic> json) {
    final rawList = json['content'] as List<dynamic>? ?? [];
    final content = rawList
        .whereType<Map<String, dynamic>>()
        .map((e) => FeedReviewDto.fromJson(e))
        .toList();

    return UserReviewsPageDto(
      content: content,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 10,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  UserReviewsPage toEntity() {
    return UserReviewsPage(
      reviews: content.map((e) => e.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}
