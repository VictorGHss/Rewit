import 'package:flutter/material.dart';
import '../../domain/entities/review_creation_input.dart';
import 'rating_bar_widget.dart';

/// Widget de edição de um alvo individual dentro da avaliação multi-alvo.
class TargetItemInputWidget extends StatefulWidget {
  final int index;
  final CreateReviewTargetInput target;
  final bool canRemove;
  final bool enabled;
  final ValueChanged<String> onTargetIdChanged;
  final ValueChanged<double> onRatingChanged;
  final ValueChanged<String> onSpecificCommentChanged;
  final VoidCallback onRemove;

  const TargetItemInputWidget({
    super.key,
    required this.index,
    required this.target,
    required this.canRemove,
    this.enabled = true,
    required this.onTargetIdChanged,
    required this.onRatingChanged,
    required this.onSpecificCommentChanged,
    required this.onRemove,
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

            // Campo de ID do Alvo (UUID)
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
            const SizedBox(height: 12),

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
}
