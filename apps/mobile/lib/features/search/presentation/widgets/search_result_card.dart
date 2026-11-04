import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';
import '../../domain/entities/search_entities.dart';
import 'target_type_style.dart';

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
    final scheme = theme.colorScheme;
    final style = TargetTypeStyle.of(item.targetType, scheme);

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 4.0),
      child: InkWell(
        onTap: onTap,
        borderRadius: RewitRadius.borderLg,
        child: Padding(
          padding: const EdgeInsets.all(12.0),
          child: Row(
            children: [
              // Ícone representacional do tipo de alvo
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: style.tone.container,
                  shape: BoxShape.circle,
                  border: Border.all(color: style.tone.border, width: 0.8),
                ),
                child: Icon(style.icon, size: 24, color: style.tone.accent),
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
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: style.tone.container,
                            borderRadius: RewitRadius.borderXs,
                            border: Border.all(
                              color: style.tone.border,
                              width: 0.8,
                            ),
                          ),
                          child: Text(
                            item.targetType.displayName,
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.bold,
                              color: style.tone.onContainer,
                            ),
                          ),
                        ),
                        if (item.category != null &&
                            item.category!.isNotEmpty) ...[
                          const SizedBox(width: 6),
                          Flexible(
                            child: Text(
                              '• ${item.category!}',
                              style: TextStyle(
                                fontSize: 12,
                                color: scheme.onSurfaceVariant,
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
                color: scheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
