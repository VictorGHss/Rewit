import 'package:flutter/material.dart';

/// Grupo imutável de cores semânticas coordenadas para superfícies contextuais,
/// selos, chips e banners (sucesso, alerta, erro, verificação e categorias).
@immutable
class RewitSemanticTone {
  /// Fundo suave do container contextual.
  final Color container;

  /// Texto principal de leitura sobre [container] (contraste WCAG AA >= 4.5:1).
  final Color onContainer;

  /// Borda sutil ou estrutural associada ao estado semântico.
  final Color border;

  /// Cor de destaque para ícones ou rótulos sobre superfícies ou sobre [container].
  final Color accent;

  const RewitSemanticTone({
    required this.container,
    required this.onContainer,
    required this.border,
    required this.accent,
  });

  RewitSemanticTone copyWith({
    Color? container,
    Color? onContainer,
    Color? border,
    Color? accent,
  }) {
    return RewitSemanticTone(
      container: container ?? this.container,
      onContainer: onContainer ?? this.onContainer,
      border: border ?? this.border,
      accent: accent ?? this.accent,
    );
  }

  static RewitSemanticTone lerp(
    RewitSemanticTone a,
    RewitSemanticTone b,
    double t,
  ) {
    return RewitSemanticTone(
      container: Color.lerp(a.container, b.container, t)!,
      onContainer: Color.lerp(a.onContainer, b.onContainer, t)!,
      border: Color.lerp(a.border, b.border, t)!,
      accent: Color.lerp(a.accent, b.accent, t)!,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is RewitSemanticTone &&
        other.container == container &&
        other.onContainer == onContainer &&
        other.border == border &&
        other.accent == accent;
  }

  @override
  int get hashCode => Object.hash(container, onContainer, border, accent);
}

/// Extensão semântica oficial do Design System do Rewit.
///
/// Concentra tokens funcionais que não pertencem diretamente ao `ColorScheme`
/// padrão do Material 3 (estados de confirmação, alertas, selo de presença
/// verificada, estrelas de avaliação, tipos de alvo e superfícies focais).
@immutable
class RewitSemanticColors extends ThemeExtension<RewitSemanticColors> {
  final RewitSemanticTone success;
  final RewitSemanticTone warning;
  final RewitSemanticTone danger;
  final RewitSemanticTone info;
  final RewitSemanticTone verified;
  final RewitSemanticTone ratingBadge;
  final Color starFilled;
  final Color starEmpty;
  final RewitSemanticTone placeTarget;
  final RewitSemanticTone productTarget;
  final RewitSemanticTone serviceTarget;
  final Color elevatedFocalSurface;
  final Color anonymousAvatarBackground;
  final Color anonymousAvatarForeground;
  final Color mediaScrim;
  final Color onMediaScrim;

  const RewitSemanticColors({
    required this.success,
    required this.warning,
    required this.danger,
    required this.info,
    required this.verified,
    required this.ratingBadge,
    required this.starFilled,
    required this.starEmpty,
    required this.placeTarget,
    required this.productTarget,
    required this.serviceTarget,
    required this.elevatedFocalSurface,
    required this.anonymousAvatarBackground,
    required this.anonymousAvatarForeground,
    required this.mediaScrim,
    required this.onMediaScrim,
  });

