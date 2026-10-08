import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';
import '../../domain/entities/product_detail.dart';
import '../../domain/entities/product_identifier.dart';
import '../../domain/repositories/product_repository.dart';
import '../state/product_detail_notifier.dart';
import '../state/product_detail_state.dart';

/// Tela completa de Detalhes do Produto no catálogo Rewit (C5.3).
///
/// Exibe dados cadastrais, identificadores comerciais públicos (EAN/UPC/GTIN/ISBN),
/// locais com presença registrada, estatísticas agregadas, CTA para avaliação pré-selecionada
/// e listagem paginada de avaliações da comunidade.
class ProductDetailScreen extends StatefulWidget {
  final String productId;
  final PlaceDetail? contextPlace;
  final ProductRepository? repository;
  final ProductDetailNotifier? notifier;

  const ProductDetailScreen({
    super.key,
    required this.productId,
    this.contextPlace,
    this.repository,
    this.notifier,
  });

  @override
  State<ProductDetailScreen> createState() => _ProductDetailScreenState();
}

class _ProductDetailScreenState extends State<ProductDetailScreen> {
  late final ProductDetailNotifier _notifier;
  bool _ownsNotifier = false;
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.repository != null) {
      _notifier = ProductDetailNotifier(
        repository: widget.repository!,
        productId: widget.productId,
        contextPlace: widget.contextPlace,
      );
      _ownsNotifier = true;
    } else {
      throw StateError('ProductRepository ou ProductDetailNotifier deve ser fornecido para ProductDetailScreen.');
    }

    _scrollController.addListener(_onScroll);

    if (_notifier.state is ProductDetailInitial) {
      _notifier.loadProduct();
    }
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final maxScroll = _scrollController.position.maxScrollExtent;
    final currentScroll = _scrollController.position.pixels;

    if (maxScroll - currentScroll <= 200) {
      _notifier.loadMoreReviews();
    }
  }

  Future<void> _navigateToCreateReview(BuildContext context, ProductDetail product) async {
    await Navigator.of(context).pushNamed(
      AppRouter.reviewCreate,
      arguments: {
        'targetId': product.id,
        'targetName': product.name,
        'targetType': 'PRODUCT',
        'category': product.category,
      },
    );
    if (mounted) {
      _notifier.refresh();
    }
  }

  void _navigateToReviewDetail(FeedReview review) {
    Navigator.of(context).pushNamed(
      AppRouter.reviewDetail,
      arguments: review,
    );
  }

  void _navigateToAuthorProfile(String authorId) {
    Navigator.of(context).pushNamed(
      AppRouter.profile,
      arguments: authorId,
    );
  }

  void _navigateToPlaceDetail(String placeId) {
    Navigator.of(context).pushNamed(
      AppRouter.placeDetail,
      arguments: placeId,
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Detalhes do Produto'),
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          final state = _notifier.state;

          if (state is ProductDetailLoading || state is ProductDetailInitial) {
            return const Center(
              child: LoadingIndicator(message: 'Carregando detalhes do produto...'),
            );
          }

          if (state is ProductDetailNotFound) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24.0),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.inventory_2_outlined,
                      size: 64,
                      color: theme.colorScheme.onSurface.withAlpha(120),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'Produto indisponível',
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Este produto não foi encontrado ou não está mais ativo no catálogo.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(160),
                      ),
                    ),
                    const SizedBox(height: 24),
                    if (Navigator.of(context).canPop())
                      OutlinedButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: const Text('Voltar'),
                      ),
                  ],
                ),
              ),
            );
          }

          if (state is ProductDetailError) {
            return Center(
              child: ErrorView(
                title: 'Não foi possível carregar o produto',
                message: state.message,
                onRetry: () => _notifier.loadProduct(),
              ),
            );
          }

          if (state is ProductDetailLoaded) {
            return _buildLoadedContent(context, state, theme);
          }

          return const SizedBox.shrink();
        },
      ),
    );
  }

  Widget _buildLoadedContent(
    BuildContext context,
    ProductDetailLoaded state,
    ThemeData theme,
  ) {
    final product = state.product;
    final stats = state.stats;

    return RefreshIndicator(
      onRefresh: () => _notifier.refresh(),
      child: ListView(
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16.0),
        children: [
          // 1. Imagem do Produto
          _buildProductImage(context, product, theme),

          const SizedBox(height: 16),

          // 2. Cabeçalho: Nome, Marca, Modelo e Categoria
          Text(
            product.name,
            style: theme.textTheme.headlineSmall?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 6),

          Wrap(
            spacing: 12,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (product.brand.isNotEmpty)
                Text(
                  'Marca: ${product.brand}',
                  style: TextStyle(
                    color: theme.colorScheme.onSurface.withAlpha(180),
                    fontWeight: FontWeight.w500,
                  ),
                ),
              if (product.model != null && product.model!.trim().isNotEmpty)
                Text(
                  'Modelo: ${product.model!.trim()}',
                  style: TextStyle(
                    color: theme.colorScheme.onSurface.withAlpha(180),
                  ),
                ),
              Chip(
                label: Text(
                  product.category,
                  style: const TextStyle(fontSize: 12),
                ),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
            ],
          ),

          if (product.description != null && product.description!.trim().isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(
              product.description!.trim(),
              style: TextStyle(
                color: theme.colorScheme.onSurface.withAlpha(200),
                height: 1.3,
              ),
            ),
          ],

          const SizedBox(height: 16),

          // 3. Estatísticas Agregadas
          Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
              child: Row(
                children: [
                  const Icon(Icons.star_rounded, color: Colors.amber, size: 32),
                  const SizedBox(width: 8),
                  if (stats != null && stats.hasReviews) ...[
                    Text(
                      stats.formattedRating,
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      '(${stats.reviewsCount} ${stats.reviewsCount == 1 ? 'avaliação' : 'avaliações'})',
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(160),
                        fontSize: 14,
                      ),
                    ),
                  ] else ...[
                    Text(
                      'Sem avaliações ainda',
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(160),
                        fontSize: 14,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),

          const SizedBox(height: 16),

          // 4. CTA: Avaliar este produto
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: () => _navigateToCreateReview(context, product),
              icon: const Icon(Icons.rate_review_outlined),
              label: const Text('Avaliar este produto'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
            ),
          ),

          const SizedBox(height: 24),

          // 5. Identificadores Comerciais Públicos
          Text(
            'Identificadores',
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 8),
          _buildIdentifiersCard(state.identifiers, theme),

          const SizedBox(height: 24),

          // 6. Onde Encontrar (Locais com Presença)
          Text(
            'Onde encontrar',
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 8),
          _buildPlacesCard(state.places, theme),

          const SizedBox(height: 24),

          // 7. Seção de Avaliações
          Text(
            'Avaliações da Comunidade',
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 8),

          if (state.reviews.isEmpty) ...[
            Card(
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
                side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 32.0),
                child: Column(
                  children: [
                    Icon(
                      Icons.rate_review_outlined,
                      size: 48,
                      color: theme.colorScheme.primary.withAlpha(120),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      'Nenhuma avaliação ainda',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Seja a primeira pessoa a compartilhar sua experiência sobre este produto!',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(150),
                        fontSize: 14,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ] else ...[
            ...state.reviews.map(
              (review) => Padding(
                padding: const EdgeInsets.only(bottom: 8.0),
                child: ReviewCard(
                  review: review,
                  onTap: () => _navigateToReviewDetail(review),
                  onAuthorTap: (authorId) => _navigateToAuthorProfile(authorId),
                ),
              ),
            ),

            if (state.isLoadingMoreReviews)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16.0),
                child: Center(child: CircularProgressIndicator()),
              ),

            if (state.loadMoreReviewsError != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: Column(
                  children: [
                    Text(
                      state.loadMoreReviewsError!,
                      style: TextStyle(color: theme.colorScheme.error, fontSize: 13),
                    ),
                    TextButton(
                      onPressed: () => _notifier.loadMoreReviews(),
                      child: const Text('Tentar carregar mais'),
                    ),
                  ],
                ),
              ),
          ],
        ],
      ),
    );
  }

  Widget _buildProductImage(BuildContext context, ProductDetail product, ThemeData theme) {
    if (!product.hasImage) {
      return Container(
        height: 180,
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(80)),
        ),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.inventory_2_outlined,
                size: 64,
                color: theme.colorScheme.primary.withAlpha(120),
              ),
              const SizedBox(height: 8),
              Text(
                'Sem imagem do produto',
                style: TextStyle(
                  color: theme.colorScheme.onSurface.withAlpha(120),
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
      );
    }

    // Carregamento de imagem autenticada/segura via FutureBuilder
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: Container(
        height: 200,
        width: double.infinity,
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
          border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(80)),
        ),
        child: FutureBuilder<Uint8List>(
          future: _notifier.repository.getProductImageBytes(product.imageUrl!),
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(
                child: SizedBox(
                  width: 28,
                  height: 28,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              );
            }
            if (snapshot.hasError || !snapshot.hasData) {
              return Center(
                child: Icon(
                  Icons.broken_image_outlined,
                  size: 48,
                  color: theme.colorScheme.error.withAlpha(160),
                ),
              );
            }
            return Image.memory(
              snapshot.data!,
              fit: BoxFit.contain,
              errorBuilder: (_, __, ___) => Center(
                child: Icon(
                  Icons.broken_image_outlined,
                  size: 48,
                  color: theme.colorScheme.error.withAlpha(160),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildIdentifiersCard(List<ProductIdentifier> identifiers, ThemeData theme) {
    if (identifiers.isEmpty) {
      return Card(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
        ),
        child: const Padding(
          padding: EdgeInsets.all(16.0),
          child: Text(
            'Nenhum identificador cadastrado',
            style: TextStyle(fontStyle: FontStyle.italic),
          ),
        ),
      );
    }

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            for (int i = 0; i < identifiers.length; i++) ...[
              if (i > 0) const Divider(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primaryContainer.withAlpha(120),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      identifiers[i].identifierType,
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 12,
                        color: theme.colorScheme.primary,
                      ),
                    ),
                  ),
                  Text(
                    identifiers[i].identifierValue,
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildPlacesCard(List<PlaceDetail> places, ThemeData theme) {
    if (places.isEmpty) {
      return Card(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Row(
            children: [
              Icon(
                Icons.storefront_outlined,
                size: 28,
                color: theme.colorScheme.onSurface.withAlpha(120),
              ),
              const SizedBox(width: 12),
              const Expanded(
                child: Text(
                  'Nenhum local registrado ainda',
                  style: TextStyle(fontStyle: FontStyle.italic),
                ),
              ),
            ],
          ),
        ),
      );
    }

    return Column(
      children: [
        for (final place in places)
          Card(
            elevation: 0,
            margin: const EdgeInsets.only(bottom: 8.0),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
            ),
            child: ListTile(
              leading: Icon(Icons.storefront, color: theme.colorScheme.primary),
              title: Text(
                place.name,
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              subtitle: Text(
                place.formattedAddress,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _navigateToPlaceDetail(place.id),
            ),
          ),
      ],
    );
  }
}

