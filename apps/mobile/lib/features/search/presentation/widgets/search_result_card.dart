import 'package:flutter/material.dart';
import '../../domain/entities/search_entities.dart';

/// Card de exibição visual de um resultado do catálogo na Busca Global.
///
/// Apresenta diferenciação clara entre Local (Place) e Produto (Product),
/// badges identificadores e campos reais fornecidos pelo Search V1.
class SearchResultCard extends StatelessWidget {
  final SearchResultItem item;
  final VoidCallback? onTap;

  const SearchResultCard({
    super.key,
    required this.item,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isPlace = item.isPlace;
    final isProduct = item.isProduct;

    final IconData typeIcon = isPlace
        ? Icons.storefront_rounded
        : isProduct
            ? Icons.inventory_2_outlined
            : Icons.category_outlined;

    final Color badgeColor = isPlace
        ? theme.colorScheme.primary
        : isProduct
            ? Colors.teal
            : theme.colorScheme.secondary;

    return Card(
      elevation: 0.5,
      margin: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 4.0),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(12.0),
          child: Row(
            children: [
              // Ícone representacional do tipo de alvo
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: badgeColor.withAlpha(25),
                  shape: BoxShape.circle,
                ),
                child: Icon(typeIcon, size: 24, color: badgeColor),
              ),
              const SizedBox(width: 14),

              // Informações do item
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.name,
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        // Badge do tipo (Local / Produto)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: badgeColor.withAlpha(25),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            item.targetType.displayName,
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.bold,
                              color: badgeColor,
                            ),
                          ),
                        ),
                        if (item.category != null && item.category!.isNotEmpty) ...[
                          const SizedBox(width: 6),
                          Flexible(
                            child: Text(
                              '• ${item.category!}',
                              style: TextStyle(
                                fontSize: 12,
                                color: theme.colorScheme.onSurface.withAlpha(140),
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ],
                ),
              ),

              // Indicador de ação
              Icon(
                Icons.chevron_right,
                size: 20,
                color: theme.colorScheme.onSurface.withAlpha(100),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
