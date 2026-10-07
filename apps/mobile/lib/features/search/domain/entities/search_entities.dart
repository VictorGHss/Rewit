/// Tipos de alvos avaliáveis suportados no catálogo Rewit.
enum TargetType {
  place,
  product,
  unknown;

  static TargetType fromString(String? value) {
    switch (value?.toUpperCase()) {
      case 'PLACE':
        return TargetType.place;
      case 'PRODUCT':
        return TargetType.product;
      default:
        return TargetType.unknown;
    }
  }

  String get displayName {
    switch (this) {
      case TargetType.place:
        return 'Local';
      case TargetType.product:
        return 'Produto';
      case TargetType.unknown:
        return 'Item';
    }
  }
}

/// Entidade representacional de um item retornado pelo Search V1 do backend.
class SearchResultItem {
  final String id;
  final String name;
  final String? slug;
  final String? category;
  final TargetType targetType;
  final String rawTargetType;
  final String status;

  const SearchResultItem({
    required this.id,
    required this.name,
    this.slug,
    this.category,
    required this.targetType,
    required this.rawTargetType,
    required this.status,
  });

  bool get isPlace => targetType == TargetType.place;
  bool get isProduct => targetType == TargetType.product;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SearchResultItem &&
          runtimeType == other.runtimeType &&
          id == other.id;

  @override
  int get hashCode => id.hashCode;
}

/// Página de resultados da busca unificada do catálogo.
class SearchPage {
  final List<SearchResultItem> items;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const SearchPage({
    required this.items,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  bool get isEmpty => items.isEmpty;
  bool get isNotEmpty => items.isNotEmpty;
}
