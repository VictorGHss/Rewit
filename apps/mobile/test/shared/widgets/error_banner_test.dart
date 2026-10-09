import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

void main() {
  Widget buildSubject({
    required String message,
    int? retryAfterSeconds,
    bool isSuccess = false,
    Widget? footer,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: ErrorBanner(
          message: message,
          retryAfterSeconds: retryAfterSeconds,
          isSuccess: isSuccess,
          footer: footer,
        ),
      ),
    );
  }

  group('ErrorBanner Widget Tests', () {
    testWidgets('exibe mensagem de erro padrão com ícone de erro', (tester) async {
      await tester.pumpWidget(buildSubject(message: 'Credenciais inválidas.'));

      expect(find.text('Credenciais inválidas.'), findsOneWidget);
      expect(find.byIcon(Icons.error_outline), findsOneWidget);
      expect(find.byIcon(Icons.check_circle_outline), findsNothing);
    });

    testWidgets('exibe mensagem de sucesso com ícone de sucesso quando isSuccess = true', (tester) async {
      await tester.pumpWidget(buildSubject(
        message: 'Senha alterada com sucesso!',
        isSuccess: true,
      ));

      expect(find.text('Senha alterada com sucesso!'), findsOneWidget);
      expect(find.byIcon(Icons.check_circle_outline), findsOneWidget);
      expect(find.byIcon(Icons.error_outline), findsNothing);
    });

    testWidgets('exibe aviso de rate limit formatado quando retryAfterSeconds é fornecido', (tester) async {
      await tester.pumpWidget(buildSubject(
        message: 'Muitas requisições.',
        retryAfterSeconds: 45,
      ));

      expect(find.text('Muitas requisições.'), findsOneWidget);
      expect(find.text('Aguarde 45 segundos antes de tentar novamente.'), findsOneWidget);
    });

    testWidgets('formata singular quando retryAfterSeconds é 1', (tester) async {
      await tester.pumpWidget(buildSubject(
        message: 'Aguarde um momento.',
        retryAfterSeconds: 1,
      ));

      expect(find.text('Aguarde 1 segundo antes de tentar novamente.'), findsOneWidget);
    });

    testWidgets('renderiza footer customizado quando informado', (tester) async {
      await tester.pumpWidget(buildSubject(
        message: 'Erro com ação.',
        footer: const Text('Footer customizado'),
      ));

      expect(find.text('Footer customizado'), findsOneWidget);
    });

    test('formatRetryAfter lida com plural e singular', () {
      expect(ErrorBanner.formatRetryAfter(0), 'Aguarde 1 segundo antes de tentar novamente.');
      expect(ErrorBanner.formatRetryAfter(1), 'Aguarde 1 segundo antes de tentar novamente.');
      expect(ErrorBanner.formatRetryAfter(2), 'Aguarde 2 segundos antes de tentar novamente.');
      expect(ErrorBanner.formatRetryAfter(60), 'Aguarde 60 segundos antes de tentar novamente.');
    });
  });
}
