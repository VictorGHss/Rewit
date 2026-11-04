import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/presentation/widgets/search_result_card.dart';

/// Razão de contraste WCAG 2.1 entre duas cores opacas via luminância relativa.
double _wcagContrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  final lighter = la > lb ? la : lb;
  final darker = la > lb ? lb : la;
  return (lighter + 0.05) / (darker + 0.05);
}

bool _isTealOrLegacyBlue(Color color) {
  if (color == const Color(0xFF0D9488) ||
      color == const Color(0xFF1E3A8A) ||
      color == const Color(0xFF1D4ED8)) {
    return true;
  }
  final hsl = HSLColor.fromColor(color);
  return hsl.saturation > 0.25 && hsl.hue >= 150 && hsl.hue <= 230;
}

void main() {
  final light = AppTheme.lightTheme;
  final dark = AppTheme.darkTheme;
  final lightScheme = light.colorScheme;
  final darkScheme = dark.colorScheme;
  final lightSemantic = light.extension<RewitSemanticColors>()!;
  final darkSemantic = dark.extension<RewitSemanticColors>()!;

  group('1. Construção de AppTheme.lightTheme, AppTheme.darkTheme e tokens', () {
    test('configura Material 3, brightness, sub-temas explícitos e SystemUiOverlayStyle', () {
      expect(light.useMaterial3, isTrue);
      expect(dark.useMaterial3, isTrue);
      expect(light.brightness, Brightness.light);
      expect(dark.brightness, Brightness.dark);

      for (final theme in [light, dark]) {
        expect(theme.extension<RewitSemanticColors>(), isNotNull);
        expect(theme.cardTheme.color, isNotNull);
        expect(theme.cardTheme.elevation, 0);
        expect(theme.chipTheme.backgroundColor, isNotNull);
        expect(theme.chipTheme.selectedColor, isNotNull);
        expect(theme.dialogTheme.backgroundColor, isNotNull);
        expect(theme.bottomSheetTheme.backgroundColor, isNotNull);
        expect(theme.bottomSheetTheme.modalBackgroundColor, isNotNull);
        expect(theme.snackBarTheme.backgroundColor, isNotNull);
        expect(theme.snackBarTheme.contentTextStyle?.color, isNotNull);
        expect(theme.filledButtonTheme.style, isNotNull);
        expect(theme.elevatedButtonTheme.style, isNotNull);
        expect(theme.outlinedButtonTheme.style, isNotNull);
        expect(theme.textButtonTheme.style, isNotNull);
        expect(theme.inputDecorationTheme.filled, isTrue);
        expect(theme.inputDecorationTheme.fillColor, isNotNull);
        expect(theme.dividerTheme.color, isNotNull);
        expect(theme.navigationBarTheme.backgroundColor, isNotNull);
        expect(theme.navigationBarTheme.indicatorColor, isNotNull);
        expect(theme.appBarTheme.systemOverlayStyle, isNotNull);
      }

      expect(
        light.appBarTheme.systemOverlayStyle?.statusBarIconBrightness,
        Brightness.dark,
      );
      expect(
        dark.appBarTheme.systemOverlayStyle?.statusBarIconBrightness,
        Brightness.light,
      );
      expect(
        light.appBarTheme.systemOverlayStyle?.statusBarColor,
        Colors.transparent,
      );
      expect(
        dark.appBarTheme.systemOverlayStyle?.statusBarColor,
        Colors.transparent,
      );
    });

    test('expõe tokens de espaçamento, radius e tipografia global consistente', () {
      expect(RewitSpacing.xxs, 2);
      expect(RewitSpacing.xs, 4);
      expect(RewitSpacing.sm, 8);
      expect(RewitSpacing.md, 12);
      expect(RewitSpacing.lg, 16);
      expect(RewitSpacing.xl, 20);
      expect(RewitSpacing.xxl, 24);
      expect(RewitSpacing.xxxl, 32);

      expect(RewitRadius.xs, 4);
      expect(RewitRadius.sm, 6);
      expect(RewitRadius.md, 8);
      expect(RewitRadius.lg, 12);
      expect(RewitRadius.xl, 16);
      expect(RewitRadius.sheet, 20);
      expect(RewitRadius.pill, 999);

      expect(light.textTheme.bodyMedium?.color, AppTheme.textPrimaryColor);
      expect(light.textTheme.bodySmall?.color, AppTheme.textSecondaryColor);
      expect(dark.textTheme.bodyMedium?.color, AppTheme.darkTextPrimaryColor);
      expect(dark.textTheme.bodySmall?.color, AppTheme.darkTextSecondaryColor);
    });

    test('RewitSemanticColors suporta copyWith, lerp, igualdade e fallback de contexto', () {
      expect(RewitSemanticColors.light, equals(RewitSemanticColors.light.copyWith()));
      expect(RewitSemanticColors.light, isNot(equals(RewitSemanticColors.dark)));

      final interpolated = RewitSemanticColors.light.lerp(RewitSemanticColors.dark, 0.5);
      expect(
        interpolated.elevatedFocalSurface,
        Color.lerp(
          RewitSemanticColors.light.elevatedFocalSurface,
          RewitSemanticColors.dark.elevatedFocalSurface,
          0.5,
        ),
      );
      expect(
        RewitSemanticColors.light.lerp(null, 0.5),
        same(RewitSemanticColors.light),
      );
    });

    testWidgets('RewitSemanticColors.of retorna fallback seguro mesmo em ThemeData cru', (tester) async {
      late RewitSemanticColors resolvedDark;
      late RewitSemanticColors resolvedLight;

      await tester.pumpWidget(
        MaterialApp(
          themeAnimationDuration: Duration.zero,
          theme: ThemeData.light(),
          home: Builder(
            builder: (context) {
              resolvedLight = RewitSemanticColors.of(context);
              return const SizedBox.shrink();
            },
          ),
        ),
      );
      expect(resolvedLight, equals(RewitSemanticColors.light));

      await tester.pumpWidget(
        MaterialApp(
          themeAnimationDuration: Duration.zero,
          theme: ThemeData.dark(),
          home: Builder(
            builder: (context) {
              resolvedDark = RewitSemanticColors.of(context);
              return const SizedBox.shrink();
            },
          ),
        ),
      );
      expect(resolvedDark, equals(RewitSemanticColors.dark));
    });
  });

  group('2. Papéis principais do ColorScheme nos temas claro e escuro', () {
    test('tema claro usa lavanda suave #F6F4FB, roxo #6D28D9 e branco moderado sem azul/teal legados', () {
      expect(light.scaffoldBackgroundColor, const Color(0xFFF6F4FB));
      expect(lightScheme.surface, const Color(0xFFF6F4FB));
      expect(lightScheme.surfaceContainerLow, const Color(0xFFF1EEFA));
      expect(light.cardTheme.color, const Color(0xFFF1EEFA));
      expect(lightSemantic.elevatedFocalSurface, const Color(0xFFFFFFFF));
      expect(lightScheme.primary, const Color(0xFF6D28D9));
      expect(lightScheme.onSurface, const Color(0xFF181520));
      expect(lightScheme.onSurfaceVariant, const Color(0xFF534C63));

      for (final role in [
        lightScheme.primary,
        lightScheme.secondary,
        lightScheme.secondaryContainer,
        lightScheme.onSecondaryContainer,
      ]) {
        expect(_isTealOrLegacyBlue(role), isFalse);
      }
    });

    test('tema escuro usa preto #09090D, grafite #17151D/#211B2B, ação #8055D9 e lavanda #B69AFF', () {
      expect(dark.scaffoldBackgroundColor, const Color(0xFF09090D));
      expect(darkScheme.surfaceDim, const Color(0xFF09090D));
      expect(darkScheme.surface, const Color(0xFF17151D));
      expect(dark.cardTheme.color, const Color(0xFF17151D));
      expect(darkScheme.surfaceContainerHigh, const Color(0xFF211B2B));
      expect(darkSemantic.elevatedFocalSurface, const Color(0xFF211B2B));
      expect(dark.dialogTheme.backgroundColor, const Color(0xFF211B2B));
      expect(dark.bottomSheetTheme.backgroundColor, const Color(0xFF211B2B));
      expect(darkScheme.secondary, const Color(0xFFB69AFF));

      final filledBg = dark.filledButtonTheme.style!.backgroundColor!.resolve(const <WidgetState>{})!;
      final elevatedBg = dark.elevatedButtonTheme.style!.backgroundColor!.resolve(const <WidgetState>{})!;
      expect(filledBg, const Color(0xFF8055D9));
      expect(elevatedBg, const Color(0xFF8055D9));
    });
  });

  group('7. Contraste matemático real WCAG AA (texto normal >= 4.5:1 e gráfico/grande >= 3:1)', () {
    void verifyThemeContrast(String themeLabel, ThemeData theme) {
      final scheme = theme.colorScheme;
      final semantic = theme.extension<RewitSemanticColors>()!;
      final nav = theme.navigationBarTheme;

      final mainSurfaces = <String, Color>{
        'scaffoldBackground': theme.scaffoldBackgroundColor,
        'scheme.surface': scheme.surface,
        'cardTheme.color': theme.cardTheme.color!,
        'dialogTheme.backgroundColor': theme.dialogTheme.backgroundColor!,
        'bottomSheetTheme.backgroundColor': theme.bottomSheetTheme.backgroundColor!,
        'navigationBarTheme.backgroundColor': nav.backgroundColor!,
        'inputDecorationTheme.fillColor': theme.inputDecorationTheme.fillColor!,
      };

      for (final entry in mainSurfaces.entries) {
        expect(
          _wcagContrast(scheme.onSurface, entry.value),
          greaterThanOrEqualTo(4.5),
          reason: '[$themeLabel] onSurface sobre ${entry.key}',
        );
        expect(
          _wcagContrast(scheme.onSurfaceVariant, entry.value),
          greaterThanOrEqualTo(4.5),
          reason: '[$themeLabel] onSurfaceVariant sobre ${entry.key}',
        );
      }

      // Pares de containers Material 3
      final containerPairs = <String, (Color, Color)>{
        'onPrimary / primary': (scheme.onPrimary, scheme.primary),
        'onPrimaryContainer / primaryContainer': (scheme.onPrimaryContainer, scheme.primaryContainer),
        'onSecondaryContainer / secondaryContainer': (scheme.onSecondaryContainer, scheme.secondaryContainer),
        'onTertiaryContainer / tertiaryContainer': (scheme.onTertiaryContainer, scheme.tertiaryContainer),
        'onError / error': (scheme.onError, scheme.error),
        'onErrorContainer / errorContainer': (scheme.onErrorContainer, scheme.errorContainer),
        'onInverseSurface / inverseSurface': (scheme.onInverseSurface, scheme.inverseSurface),
      };
      for (final entry in containerPairs.entries) {
        final (fg, bg) = entry.value;
        expect(
          _wcagContrast(fg, bg),
          greaterThanOrEqualTo(4.5),
          reason: '[$themeLabel] ${entry.key}',
        );
      }

      // Botões de ação primária, secundária e texto
      final filledBg = theme.filledButtonTheme.style!.backgroundColor!.resolve(const <WidgetState>{})!;
      final filledFg = theme.filledButtonTheme.style!.foregroundColor!.resolve(const <WidgetState>{})!;
      expect(
        _wcagContrast(filledFg, filledBg),
        greaterThanOrEqualTo(4.5),
        reason: '[$themeLabel] FilledButton foreground sobre background',
      );

      final outlinedFg = theme.outlinedButtonTheme.style!.foregroundColor!.resolve(const <WidgetState>{})!;
      final outlinedSide = theme.outlinedButtonTheme.style!.side!.resolve(const <WidgetState>{})!;
      final textBtnFg = theme.textButtonTheme.style!.foregroundColor!.resolve(const <WidgetState>{})!;

      for (final surface in [
        theme.scaffoldBackgroundColor,
        theme.cardTheme.color!,
        theme.dialogTheme.backgroundColor!,
      ]) {
        expect(
          _wcagContrast(outlinedFg, surface),
          greaterThanOrEqualTo(4.5),
          reason: '[$themeLabel] OutlinedButton texto sobre superfície',
        );
        expect(
          _wcagContrast(textBtnFg, surface),
          greaterThanOrEqualTo(4.5),
          reason: '[$themeLabel] TextButton texto sobre superfície',
        );
        expect(
          _wcagContrast(outlinedSide.color, surface),
          greaterThanOrEqualTo(3.0),
          reason: '[$themeLabel] OutlinedButton borda (>= 3:1) sobre superfície',
        );
      }

      // NavigationBar selecionada e não selecionada
      final navSelectedIcon = nav.iconTheme!.resolve(const {WidgetState.selected})!.color!;
      final navUnselectedIcon = nav.iconTheme!.resolve(const <WidgetState>{})!.color!;
      final navSelectedLabel = nav.labelTextStyle!.resolve(const {WidgetState.selected})!.color!;
      final navUnselectedLabel = nav.labelTextStyle!.resolve(const <WidgetState>{})!.color!;
      expect(_wcagContrast(navSelectedIcon, nav.indicatorColor!), greaterThanOrEqualTo(4.5));
      expect(_wcagContrast(navSelectedLabel, nav.backgroundColor!), greaterThanOrEqualTo(4.5));
      expect(_wcagContrast(navUnselectedIcon, nav.backgroundColor!), greaterThanOrEqualTo(4.5));
      expect(_wcagContrast(navUnselectedLabel, nav.backgroundColor!), greaterThanOrEqualTo(4.5));

      // Todos os tons semânticos (sucesso, alerta, erro, info, verificação, nota, tipos de alvo)
      final tones = <String, RewitSemanticTone>{
        'success': semantic.success,
        'warning': semantic.warning,
        'danger': semantic.danger,
        'info': semantic.info,
        'verified': semantic.verified,
        'ratingBadge': semantic.ratingBadge,
        'placeTarget': semantic.placeTarget,
        'productTarget': semantic.productTarget,
        'serviceTarget': semantic.serviceTarget,
      };
      for (final entry in tones.entries) {
        final tone = entry.value;
        expect(
          _wcagContrast(tone.onContainer, tone.container),
          greaterThanOrEqualTo(4.5),
          reason: '[$themeLabel] RewitSemanticColors.${entry.key} onContainer / container',
        );
        expect(
          _wcagContrast(tone.accent, theme.cardTheme.color!),
          greaterThanOrEqualTo(3.0),
          reason: '[$themeLabel] RewitSemanticColors.${entry.key}.accent sobre card',
        );
        expect(
          _wcagContrast(tone.accent, semantic.elevatedFocalSurface),
          greaterThanOrEqualTo(3.0),
          reason: '[$themeLabel] RewitSemanticColors.${entry.key}.accent sobre elevatedFocalSurface',
        );
      }

      // Avatar anônimo e elementos gráficos essenciais (>= 3:1)
      expect(
        _wcagContrast(
          semantic.anonymousAvatarForeground,
          semantic.anonymousAvatarBackground,
        ),
        greaterThanOrEqualTo(4.5),
        reason: '[$themeLabel] avatar anônimo',
      );
      expect(
        _wcagContrast(scheme.outline, theme.scaffoldBackgroundColor),
        greaterThanOrEqualTo(3.0),
        reason: '[$themeLabel] outline sobre scaffold',
      );
      expect(
        _wcagContrast(scheme.outline, theme.cardTheme.color!),
        greaterThanOrEqualTo(3.0),
        reason: '[$themeLabel] outline sobre card',
      );
      expect(
        _wcagContrast(semantic.starFilled, theme.cardTheme.color!),
        greaterThanOrEqualTo(3.0),
        reason: '[$themeLabel] starFilled sobre card',
      );
      expect(
        _wcagContrast(semantic.starFilled, semantic.elevatedFocalSurface),
        greaterThanOrEqualTo(3.0),
        reason: '[$themeLabel] starFilled sobre elevatedFocalSurface',
      );
    }

    test('tema claro atende integralmente aos limiares matemáticos WCAG AA', () {
      verifyThemeContrast('Light', light);
    });

    test('tema escuro atende integralmente aos limiares matemáticos WCAG AA', () {
      verifyThemeContrast('Dark', dark);
    });
  });

  group('8. Ausência de superfícies brancas e de grandes superfícies roxas no tema escuro', () {
    test('nenhuma superfície estrutural ou de card no darkTheme é branca ou roxa saturada', () {
      final structuralSurfaces = <String, Color>{
        'scaffoldBackgroundColor': dark.scaffoldBackgroundColor,
        'cardTheme.color': dark.cardTheme.color!,
        'dialogTheme.backgroundColor': dark.dialogTheme.backgroundColor!,
        'bottomSheetTheme.backgroundColor': dark.bottomSheetTheme.backgroundColor!,
        'navigationBarTheme.backgroundColor': dark.navigationBarTheme.backgroundColor!,
        'surface': darkScheme.surface,
        'surfaceDim': darkScheme.surfaceDim,
        'surfaceContainerLowest': darkScheme.surfaceContainerLowest,
        'surfaceContainerLow': darkScheme.surfaceContainerLow,
        'surfaceContainer': darkScheme.surfaceContainer,
        'surfaceContainerHigh': darkScheme.surfaceContainerHigh,
        'surfaceContainerHighest': darkScheme.surfaceContainerHighest,
        'elevatedFocalSurface': darkSemantic.elevatedFocalSurface,
      };

      for (final entry in structuralSurfaces.entries) {
        final color = entry.value;
        final hsl = HSLColor.fromColor(color);
        expect(color, isNot(Colors.white), reason: '${entry.key} não pode ser branco');
        expect(
          color.computeLuminance(),
          lessThan(0.05),
          reason: '${entry.key} deve permanecer preto/grafite profundo',
        );
        expect(
          hsl.saturation,
          lessThan(0.30),
          reason: '${entry.key} não deve pintar grandes superfícies de roxo saturado',
        );
      }
    });

    testWidgets('cards renderizados (ReviewCard e SearchResultCard) não expõem fundo branco no dark mode',
        (tester) async {
      final sampleReview = FeedReview(
        id: 'rev-dark-1',
        author: const FeedAuthor(
          id: 'u-1',
          handle: 'mariana',
          displayName: 'Mariana Costa',
        ),
        experienceText: 'Excelente café filtrado e ambiente silencioso.',
        isVerifiedOnSite: true,
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        createdAt: DateTime.utc(2026, 10, 27, 12, 0),
        targets: const [
          FeedTarget(
            id: 'tgt-1',
            targetId: 'place-1',
            rating: 4.8,
            targetType: 'PLACE',
            targetName: 'Torrefação Central',
          ),
        ],
      );

      const sampleSearchItem = SearchResultItem(
        id: 'place-1',
        name: 'Torrefação Central',
        slug: 'torrefacao-central',
        category: 'CAFETERIA',
        targetType: TargetType.place,
        rawTargetType: 'PLACE',
        status: 'ACTIVE',
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: dark,
          home: Scaffold(
            body: ListView(
              children: [
                ReviewCard(review: sampleReview),
                SearchResultCard(item: sampleSearchItem, onTap: () {}),
              ],
            ),
          ),
        ),
      );

      final cards = tester.widgetList<Card>(find.byType(Card)).toList();
      expect(cards, isNotEmpty);
      for (final card in cards) {
        final resolvedColor = card.color ?? dark.cardTheme.color!;
        expect(resolvedColor, isNot(Colors.white));
        expect(resolvedColor.computeLuminance(), lessThan(0.05));
      }

      final materials = tester.widgetList<Material>(find.byType(Material));
      for (final mat in materials) {
        if (mat.color != null && mat.color!.a > 0) {
          expect(mat.color, isNot(Colors.white));
          expect(mat.color!.computeLuminance(), lessThan(0.10));
        }
      }
    });

    testWidgets('NavigationBar renderiza indicadores corretos nos temas claro e escuro', (tester) async {
      for (final theme in [light, dark]) {
        await tester.pumpWidget(
          MaterialApp(
            themeAnimationDuration: Duration.zero,
            theme: theme,
            home: Scaffold(
              bottomNavigationBar: NavigationBar(
                selectedIndex: 0,
                destinations: const [
                  NavigationDestination(icon: Icon(Icons.home_outlined), label: 'Início'),
                  NavigationDestination(icon: Icon(Icons.person_outline), label: 'Perfil'),
                ],
              ),
            ),
          ),
        );

        final indicator = tester.widget<NavigationIndicator>(find.byType(NavigationIndicator).first);
        expect(indicator.color, theme.colorScheme.secondaryContainer);
      }
    });
  });
}
