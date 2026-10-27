import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/interests/data/models/interest_dtos.dart';
import 'package:rewit_mobile/features/interests/data/repositories/interest_repository_impl.dart';
import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/interests/presentation/screens/edit_interests_screen.dart';
import 'package:rewit_mobile/features/interests/presentation/screens/interests_onboarding_screen.dart';
import 'package:rewit_mobile/features/interests/presentation/state/interests_notifier.dart';
import 'package:rewit_mobile/features/interests/presentation/widgets/interests_gate.dart';
import 'package:rewit_mobile/shared/widgets/app_button.dart';

// ---------------------------------------------------------------------------------------------------------------
// Catálogo e fakes
// ---------------------------------------------------------------------------------------------------------------

const _catalog = InterestCatalog(minInterestsToComplete: 3, questions: [
  InterestQuestion(code: 'FOOD_AND_DRINK', title: 'Comer e beber', question: 'Onde você gosta de comer e beber?', options: [
    InterestOption(code: 'RESTAURANT', label: 'Restaurantes'),
    InterestOption(code: 'CAFE', label: 'Cafés e cafeterias'),
    InterestOption(code: 'BAR', label: 'Bares e pubs'),
  ]),
  InterestQuestion(code: 'LEISURE_AND_WELLBEING', title: 'Lazer e bem-estar', question: 'Como você aproveita o tempo livre?',
      options: [
        InterestOption(code: 'PARK', label: 'Parques e atividades ao ar livre'),
        InterestOption(code: 'GAMES', label: 'Games e entretenimento'),
        InterestOption(code: 'TRAVEL', label: 'Hotéis e viagens'),
      ]),
]);

ApiException _problem(int status, String code, String detail) => ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Erro',
      status: status,
      detail: detail,
      code: code,
    ));

/// Repositório em memória com as regras do backend (rascunho livre; conclusão definitiva com mínimo de 3).
class _FakeInterestRepository implements InterestRepository {
  UserInterests current;
  final List<({List<String> codes, bool complete})> saves = [];
  Object? loadError;
  Object? nextSaveError;
  int loads = 0;

  _FakeInterestRepository({UserInterests? initial})
      : current = initial ?? const UserInterests(status: InterestOnboardingStatus.notStarted, interests: []);

  _FakeInterestRepository.completed(List<String> codes)
      : current = UserInterests(
          status: InterestOnboardingStatus.completed,
          completedAt: DateTime.utc(2026, 10, 27),
          interests: _options(codes),
        );

  static List<InterestOption> _options(List<String> codes) =>
      _catalog.options.where((o) => codes.contains(o.code)).toList();

  @override
  Future<InterestCatalog> getCatalog() async {
    if (loadError != null) throw loadError!;
    return _catalog;
  }

  @override
  Future<UserInterests> getMyInterests() async {
    loads++;
    if (loadError != null) throw loadError!;
    return current;
  }

  @override
  Future<UserInterests> saveMyInterests({required List<String> codes, required bool completeOnboarding}) async {
    final error = nextSaveError;
    if (error != null) {
      nextSaveError = null;
      throw error;
    }
    saves.add((codes: List.of(codes), complete: completeOnboarding));
    final completed = current.isCompleted || completeOnboarding;
    if (completed && codes.length < _catalog.minInterestsToComplete) {
      throw _problem(422, 'INTERESTS_BELOW_MINIMUM', 'Selecione pelo menos 3 interesses');
    }
    current = UserInterests(
      status: completed ? InterestOnboardingStatus.completed : InterestOnboardingStatus.inProgress,
      completedAt: current.completedAt ?? (completeOnboarding ? DateTime.utc(2026, 10, 27, 12) : null),
      interests: _options(codes),
    );
    return current;
  }
}

AuthUserDto _user(String id) => AuthUserDto(id: id, email: '$id@rewit.test', handle: id, displayName: 'Pessoa $id');
Authenticated _session(String id) =>
    Authenticated(user: _user(id), tokens: const AuthTokens(accessToken: 'a', refreshToken: 'r'));

