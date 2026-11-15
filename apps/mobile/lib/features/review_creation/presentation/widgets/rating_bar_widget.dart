import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';

/// Componente interativo de seleção de nota entre 1.0 e 5.0.
class RatingBarWidget extends StatelessWidget {
  final double rating;
  final ValueChanged<double> onRatingChanged;
  final bool enabled;

  const RatingBarWidget({
    super.key,
    required this.rating,
    required this.onRatingChanged,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final semantic = RewitSemanticColors.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Row(
              mainAxisSize: MainAxisSize.min,
              children: List.generate(5, (index) {
                final starValue = index + 1;
                final isFilled = rating >= starValue;
                final isHalf = rating >= starValue - 0.5 && rating < starValue;

                final Color starColor;
                if (!enabled) {
                  starColor = scheme.outline;
                } else if (isFilled || isHalf) {
                  starColor = semantic.starFilled;
                } else {
                  starColor = semantic.starEmpty;
                }

                return InkWell(
                  onTap: enabled
                      ? () {
                          // Se clicar na mesma estrela inteira, alterna para meia estrela
                          if (rating == starValue.toDouble()) {
                            onRatingChanged((starValue - 0.5).clamp(1.0, 5.0));
                          } else {
                            onRatingChanged(starValue.toDouble());
                          }
                        }
                      : null,
                  borderRadius: BorderRadius.circular(20),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4.0,
                      vertical: 4.0,
                    ),
                    child: Icon(
                      isFilled
                          ? Icons.star
                          : (isHalf ? Icons.star_half : Icons.star_border),
                      color: starColor,
                      size: 28,
                    ),
                  ),
                );
              }),
            ),
            const SizedBox(width: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: scheme.secondaryContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                rating.toStringAsFixed(1),
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 15,
                  color: scheme.onSecondaryContainer,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
