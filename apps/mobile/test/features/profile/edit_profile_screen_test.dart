import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/profile/domain/entities/follow_user_summary.dart';
import 'package:rewit_mobile/features/profile/domain/entities/update_profile_input.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/edit_profile_screen.dart';

class FakeAuthRepoForEdit implements AuthRepository {
  AuthUserDto user;

  FakeAuthRepoForEdit({
    this.user = const AuthUserDto(
      id: 'usr-123',
      email: 'eu@rewit.app',
      handle: 'meu_handle',
      displayName: 'Meu Nome Original',
      isVerified: true,
      isAnonymousDefault: false,
      reputationScore: 50,
    ),
  });

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => user;

  @override
  Future<Authenticated> login({required String email, required String password}) async {
    return Authenticated(
      user: user,
      tokens: const AuthTokens(accessToken: 'access-123', refreshToken: 'refresh-123'),
    );
  }

  @override
  Future<Authenticated> refreshTokens() async => Authenticated(
        user: user,
        tokens: const AuthTokens(accessToken: 'access-123', refreshToken: 'refresh-123'),
      );

  @override
  Future<void> logout() async {}

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async {
    return Authenticated(
      user: user,
      tokens: const AuthTokens(accessToken: 'access-123', refreshToken: 'refresh-123'),
    );
  }
}

class FakeProfileRepoForEdit implements UserProfileRepository {
  UpdateProfileInput? capturedInput;
  int updateCallCount = 0;
  bool shouldDelay = false;
  ApiException? apiExceptionToThrow;
  NetworkException? networkExceptionToThrow;
  UserProfile? customReturn;

  @override
  Future<UserProfile> updateMyProfile(UpdateProfileInput input) async {
    updateCallCount++;
    capturedInput = input;

    if (shouldDelay) {
      await Future.delayed(const Duration(milliseconds: 200));
    }
    if (apiExceptionToThrow != null) {
      throw apiExceptionToThrow!;
    }
    if (networkExceptionToThrow != null) {
      throw networkExceptionToThrow!;
    }

    return customReturn ??
        UserProfile(
          id: 'usr-123',
          handle: input.handle,
          displayName: input.displayName,
          bio: input.bio,
          isAnonymousDefault: input.isAnonymousDefault,
          stats: const UserStats(),
        );
  }

  @override
  Future<UserProfile> getUserProfile(String userId) async {
    throw UnimplementedError();
  }

  @override
  Future<bool> followUser(String userId) async => true;

  @override
  Future<bool> unfollowUser(String userId) async => false;

  @override
  Future<PagedFollowUsers> getFollowers(String userId, {int page = 0, int size = 10}) async {
    throw UnimplementedError();
  }

  @override
  Future<PagedFollowUsers> getFollowing(String userId, {int page = 0, int size = 10}) async {
    throw UnimplementedError();
  }
}

