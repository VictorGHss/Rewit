import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';

/// Fundação visual Next-Gen do Rewit Mobile (Temas Claro e Escuro).
///
/// Diretrizes centrais:
/// - Tema claro: fundo suave `#F6F4FB`, superfícies em tons complementares de
///   lavanda, branco moderado para superfícies focais elevadas, primária roxa
///   `#6D28D9` e texto grafite profundo.
/// - Tema escuro: predominância de preto `#09090D` e grafite `#17151D` /
///   `#211B2B`, sem cards brancos e sem grandes superfícies roxas; roxo
///   `#8055D9` reservado a ações primárias e lavanda `#B69AFF` para detalhes.
class AppTheme {
  const AppTheme._();

  // ===========================================================================
  // Tokens de Identidade — Tema Claro
  // ===========================================================================
  static const Color primaryColor = Color(0xFF6D28D9); // Purple 700
  static const Color primaryVariant = Color(0xFF5B21B6); // Purple 800
  static const Color secondaryColor = Color(0xFF7C3AED); // Violet 600
  static const Color accentContainerColor = Color(0xFFE4D8FF); // Lavanda seleção
  static const Color onAccentContainerColor = Color(0xFF3E1680);
  static const Color backgroundColor = Color(0xFFF6F4FB); // Fundo lavanda suave
  static const Color surfaceColor = Color(0xFFF1EEFA); // Cards lavanda suave
  static const Color elevatedFocalColor = Color(0xFFFFFFFF); // Branco focal moderado
  static const Color errorColor = Color(0xFFB91C1C); // Red 700 (WCAG AA >= 4.5)
  static const Color textPrimaryColor = Color(0xFF181520); // Grafite profundo
  static const Color textSecondaryColor = Color(0xFF534C63); // Grafite médio
  static const Color outlineColor = Color(0xFF79718A); // Contraste gráfico >= 3:1
  static const Color borderSubtleColor = Color(0xFFD5CDE8); // Divisor lavanda sutil

  // ===========================================================================
  // Tokens de Identidade — Tema Escuro
  // ===========================================================================
  static const Color darkBackgroundColor = Color(0xFF09090D); // Preto predominante
  static const Color darkSurfaceColor = Color(0xFF17151D); // Superfície principal grafite
  static const Color darkElevatedSurfaceColor = Color(0xFF211B2B); // Superfície elevada
  static const Color darkActionPrimaryColor = Color(0xFF8055D9); // Roxo de ação primária
  static const Color darkPrimaryAccessibleColor = Color(0xFF9D78F2); // Tonalidade roxa acessível p/ texto
  static const Color darkLavenderDetailColor = Color(0xFFB69AFF); // Lavanda para detalhes
  static const Color darkAccentContainerColor = Color(0xFF2A213D); // Indicador escuro discreto
  static const Color darkOnAccentContainerColor = Color(0xFFE5DBFF);
  static const Color darkErrorColor = Color(0xFFF87171); // Red 400 acessível em fundo escuro
  static const Color darkTextPrimaryColor = Color(0xFFF2EFFA); // Branco suave
  static const Color darkTextSecondaryColor = Color(0xFFB5AFC4); // Cinza-lavanda claro
  static const Color darkOutlineColor = Color(0xFF8A829C); // Contraste gráfico >= 3:1
  static const Color darkBorderSubtleColor = Color(0xFF2E283D); // Borda grafite sutil