  /// Paleta semântica calibrada para o tema claro (Lavanda & Roxo).
  static const RewitSemanticColors light = RewitSemanticColors(
    success: RewitSemanticTone(
      container: Color(0xFFDCFCE7),
      onContainer: Color(0xFF14532D),
      border: Color(0xFF86EFAC),
      accent: Color(0xFF166534),
    ),
    warning: RewitSemanticTone(
      container: Color(0xFFFEF3C7),
      onContainer: Color(0xFF78350F),
      border: Color(0xFFFCD34D),
      accent: Color(0xFF92400E),
    ),
    danger: RewitSemanticTone(
      container: Color(0xFFFEE2E2),
      onContainer: Color(0xFF7F1D1D),
      border: Color(0xFFFCA5A5),
      accent: Color(0xFFB91C1C),
    ),
    info: RewitSemanticTone(
      container: Color(0xFFEDE6FF),
      onContainer: Color(0xFF3B1578),
      border: Color(0xFFC4B5FD),
      accent: Color(0xFF5B21B6),
    ),
    verified: RewitSemanticTone(
      container: Color(0xFFD1FAE5),
      onContainer: Color(0xFF064E3B),
      border: Color(0xFF6EE7B7),
      accent: Color(0xFF047857),
    ),
    ratingBadge: RewitSemanticTone(
      container: Color(0xFFFEF3C7),
      onContainer: Color(0xFF78350F),
      border: Color(0xFFFDE68A),
      accent: Color(0xFF92400E),
    ),
    starFilled: Color(0xFFC96A04),
    starEmpty: Color(0xFF8C82A3),
    placeTarget: RewitSemanticTone(
      container: Color(0xFFEDE6FF),
      onContainer: Color(0xFF4C1D95),
      border: Color(0xFFD3C4FC),
      accent: Color(0xFF5B21B6),
    ),
    productTarget: RewitSemanticTone(
      container: Color(0xFFFFEDD5),
      onContainer: Color(0xFF7C2D12),
      border: Color(0xFFFED7AA),
      accent: Color(0xFF9A3412),
    ),
    serviceTarget: RewitSemanticTone(
      container: Color(0xFFE0E7FF),
      onContainer: Color(0xFF312E81),
      border: Color(0xFFC7D2FE),
      accent: Color(0xFF3730A3),
    ),
    elevatedFocalSurface: Color(0xFFFFFFFF),
    anonymousAvatarBackground: Color(0xFF534C63),
    anonymousAvatarForeground: Color(0xFFFFFFFF),
    mediaScrim: Color(0xAD09090D),
    onMediaScrim: Color(0xFFFFFFFF),
  );

  /// Paleta semântica calibrada para o tema escuro (Preto, Grafite & Lavanda).
  static const RewitSemanticColors dark = RewitSemanticColors(
    success: RewitSemanticTone(
      container: Color(0xFF10281D),
      onContainer: Color(0xFFBBF7D0),
      border: Color(0xFF1F5138),
      accent: Color(0xFF4ADE80),
    ),
    warning: RewitSemanticTone(
      container: Color(0xFF2B200E),
      onContainer: Color(0xFFFDE68A),
      border: Color(0xFF5E4315),
      accent: Color(0xFFFBBF24),
    ),
    danger: RewitSemanticTone(
      container: Color(0xFF331418),
      onContainer: Color(0xFFFECACA),
      border: Color(0xFF6B242B),
      accent: Color(0xFFF87171),
    ),
    info: RewitSemanticTone(
      container: Color(0xFF241C38),
      onContainer: Color(0xFFE5DBFF),
      border: Color(0xFF47366E),
      accent: Color(0xFFB69AFF),
    ),
    verified: RewitSemanticTone(
      container: Color(0xFF0E2922),
      onContainer: Color(0xFFA7F3D0),
      border: Color(0xFF1D5446),
      accent: Color(0xFF34D399),
    ),
    ratingBadge: RewitSemanticTone(
      container: Color(0xFF291F0F),
      onContainer: Color(0xFFFDE68A),
      border: Color(0xFF543E18),
      accent: Color(0xFFFBBF24),
    ),
    starFilled: Color(0xFFFBBF24),
    starEmpty: Color(0xFF7D7491),
    placeTarget: RewitSemanticTone(
      container: Color(0xFF251C3A),
      onContainer: Color(0xFFE4D9FF),
      border: Color(0xFF46356B),
      accent: Color(0xFFC4ADFF),
    ),
    productTarget: RewitSemanticTone(
      container: Color(0xFF2E1D12),
      onContainer: Color(0xFFFED7AA),
      border: Color(0xFF5C371E),
      accent: Color(0xFFFB923C),
    ),
    serviceTarget: RewitSemanticTone(
      container: Color(0xFF1B213B),
      onContainer: Color(0xFFC7D2FE),
      border: Color(0xFF344073),
      accent: Color(0xFFA5B4FC),
    ),
    elevatedFocalSurface: Color(0xFF211B2B),
    anonymousAvatarBackground: Color(0xFF2D273B),
    anonymousAvatarForeground: Color(0xFFEAE5F5),
    mediaScrim: Color(0xBD09090D),
    onMediaScrim: Color(0xFFFFFFFF),
  );

  /// Recupera os tokens semânticos do tema atual, com fallback seguro por
  /// brilho caso a árvore de widgets tenha sido montada sem `AppTheme` (ex.: testes isolados).
  static RewitSemanticColors of(BuildContext context) {
    final theme = Theme.of(context);
    return theme.extension<RewitSemanticColors>() ??
        (theme.brightness == Brightness.dark ? dark : light);
  }

