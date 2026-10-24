import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';

/// Contraste WCAG entre duas cores opacas.
double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  final lighter = la > lb ? la : lb;
  final darker = la > lb ? lb : la;
  return (lighter + 0.05) / (darker + 0.05);
}

bool _isTealLike(Color color) {
  final hsl = HSLColor.fromColor(color);
  // Verde-água/teal: matiz entre ~150° e ~190° com saturação perceptível
  return hsl.saturation > 0.25 && hsl.hue >= 150 && hsl.hue <= 190;
}

void main() {
  final theme = AppTheme.lightTheme;
  final scheme = theme.colorScheme;
  final nav = theme.navigationBarTheme;

  Color iconColor(Set<WidgetState> states) => nav.iconTheme!.resolve(states)!.color!;
  Color labelColor(Set<WidgetState> states) => nav.labelTextStyle!.resolve(states)!.color!;

  group('AppTheme: identidade azul', () {
    test('secundário e containers ficam na família azul, sem o antigo verde-água #0D9488', () {
      expect(scheme.primary, const Color(0xFF1E3A8A));
      expect(scheme.secondary, const Color(0xFF1D4ED8));
      expect(scheme.secondaryContainer, AppTheme.accentContainerColor);
      expect(scheme.onSecondaryContainer, const Color(0xFF1E3A8A));
      for (final color in [scheme.secondary, scheme.secondaryContainer, scheme.onSecondaryContainer]) {
        expect(color, isNot(const Color(0xFF0D9488)));
        expect(_isTealLike(color), isFalse, reason: 'cor ${color.toARGB32().toRadixString(16)}');
      }
    });

    test('indicador da navegação inferior é azul claro com ícone e rótulo azul-escuro', () {
      expect(nav.indicatorColor, AppTheme.accentContainerColor);
      expect(_isTealLike(nav.indicatorColor!), isFalse);
      expect(iconColor({WidgetState.selected}), AppTheme.primaryColor);
      expect(labelColor({WidgetState.selected}), AppTheme.primaryColor);
      expect(iconColor({}), AppTheme.textSecondaryColor);
      expect(labelColor({}), AppTheme.textSecondaryColor);
    });

    test('contraste acessível: selecionado sobre o indicador e não selecionado sobre a barra', () {
      expect(_contrast(AppTheme.primaryColor, nav.indicatorColor!), greaterThanOrEqualTo(4.5));
      expect(_contrast(AppTheme.textSecondaryColor, nav.backgroundColor!), greaterThanOrEqualTo(4.5));
    });
  });

  testWidgets('NavigationBar renderizada usa o indicador azul do tema', (tester) async {
    await tester.pumpWidget(MaterialApp(
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
    ));

    final indicator = tester.widget<NavigationIndicator>(find.byType(NavigationIndicator).first);
    expect(indicator.color, AppTheme.accentContainerColor);
    expect(_isTealLike(indicator.color!), isFalse);
  });
}