  // ===========================================================================
  // Estilos de Barras do Sistema
  // ===========================================================================
  static const SystemUiOverlayStyle lightSystemOverlayStyle =
      SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarBrightness: Brightness.light,
    statusBarIconBrightness: Brightness.dark,
    systemNavigationBarColor: surfaceColor,
    systemNavigationBarDividerColor: borderSubtleColor,
    systemNavigationBarIconBrightness: Brightness.dark,
  );

  static const SystemUiOverlayStyle darkSystemOverlayStyle =
      SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarBrightness: Brightness.dark,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarColor: Color(0xFF121017),
    systemNavigationBarDividerColor: darkBorderSubtleColor,
    systemNavigationBarIconBrightness: Brightness.light,
  );

  // ===========================================================================
  // ColorSchemes Material 3 Explícitos
  // ===========================================================================
  static const ColorScheme lightColorScheme = ColorScheme(
    brightness: Brightness.light,
    primary: primaryColor,
    onPrimary: Color(0xFFFFFFFF),
    primaryContainer: Color(0xFFEDE6FF),
    onPrimaryContainer: Color(0xFF351173),
    secondary: secondaryColor,
    onSecondary: Color(0xFFFFFFFF),
    secondaryContainer: accentContainerColor,
    onSecondaryContainer: onAccentContainerColor,
    tertiary: Color(0xFF7E22CE),
    onTertiary: Color(0xFFFFFFFF),
    tertiaryContainer: Color(0xFFF3E8FF),
    onTertiaryContainer: Color(0xFF4A0E78),
    error: errorColor,
    onError: Color(0xFFFFFFFF),
    errorContainer: Color(0xFFFEE2E2),
    onErrorContainer: Color(0xFF7F1D1D),
    surface: backgroundColor,
    onSurface: textPrimaryColor,
    onSurfaceVariant: textSecondaryColor,
    surfaceDim: Color(0xFFE3DDF0),
    surfaceBright: elevatedFocalColor,
    surfaceContainerLowest: elevatedFocalColor,
    surfaceContainerLow: surfaceColor,
    surfaceContainer: Color(0xFFECE6F8),
    surfaceContainerHigh: Color(0xFFE5DEF4),
    surfaceContainerHighest: Color(0xFFDDD4F0),
    outline: outlineColor,
    outlineVariant: borderSubtleColor,
    shadow: Color(0x1F181520),
    scrim: Color(0x9909090D),
    inverseSurface: Color(0xFF211B2B),
    onInverseSurface: Color(0xFFF4F1FA),
    inversePrimary: darkLavenderDetailColor,
    surfaceTint: Colors.transparent,
  );

  static const ColorScheme darkColorScheme = ColorScheme(
    brightness: Brightness.dark,
    primary: darkPrimaryAccessibleColor,
    onPrimary: Color(0xFF120B24),
    primaryContainer: Color(0xFF2F1F54),
    onPrimaryContainer: Color(0xFFEADDFF),
    secondary: darkLavenderDetailColor,
    onSecondary: Color(0xFF1B1133),
    secondaryContainer: darkAccentContainerColor,
    onSecondaryContainer: darkOnAccentContainerColor,
    tertiary: Color(0xFFD8B4FE),
    onTertiary: Color(0xFF2B0E4A),
    tertiaryContainer: Color(0xFF381E54),
    onTertiaryContainer: Color(0xFFF3E8FF),
    error: darkErrorColor,
    onError: Color(0xFF2D0606),
    errorContainer: Color(0xFF3F1518),
    onErrorContainer: Color(0xFFFECACA),
    surface: darkSurfaceColor,
    onSurface: darkTextPrimaryColor,
    onSurfaceVariant: darkTextSecondaryColor,
    surfaceDim: darkBackgroundColor,
    surfaceBright: Color(0xFF322B40),
    surfaceContainerLowest: Color(0xFF0D0C12),
    surfaceContainerLow: Color(0xFF131118),
    surfaceContainer: Color(0xFF1B1823),
    surfaceContainerHigh: darkElevatedSurfaceColor,
    surfaceContainerHighest: Color(0xFF2B2438),
    outline: darkOutlineColor,
    outlineVariant: darkBorderSubtleColor,
    shadow: Color(0x66000000),
    scrim: Color(0xBD09090D),
    inverseSurface: Color(0xFFF1EEFA),
    onInverseSurface: Color(0xFF181520),
    inversePrimary: primaryColor,
    surfaceTint: Colors.transparent,
  );

  // ===========================================================================
  // Tipografia Global Consistente
  // ===========================================================================
  static TextTheme _buildTextTheme(
    Color primaryText,
    Color secondaryText,
    Brightness brightness,
  ) {
    final base = brightness == Brightness.dark
        ? Typography.material2021().white
        : Typography.material2021().black;

    return base.copyWith(
      displayLarge: base.displayLarge?.copyWith(
        color: primaryText,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.25,
      ),
      displayMedium: base.displayMedium?.copyWith(
        color: primaryText,
        fontWeight: FontWeight.w700,
      ),
      displaySmall: base.displaySmall?.copyWith(
        color: primaryText,
        fontWeight: FontWeight.w700,
      ),
      headlineLarge: base.headlineLarge?.copyWith(
        color: primaryText,
        fontWeight: FontWeight.w700,
      ),
      headlineMedium: base.headlineMedium?.copyWith(
        color: primaryText,
        fontWeight: FontWeight.w700,
      ),
      headlineSmall: base.headlineSmall?.copyWith(
        color: primaryText,
        fontWeight: FontWeight.w600,
      ),
      titleLarge: base.titleLarge?.copyWith(
        color: primaryText,
        fontSize: 20,
        fontWeight: FontWeight.w600,
      ),
      titleMedium: base.titleMedium?.copyWith(
        color: primaryText,
        fontSize: 16,
        fontWeight: FontWeight.w600,
      ),
      titleSmall: base.titleSmall?.copyWith(
        color: primaryText,
        fontSize: 14,
        fontWeight: FontWeight.w600,
      ),
      bodyLarge: base.bodyLarge?.copyWith(
        color: primaryText,
        fontSize: 16,
        height: 1.45,
      ),
      bodyMedium: base.bodyMedium?.copyWith(
        color: primaryText,
        fontSize: 14,
        height: 1.45,
      ),
      bodySmall: base.bodySmall?.copyWith(
        color: secondaryText,
        fontSize: 12,
        height: 1.4,
      ),
      labelLarge: base.labelLarge?.copyWith(
        color: primaryText,
        fontSize: 14,
        fontWeight: FontWeight.w600,
      ),
      labelMedium: base.labelMedium?.copyWith(
        color: secondaryText,
        fontSize: 12,
        fontWeight: FontWeight.w500,
      ),
      labelSmall: base.labelSmall?.copyWith(
        color: secondaryText,
        fontSize: 11,
        fontWeight: FontWeight.w500,
      ),
    );
  }

  // ===========================================================================
  // Temas Completos (Light & Dark)
  // ===========================================================================
  static ThemeData get lightTheme => _buildTheme(
        colorScheme: lightColorScheme,
        semanticColors: RewitSemanticColors.light,
        scaffoldBackground: backgroundColor,
        appBarBackground: backgroundColor,
        navBarBackground: surfaceColor,
        navSelectedForeground: primaryColor,
        cardBackground: surfaceColor,
        elevatedModalBackground: elevatedFocalColor,
        inputFillColor: elevatedFocalColor,
        actionButtonBackground: primaryColor,
        actionButtonForeground: const Color(0xFFFFFFFF),
        outlinedButtonForeground: primaryColor,
        outlinedButtonBorder: primaryColor,
        textButtonForeground: primaryColor,
        overlayStyle: lightSystemOverlayStyle,
      );

  static ThemeData get darkTheme => _buildTheme(
        colorScheme: darkColorScheme,
        semanticColors: RewitSemanticColors.dark,
        scaffoldBackground: darkBackgroundColor,
        appBarBackground: darkBackgroundColor,
        navBarBackground: const Color(0xFF121017),
        navSelectedForeground: darkLavenderDetailColor,
        cardBackground: darkSurfaceColor,
        elevatedModalBackground: darkElevatedSurfaceColor,
        inputFillColor: const Color(0xFF131118),
        actionButtonBackground: darkActionPrimaryColor,
        actionButtonForeground: const Color(0xFFFFFFFF),
        outlinedButtonForeground: darkLavenderDetailColor,
        outlinedButtonBorder: darkActionPrimaryColor,
        textButtonForeground: darkLavenderDetailColor,
        overlayStyle: darkSystemOverlayStyle,
      );

  static ThemeData _buildTheme({
    required ColorScheme colorScheme,
    required RewitSemanticColors semanticColors,
    required Color scaffoldBackground,
    required Color appBarBackground,
    required Color navBarBackground,
    required Color navSelectedForeground,
    required Color cardBackground,
    required Color elevatedModalBackground,
    required Color inputFillColor,
    required Color actionButtonBackground,
    required Color actionButtonForeground,
    required Color outlinedButtonForeground,
    required Color outlinedButtonBorder,
    required Color textButtonForeground,
    required SystemUiOverlayStyle overlayStyle,
  }) {
    final textTheme = _buildTextTheme(
      colorScheme.onSurface,
      colorScheme.onSurfaceVariant,
      colorScheme.brightness,
    );

    final disabledContainerColor = colorScheme.onSurface.withAlpha(30);
    final disabledForegroundColor = colorScheme.onSurface.withAlpha(110);

    return ThemeData(
      useMaterial3: true,
      brightness: colorScheme.brightness,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: scaffoldBackground,
      canvasColor: scaffoldBackground,
      dividerColor: colorScheme.outlineVariant,
      textTheme: textTheme,
      extensions: <ThemeExtension<dynamic>>[
        semanticColors,
      ],
      appBarTheme: AppBarTheme(
        backgroundColor: appBarBackground,
        foregroundColor: colorScheme.onSurface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: true,
        systemOverlayStyle: overlayStyle,
        iconTheme: IconThemeData(color: colorScheme.onSurface),
        actionsIconTheme: IconThemeData(color: colorScheme.onSurface),
        titleTextStyle: textTheme.titleLarge?.copyWith(
          color: colorScheme.onSurface,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: navBarBackground,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        indicatorColor: colorScheme.secondaryContainer,
        iconTheme: WidgetStateProperty.resolveWith(
          (states) => IconThemeData(
            color: states.contains(WidgetState.selected)
                ? navSelectedForeground
                : colorScheme.onSurfaceVariant,
          ),
        ),
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => TextStyle(
            fontSize: 12,
            color: states.contains(WidgetState.selected)
                ? navSelectedForeground
                : colorScheme.onSurfaceVariant,
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w600
                : FontWeight.w500,
          ),
        ),
      ),
      cardTheme: CardThemeData(
        color: cardBackground,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: RewitRadius.borderLg,
          side: BorderSide(color: colorScheme.outlineVariant),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: colorScheme.surfaceContainerLow,
        disabledColor: disabledContainerColor,
        selectedColor: colorScheme.secondaryContainer,
        secondarySelectedColor: colorScheme.secondaryContainer,
        surfaceTintColor: Colors.transparent,
        padding: const EdgeInsets.symmetric(
          horizontal: RewitSpacing.sm,
          vertical: RewitSpacing.xs,
        ),
        labelStyle: textTheme.labelMedium!.copyWith(
          color: colorScheme.onSurface,
          fontWeight: FontWeight.w500,
        ),
        secondaryLabelStyle: textTheme.labelMedium!.copyWith(
          color: colorScheme.onSecondaryContainer,
          fontWeight: FontWeight.w600,
        ),
        checkmarkColor: colorScheme.onSecondaryContainer,
        iconTheme: IconThemeData(
          color: colorScheme.onSurfaceVariant,
          size: 16,
        ),
        side: BorderSide(color: colorScheme.outlineVariant),
        shape: const RoundedRectangleBorder(
          borderRadius: RewitRadius.borderMd,
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: elevatedModalBackground,
        surfaceTintColor: Colors.transparent,
        elevation: 4,
        titleTextStyle: textTheme.titleLarge?.copyWith(
          color: colorScheme.onSurface,
          fontWeight: FontWeight.w700,
        ),
        contentTextStyle: textTheme.bodyMedium?.copyWith(
          color: colorScheme.onSurfaceVariant,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: RewitRadius.borderXl,
          side: BorderSide(color: colorScheme.outlineVariant),
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: elevatedModalBackground,
        modalBackgroundColor: elevatedModalBackground,
        surfaceTintColor: Colors.transparent,
        dragHandleColor: colorScheme.outline,
        elevation: 4,
        modalElevation: 6,
        shape: const RoundedRectangleBorder(
          borderRadius: RewitRadius.sheetTop,
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: colorScheme.inverseSurface,
        contentTextStyle: textTheme.bodyMedium?.copyWith(
          color: colorScheme.onInverseSurface,
          fontWeight: FontWeight.w500,
        ),
        actionTextColor: colorScheme.inversePrimary,
        closeIconColor: colorScheme.onInverseSurface,
        elevation: 3,
        shape: const RoundedRectangleBorder(
          borderRadius: RewitRadius.borderMd,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: actionButtonBackground,
          foregroundColor: actionButtonForeground,
          disabledBackgroundColor: disabledContainerColor,
          disabledForegroundColor: disabledForegroundColor,
          minimumSize: const Size(64, 44),
          padding: const EdgeInsets.symmetric(
            horizontal: RewitSpacing.lg,
            vertical: RewitSpacing.md,
          ),
          shape: const RoundedRectangleBorder(
            borderRadius: RewitRadius.borderMd,
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: actionButtonBackground,
          foregroundColor: actionButtonForeground,
          disabledBackgroundColor: disabledContainerColor,
          disabledForegroundColor: disabledForegroundColor,
          surfaceTintColor: Colors.transparent,
          minimumSize: const Size(64, 44),
          padding: const EdgeInsets.symmetric(
            horizontal: RewitSpacing.lg,
            vertical: RewitSpacing.md,
          ),
          shape: const RoundedRectangleBorder(
            borderRadius: RewitRadius.borderMd,
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
          elevation: 0,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: outlinedButtonForeground,
          disabledForegroundColor: disabledForegroundColor,
          minimumSize: const Size(64, 44),
          padding: const EdgeInsets.symmetric(
            horizontal: RewitSpacing.lg,
            vertical: RewitSpacing.md,
          ),
          side: BorderSide(color: outlinedButtonBorder),
          shape: const RoundedRectangleBorder(
            borderRadius: RewitRadius.borderMd,
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: textButtonForeground,
          disabledForegroundColor: disabledForegroundColor,
          minimumSize: const Size(48, 40),
          padding: const EdgeInsets.symmetric(
            horizontal: RewitSpacing.md,
            vertical: RewitSpacing.sm,
          ),
          shape: const RoundedRectangleBorder(
            borderRadius: RewitRadius.borderMd,
          ),
          textStyle: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: inputFillColor,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: RewitSpacing.lg,
          vertical: 14,
        ),
        border: OutlineInputBorder(
          borderRadius: RewitRadius.borderMd,
          borderSide: BorderSide(color: colorScheme.outlineVariant),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: RewitRadius.borderMd,
          borderSide: BorderSide(color: colorScheme.outlineVariant),
        ),
        disabledBorder: OutlineInputBorder(
          borderRadius: RewitRadius.borderMd,
          borderSide: BorderSide(color: colorScheme.outlineVariant.withAlpha(120)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: RewitRadius.borderMd,
          borderSide: BorderSide(color: colorScheme.primary, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: RewitRadius.borderMd,
          borderSide: BorderSide(color: colorScheme.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: RewitRadius.borderMd,
          borderSide: BorderSide(color: colorScheme.error, width: 2),
        ),
        labelStyle: TextStyle(
          color: colorScheme.onSurfaceVariant,
          fontSize: 14,
        ),
        hintStyle: TextStyle(
          color: colorScheme.onSurfaceVariant,
          fontSize: 14,
        ),
        errorStyle: TextStyle(
          color: colorScheme.error,
          fontSize: 12,
        ),
        prefixIconColor: colorScheme.onSurfaceVariant,
        suffixIconColor: colorScheme.onSurfaceVariant,
        errorMaxLines: 2,
      ),
      dividerTheme: DividerThemeData(
        color: colorScheme.outlineVariant,
        thickness: 1,
        space: 1,
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: elevatedModalBackground,
        surfaceTintColor: Colors.transparent,
        elevation: 4,
        textStyle: textTheme.bodyMedium?.copyWith(color: colorScheme.onSurface),
        shape: RoundedRectangleBorder(
          borderRadius: RewitRadius.borderLg,
          side: BorderSide(color: colorScheme.outlineVariant),
        ),
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: actionButtonBackground,
        foregroundColor: actionButtonForeground,
        elevation: 2,
        shape: const RoundedRectangleBorder(
          borderRadius: RewitRadius.borderXl,
        ),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: colorScheme.primary,
        circularTrackColor: colorScheme.surfaceContainerHighest,
        linearTrackColor: colorScheme.surfaceContainerHighest,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) {
            return colorScheme.onSurface.withAlpha(96);
          }
          if (states.contains(WidgetState.selected)) {
            return actionButtonForeground;
          }
          return colorScheme.outline;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.disabled)) {
            return disabledContainerColor;
          }
          if (states.contains(WidgetState.selected)) {
            return actionButtonBackground;
          }
          return colorScheme.surfaceContainerHighest;
        }),
      ),
      tabBarTheme: TabBarThemeData(
        labelColor: navSelectedForeground,
        unselectedLabelColor: colorScheme.onSurfaceVariant,
        indicatorColor: navSelectedForeground,
        dividerColor: colorScheme.outlineVariant,
      ),
    );
  }
}