  @override
  RewitSemanticColors copyWith({
    RewitSemanticTone? success,
    RewitSemanticTone? warning,
    RewitSemanticTone? danger,
    RewitSemanticTone? info,
    RewitSemanticTone? verified,
    RewitSemanticTone? ratingBadge,
    Color? starFilled,
    Color? starEmpty,
    RewitSemanticTone? placeTarget,
    RewitSemanticTone? productTarget,
    RewitSemanticTone? serviceTarget,
    Color? elevatedFocalSurface,
    Color? anonymousAvatarBackground,
    Color? anonymousAvatarForeground,
    Color? mediaScrim,
    Color? onMediaScrim,
  }) {
    return RewitSemanticColors(
      success: success ?? this.success,
      warning: warning ?? this.warning,
      danger: danger ?? this.danger,
      info: info ?? this.info,
      verified: verified ?? this.verified,
      ratingBadge: ratingBadge ?? this.ratingBadge,
      starFilled: starFilled ?? this.starFilled,
      starEmpty: starEmpty ?? this.starEmpty,
      placeTarget: placeTarget ?? this.placeTarget,
      productTarget: productTarget ?? this.productTarget,
      serviceTarget: serviceTarget ?? this.serviceTarget,
      elevatedFocalSurface: elevatedFocalSurface ?? this.elevatedFocalSurface,
      anonymousAvatarBackground:
          anonymousAvatarBackground ?? this.anonymousAvatarBackground,
      anonymousAvatarForeground:
          anonymousAvatarForeground ?? this.anonymousAvatarForeground,
      mediaScrim: mediaScrim ?? this.mediaScrim,
      onMediaScrim: onMediaScrim ?? this.onMediaScrim,
    );
  }

  @override
  RewitSemanticColors lerp(
    covariant ThemeExtension<RewitSemanticColors>? other,
    double t,
  ) {
    if (other is! RewitSemanticColors) return this;
    return RewitSemanticColors(
      success: RewitSemanticTone.lerp(success, other.success, t),
      warning: RewitSemanticTone.lerp(warning, other.warning, t),
      danger: RewitSemanticTone.lerp(danger, other.danger, t),
      info: RewitSemanticTone.lerp(info, other.info, t),
      verified: RewitSemanticTone.lerp(verified, other.verified, t),
      ratingBadge: RewitSemanticTone.lerp(ratingBadge, other.ratingBadge, t),
      starFilled: Color.lerp(starFilled, other.starFilled, t)!,
      starEmpty: Color.lerp(starEmpty, other.starEmpty, t)!,
      placeTarget: RewitSemanticTone.lerp(placeTarget, other.placeTarget, t),
      productTarget: RewitSemanticTone.lerp(
        productTarget,
        other.productTarget,
        t,
      ),
      serviceTarget: RewitSemanticTone.lerp(
        serviceTarget,
        other.serviceTarget,
        t,
      ),
      elevatedFocalSurface: Color.lerp(
        elevatedFocalSurface,
        other.elevatedFocalSurface,
        t,
      )!,
      anonymousAvatarBackground: Color.lerp(
        anonymousAvatarBackground,
        other.anonymousAvatarBackground,
        t,
      )!,
      anonymousAvatarForeground: Color.lerp(
        anonymousAvatarForeground,
        other.anonymousAvatarForeground,
        t,
      )!,
      mediaScrim: Color.lerp(mediaScrim, other.mediaScrim, t)!,
      onMediaScrim: Color.lerp(onMediaScrim, other.onMediaScrim, t)!,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is RewitSemanticColors &&
        other.success == success &&
        other.warning == warning &&
        other.danger == danger &&
        other.info == info &&
        other.verified == verified &&
        other.ratingBadge == ratingBadge &&
        other.starFilled == starFilled &&
        other.starEmpty == starEmpty &&
        other.placeTarget == placeTarget &&
        other.productTarget == productTarget &&
        other.serviceTarget == serviceTarget &&
        other.elevatedFocalSurface == elevatedFocalSurface &&
        other.anonymousAvatarBackground == anonymousAvatarBackground &&
        other.anonymousAvatarForeground == anonymousAvatarForeground &&
        other.mediaScrim == mediaScrim &&
        other.onMediaScrim == onMediaScrim;
  }

  @override
  int get hashCode => Object.hashAll([
        success,
        warning,
        danger,
        info,
        verified,
        ratingBadge,
        starFilled,
        starEmpty,
        placeTarget,
        productTarget,
        serviceTarget,
        elevatedFocalSurface,
        anonymousAvatarBackground,
        anonymousAvatarForeground,
        mediaScrim,
        onMediaScrim,
      ]);
}

