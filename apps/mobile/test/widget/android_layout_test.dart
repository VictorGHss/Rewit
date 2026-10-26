import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';

class _StubAuthRepo implements AuthRepository {
  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<String> requestSocialNonce() => throw UnimplementedError();

  @override
  Future<Authenticated> signInWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<bool> hasStoredSession() async => true;
  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'user-1',
        email: 'ana@rewit.com',
        handle: 'ana_paula_com_um_handle_bem_longo',
        displayName: 'Ana Paula de Souza e Silva Albuquerque',
        isVerified: true,
        reputationScore: 123456,
      );
  @override
  Future<Authenticated> login({required String email, required String password}) async => throw UnimplementedError();
  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();
  @override
  Future<void> logout() async {}
  @override
  Future<Authenticated> reactivate({required String email, required String password}) async =>
      throw UnimplementedError();
  @override
  Future<void> deactivateAccount() async {}
  @override
  Future<void> changePassword({required String currentPassword, required String newPassword}) async {}
}

void _setScreen(WidgetTester tester, double width, {double height = 800}) {
  tester.view.physicalSize = Size(width, height);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Widget _withTextScale(Widget child, double scale) {
  return MaterialApp(
    theme: AppTheme.lightTheme,
    onGenerateRoute: (settings) => MaterialPageRoute(builder: (_) => Scaffold(body: Text('rota ${settings.name}'))),
    builder: (context, app) => MediaQuery(
      data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)),
      child: app!,
    ),
    home: child,
  );
}