class _FakeAuthRepository implements AuthRepository {
  String loginAs;
  final bool storedSession;

  _FakeAuthRepository({this.loginAs = 'ana', this.storedSession = false});

  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) async =>
      _session(handle);

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
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> login({required String email, required String password}) async => _session(loginAs);
  @override
  Future<Authenticated> reactivate({required String email, required String password}) async => _session(loginAs);
  @override
  Future<void> deactivateAccount() async {}
  @override
  Future<void> changePassword({required String currentPassword, required String newPassword}) async {}
  @override
  Future<Authenticated> refreshTokens() async => _session(loginAs);
  @override
  Future<AuthUserDto> getMe() async => _user(loginAs);
  @override
  Future<void> logout() async {}
  @override
  Future<bool> hasStoredSession() async => storedSession;
}

/// Um repositório por usuário: a sessão decide qual conta o backend vê.
class _PerUserInterestRepository implements InterestRepository {
  final AuthNotifier auth;
  final Map<String, _FakeInterestRepository> accounts;

  _PerUserInterestRepository(this.auth, this.accounts);

  _FakeInterestRepository get _account {
    final state = auth.state;
    if (state is! Authenticated) throw _problem(401, 'AUTHENTICATION_REQUIRED', 'Autenticação necessária');
    return accounts.putIfAbsent(state.user.id, () => _FakeInterestRepository());
  }

  @override
  Future<InterestCatalog> getCatalog() => _account.getCatalog();
  @override
  Future<UserInterests> getMyInterests() => _account.getMyInterests();
  @override
  Future<UserInterests> saveMyInterests({required List<String> codes, required bool completeOnboarding}) =>
      _account.saveMyInterests(codes: codes, completeOnboarding: completeOnboarding);
}

InterestsNotifier _notifier(InterestRepository repository, {String user = 'ana', String? Function()? current}) =>
    InterestsNotifier(repository: repository, userId: user, currentUserId: current ?? () => user);

Widget _gate(InterestRepository repository, {String user = 'ana', VoidCallback? onLogout}) => MaterialApp(
      home: InterestsGate(
        userId: user,
        repository: repository,
        currentUserId: () => user,
        homeBuilder: (_) => const Scaffold(body: Text('APP', key: Key('home_stub'))),
        onLogout: onLogout,
      ),
    );

Future<void> _tap(WidgetTester tester, Key key) async {
  await tester.ensureVisible(find.byKey(key));
  await tester.tap(find.byKey(key));
  await tester.pumpAndSettle();
}