void main() {
  group('EditProfileScreen Widget Tests', () {
    late FakeAuthRepoForEdit authRepo;
    late AuthNotifier authNotifier;
    late FakeProfileRepoForEdit profileRepo;

    const sampleInitialProfile = UserProfile(
      id: 'usr-123',
      handle: 'handle_inicial',
      displayName: 'Nome Inicial',
      bio: 'Minha bio existente',
      isAnonymousDefault: true,
      stats: UserStats(followersCount: 10),
    );

    setUp(() async {
      authRepo = FakeAuthRepoForEdit();
      authNotifier = AuthNotifier(authRepository: authRepo);
      await authNotifier.checkAuthStatus();
      profileRepo = FakeProfileRepoForEdit();
    });

    Future<void> pumpScreen(
      WidgetTester tester, {
      UserProfile? initialProfile,
    }) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: EditProfileScreen(
            initialProfile: initialProfile,
            repository: profileRepo,
            authNotifier: authNotifier,
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('preenche campos com dados do initialProfile', (tester) async {
      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      expect(find.text('handle_inicial'), findsOneWidget);
      expect(find.text('Nome Inicial'), findsOneWidget);
      expect(find.text('Minha bio existente'), findsOneWidget);

      final switchWidget = tester.widget<SwitchListTile>(
        find.byKey(const Key('edit_profile_anonymous_switch')),
      );
      expect(switchWidget.value, isTrue);
    });

    testWidgets('preenche campos com fallback do authNotifier quando initialProfile for nulo', (tester) async {
      await pumpScreen(tester, initialProfile: null);

      expect(find.text('meu_handle'), findsOneWidget);
      expect(find.text('Meu Nome Original'), findsOneWidget);
      expect(find.byKey(const Key('edit_profile_bio_field')), findsOneWidget);

      final switchWidget = tester.widget<SwitchListTile>(
        find.byKey(const Key('edit_profile_anonymous_switch')),
      );
      expect(switchWidget.value, isFalse);
    });

    testWidgets('validação do handle: exibe mensagens de erro em entradas inválidas', (tester) async {
      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      // 1. Vazio
      await tester.enterText(find.byKey(const Key('edit_profile_handle_field')), '');
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('Informe seu nome de usuário'), findsOneWidget);

      // 2. Curto (<3)
      await tester.enterText(find.byKey(const Key('edit_profile_handle_field')), 'ab');
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('O handle deve ter no mínimo 3 caracteres'), findsOneWidget);

      // 3. Longo (>30)
      await tester.enterText(find.byKey(const Key('edit_profile_handle_field')), 'a' * 31);
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('O handle deve ter no máximo 30 caracteres'), findsOneWidget);

      // 4. Caracteres proibidos (ex: espaços ou traços)
      await tester.enterText(find.byKey(const Key('edit_profile_handle_field')), 'nome-invalido!');
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('Use apenas letras, números e sublinhado (_)'), findsOneWidget);
      expect(profileRepo.updateCallCount, 0);
    });

    testWidgets('validação do displayName: exibe mensagens de erro em entradas inválidas', (tester) async {
      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      // 1. Vazio
      await tester.enterText(find.byKey(const Key('edit_profile_display_name_field')), '   ');
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('Informe seu nome de exibição'), findsOneWidget);

      // 2. Curto (<2)
      await tester.enterText(find.byKey(const Key('edit_profile_display_name_field')), 'A');
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('O nome deve ter no mínimo 2 caracteres'), findsOneWidget);
      expect(profileRepo.updateCallCount, 0);
    });

    testWidgets('bio exibe contador visual de até 500 caracteres', (tester) async {
      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      expect(find.text('19/500'), findsOneWidget); // 'Minha bio existente' tem 19 caracteres
    });

    testWidgets('switch de avaliações anônimas por padrão alterna valor', (tester) async {
      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      expect(
        tester.widget<SwitchListTile>(find.byKey(const Key('edit_profile_anonymous_switch'))).value,
        isTrue,
      );

      await tester.tap(find.byKey(const Key('edit_profile_anonymous_switch')));
      await tester.pumpAndSettle();

      expect(
        tester.widget<SwitchListTile>(find.byKey(const Key('edit_profile_anonymous_switch'))).value,
        isFalse,
      );
    });

    testWidgets('salvar com sucesso envia PATCH limpo, atualiza authNotifier, mostra SnackBar e faz pop', (tester) async {
      UserProfile? returnedFromPop;

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                onPressed: () async {
                  returnedFromPop = await Navigator.of(context).push<UserProfile>(
                    MaterialPageRoute(
                      builder: (_) => EditProfileScreen(
                        initialProfile: sampleInitialProfile,
                        repository: profileRepo,
                        authNotifier: authNotifier,
                      ),
                    ),
                  );
                },
                child: const Text('Abrir Edição'),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Abrir Edição'));
      await tester.pumpAndSettle();

      // Preenche novos valores, incluindo prefixo '@' que deve ser higienizado
      await tester.enterText(find.byKey(const Key('edit_profile_handle_field')), '@chef_sensacao');
      await tester.enterText(find.byKey(const Key('edit_profile_display_name_field')), 'Chef Sensação');
      await tester.enterText(find.byKey(const Key('edit_profile_bio_field')), 'Crítico culinário e chef.');

      // Clica em Salvar
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();

      // Verifica chamada ao repositório
      expect(profileRepo.updateCallCount, 1);
      expect(profileRepo.capturedInput?.handle, 'chef_sensacao'); // '@' removido
      expect(profileRepo.capturedInput?.displayName, 'Chef Sensação');
      expect(profileRepo.capturedInput?.bio, 'Crítico culinário e chef.');
      expect(profileRepo.capturedInput?.isAnonymousDefault, isTrue);

      // Verifica atualização no AuthNotifier
      final authState = authNotifier.state as Authenticated;
      expect(authState.user.handle, 'chef_sensacao');
      expect(authState.user.displayName, 'Chef Sensação');
      expect(authState.user.isAnonymousDefault, isTrue);

      // Verifica SnackBar e retorno via pop
      expect(find.text('Perfil atualizado com sucesso!'), findsOneWidget);
      expect(returnedFromPop, isNotNull);
      expect(returnedFromPop?.handle, 'chef_sensacao');
      expect(returnedFromPop?.displayName, 'Chef Sensação');
    });

    testWidgets('bloqueia double-submit durante salvamento em andamento', (tester) async {
      profileRepo.shouldDelay = true;

      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      // Clica em Salvar
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pump(); // Inicia salvamento sem settle

      // Botão deve mostrar estado de progresso
      expect(find.text('Salvando...'), findsOneWidget);

      // Tenta clicar novamente enquanto está salvando
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.tap(find.byKey(const Key('edit_profile_save_button')));
      await tester.pump();

      // Aguarda conclusão do delay
      await tester.pumpAndSettle(const Duration(milliseconds: 300));

      expect(profileRepo.updateCallCount, 1);
    });

    testWidgets('erro 409 (conflito de handle) exibe mensagem amigável e preserva entradas digitadas', (tester) async {
      profileRepo.apiExceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/conflict',
          title: 'Conflito',
          status: 409,
          detail: 'Este nome de usuário já está em uso por outro usuário.',
          code: 'HANDLE_ALREADY_EXISTS',
        ),
      );

      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      await tester.enterText(find.byKey(const Key('edit_profile_handle_field')), 'handle_duplicado');
      await tester.enterText(find.byKey(const Key('edit_profile_display_name_field')), 'Nome Tentado');
      await tester.enterText(find.byKey(const Key('edit_profile_bio_field')), 'Bio que deve ser preservada');

      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();

      // Mensagem de erro exibida
      expect(find.text('Este nome de usuário já está em uso por outro usuário.'), findsOneWidget);

      // Campos continuam com o texto digitado (não foram limpos)
      expect(find.text('handle_duplicado'), findsOneWidget);
      expect(find.text('Nome Tentado'), findsOneWidget);
      expect(find.text('Bio que deve ser preservada'), findsOneWidget);
    });

    testWidgets('erro 400 exibe mensagem de validação e preserva dados', (tester) async {
      profileRepo.apiExceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/validation',
          title: 'Dados Inválidos',
          status: 400,
          detail: 'Formato inválido de biografia.',
        ),
      );

      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      await tester.enterText(find.byKey(const Key('edit_profile_bio_field')), 'Bio com problema');
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();

      expect(find.text('Formato inválido de biografia.'), findsOneWidget);
      expect(find.text('Bio com problema'), findsOneWidget);
    });

    testWidgets('erro de rede (NetworkException) exibe aviso e preserva dados', (tester) async {
      profileRepo.networkExceptionToThrow = const NetworkException('Sem conexão com a internet. Verifique sua rede.');

      await pumpScreen(tester, initialProfile: sampleInitialProfile);

      await tester.enterText(find.byKey(const Key('edit_profile_display_name_field')), 'Novo Nome Offline');
      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();

      expect(find.text('Sem conexão com a internet. Verifique sua rede.'), findsOneWidget);
      expect(find.text('Novo Nome Offline'), findsOneWidget);
    });

    testWidgets('AuthNotifier.updateCurrentUser atualiza estado Authenticated preservando tokens e email', (tester) async {
      expect(authNotifier.state, isA<Authenticated>());
      final initialAuth = authNotifier.state as Authenticated;
      expect(initialAuth.user.email, 'eu@rewit.app');
      expect(initialAuth.user.id, 'usr-123');

      authNotifier.updateCurrentUser(
        handle: 'novo_handle_notif',
        displayName: 'Novo Nome Notif',
        isAnonymousDefault: true,
      );

      final updatedAuth = authNotifier.state as Authenticated;
      expect(updatedAuth.user.handle, 'novo_handle_notif');
      expect(updatedAuth.user.displayName, 'Novo Nome Notif');
      expect(updatedAuth.user.isAnonymousDefault, isTrue);
      // Imutabilidade das credenciais de sessão
      expect(updatedAuth.user.email, 'eu@rewit.app');
      expect(updatedAuth.user.id, 'usr-123');
      expect(updatedAuth.tokens.accessToken, initialAuth.tokens.accessToken);
      expect(updatedAuth.tokens.refreshToken, initialAuth.tokens.refreshToken);
    });
  });
}
