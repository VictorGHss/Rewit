import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/app/theme/theme_controller.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';

class _FakeFlutterSecureStorage extends FlutterSecureStorage {
  final Map<String, String> data = {};
  bool shouldThrow = false;

  _FakeFlutterSecureStorage() : super();

  @override
  Future<String?> read({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (shouldThrow) throw Exception('Storage read error');
    return data[key];
  }

  @override
  Future<void> write({
    required String key,
    required String? value,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (shouldThrow) throw Exception('Storage write error');
    if (value != null) {
      data[key] = value;
    } else {
      data.remove(key);
    }
  }

  @override
  Future<void> delete({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (shouldThrow) throw Exception('Storage delete error');
    data.remove(key);
  }
}

class _FakeAuthRepository implements AuthRepository {
  AuthUserDto currentUser = const AuthUserDto(
    id: 'user-1',
    email: 'ana@rewit.com',
    handle: 'anapaula',
    displayName: 'Ana Paula',
    isVerified: true,
    reputationScore: 100,
  );
  bool storedSession = true;

  Authenticated get _authenticated => Authenticated(
        user: currentUser,
        tokens: const AuthTokens(accessToken: 'acc', refreshToken: 'ref'),
      );

  @override
  Future<bool> hasStoredSession() async => storedSession;

  @override
  Future<AuthUserDto> getMe() async => currentUser;

  @override
  Future<Authenticated> login({
    required String email,
    required String password,
  }) async =>
      _authenticated;

  @override
  Future<Authenticated> refreshTokens() async => _authenticated;

  @override
  Future<void> logout() async {
    storedSession = false;
  }

  @override
  Future<void> deactivateAccount() async {
    storedSession = false;
  }

  @override
  Future<Authenticated> reactivate({
    required String email,
    required String password,
  }) async =>
      _authenticated;

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {}

  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) async =>
      _authenticated;

  @override
  Future<String> requestSocialNonce() async => 'nonce';

  @override
  Future<Authenticated> signInWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) async =>
      _authenticated;

  @override
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) async =>
      _authenticated;
}

Matcher _optionSemantics({required bool isSelected}) => matchesSemantics(
      isButton: true,
      hasSelectedState: true,
      isSelected: isSelected,
      isFocusable: true,
      hasTapAction: true,
      hasFocusAction: true,
    );