void main() {
  // -------------------------------------------------------------------------------------------------------------
  group('Contrato HTTP dos interesses', () {
    test('repositório usa os endpoints documentados, com o token da sessão e sem identificar a conta no corpo', () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        switch ('${request.method} ${request.url.path}') {
          case 'GET /api/v1/interests/catalog':
            return http.Response(
                jsonEncode({
                  'minInterestsToComplete': 3,
                  'questions': [
                    {
                      'code': 'FOOD_AND_DRINK',
                      'title': 'Comer e beber',
                      'question': 'Onde você gosta de comer e beber?',
                      'options': [
                        {'code': 'CAFE', 'label': 'Cafés e cafeterias'}
                      ],
                    }
                  ],
                }),
                200,
                headers: {'content-type': 'application/json; charset=utf-8'});
          case 'GET /api/v1/me/interests':
          case 'PUT /api/v1/me/interests':
            return http.Response(
                jsonEncode({
                  'onboardingStatus': 'IN_PROGRESS',
                  'onboardingCompletedAt': null,
                  'interests': [
                    {'code': 'CAFE', 'label': 'Cafés e cafeterias'}
                  ],
                }),
                200,
                headers: {'content-type': 'application/json; charset=utf-8'});
        }
        return http.Response('{}', 404);
      });
      final storage = InMemoryTokenStorage();
      await storage.saveTokens(accessToken: 'token-ana', refreshToken: 'refresh-ana');
      final repository = InterestRepositoryImpl(
        httpClient: RewitHttpClient(baseUrl: 'https://api.rewit.test', client: client, tokenStorage: storage),
      );

      final catalog = await repository.getCatalog();
      expect(catalog.minInterestsToComplete, 3);
      expect(catalog.questions.single.options.single.label, 'Cafés e cafeterias');

      final mine = await repository.getMyInterests();
      expect(mine.status, InterestOnboardingStatus.inProgress);
      expect(mine.codes, {'CAFE'});

      await repository.saveMyInterests(codes: ['CAFE', 'BAR'], completeOnboarding: false);
      final put = requests.last;
      expect(put.method, 'PUT');
      expect(put.url.path, '/api/v1/me/interests');
      expect(put.headers['Authorization'], 'Bearer token-ana');
      expect(jsonDecode(put.body), {
        'interests': ['CAFE', 'BAR'],
        'completeOnboarding': false,
      });
    });

    test('situação desconhecida ou campo ausente é resposta inválida, não questionário concluído', () {
      expect(() => InterestDtos.userInterests({'onboardingStatus': 'SKIPPED', 'interests': []}),
          throwsA(isA<FormatException>()));
      expect(() => InterestDtos.userInterests({'onboardingStatus': 'COMPLETED'}), throwsA(isA<FormatException>()));
      expect(() => InterestDtos.catalog({'questions': []}), throwsA(isA<FormatException>()));
      final completed = InterestDtos.userInterests({
        'onboardingStatus': 'COMPLETED',
        'onboardingCompletedAt': '2026-10-27T12:00:00Z',
        'interests': [],
      });
      expect(completed.isCompleted, isTrue);
      expect(completed.completedAt, DateTime.utc(2026, 10, 27, 12));
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('InterestsNotifier: questionário concluído x incompleto e retomada', () {
    test('conta nova começa na primeira pergunta, sem seleção', () async {
      final notifier = _notifier(_FakeInterestRepository());
      await notifier.load();

      expect(notifier.isReady, isTrue);
      expect(notifier.isCompleted, isFalse);
      expect(notifier.step, 0);
      expect(notifier.currentQuestion!.code, 'FOOD_AND_DRINK');
      expect(notifier.selectedCodes, isEmpty);
    });

    test('rascunho gravado é restaurado e o questionário retoma depois da última pergunta respondida', () async {
      final repository = _FakeInterestRepository(
        initial: UserInterests(
          status: InterestOnboardingStatus.inProgress,
          interests: _FakeInterestRepository._options(['CAFE', 'BAR']),
        ),
      );
      final notifier = _notifier(repository);
      await notifier.load();

      expect(notifier.selectedCodes, {'CAFE', 'BAR'});
      expect(notifier.step, 1, reason: 'a pergunta 1 já foi respondida');

      final answeredAll = _notifier(_FakeInterestRepository(
        initial: UserInterests(
          status: InterestOnboardingStatus.inProgress,
          interests: _FakeInterestRepository._options(['CAFE', 'PARK']),
        ),
      ));
      await answeredAll.load();
      expect(answeredAll.isOnSummary, isTrue);
    });

    test('questionário concluído é reconhecido e não volta a ser apresentado', () async {
      final notifier = _notifier(_FakeInterestRepository.completed(['CAFE', 'BAR', 'PARK']));
      await notifier.load();
      expect(notifier.isCompleted, isTrue);
      expect(notifier.justCompleted, isFalse);
    });

    test('avançar grava o rascunho; voltar não grava; avançar sem mudança não regrava', () async {
      final repository = _FakeInterestRepository();
      final notifier = _notifier(repository);
      await notifier.load();

      notifier.toggle('CAFE');
      notifier.toggle('SAUNA'); // fora do catálogo: ignorado
      expect(await notifier.next(), isTrue);
      expect(repository.saves, hasLength(1));
      expect(repository.saves.single.codes, ['CAFE']);
      expect(repository.saves.single.complete, isFalse);
      expect(repository.current.status, InterestOnboardingStatus.inProgress);

      notifier.back();
      expect(notifier.step, 0);
      expect(notifier.selectedCodes, {'CAFE'});
      expect(await notifier.next(), isTrue);
      expect(repository.saves, hasLength(1), reason: 'nada mudou desde a última gravação');
    });

    test('primeiro avanço sem escolha também grava (o questionário passa a em andamento)', () async {
      final repository = _FakeInterestRepository();
      final notifier = _notifier(repository);
      await notifier.load();

      expect(await notifier.next(), isTrue);
      expect(repository.saves.single.codes, isEmpty);
      expect(repository.current.status, InterestOnboardingStatus.inProgress);
    });

    test('sem conexão: permanece na pergunta, mantém a seleção e a nova tentativa grava', () async {
      final repository = _FakeInterestRepository()..nextSaveError = const NetworkException('Falha de conexão');
      final notifier = _notifier(repository);
      await notifier.load();
      notifier.toggle('BAR');

      expect(await notifier.next(), isFalse);
      expect(notifier.step, 0);
      expect(notifier.selectedCodes, {'BAR'});
      expect(notifier.errorMessage, contains('Sem conexão'));

      expect(await notifier.next(), isTrue);
      expect(notifier.step, 1);
      expect(notifier.errorMessage, isNull);
      expect(repository.saves.single.codes, ['BAR']);
    });

    test('concluir abaixo do mínimo não chama o backend', () async {
      final repository = _FakeInterestRepository();
      final notifier = _notifier(repository);
      await notifier.load();
      notifier.toggle('CAFE');
      notifier.toggle('BAR');

      expect(await notifier.complete(), isFalse);
      expect(repository.saves, isEmpty);
      expect(notifier.errorMessage, contains('pelo menos 3'));
    });

    test('conclusão grava com completeOnboarding=true, em ordem do catálogo, e exige a confirmação na tela', () async {
      final repository = _FakeInterestRepository();
      final notifier = _notifier(repository);
      await notifier.load();
      for (final code in ['PARK', 'CAFE', 'RESTAURANT']) {
        notifier.toggle(code);
      }

      expect(await notifier.complete(), isTrue);
      expect(repository.saves.single.codes, ['RESTAURANT', 'CAFE', 'PARK']);
      expect(repository.saves.single.complete, isTrue);
      expect(notifier.isCompleted, isTrue);
      expect(notifier.justCompleted, isTrue);

      notifier.acknowledgeCompletion();
      expect(notifier.justCompleted, isFalse);
    });

    test('reenvio e alteração depois da conclusão gravam a seleção nova e não repetem a confirmação', () async {
      final repository = _FakeInterestRepository.completed(['CAFE', 'BAR', 'PARK']);
      final notifier = _notifier(repository);
      await notifier.load();

      expect(await notifier.complete(), isTrue, reason: 'reenvio idêntico');
      notifier.toggle('BAR');
      notifier.toggle('GAMES');
      expect(await notifier.complete(), isTrue);

      expect(repository.saves.map((s) => s.codes).toList(), [
        ['CAFE', 'BAR', 'PARK'],
        ['CAFE', 'PARK', 'GAMES'],
      ]);
      expect(repository.current.completedAt, DateTime.utc(2026, 10, 27), reason: 'data da primeira conclusão');
      expect(notifier.justCompleted, isFalse);
    });

    test('sessão de outra conta: nada é gravado', () async {
      String? sessionUser = 'ana';
      final repository = _FakeInterestRepository();
      final notifier = _notifier(repository, current: () => sessionUser);
      await notifier.load();
      notifier.toggle('CAFE');

      sessionUser = 'bruno';
      expect(await notifier.next(), isFalse);
      sessionUser = null;
      expect(await notifier.next(), isFalse);

      expect(repository.saves, isEmpty);
      expect(notifier.errorMessage, contains('sessão mudou'));
    });

    test('sessão expirada no backend (401) mostra aviso e não avança', () async {
      final repository = _FakeInterestRepository()
        ..nextSaveError = _problem(401, 'AUTHENTICATION_REQUIRED', 'Autenticação necessária');
      final notifier = _notifier(repository);
      await notifier.load();

      expect(await notifier.next(), isFalse);
      expect(notifier.step, 0);
      expect(notifier.errorMessage, contains('sessão expirou'));
    });

    test('falha ao carregar expõe o erro e a nova tentativa recupera', () async {
      final repository = _FakeInterestRepository()..loadError = _problem(503, 'SERVICE_UNAVAILABLE', 'Indisponível');
      final notifier = _notifier(repository);
      await notifier.load();
      expect(notifier.loadError, 'Indisponível');
      expect(notifier.isReady, isFalse);

      repository.loadError = null;
      await notifier.load();
      expect(notifier.loadError, isNull);
      expect(notifier.isReady, isTrue);
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Telas do questionário', () {
    testWidgets('questionário concluído vai direto ao app', (tester) async {
      await tester.pumpWidget(_gate(_FakeInterestRepository.completed(['CAFE', 'BAR', 'PARK'])));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('home_stub')), findsOneWidget);
      expect(find.byType(InterestsOnboardingScreen), findsNothing);
    });

    testWidgets('fluxo completo: escolher, avançar, voltar, confirmar, conclusão e app', (tester) async {
      final repository = _FakeInterestRepository();
      await tester.pumpWidget(_gate(repository));
      await tester.pumpAndSettle();

      expect(find.text('Onde você gosta de comer e beber?'), findsOneWidget);
      expect(find.text('Pergunta 1 de 2'), findsOneWidget);
      expect(tester.widget<AppButton>(find.byKey(const Key('interests_back'))).onPressed, isNull);

      await _tap(tester, const Key('interest_option_CAFE'));
      await _tap(tester, const Key('interest_option_BAR'));
      expect(tester.widget<FilterChip>(find.byKey(const Key('interest_option_CAFE'))).selected, isTrue);
      expect(find.text('2 interesses escolhidos · mínimo de 3 para concluir'), findsOneWidget);

      await _tap(tester, const Key('interests_next'));
      expect(find.text('Como você aproveita o tempo livre?'), findsOneWidget);
      expect(repository.saves.last.codes, ['CAFE', 'BAR']);

      await _tap(tester, const Key('interests_back'));
      expect(find.text('Onde você gosta de comer e beber?'), findsOneWidget);
      expect(tester.widget<FilterChip>(find.byKey(const Key('interest_option_BAR'))).selected, isTrue);
      await _tap(tester, const Key('interests_next'));

      await _tap(tester, const Key('interest_option_PARK'));
      await _tap(tester, const Key('interests_next'));
      expect(find.text('Confirme seus interesses'), findsOneWidget);
      expect(find.byKey(const Key('interest_summary_PARK')), findsOneWidget);

      await _tap(tester, const Key('interests_confirm'));
      expect(repository.saves.last.complete, isTrue);
      expect(find.text('Tudo pronto!'), findsOneWidget);
      expect(find.byKey(const Key('home_stub')), findsNothing);

      await _tap(tester, const Key('interests_start_app'));
      expect(find.byKey(const Key('home_stub')), findsOneWidget);
      expect(repository.current.isCompleted, isTrue);
    });

    testWidgets('resumo abaixo do mínimo desabilita Confirmar e explica o motivo', (tester) async {
      final repository = _FakeInterestRepository(
        initial: UserInterests(
          status: InterestOnboardingStatus.inProgress,
          interests: _FakeInterestRepository._options(['CAFE', 'PARK']),
        ),
      );
      await tester.pumpWidget(_gate(repository));
      await tester.pumpAndSettle();

      expect(find.text('Confirme seus interesses'), findsOneWidget, reason: 'retomada no resumo');
      expect(find.byKey(const Key('interests_minimum_hint')), findsOneWidget);
      expect(tester.widget<AppButton>(find.byKey(const Key('interests_confirm'))).onPressed, isNull);
      expect(repository.saves, isEmpty);
    });

    testWidgets('falha do backend ao gravar mostra o erro e mantém a pergunta', (tester) async {
      final repository = _FakeInterestRepository()..nextSaveError = _problem(500, 'INTERNAL_ERROR', 'Erro interno');
      await tester.pumpWidget(_gate(repository));
      await tester.pumpAndSettle();

      await _tap(tester, const Key('interest_option_CAFE'));
      await _tap(tester, const Key('interests_next'));

      expect(find.byKey(const Key('interests_error')), findsOneWidget);
      expect(find.text('Erro interno'), findsOneWidget);
      expect(find.text('Pergunta 1 de 2'), findsOneWidget);

      await _tap(tester, const Key('interests_next'));
      expect(find.text('Pergunta 2 de 2'), findsOneWidget);
      expect(find.byKey(const Key('interests_error')), findsNothing);
    });

    testWidgets('falha ao carregar oferece nova tentativa e saída da conta', (tester) async {
      var logouts = 0;
      final repository = _FakeInterestRepository()..loadError = const NetworkException('Falha de conexão');
      await tester.pumpWidget(_gate(repository, onLogout: () => logouts++));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('interests_gate_error')), findsOneWidget);
      expect(find.textContaining('Sem conexão'), findsOneWidget);
      await _tap(tester, const Key('interests_gate_logout'));
      expect(logouts, 1);

      repository.loadError = null;
      await tester.tap(find.text('Tentar novamente'));
      await tester.pumpAndSettle();
      expect(find.byType(InterestsOnboardingScreen), findsOneWidget);
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Integração com o app e a autenticação', () {
    Future<AuthNotifier> pumpApp(
      WidgetTester tester,
      Map<String, _FakeInterestRepository> accounts, {
      _FakeAuthRepository? authRepository,
      bool signedIn = false,
    }) async {
      final auth = AuthNotifier(authRepository: authRepository ?? _FakeAuthRepository(storedSession: signedIn));
      await auth.checkAuthStatus();
      await tester.pumpWidget(RewitApp(
        config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
        router: AppRouter(authNotifier: auth, interestRepository: _PerUserInterestRepository(auth, accounts)),
      ));
      await tester.pumpAndSettle();
      return auth;
    }

    testWidgets('cadastro leva ao questionário em vez do app', (tester) async {
      final accounts = <String, _FakeInterestRepository>{};
      final auth = await pumpApp(tester, accounts);
      expect(find.byType(LoginScreen), findsOneWidget);

      await auth.register(displayName: 'Nova', handle: 'nova', email: 'nova@rewit.test', password: 'Senha@123');
      await tester.pumpAndSettle();

      expect(find.byType(InterestsOnboardingScreen), findsOneWidget);
      expect(find.byType(HomeScreen), findsNothing);
      expect(accounts['nova']!.loads, 1);
    });

    testWidgets('conta com questionário concluído entra direto no app a cada login', (tester) async {
      final accounts = {'ana': _FakeInterestRepository.completed(['CAFE', 'BAR', 'PARK'])};
      final auth = await pumpApp(tester, accounts, signedIn: true);
      expect(find.byType(HomeScreen), findsOneWidget);

      await auth.logout();
      await tester.pumpAndSettle();
      await auth.login('ana@rewit.test', 'Senha@123');
      await tester.pumpAndSettle();
      expect(find.byType(HomeScreen), findsOneWidget);
      expect(find.byType(InterestsOnboardingScreen), findsNothing);
      expect(accounts['ana']!.saves, isEmpty);
    });

    testWidgets('sessão expirada no questionário volta ao login; retomada no novo login, sem herdar outra conta',
        (tester) async {
      final authRepository = _FakeAuthRepository(loginAs: 'ana', storedSession: true);
      final accounts = <String, _FakeInterestRepository>{};
      final auth = await pumpApp(tester, accounts, authRepository: authRepository, signedIn: true);

      await _tap(tester, const Key('interest_option_CAFE'));
      await _tap(tester, const Key('interests_next'));
      await _tap(tester, const Key('interest_option_GAMES'));

      await auth.handleSessionExpired();
      await tester.pumpAndSettle();
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(find.byType(InterestsOnboardingScreen), findsNothing);
      expect(accounts['ana']!.saves.map((s) => s.codes).toList(), [
        ['CAFE']
      ], reason: 'GAMES não foi confirmado com Avançar e nada foi gravado depois da expiração');

      // Outra conta entra no mesmo aparelho: começa do zero
      authRepository.loginAs = 'bruno';
      await auth.login('bruno@rewit.test', 'Senha@123');
      await tester.pumpAndSettle();
      expect(find.text('Pergunta 1 de 2'), findsOneWidget);
      expect(tester.widget<FilterChip>(find.byKey(const Key('interest_option_CAFE'))).selected, isFalse);
      expect(accounts['bruno']!.saves, isEmpty);

      // A primeira conta volta e retoma o próprio rascunho
      await auth.logout();
      await tester.pumpAndSettle();
      authRepository.loginAs = 'ana';
      await auth.login('ana@rewit.test', 'Senha@123');
      await tester.pumpAndSettle();
      expect(find.text('Pergunta 2 de 2'), findsOneWidget);
      expect(accounts['ana']!.current.codes, {'CAFE'});
    });

    testWidgets('interesses podem ser alterados depois pelas configurações da conta', (tester) async {
      final accounts = {'ana': _FakeInterestRepository.completed(['CAFE', 'BAR', 'PARK'])};
      await pumpApp(tester, accounts, signedIn: true);

      Navigator.of(tester.element(find.byType(HomeScreen))).pushNamed(AppRouter.accountSettings);
      await tester.pumpAndSettle();
      await _tap(tester, const Key('edit_interests_tile'));
      expect(find.byType(EditInterestsScreen), findsOneWidget);
      expect(tester.widget<FilterChip>(find.byKey(const Key('interest_option_BAR'))).selected, isTrue);

      await _tap(tester, const Key('interest_option_BAR'));
      expect(tester.widget<TextButton>(find.byKey(const Key('edit_interests_save'))).onPressed, isNull,
          reason: 'abaixo do mínimo');

      await _tap(tester, const Key('interest_option_TRAVEL'));
      await _tap(tester, const Key('edit_interests_save'));

      expect(accounts['ana']!.saves.single.codes, ['CAFE', 'PARK', 'TRAVEL']);
      expect(find.byType(EditInterestsScreen), findsNothing);
      expect(find.text('Interesses atualizados.'), findsOneWidget);
    });

    testWidgets('edição recusada pelo backend mantém a tela e mostra o erro', (tester) async {
      final repository = _FakeInterestRepository.completed(['CAFE', 'BAR', 'PARK'])
        ..nextSaveError = _problem(401, 'ACCOUNT_DISABLED', 'Conta de usuário inativa ou inexistente');
      await tester.pumpWidget(MaterialApp(
        home: EditInterestsScreen(repository: repository, userId: 'ana', currentUserId: () => 'ana'),
      ));
      await tester.pumpAndSettle();

      await _tap(tester, const Key('edit_interests_save'));
      expect(find.byKey(const Key('edit_interests_error')), findsOneWidget);
      expect(find.byType(EditInterestsScreen), findsOneWidget);
    });
  });
}