void main() {
  group('HomeScreen: cabeçalho e navegação', () {
    late AuthNotifier notifier;

    setUp(() async {
      notifier = AuthNotifier(authRepository: _StubAuthRepo());
      await notifier.checkAuthStatus();
    });

    for (final width in [320.0, 360.0, 412.0]) {
      testWidgets('título "Rewit" fica centralizado na largura $width', (tester) async {
        _setScreen(tester, width);
        await tester.pumpWidget(_withTextScale(HomeScreen(authNotifier: notifier), 1.0));
        await tester.pumpAndSettle();

        final appBar = tester.widget<AppBar>(find.byType(AppBar));
        expect(appBar.centerTitle, isTrue);
        expect(tester.getCenter(find.text('Rewit')).dx, moreOrLessEquals(width / 2, epsilon: 1.0));
      });
    }

    testWidgets('cabeçalho não transborda em tela estreita com fonte ampliada', (tester) async {
      _setScreen(tester, 320);
      await tester.pumpWidget(_withTextScale(HomeScreen(authNotifier: notifier), 1.5));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Rewit'), findsOneWidget);
    });

    testWidgets('tocar no cartão do usuário abre a aba Perfil e voltar retorna ao Início', (tester) async {
      _setScreen(tester, 360);
      await tester.pumpWidget(_withTextScale(HomeScreen(authNotifier: notifier), 1.0));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('home_user_header_card')));
      await tester.pumpAndSettle();
      expect(tester.widget<IndexedStack>(find.byType(IndexedStack)).index, 3);
      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 3);

      // Voltar do Android numa aba que não é o Início volta ao Início, sem fechar o app
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(tester.widget<IndexedStack>(find.byType(IndexedStack)).index, 0);
      expect(find.byType(HomeScreen), findsOneWidget);
    });

    testWidgets('AppBar não tem atalho redundante de perfil; scanner à esquerda e menu à direita', (tester) async {
      _setScreen(tester, 360);
      await tester.pumpWidget(_withTextScale(HomeScreen(authNotifier: notifier), 1.0));
      await tester.pumpAndSettle();

      final appBar = find.byType(AppBar);
      expect(find.descendant(of: appBar, matching: find.byTooltip('Meu perfil')), findsNothing);
      expect(find.descendant(of: appBar, matching: find.byIcon(Icons.account_circle_outlined)), findsNothing);
      expect(find.byKey(const Key('home_open_profile_button')), findsNothing);

      final scanner = find.descendant(of: appBar, matching: find.byTooltip('Escanear Código'));
      expect(scanner, findsOneWidget);
      expect(tester.getCenter(scanner).dx, lessThan(360 / 2));
      final more = find.descendant(of: appBar, matching: find.byTooltip('Mais opções'));
      expect(more, findsOneWidget);
      expect(tester.getCenter(more).dx, greaterThan(360 / 2));
    });

    testWidgets('a aba Perfil da navegação inferior abre o próprio perfil', (tester) async {
      _setScreen(tester, 360);
      await tester.pumpWidget(_withTextScale(HomeScreen(authNotifier: notifier), 1.0));
      await tester.pumpAndSettle();

      await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Perfil')));
      await tester.pumpAndSettle();
      expect(tester.widget<IndexedStack>(find.byType(IndexedStack)).index, 3);
      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 3);
    });

    testWidgets('scanner do AppBar continua abrindo a rota do scanner', (tester) async {
      _setScreen(tester, 360);
      await tester.pumpWidget(_withTextScale(HomeScreen(authNotifier: notifier), 1.0));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('home_scanner_button')));
      await tester.pumpAndSettle();
      expect(find.text('rota ${AppRouter.scanner}'), findsOneWidget);
    });

    testWidgets('menu "Mais opções" leva às configurações da conta', (tester) async {
      _setScreen(tester, 360);
      await tester.pumpWidget(_withTextScale(HomeScreen(authNotifier: notifier), 1.0));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Mais opções'));
      await tester.pumpAndSettle();
      expect(find.text('Sair da Conta'), findsOneWidget);
      await tester.tap(find.text('Configurações da Conta'));
      await tester.pumpAndSettle();

      expect(find.text('rota ${AppRouter.accountSettings}'), findsOneWidget);
    });
  });

  group('ReviewCard: visibilidade, voto útil e quebra de texto', () {
    FeedReview review({
      String id = 'rev-1',
      bool anonymous = false,
      String visibility = 'PUBLIC',
      bool verified = false,
      int helpful = 0,
      List<FeedTarget> targets = const [],
    }) {
      return FeedReview(
        id: id,
        author: anonymous
            ? const FeedAuthor(displayName: 'Anônimo', isAnonymous: true)
            : const FeedAuthor(id: 'a1', handle: 'autor', displayName: 'Autor com um nome realmente comprido'),
        experienceText: 'Texto da avaliação',
        visibility: visibility,
        status: 'ACTIVE',
        isAnonymous: anonymous,
        isVerifiedOnSite: verified,
        helpfulCount: helpful,
        isHelpfulByMe: false,
        targets: targets,
        createdAt: DateTime(2026, 10, 6),
      );
    }

    Widget card(FeedReview r, {VoidCallback? onTap, VoidCallback? onHelpfulTap, double scale = 1.0}) {
      return _withTextScale(
        Scaffold(body: SingleChildScrollView(child: ReviewCard(review: r, onTap: onTap, onHelpfulTap: onHelpfulTap))),
        scale,
      );
    }

    final cases = {
      'PUBLIC identificada': (false, 'PUBLIC', 'Público'),
      'FOLLOWERS identificada': (false, 'FOLLOWERS', 'Seguidores'),
      'PRIVATE identificada': (false, 'PRIVATE', 'Somente você'),
      'PRIVATE anônima': (true, 'PRIVATE', 'Somente você'),
      'PUBLIC anônima': (true, 'PUBLIC', 'Público'),
      'linha antiga anônima + FOLLOWERS': (true, 'FOLLOWERS', 'Somente você'),
    };
    cases.forEach((name, data) {
      testWidgets('rótulo de visibilidade para $name é "${data.$3}"', (tester) async {
        await tester.pumpWidget(card(review(anonymous: data.$1, visibility: data.$2)));
        expect(find.byKey(const Key('review_card_visibility_rev-1')), findsOneWidget);
        expect(tester.widget<Text>(find.byKey(const Key('review_card_visibility_rev-1'))).data, data.$3);
      });
    });

    testWidgets('PRIVATE nunca aparece como público', (tester) async {
      await tester.pumpWidget(card(review(visibility: 'PRIVATE')));
      expect(find.text('Público'), findsNothing);
    });

    testWidgets('linha antiga anônima + FOLLOWERS explica a visibilidade efetiva na dica', (tester) async {
      await tester.pumpWidget(card(review(anonymous: true, visibility: 'FOLLOWERS')));
      expect(
        find.byTooltip('Visível somente para você. Avaliações anônimas não podem ser restritas a seguidores.'),
        findsOneWidget,
      );
    });

    testWidgets('voto útil tem área de toque de 48 dp e não aciona o toque no card', (tester) async {
      var cardTaps = 0;
      var helpfulTaps = 0;
      await tester.pumpWidget(card(
        review(helpful: 3),
        onTap: () => cardTaps++,
        onHelpfulTap: () => helpfulTaps++,
      ));

      final target = find.byKey(const Key('review_card_helpful_rev-1'));
      expect(tester.getSize(target).height, greaterThanOrEqualTo(48));
      expect(tester.getSize(target).width, greaterThanOrEqualTo(48));

      await tester.tap(target);
      expect(helpfulTaps, 1);
      expect(cardTaps, 0);
    });

    testWidgets('selos de presença, contador e rodapé não transbordam em 280 dp com fonte em 2x', (tester) async {
      _setScreen(tester, 280, height: 1600);
      await tester.pumpWidget(card(
        review(
          verified: true,
          helpful: 123456,
          visibility: 'FOLLOWERS',
          targets: const [
            FeedTarget(id: 'rt1', targetId: 't1', rating: 4.5, targetType: 'PLACE'),
            FeedTarget(id: 'rt2', targetId: 't2', rating: 3.0, targetType: 'PRODUCT'),
          ],
        ),
        scale: 2.0,
      ));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('Presença confirmada no local'), findsOneWidget);
      expect(find.text('123456 úteis'), findsOneWidget);
      expect(find.text('Seguidores'), findsOneWidget);
    });
  });
}
