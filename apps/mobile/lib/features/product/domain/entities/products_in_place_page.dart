import 'product_detail.dart';

/// Envelope paginado de produtos presentes em um local físico (Place).
class ProductsInPlacePage {
  final List<ProductDetail> products;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const ProductsInPlacePage({
    required this.products,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  bool get isEmpty => products.isEmpty;
  bool get isNotEmpty => products.isNotEmpty;
}

