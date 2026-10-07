import '../../domain/entities/search_entities.dart';

/// DTO de serialização para cada item retornado pelo Search V1 (`SearchResultResponse`).
class SearchResultItemDto {
  final String id;
  final String name;
  final String? slug;
  final String? category;
  final String targetType;
  final String status;

  const SearchResultItemDto({
    required this.id,
    required this.name,
    this.slug,
    this.category,
    required this.targetType,
    required this.status,
  });

  factory SearchResultItemDto.fromJson(Map<String, dynamic> json) {
    return SearchResultItemDto(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      slug: json['slug'] as String?,
      category: json['category'] as String?,
      targetType: json['targetType'] as String? ?? 'PLACE',
      status: json['status'] as String? ?? 'ACTIVE',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      if (slug != null) 'slug': slug,
      if (category != null) 'category': category,
      'targetType': targetType,
      'status': status,
    };
  }

  SearchResultItem toEntity() {
    return SearchResultItem(
      id: id,
      name: name,
      slug: slug,
      category: category,
      targetType: TargetType.fromString(targetType),
      rawTargetType: targetType,
      status: status,
    );
  }
}

/// DTO de envelope para resposta paginada do Search V1 (`PagedResponse<SearchResultResponse>`).
class SearchPagedResponseDto {
  final List<SearchResultItemDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const SearchPagedResponseDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory SearchPagedResponseDto.fromJson(Map<String, dynamic> json) {
    final rawContent = json['content'] as List<dynamic>? ?? [];
    final content = rawContent
        .whereType<Map<String, dynamic>>()
        .map((item) => SearchResultItemDto.fromJson(item))
        .toList();

    return SearchPagedResponseDto(
      content: content,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 20,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'content': content.map((e) => e.toJson()).toList(),
      'pageNumber': pageNumber,
      'pageSize': pageSize,
      'totalElements': totalElements,
      'totalPages': totalPages,
      'isLast': isLast,
    };
  }

  SearchPage toEntity() {
    return SearchPage(
      items: content.map((item) => item.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}
