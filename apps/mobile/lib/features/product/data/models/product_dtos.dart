import '../../domain/entities/product_detail.dart';
import '../../domain/entities/product_identifier.dart';
import '../../domain/entities/products_in_place_page.dart';

/// DTO de serialização para detalhes de um Produto Global (Product).
class ProductDto {
  final String id;
  final String name;
  final String brand;
  final String? model;
  final String? description;
  final String category;
  final String? imageUrl;
  final String status;

  const ProductDto({
    required this.id,
    required this.name,
    required this.brand,
    this.model,
    this.description,
    required this.category,
    this.imageUrl,
    required this.status,
  });

  factory ProductDto.fromJson(Map<String, dynamic> json) {
    return ProductDto(
      id: json['id'] as String,
      name: json['name'] as String,
      brand: json['brand'] as String? ?? '',
      model: json['model'] as String?,
      description: json['description'] as String?,
      category: json['category'] as String? ?? 'GERAL',
      imageUrl: json['imageUrl'] as String?,
      status: json['status'] as String? ?? 'ACTIVE',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'brand': brand,
      if (model != null) 'model': model,
      if (description != null) 'description': description,
      'category': category,
      if (imageUrl != null) 'imageUrl': imageUrl,
      'status': status,
    };
  }

  ProductDetail toEntity() {
    return ProductDetail(
      id: id,
      name: name,
      brand: brand,
      model: model,
      description: description,
      category: category,
      imageUrl: imageUrl,
      status: status,
    );
  }
}

/// DTO de serialização para um identificador comercial público de produto.
class ProductIdentifierDto {
  final String identifierType;
  final String identifierValue;

  const ProductIdentifierDto({
    required this.identifierType,
    required this.identifierValue,
  });

  factory ProductIdentifierDto.fromJson(Map<String, dynamic> json) {
    return ProductIdentifierDto(
      identifierType: json['identifierType'] as String? ?? '',
      identifierValue: json['identifierValue'] as String? ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'identifierType': identifierType,
      'identifierValue': identifierValue,
    };
  }

  ProductIdentifier toEntity() {
    return ProductIdentifier(
      identifierType: identifierType,
      identifierValue: identifierValue,
    );
  }
}

/// DTO da resposta envelope da lista de identificadores do produto (`GET /api/v1/products/{id}/identifiers`).
class ProductIdentifiersResponseDto {
  final List<ProductIdentifierDto> identifiers;

  const ProductIdentifiersResponseDto({
    required this.identifiers,
  });

  factory ProductIdentifiersResponseDto.fromJson(Map<String, dynamic> json) {
    final rawList = json['identifiers'] as List<dynamic>? ?? [];
    final items = rawList
        .whereType<Map<String, dynamic>>()
        .map((item) => ProductIdentifierDto.fromJson(item))
        .toList();

    return ProductIdentifiersResponseDto(identifiers: items);
  }

  List<ProductIdentifier> toEntityList() {
    return identifiers
        .map((item) => item.toEntity())
        .where((id) => id.isPublic)
        .toList();
  }
}

/// DTO de resposta paginada de produtos presentes em um local (`GET /api/v1/places/{id}/products`).
class ProductsInPlacePageDto {
  final List<ProductDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const ProductsInPlacePageDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory ProductsInPlacePageDto.fromJson(Map<String, dynamic> json) {
    final rawList = json['content'] as List<dynamic>? ?? [];
    final content = rawList
        .whereType<Map<String, dynamic>>()
        .map((e) => ProductDto.fromJson(e))
        .toList();

    return ProductsInPlacePageDto(
      content: content,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 20,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  ProductsInPlacePage toEntity() {
    return ProductsInPlacePage(
      products: content.map((e) => e.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}