void main() {
  group('3. ThemeMode.system como padrão inicial', () {
    test('inicia em ThemeMode.system antes e depois de load() quando não há preferência salva', () async {
      final storage = InMemoryThemePreferenceStorage();
      final controller = ThemeController(storage: storage);

      expect(controller.themeMode, ThemeMode.system);
      await controller.load();
      expect(controller.themeMode, ThemeMode.system);
    });
  });

  group('4 & 5. Seleção manual, persistência local e independência de sessão', () {
    test('seleciona manualmente claro, escuro e sistema e recupera preferência após reinicialização', () async {
      final fakeNativeStorage = _FakeFlutterSecureStorage();
      final prefStorage = SecureThemePreferenceStorage(storage: fakeNativeStorage);
      final firstSessionController = ThemeController(storage: prefStorage);

      await firstSessionController.load();
      expect(firstSessionController.themeMode, ThemeMode.system);

      await firstSessionController.setThemeMode(ThemeMode.dark);
      expect(firstSessionController.themeMode, ThemeMode.dark);
      expect(fakeNativeStorage.data[SecureThemePreferenceStorage.storageKey], 'dark');

      // Simula reinicialização completa do app criando nova instância de controller e storage sobre os dados persistidos
      final restartedStorage = SecureThemePreferenceStorage(storage: fakeNativeStorage);
      final restartedController = ThemeController(storage: restartedStorage);
      expect(restartedController.themeMode, ThemeMode.system);
      await restartedController.load();
      expect(restartedController.themeMode, ThemeMode.dark);

      await restartedController.setThemeMode(ThemeMode.light);
      expect(restartedController.themeMode, ThemeMode.light);

      final thirdController = ThemeController(
        storage: SecureThemePreferenceStorage(storage: fakeNativeStorage),
      );
      await thirdController.load();
      expect(thirdController.themeMode, ThemeMode.light);
    });

    test('ignora valores inválidos e opera com fallback em memória se o storage nativo falhar', () async {
      final fakeNativeStorage = _FakeFlutterSecureStorage();
      fakeNativeStorage.data[SecureThemePreferenceStorage.storageKey] = 'invalid_value';
      final prefStorage = SecureThemePreferenceStorage(storage: fakeNativeStorage);
      final controller = ThemeController(storage: prefStorage);

      await controller.load();
      expect(controller.themeMode, ThemeMode.system);

      fakeNativeStorage.shouldThrow = true;
      await controller.setThemeMode(ThemeMode.dark);
      expect(controller.themeMode, ThemeMode.dark);
      expect(await prefStorage.readThemeMode(), ThemeMode.dark);
    });

    test('preferência de aparência não é afetada por logout nem troca de conta no mesmo dispositivo', () async {
      final sharedNativeStorage = _FakeFlutterSecureStorage();
      final tokenStorage = SecureTokenStorage(storage: sharedNativeStorage);
      final themeStorage = SecureThemePreferenceStorage(storage: sharedNativeStorage);
      final themeController = ThemeController(storage: themeStorage);

      await tokenStorage.saveTokens(
        accessToken: 'jwt-conta-1',
        refreshToken: 'refresh-conta-1',
      );
      await themeController.setThemeMode(ThemeMode.dark);

      // Logout limpa tokens da conta, mas preserva a preferência visual local do aparelho
      await tokenStorage.clearTokens();
      expect(await tokenStorage.hasAccessToken(), isFalse);
      expect(await themeStorage.readThemeMode(), ThemeMode.dark);

      // Troca de sessão para outra conta mantém a aparência escolhida
      final authRepo = _FakeAuthRepository();
      final authNotifier = AuthNotifier(
        authRepository: authRepo,
        clearLocalSession: tokenStorage.clearTokens,
      );
      await authNotifier.checkAuthStatus();
      await authNotifier.logout();
      authRepo.storedSession = true;
      authRepo.currentUser = const AuthUserDto(
        id: 'user-2',
        email: 'bruno@rewit.com',
        handle: 'brunolima',
        displayName: 'Bruno Lima',
        isVerified: false,
        reputationScore: 10,
      );
      await authNotifier.checkAuthStatus();

      expect(themeController.themeMode, ThemeMode.dark);
      final reloaded = ThemeController(storage: themeStorage);
      await reloaded.load();
      expect(reloaded.themeMode, ThemeMode.dark);
      authNotifier.dispose();
    });

    testWidgets('seletor acessível em AccountSettingsScreen exibe seleção atual e altera ThemeController',
        (tester) async {
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      final authRepo = _FakeAuthRepository();
      final authNotifier = AuthNotifier(authRepository: authRepo);
      await authNotifier.checkAuthStatus();
      final themeController = ThemeController(storage: InMemoryThemePreferenceStorage());

      final semanticsHandle = tester.ensureSemantics();
      try {
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            darkTheme: AppTheme.darkTheme,
            themeMode: ThemeMode.light,
            home: AccountSettingsScreen(
              authNotifier: authNotifier,
              themeController: themeController,
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.text('Aparência'), findsOneWidget);
        expect(find.text('Seguir o sistema'), findsOneWidget);
        expect(find.text('Claro'), findsOneWidget);
        expect(find.text('Escuro'), findsOneWidget);

        final systemFinder = find.byKey(const Key('theme_mode_option_system'));
        final lightFinder = find.byKey(const Key('theme_mode_option_light'));
        final darkFinder = find.byKey(const Key('theme_mode_option_dark'));

        expect(tester.getSemantics(systemFinder), _optionSemantics(isSelected: true));
        expect(tester.getSemantics(lightFinder), _optionSemantics(isSelected: false));
        expect(tester.getSemantics(darkFinder), _optionSemantics(isSelected: false));

        await tester.tap(darkFinder);
        await tester.pumpAndSettle();

        expect(themeController.themeMode, ThemeMode.dark);
        expect(tester.getSemantics(darkFinder), _optionSemantics(isSelected: true));
        expect(tester.getSemantics(systemFinder), _optionSemantics(isSelected: false));

        await tester.tap(lightFinder);
        await tester.pumpAndSettle();

        expect(themeController.themeMode, ThemeMode.light);
        expect(tester.getSemantics(lightFinder), _optionSemantics(isSelected: true));
      } finally {
        semanticsHandle.dispose();
        authNotifier.dispose();
      }
    });
  });

  group('6. Atualização imediata sem reinicialização em RewitApp', () {
    testWidgets('alterar ThemeController atualiza MaterialApp em tempo real sem perder estado ou rota',
        (tester) async {
      final authRepo = _FakeAuthRepository();
      final authNotifier = AuthNotifier(authRepository: authRepo);
      await authNotifier.checkAuthStatus();

      final themeController = ThemeController(storage: InMemoryThemePreferenceStorage());
      final router = AppRouter(
        authNotifier: authNotifier,
        themeController: themeController,
      );

      await tester.pumpWidget(
        RewitApp(
          config: const AppConfig(apiBaseUrl: 'http://localhost:8080'),
          router: router,
          themeController: themeController,
        ),
      );
      await tester.pumpAndSettle();

      MaterialApp materialApp() => tester.widget<MaterialApp>(find.byType(MaterialApp));
      expect(materialApp().themeMode, ThemeMode.system);
      expect(materialApp().theme?.scaffoldBackgroundColor, AppTheme.backgroundColor);
      expect(materialApp().darkTheme?.scaffoldBackgroundColor, AppTheme.darkBackgroundColor);

      // Navega até Configurações da Conta e altera aparência em tempo real
      final NavigatorState nav = tester.state<NavigatorState>(find.byType(Navigator));
      nav.pushNamed(AppRouter.accountSettings);
      await tester.pumpAndSettle();
      expect(find.byType(AccountSettingsScreen), findsOneWidget);

      final darkOption = find.byKey(const Key('theme_mode_option_dark'));
      await tester.ensureVisible(darkOption);
      await tester.tap(darkOption);
      await tester.pumpAndSettle();

      expect(materialApp().themeMode, ThemeMode.dark);
      // Continua na mesma rota sem reinicializar o navegador
      expect(find.byType(AccountSettingsScreen), findsOneWidget);
      final scaffold = tester.widget<Scaffold>(find.byType(Scaffold).last);
      final resolvedContext = tester.element(find.byType(AccountSettingsScreen));
      expect(Theme.of(resolvedContext).brightness, Brightness.dark);
      expect(
        scaffold.backgroundColor ?? Theme.of(resolvedContext).scaffoldBackgroundColor,
        AppTheme.darkBackgroundColor,
      );

      // Alterna para Claro em tempo real
      final lightOption = find.byKey(const Key('theme_mode_option_light'));
      await tester.ensureVisible(lightOption);
      await tester.tap(lightOption);
      await tester.pumpAndSettle();

      expect(materialApp().themeMode, ThemeMode.light);
      expect(Theme.of(tester.element(find.byType(AccountSettingsScreen))).brightness, Brightness.light);
      authNotifier.dispose();
    });
  });
}
