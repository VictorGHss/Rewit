import 'dart:convert';
import 'dart:typed_data';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/place/data/models/place_dtos.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import '../../domain/entities/product_detail.dart';
import '../../domain/entities/product_identifier.dart';
import '../../domain/entities/products_in_place_page.dart';
import '../../domain/repositories/product_repository.dart';
import '../models/product_dtos.dart';

/// Implementação padrão de [ProductRepository] consumindo a API REST do Rewit via [RewitHttpClient].
class ProductRepositoryImpl implements ProductRepository {
  final RewitHttpClient httpClient;

  ProductRepositoryImpl({required this.httpClient});

  @override
  Future<ProductDetail> getProductById(String id) async {
    final response = await httpClient.get(ApiEndpoints.productDetail(id));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter detalhes do produto.');
    }
    return ProductDto.fromJson(decoded).toEntity();
  }

  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) async {
    final response = await httpClient.get(ApiEndpoints.productIdentifiers(id));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar identificadores do produto.');
    }
    return ProductIdentifiersResponseDto.fromJson(decoded).toEntityList();
  }

  @override
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  }) async {
    final response = await httpClient.get(
      ApiEndpoints.productByIdentifier(type.trim(), value.trim()),
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor no lookup do produto.');
    }
    return ProductDto.fromJson(decoded).toEntity();
  }

  @override
  Future<ProductsInPlacePage> getProductsInPlace(
    String placeId, {
    int page = 0,
    int size = 20,
  }) async {
    final response = await httpClient.get(
      ApiEndpoints.placeProductsPath(placeId),
      queryParameters: {
        'page': page,
        'size': size,
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar produtos do local.');
    }
    return ProductsInPlacePageDto.fromJson(decoded).toEntity();
  }

  @override
  Future<TargetStats> getProductStats(String id) async {
    final response = await httpClient.get(ApiEndpoints.targetStats(id));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter estatísticas do produto.');
    }
    return TargetStatsDto.fromJson(decoded).toEntity();
  }

  @override
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async {
    final response = await httpClient.get(
      ApiEndpoints.targetReviewsPath(productId),
      queryParameters: {
        'page': page,
        'size': size,
        'sort': sort,
        'verifiedOnly': verifiedOnly,
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar avaliações do produto.');
    }
    return TargetReviewsPageDto.fromJson(decoded).toEntity();
  }

  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async {
    return httpClient.getBytes(imageUrl);
  }
}

