import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import '../../domain/entities/review_creation_input.dart';
import 'rating_bar_widget.dart';
import 'target_search_selector_widget.dart';

/// Widget de edição e seleção de um alvo individual dentro da avaliação multi-alvo.
class TargetItemInputWidget extends StatefulWidget {
  final int index;
  final CreateReviewTargetInput target;
  final bool canRemove;
  final bool enabled;
  final SearchRepository? searchRepository;
  final ValueChanged<String> onTargetIdChanged;
  final ValueChanged<double> onRatingChanged;
  final ValueChanged<String> onSpecificCommentChanged;
  final VoidCallback onRemove;
  final ValueChanged<SearchResultItem>? onTargetSelected;
  final VoidCallback? onClearSelection;
  final bool Function(String targetId)? isTargetSelectedElsewhere;

  const TargetItemInputWidget({
    super.key,
    required this.index,
    required this.target,
    required this.canRemove,
    this.enabled = true,
    this.searchRepository,
    required this.onTargetIdChanged,
    required this.onRatingChanged,
    required this.onSpecificCommentChanged,
    required this.onRemove,
    this.onTargetSelected,
    this.onClearSelection,
    this.isTargetSelectedElsewhere,
  });

  @override
  State<TargetItemInputWidget> createState() => _TargetItemInputWidgetState();
}

class _TargetItemInputWidgetState extends State<TargetItemInputWidget> {
  late final TextEditingController _targetIdController;
  late final TextEditingController _commentController;

  @override
  void initState() {
    super.initState();
    _targetIdController = TextEditingController(text: widget.target.rateableTargetId);
    _commentController = TextEditingController(text: widget.target.specificComment ?? '');
  }

  @override
  void didUpdateWidget(covariant TargetItemInputWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.target.rateableTargetId != widget.target.rateableTargetId &&
        _targetIdController.text != widget.target.rateableTargetId) {
      _targetIdController.text = widget.target.rateableTargetId;
    }
    if (oldWidget.target.specificComment != widget.target.specificComment &&
        _commentController.text != (widget.target.specificComment ?? '')) {
      _commentController.text = widget.target.specificComment ?? '';
    }
  }

  @override
  void dispose() {
    _targetIdController.dispose();
    _commentController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isTargetSelected = widget.target.targetName != null &&
        widget.target.targetName!.isNotEmpty &&
        widget.target.rateableTargetId.isNotEmpty;

    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: Colors.grey.withAlpha(50)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(6),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.primary.withAlpha(20),
                        shape: BoxShape.circle,
                      ),
                      child: Text(
                        '#${widget.index + 1}',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                          color: theme.colorScheme.primary,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      widget.index == 0 ? 'Alvo Principal' : 'Alvo Adicional',
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                if (widget.canRemove)
                  IconButton(
                    icon: Icon(Icons.delete_outline, color: theme.colorScheme.error),
                    tooltip: 'Remover este alvo',
                    onPressed: widget.enabled ? widget.onRemove : null,
                  ),
              ],
            ),
            const SizedBox(height: 12),

            // Seleção ou exibição do alvo
            if (isTargetSelected) ...[
              _buildSelectedTargetCard(context, theme),
            ] else if (widget.searchRepository != null) ...[
              TargetSearchSelectorWidget(
                searchRepository: widget.searchRepository!,
                targetIndex: widget.index,
                enabled: widget.enabled,
                isTargetSelectedElsewhere: widget.isTargetSelectedElsewhere,
                onTargetSelected: (item) {
                  widget.onTargetSelected?.call(item);
                },
                onManualTargetIdEntered: widget.onTargetIdChanged,
              ),
            ] else ...[
              // Fallback para inserção direta de UUID quando searchRepository não for injetado
              TextFormField(
                controller: _targetIdController,
                enabled: widget.enabled,
                decoration: const InputDecoration(
                  labelText: 'Identificador do Alvo (UUID) *',
                  hintText: 'ex: 00000000-0000-0000-0000-000000000001',
                  prefixIcon: Icon(Icons.storefront_outlined, size: 20),
                  isDense: true,
                  border: OutlineInputBorder(),
                ),
                onChanged: widget.onTargetIdChanged,
              ),
            ],
            const SizedBox(height: 16),

            // Seletor de Nota (1.0 a 5.0)
            Text(
              'Nota de Avaliação *',
              style: theme.textTheme.bodySmall?.copyWith(
                fontWeight: FontWeight.w600,
                color: theme.colorScheme.onSurface.withAlpha(180),
              ),
            ),
            const SizedBox(height: 4),
            RatingBarWidget(
              rating: widget.target.rating,
              enabled: widget.enabled,
              onRatingChanged: widget.onRatingChanged,
            ),
            const SizedBox(height: 12),

            // Comentário Específico
            TextFormField(
              controller: _commentController,
              enabled: widget.enabled,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Comentário sobre este alvo (opcional)',
                hintText: 'Detalhes sobre o atendimento, prato ou serviço...',
                isDense: true,
                border: OutlineInputBorder(),
              ),
              onChanged: widget.onSpecificCommentChanged,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSelectedTargetCard(BuildContext context, ThemeData theme) {
    final isPlace = widget.target.targetType?.toUpperCase() == 'PLACE';
    final isProduct = widget.target.targetType?.toUpperCase() == 'PRODUCT';

    final Color badgeColor = isPlace
        ? theme.colorScheme.primary
        : isProduct
            ? Colors.teal
            : theme.colorScheme.secondary;

    final IconData typeIcon = isPlace
        ? Icons.storefront_rounded
        : isProduct
            ? Icons.inventory_2_outlined
            : Icons.category_outlined;

    final String typeLabel = isPlace
        ? 'Local'
        : isProduct
            ? 'Produto'
            : (widget.target.targetType ?? 'Alvo');

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withAlpha(60),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: badgeColor.withAlpha(80)),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: badgeColor.withAlpha(25),
              shape: BoxShape.circle,
            ),
            child: Icon(typeIcon, color: badgeColor, size: 22),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.target.targetName ?? widget.target.rateableTargetId,
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                ),
                const SizedBox(height: 2),
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1.5),
                      decoration: BoxDecoration(
                        color: badgeColor.withAlpha(25),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        typeLabel,
                        style: TextStyle(
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                          color: badgeColor,
                        ),
                      ),
                    ),
                    if (widget.target.category != null && widget.target.category!.isNotEmpty) ...[
                      const SizedBox(width: 6),
                      Flexible(
                        child: Text(
                          '• ${widget.target.category!}',
                          style: TextStyle(
                            fontSize: 11,
                            color: theme.colorScheme.onSurface.withAlpha(140),
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  'ID: ${widget.target.rateableTargetId}',
                  style: TextStyle(
                    fontSize: 10,
                    fontFamily: 'monospace',
                    color: theme.colorScheme.onSurface.withAlpha(120),
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          OutlinedButton.icon(
            onPressed: widget.enabled
                ? () {
                    if (widget.onClearSelection != null) {
                      widget.onClearSelection!();
                    } else {
                      widget.onTargetIdChanged('');
                    }
                  }
                : null,
            icon: const Icon(Icons.swap_horiz, size: 16),
            label: const Text('Trocar', style: TextStyle(fontSize: 12)),
            style: OutlinedButton.styleFrom(
              visualDensity: VisualDensity.compact,
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            ),
          ),
        ],
      ),
    );
  }
}
