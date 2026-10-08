import 'dart:typed_data';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import '../entities/product_detail.dart';
import '../entities/product_identifier.dart';
import '../entities/products_in_place_page.dart';

/// Contrato do repositório para acesso e consulta de produtos globais, seus identificadores e presenças (C5.3).
abstract class ProductRepository {
  /// Obtém os detalhes completos de um produto pelo seu identificador único.
  Future<ProductDetail> getProductById(String id);

  /// Lista os identificadores comerciais públicos (EAN, UPC, GTIN, ISBN) cadastrados para o produto.
  Future<List<ProductIdentifier>> getProductIdentifiers(String id);

  /// Realiza o lookup de produto a partir de um código comercial estruturado (`type` e `value`).
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  });

  /// Lista de forma paginada os produtos com presença registrada em um local físico.
  Future<ProductsInPlacePage> getProductsInPlace(
    String placeId, {
    int page = 0,
    int size = 20,
  });

  /// Obtém as estatísticas agregadas calculadas para o alvo avaliável do produto.
  Future<TargetStats> getProductStats(String id);

  /// Obtém a listagem paginada de avaliações vinculadas ao alvo avaliável do produto.
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  });

  /// Recupera os bytes brutos da imagem do produto de forma autenticada e segura.
  Future<Uint8List> getProductImageBytes(String imageUrl);
}

