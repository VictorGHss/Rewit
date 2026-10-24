import 'package:flutter/material.dart';
import '../../domain/entities/search_entities.dart';

/// Ícone, cor e rótulo de um tipo de alvo avaliável, iguais na busca, no seletor e no alvo escolhido.
///
/// As cores ficam na família azul da marca e se distinguem entre si: Local no azul primário e Produto em índigo.
class TargetTypeStyle {
  final IconData icon;
  final Color color;
  final String label;

  const TargetTypeStyle._(this.icon, this.color, this.label);

  static const Color productColor = Color(0xFF3949AB); // Indigo 600

  static TargetTypeStyle of(TargetType type, ColorScheme scheme) {
    switch (type) {
      case TargetType.place:
        return TargetTypeStyle._(Icons.storefront_rounded, scheme.primary, type.displayName);
      case TargetType.product:
        return TargetTypeStyle._(Icons.inventory_2_outlined, productColor, type.displayName);
      case TargetType.unknown:
        return TargetTypeStyle._(Icons.category_outlined, scheme.secondary, type.displayName);
    }
  }
}
