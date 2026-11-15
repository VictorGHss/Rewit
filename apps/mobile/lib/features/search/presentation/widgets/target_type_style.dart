import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import '../../domain/entities/search_entities.dart';

/// Ícone, tom semântico e rótulo de um tipo de alvo avaliável, consistentes
/// na busca, no seletor e no item de avaliação, adaptados aos temas claro e escuro.
class TargetTypeStyle {
  final IconData icon;
  final Color color;
  final RewitSemanticTone tone;
  final String label;

  const TargetTypeStyle._(this.icon, this.color, this.tone, this.label);

  Color get containerColor => tone.container;
  Color get onContainerColor => tone.onContainer;
  Color get borderColor => tone.border;

  static TargetTypeStyle of(TargetType type, ColorScheme scheme) {
    final semantic = scheme.brightness == Brightness.dark
        ? RewitSemanticColors.dark
        : RewitSemanticColors.light;

    switch (type) {
      case TargetType.place:
        return TargetTypeStyle._(
          Icons.storefront_rounded,
          semantic.placeTarget.accent,
          semantic.placeTarget,
          type.displayName,
        );
      case TargetType.product:
        return TargetTypeStyle._(
          Icons.inventory_2_outlined,
          semantic.productTarget.accent,
          semantic.productTarget,
          type.displayName,
        );
      case TargetType.unknown:
        return TargetTypeStyle._(
          Icons.category_outlined,
          semantic.serviceTarget.accent,
          semantic.serviceTarget,
          type.displayName,
        );
    }
  }
}
