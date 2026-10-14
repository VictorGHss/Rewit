import 'package:flutter/material.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../state/place_commercial_state.dart';

/// Seção visual que exibe as ofertas oficiais de produtos comercializados pelo estabelecimento (F2.3).
///
/// Visualmente distinta da listagem comunitária de produtos, com dados de disponibilidade e preço oficial.
class PlaceCommercialOffersSection extends StatelessWidget {
  final CommercialSectionState<PlaceOffersData> state;
  final VoidCallback? onRetry;
  final VoidCallback? onLoadMore;
  final ValueChanged<String>? onOfferTap;

  const PlaceCommercialOffersSection({
    super.key,
    required this.state,
    this.onRetry,
    this.onLoadMore,
    this.onOfferTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(
              Icons.storefront_outlined,
              size: 22,
              color: theme.colorScheme.primary,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Ofertas Oficiais do Estabelecimento',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  Text(
                    'Catálogo comercial publicado pelo estabelecimento',
                    style: TextStyle(
                      fontSize: 12,
                      color: theme.colorScheme.onSurface.withAlpha(140),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        _buildBody(context, theme),
      ],
    );
  }

  Widget _buildBody(BuildContext context, ThemeData theme) {
    final currentState = state;

    if (currentState is SectionLoading || currentState is SectionInitial) {
      return Card(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
        ),
        child: const Padding(
          padding: EdgeInsets.all(20.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              SizedBox(width: 12),
              Text(
                'Carregando ofertas comerciais...',
                style: TextStyle(fontSize: 13),
              ),
            ],
          ),
        ),
      );
    }

    if (currentState is SectionError<PlaceOffersData>) {
      return Card(
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                currentState.message,
                style: TextStyle(
                  color: theme.colorScheme.error,
                  fontSize: 13,
                ),
              ),
              if (onRetry != null) ...[
                const SizedBox(height: 6),
                TextButton(
                  onPressed: onRetry,
                  style: TextButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                  ),
                  child: const Text('Tentar novamente'),
                ),
              ],
            ],
          ),
        ),
      );
    }

    if (currentState is SectionLoaded<PlaceOffersData>) {
      final data = currentState.data;

      if (data.offers.isEmpty) {
        return Card(
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 24.0),
            child: Center(
              child: Column(
                children: [
                  Icon(
                    Icons.inventory_2_outlined,
                    size: 36,
                    color: theme.colorScheme.onSurface.withAlpha(100),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Nenhuma oferta comercial divulgada',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                      color: theme.colorScheme.onSurface.withAlpha(180),
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Este estabelecimento ainda não publicou preços ou produtos oficiais.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 12,
                      color: theme.colorScheme.onSurface.withAlpha(140),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      }

      return Column(
        children: [
          ...data.offers.map((offer) => _buildOfferCard(context, offer, theme)),
          if (data.isLoadingMore)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 12.0),
              child: Center(
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            ),
          if (data.loadMoreError != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8.0),
              child: Column(
                children: [
                  Text(
                    data.loadMoreError!,
                    style: TextStyle(
                      color: theme.colorScheme.error,
                      fontSize: 12,
                    ),
                  ),
                  TextButton(
                    onPressed: onLoadMore,
                    child: const Text('Tentar carregar mais ofertas'),
                  ),
                ],
              ),
            ),
          if (!data.isLastPage && !data.isLoadingMore && data.loadMoreError == null && onLoadMore != null)
            Padding(
              padding: const EdgeInsets.only(top: 8.0),
              child: Center(
                child: OutlinedButton.icon(
                  onPressed: onLoadMore,
                  icon: const Icon(Icons.expand_more, size: 18),
                  label: Text('Carregar mais ofertas (${data.offers.length} de ${data.totalElements})'),
                  style: OutlinedButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                  ),
                ),
              ),
            ),
        ],
      );
    }

    return const SizedBox.shrink();
  }

  Widget _buildOfferCard(BuildContext context, PlaceProductOffer offer, ThemeData theme) {
    final isAvailable = offer.isAvailable;

    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 8.0),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onOfferTap != null ? () => onOfferTap!(offer.product.id) : null,
        child: Padding(
          padding: const EdgeInsets.all(12.0),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: theme.colorScheme.primaryContainer.withAlpha(120),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  Icons.sell_outlined,
                  color: theme.colorScheme.primary,
                  size: 24,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      offer.product.name,
                      style: const TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 14,
                      ),
                    ),
                    if (offer.product.brand != null || offer.product.model != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        [
                          if (offer.product.brand != null) offer.product.brand!,
                          if (offer.product.model != null) offer.product.model!,
                        ].join(' - '),
                        style: TextStyle(
                          fontSize: 12,
                          color: theme.colorScheme.onSurface.withAlpha(150),
                        ),
                      ),
                    ],
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 8,
                      runSpacing: 4,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        // Badge de disponibilidade
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: isAvailable
                                ? Colors.green.withAlpha(30)
                                : Colors.orange.withAlpha(30),
                            borderRadius: BorderRadius.circular(6),
                            border: Border.all(
                              color: isAvailable
                                  ? Colors.green.withAlpha(100)
                                  : Colors.orange.withAlpha(100),
                            ),
                          ),
                          child: Text(
                            offer.localizedAvailability,
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                              color: isAvailable ? Colors.green.shade800 : Colors.orange.shade900,
                            ),
                          ),
                        ),
                        // Preço oficial
                        Text(
                          offer.formattedPrice,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.bold,
                            color: offer.price != null && offer.price! > 0
                                ? theme.colorScheme.primary
                                : theme.colorScheme.onSurface.withAlpha(170),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              if (onOfferTap != null)
                Icon(
                  Icons.chevron_right,
                  color: theme.colorScheme.onSurface.withAlpha(100),
                  size: 20,
                ),
            ],
          ),
        ),
      ),
    );
  }
}
