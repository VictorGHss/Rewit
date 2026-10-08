/// Entidade representacional do detalhe completo de um Produto Global (Product) no catálogo (C5.3).
class ProductDetail {
  final String id;
  final String name;
  final String brand;
  final String? model;
  final String? description;
  final String category;
  final String? imageUrl;
  final String status;

  const ProductDetail({
    required this.id,
    required this.name,
    required this.brand,
    this.model,
    this.description,
    required this.category,
    this.imageUrl,
    required this.status,
  });

  bool get hasImage => imageUrl != null && imageUrl!.trim().isNotEmpty;

  String get displayName {
    if (brand.trim().isNotEmpty && !name.toLowerCase().contains(brand.toLowerCase())) {
      return '$brand $name';
    }
    return name;
  }
}

