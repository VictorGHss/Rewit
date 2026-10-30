import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';
import 'package:rewit_mobile/features/rankings/data/repositories/ranking_repository_impl.dart';
import 'package:rewit_mobile/features/rankings/domain/entities/ranking_entities.dart';
import 'package:rewit_mobile/features/rankings/domain/repositories/ranking_repository.dart';
import 'package:rewit_mobile/features/rankings/presentation/screens/rankings_screen.dart';
import 'package:rewit_mobile/features/rankings/presentation/state/ranking_participation_notifier.dart';
import 'package:rewit_mobile/features/rankings/presentation/state/rankings_notifier.dart';

// ---------------------------------------------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------------------------------------------

typedef _Request = ({RankingMetric metric, RankingFilters filters, int page, String? sessionUser});

RankingEntry _entry(int position, String name, int score, {bool me = false}) => RankingEntry(
      rankPosition: position,
      user: RankingUser(id: 'id-$name', handle: name.toLowerCase(), displayName: name),
      score: score,
      isCurrentUser: me,
    );

/// Backend simulado: grava cada consulta (com a conta da sessão) e responde pelo [responder] configurado.
class _FakeRankingRepository implements RankingRepository {
  final AuthNotifier? auth;
  final List<_Request> requests = [];
  RankingPage Function(_Request request) responder;
  final Map<int, Completer<void>> gates = {};
  Object? nextError;

  bool? participation = false;
  Object? participationLoadError;
  Completer<void>? loadGate;
  Object? nextSaveError;
  final List<bool> saves = [];
  Completer<void>? saveGate;

  _FakeRankingRepository({this.auth, RankingPage Function(_Request request)? responder})
      : responder = responder ?? ((_) => const RankingPage(entries: [], pageNumber: 0, totalElements: 0, isLast: true));

  @override
  Future<RankingPage> getRanking(RankingMetric metric, RankingFilters filters, {int page = 0, int size = 20}) async {
    final state = auth?.state;
    final request = (
      metric: metric,
      filters: filters,
      page: page,
      sessionUser: state is Authenticated ? state.user.id : null,
    );
    requests.add(request);
    final gate = gates[requests.length];
    if (gate != null) await gate.future;
    final error = nextError;
    if (error != null) {
      nextError = null;
      throw error;
    }
    return responder(request);
  }

  @override
  Future<bool> getParticipation() async {
    if (loadGate != null) await loadGate!.future;
    final error = participationLoadError;
    if (error != null) {
      participationLoadError = null;
      throw error;
    }
    return participation!;
  }

  @override
  Future<bool> updateParticipation(bool showInRankings) async {
    saves.add(showInRankings);
    if (saveGate != null) await saveGate!.future;
    final error = nextSaveError;
    if (error != null) {
      nextSaveError = null;
      throw error;
    }
    participation = showInRankings;
    return showInRankings;
  }
}

const _catalog = InterestCatalog(minInterestsToComplete: 3, questions: [
  InterestQuestion(code: 'FOOD_AND_DRINK', title: 'Comer e beber', question: '?', options: [
    InterestOption(code: 'RESTAURANT', label: 'Restaurantes'),
    InterestOption(code: 'CAFE', label: 'Cafés e cafeterias'),
  ]),
]);

class _FakeInterestRepository implements InterestRepository {
  @override
  Future<InterestCatalog> getCatalog() async => _catalog;
  @override
  Future<UserInterests> getMyInterests() async =>
      const UserInterests(status: InterestOnboardingStatus.completed, interests: []);
  @override
  Future<UserInterests> saveMyInterests({required List<String> codes, required bool completeOnboarding}) =>
      throw UnimplementedError();
}

AuthUserDto _user(String id) => AuthUserDto(id: id, email: '$id@rewit.test', handle: id, displayName: 'Pessoa $id');

class _FakeAuthRepository implements AuthRepository {
  String loginAs;

  _FakeAuthRepository({this.loginAs = 'ana'});

  Authenticated _session() =>
      Authenticated(user: _user(loginAs), tokens: const AuthTokens(accessToken: 'a', refreshToken: 'r'));

  @override
  Future<bool> hasStoredSession() async => true;
  @override
  Future<AuthUserDto> getMe() async => _user(loginAs);
  @override
  Future<Authenticated> login({required String email, required String password}) async => _session();
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
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();
  @override
  Future<Authenticated> reactivate({required String email, required String password}) => throw UnimplementedError();
  @override
  Future<void> deactivateAccount() async {}
  @override
  Future<void> changePassword({required String currentPassword, required String newPassword}) async {}
  @override
  Future<Authenticated> refreshTokens() async => _session();
  @override
  Future<void> logout() async {}
}

Future<AuthNotifier> _signedIn([String user = 'ana']) async {
  final auth = AuthNotifier(authRepository: _FakeAuthRepository(loginAs: user));
  await auth.checkAuthStatus();
  return auth;
}

void _tallScreen(WidgetTester tester) {
  tester.view.physicalSize = const Size(900, 4000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> _pumpRankings(
  WidgetTester tester,
  AuthNotifier auth,
  _FakeRankingRepository repository, {
  InterestRepository? interests,
}) async {
  _tallScreen(tester);
  final router = AppRouter(authNotifier: auth, rankingRepository: repository, interestRepository: interests);
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.lightTheme,
    onGenerateRoute: router.onGenerateRoute,
    home: RankingsScreen(repository: repository, authNotifier: auth, interestRepository: interests),
  ));
  await tester.pumpAndSettle();
}

/// Escolhe a UF. Com [settle] falso, só avança o tempo do menu: uma consulta presa deixaria o indicador de
/// carregamento animando, e `pumpAndSettle` nunca terminaria.
Future<void> _chooseState(WidgetTester tester, String uf, {bool settle = true}) async {
  await tester.tap(find.byKey(const Key('ranking_state_field')));
  await tester.pump(const Duration(milliseconds: 500));
  await tester.tap(find.text(uf).last);
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump(const Duration(milliseconds: 500));
  }
}

List<String> _positions(WidgetTester tester) => [
      for (var i = 0; find.byKey(Key('ranking_position_$i')).evaluate().isNotEmpty; i++)
        tester.widget<Text>(find.byKey(Key('ranking_position_$i'))).data!,
    ];

/// Item no formato de `RankingItemResponse` do backend (agent/gemini/rankings-v1-backend @ 2b0ff7a).
Map<String, dynamic> _apiItem(int position, String name, int score,
        {bool me = false, String? handle, String? lastContributedAt}) =>
    {
      'rankPosition': position,
      'user': {'id': 'id-$name', 'handle': handle, 'displayName': name, 'avatarUrl': null},
      'score': score,
      'lastContributedAt': lastContributedAt ?? '2026-10-29T18:00:00Z',
      'isCurrentUser': me,
    };

/// Resposta no formato exato de `RankingPagedResponse` do backend: envelope paginado e `metadata` com
/// `effectiveStart`, `periodEnd` (fim exclusivo) e `isPartialPeriod`.
Map<String, dynamic> _apiPage({
  List<Map<String, dynamic>> content = const [],
  String rankingType = 'CHECK_INS',
  String period = 'WEEK',
  String state = 'PR',
  String? city,
  String? category,
  String effectiveStart = '2026-11-02T03:00:00Z',
  String periodEnd = '2026-11-09T03:00:00Z',
  bool isPartialPeriod = false,
  int? totalPages,
}) =>
    {
      'content': content,
      'pageNumber': 0,
      'pageSize': 20,
      'totalElements': content.length,
      'totalPages': totalPages ?? (content.isEmpty ? 0 : 1),
      'isLast': true,
      'metadata': {
        'rankingType': rankingType,
        'period': period,
        'state': state,
        'city': city,
        'category': category,
        'effectiveStart': effectiveStart,
        'periodEnd': periodEnd,
        'isPartialPeriod': isPartialPeriod,
      },
    };

class _Handler extends http.BaseClient {
  final Future<http.StreamedResponse> Function(http.BaseRequest request) handler;

  _Handler(this.handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) => handler(request);
}

void main() {
  // -------------------------------------------------------------------------------------------------------------
  group('RankingRepositoryImpl', () {
    Future<(RankingRepositoryImpl, List<http.Request>)> build(Object body) async {
      final requests = <http.Request>[];
      final storage = InMemoryTokenStorage();
      await storage.saveTokens(accessToken: 'token', refreshToken: 'refresh');
      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        tokenStorage: storage,
        client: _Handler((request) async {
          requests.add(request as http.Request);
          return http.StreamedResponse(Stream.value(utf8.encode(jsonEncode(body))), 200,
              headers: {'content-type': 'application/json; charset=utf-8'});
        }),
      );
      return (RankingRepositoryImpl(httpClient: client), requests);
    }

    test('cada métrica usa a própria rota, com período, UF, cidade, categoria e página; JWT da sessão, nenhum id',
        () async {
      final (repo, requests) = await build(_apiPage(period: 'MONTH', state: 'PR', city: 'Curitiba',
          category: 'CAFE', effectiveStart: '2026-12-01T03:00:00Z', periodEnd: '2027-01-01T03:00:00Z'));
      const filters =
          RankingFilters(period: RankingPeriod.month, state: 'PR', city: '  Curitiba ', category: 'CAFE');

      for (final metric in RankingMetric.values) {
        await repo.getRanking(metric, filters, page: 1);
      }

      expect(requests.map((r) => r.url.path), [
        '/api/v1/rankings/check-ins',
        '/api/v1/rankings/places-explored',
        '/api/v1/rankings/helpful-votes',
      ]);
      expect(requests.first.url.queryParameters, {
        'period': 'MONTH',
        'state': 'PR',
        'city': 'Curitiba',
        'category': 'CAFE',
        'page': '1',
        'size': '20',
      });
      for (final request in requests) {
        expect(request.headers['authorization'], 'Bearer token', reason: request.url.path);
        expect(request.url.queryParameters.keys, isNot(contains('userId')));
      }

      await repo.getRanking(RankingMetric.checkIns, const RankingFilters(state: 'SP', city: '  '));
      expect(requests.last.url.queryParameters, {'period': 'WEEK', 'state': 'SP', 'page': '0', 'size': '20'});
      expect(() => repo.getRanking(RankingMetric.checkIns, const RankingFilters()), throwsArgumentError);
    });

    test('401 AUTHENTICATION_REQUIRED nos rankings passa pela renovação de sessão existente e repete uma vez',
        () async {
      final storage = InMemoryTokenStorage();
      await storage.saveTokens(accessToken: 'token-vencido', refreshToken: 'refresh');
      final authorizations = <String?>[];
      var refreshes = 0;
      final inner = _Handler((request) async {
        authorizations.add(request.headers['authorization']);
        if (request.headers['authorization'] == 'Bearer token-vencido') {
          return http.StreamedResponse(
            Stream.value(utf8.encode(jsonEncode({'status': 401, 'code': 'AUTHENTICATION_REQUIRED'}))),
            401,
            headers: {'content-type': 'application/problem+json'},
          );
        }
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(_apiPage(content: [_apiItem(1, 'Ana', 3, me: true)])))),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      });
      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        tokenStorage: storage,
        client: TokenRefreshingClient(
          inner: inner,
          sessionRefresher: SessionRefresher(refreshAccessToken: () async {
            refreshes++;
            await storage.saveTokens(accessToken: 'token-novo', refreshToken: 'refresh-2');
            return 'token-novo';
          }),
        ),
      );

      final page = await RankingRepositoryImpl(httpClient: client)
          .getRanking(RankingMetric.placesExplored, const RankingFilters(state: 'PR'));

      expect(refreshes, 1);
      expect(authorizations, ['Bearer token-vencido', 'Bearer token-novo']);
      expect(page.entries.single.isCurrentUser, isTrue);
    });

    test('posições empatadas, usuário da sessão e campos extras do contrato real', () {
      final page = RankingRepositoryImpl.parsePage(
        _apiPage(
          rankingType: 'HELPFUL_VOTES',
          content: [
            _apiItem(1, 'Ana', 9, me: true, handle: 'ana', lastContributedAt: '2026-10-28T10:00:00Z'),
            _apiItem(1, 'Bruno', 9),
            _apiItem(2, 'Carla', 4),
          ],
        ),
        requestedPage: 0,
      );

      expect(page.entries.map((e) => e.rankPosition), [1, 1, 2], reason: 'DENSE_RANK do backend');
      expect(page.entries.first.isCurrentUser, isTrue);
      expect(page.entries.first.user.id, 'id-Ana');
      expect(page.entries.first.lastContributedAt, DateTime.utc(2026, 10, 28, 10));
      expect(page.entries[1].user.handle, isNull);
      expect(page.entries[1].isCurrentUser, isFalse);
      expect(page.totalElements, 3);
      expect(page.isLast, isTrue);
    });

    group('metadados do período (nomes do backend: effectiveStart, periodEnd, isPartialPeriod)', () {
      test('semana completa', () {
        final page = RankingRepositoryImpl.parsePage(
          _apiPage(period: 'WEEK', effectiveStart: '2026-11-02T03:00:00Z', periodEnd: '2026-11-09T03:00:00Z'),
          requestedPage: 0,
        );
        expect(page.periodStart, DateTime.utc(2026, 11, 2, 3));
        expect(page.periodEndExclusive, DateTime.utc(2026, 11, 9, 3));
        expect(page.partialPeriod, isFalse);
      });

      test('mês completo', () {
        final page = RankingRepositoryImpl.parsePage(
          _apiPage(period: 'MONTH', effectiveStart: '2026-12-01T03:00:00Z', periodEnd: '2027-01-01T03:00:00Z'),
          requestedPage: 0,
        );
        expect(page.periodStart, DateTime.utc(2026, 12, 1, 3));
        expect(page.periodEndExclusive, DateTime.utc(2027, 1, 1, 3));
        expect(page.partialPeriod, isFalse);
      });

      test('primeiro período parcial: o início efetivo é a ativação, com fração de segundo do Instant', () {
        final page = RankingRepositoryImpl.parsePage(
          _apiPage(
            period: 'WEEK',
            effectiveStart: '2026-10-29T22:18:56.123456Z',
            periodEnd: '2026-11-02T03:00:00Z',
            isPartialPeriod: true,
            content: [_apiItem(1, 'Ana', 2)],
          ),
          requestedPage: 0,
        );
        expect(page.partialPeriod, isTrue);
        expect(page.periodStart, DateTime.utc(2026, 10, 29, 22, 18, 56, 123, 456));
        expect(page.periodEndExclusive, DateTime.utc(2026, 11, 2, 3));
        expect(page.entries, hasLength(1));
      });

      test('período anterior à ativação: lista vazia, início efetivo igual ao fim e sem período parcial', () {
        final page = RankingRepositoryImpl.parsePage(
          _apiPage(
            period: 'WEEK',
            effectiveStart: '2026-10-26T03:00:00Z',
            periodEnd: '2026-10-26T03:00:00Z',
            totalPages: 0,
          ),
          requestedPage: 0,
        );
        expect(page.entries, isEmpty);
        expect(page.totalElements, 0);
        expect(page.isLast, isTrue);
        expect(page.partialPeriod, isFalse);
        expect(page.periodStart, page.periodEndExclusive);
      });

      test('metadados ausentes ou inválidos são erro de formato, nunca valores padrão', () {
        final valid = _apiPage();
        Map<String, dynamic> withMetadata(void Function(Map<String, dynamic> metadata) change) {
          final copy = jsonDecode(jsonEncode(valid)) as Map<String, dynamic>;
          change(copy['metadata'] as Map<String, dynamic>);
          return copy;
        }

        final cases = <String, Map<String, dynamic>>{
          'sem metadata': (jsonDecode(jsonEncode(valid)) as Map<String, dynamic>)..remove('metadata'),
          'metadata nulo': (jsonDecode(jsonEncode(valid)) as Map<String, dynamic>)..['metadata'] = null,
          'sem effectiveStart': withMetadata((m) => m.remove('effectiveStart')),
          'effectiveStart nulo': withMetadata((m) => m['effectiveStart'] = null),
          'effectiveStart inválido': withMetadata((m) => m['effectiveStart'] = 'ontem'),
          'periodEnd numérico': withMetadata((m) => m['periodEnd'] = 1793325466),
          'sem isPartialPeriod': withMetadata((m) => m.remove('isPartialPeriod')),
          'isPartialPeriod como texto': withMetadata((m) => m['isPartialPeriod'] = 'true'),
          'início depois do fim': withMetadata((m) => m['effectiveStart'] = '2026-11-10T03:00:00Z'),
          'só os nomes antigos': withMetadata((m) {
            m['periodStart'] = m.remove('effectiveStart');
            m['periodEndExclusive'] = m.remove('periodEnd');
            m['partialPeriod'] = m.remove('isPartialPeriod');
          }),
        };
        for (final entry in cases.entries) {
          expect(() => RankingRepositoryImpl.parsePage(entry.value, requestedPage: 0), throwsFormatException,
              reason: entry.key);
        }
        expect(RankingRepositoryImpl.parsePage(valid, requestedPage: 0).partialPeriod, isFalse);
      });
    });

    test('erros estruturais das posições não viram lista vazia', () {
      for (final content in <List<Object?>>[
        [{'rankPosition': '1', 'user': {'displayName': 'A'}, 'score': 1}],
        [{'rankPosition': 1, 'user': {'displayName': 'A'}, 'score': 1.5}],
        [{'rankPosition': 1, 'user': {}, 'score': 1}],
        [{'rankPosition': 0, 'user': {'displayName': 'A'}, 'score': 1}],
      ]) {
        final body = _apiPage()..['content'] = content;
        expect(() => RankingRepositoryImpl.parsePage(body, requestedPage: 0), throwsFormatException,
            reason: jsonEncode(content));
      }
      final withoutContent = _apiPage()..remove('content');
      expect(() => RankingRepositoryImpl.parsePage(withoutContent, requestedPage: 0), throwsFormatException);
    });

    test('preferência: GET e PUT só com showInRankings; resposta sem booleano é inválida', () async {
      final (repo, requests) = await build({'showInRankings': true});
      expect(await repo.getParticipation(), isTrue);
      expect(await repo.updateParticipation(true), isTrue);
      expect(requests.map((r) => '${r.method} ${r.url.path}'),
          ['GET /api/v1/me/ranking-preferences', 'PUT /api/v1/me/ranking-preferences']);
      expect(jsonDecode(requests.last.body), {'showInRankings': true});

      final (invalid, _) = await build({'showInRankings': 'sim'});
      expect(invalid.getParticipation(), throwsFormatException);
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Tela Rankings da Região', () {
    testWidgets('estado obrigatório: nada é consultado sem UF; a UF busca a semana por padrão', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(auth: auth);
      await _pumpRankings(tester, auth, repository);

      expect(find.text('Rankings da Região'), findsOneWidget);
      expect(find.byKey(const Key('rankings_state_required')), findsOneWidget);
      expect(repository.requests, isEmpty);

      await _chooseState(tester, 'PR');
      expect(repository.requests.single.metric, RankingMetric.checkIns);
      expect(repository.requests.single.filters, const RankingFilters(state: 'PR'));
      expect(find.byKey(const Key('rankings_state_required')), findsNothing);
      expect(find.byKey(const Key('rankings_empty')), findsOneWidget);
    });

    testWidgets('três rankings independentes: cada métrica consulta a própria rota e mostra só o seu valor',
        (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (request) => RankingPage(
          entries: [_entry(1, 'Ana', request.metric.index + 7)],
          pageNumber: 0,
          totalElements: 1,
          isLast: true,
        ),
      );
      await _pumpRankings(tester, auth, repository);
      await _chooseState(tester, 'PR');
      expect(find.text('@ana\n7 check-ins verificados'), findsOneWidget);

      await tester.tap(find.byKey(const Key('ranking_metric_placesExplored')));
      await tester.pumpAndSettle();
      expect(find.text('@ana\n8 locais distintos explorados'), findsOneWidget);
      expect(find.textContaining('check-ins verificados\n'), findsNothing);

      await tester.tap(find.byKey(const Key('ranking_metric_helpfulVotes')));
      await tester.pumpAndSettle();
      expect(find.text('@ana\n9 votos úteis recebidos'), findsOneWidget);

      expect(repository.requests.map((r) => r.metric),
          [RankingMetric.checkIns, RankingMetric.placesExplored, RankingMetric.helpfulVotes]);
      expect(find.textContaining('pontuação geral'), findsOneWidget, reason: 'o texto explica que não há nota geral');
      expect(find.textContaining('campeão'), findsNothing);
    });

    testWidgets('filtros de período, cidade e categoria recomeçam da primeira página', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(auth: auth);
      await _pumpRankings(tester, auth, repository, interests: _FakeInterestRepository());
      await _chooseState(tester, 'SC');

      await tester.tap(find.text('Mês'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('ranking_city_field')), ' Florianópolis ');
      await tester.tap(find.byKey(const Key('ranking_city_apply')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('ranking_category_field')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cafés e cafeterias').last);
      await tester.pumpAndSettle();

      expect(repository.requests.map((r) => r.filters).toList(), const [
        RankingFilters(state: 'SC'),
        RankingFilters(period: RankingPeriod.month, state: 'SC'),
        RankingFilters(period: RankingPeriod.month, state: 'SC', city: 'Florianópolis'),
        RankingFilters(period: RankingPeriod.month, state: 'SC', city: 'Florianópolis', category: 'CAFE'),
      ]);
      expect(repository.requests.every((r) => r.page == 0), isTrue);

      // Sem bairro: só UF, cidade e categoria
      expect(find.textContaining('Bairro'), findsNothing);
    });

    testWidgets('empates mostram a mesma posição, na ordem recebida, e período parcial é explicado', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (_) => RankingPage(
          entries: [
            _entry(1, 'Ana', 12, me: true),
            _entry(1, 'Bruno', 12),
            _entry(3, 'Carla', 5),
          ],
          pageNumber: 0,
          totalElements: 3,
          isLast: true,
          periodStart: DateTime.utc(2026, 10, 29, 15),
          partialPeriod: true,
        ),
      );
      await _pumpRankings(tester, auth, repository);
      await _chooseState(tester, 'PR');

      expect(_positions(tester), ['1º', '1º', '3º']);
      expect(find.text('Ana (você)'), findsOneWidget);
      expect(find.text('Bruno'), findsOneWidget);
      expect(find.byKey(const Key('rankings_partial_period')), findsOneWidget);
      expect(find.textContaining('A contagem começou em 29/10'), findsOneWidget);
    });

    testWidgets('resposta real do backend: período parcial mostra o aviso; semana completa não', (tester) async {
      final auth = await _signedIn();
      var partial = true;
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (_) => RankingRepositoryImpl.parsePage(
          _apiPage(
            content: [_apiItem(1, 'Ana', 2, me: true, handle: 'ana')],
            effectiveStart: partial ? '2026-10-29T15:00:00Z' : '2026-11-02T03:00:00Z',
            periodEnd: partial ? '2026-11-02T03:00:00Z' : '2026-11-09T03:00:00Z',
            isPartialPeriod: partial,
          ),
          requestedPage: 0,
        ),
      );
      await _pumpRankings(tester, auth, repository);
      await _chooseState(tester, 'PR');
      expect(find.byKey(const Key('rankings_partial_period')), findsOneWidget);
      expect(find.textContaining('A contagem começou em 29/10'), findsOneWidget);
      expect(find.text('Ana (você)'), findsOneWidget);

      partial = false;
      await _chooseState(tester, 'SP');
      expect(find.byKey(const Key('rankings_partial_period')), findsNothing);
      expect(find.text('Ana (você)'), findsOneWidget);
    });

    testWidgets('paginação até o Top 100 e lista vazia', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (request) => RankingPage(
          entries: [for (var i = 0; i < 20; i++) _entry(request.page * 20 + i + 1, 'P${request.page}$i', 100 - i)],
          pageNumber: request.page,
          totalElements: 140,
          isLast: false,
        ),
      );
      final notifier = RankingsNotifier(repository: repository, userId: 'ana', currentUserId: () => 'ana');
      addTearDown(notifier.dispose);
      _tallScreen(tester);
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: RankingsScreen(repository: repository, authNotifier: auth, notifier: notifier),
      ));
      await tester.pumpAndSettle();
      await _chooseState(tester, 'PR');
      expect(notifier.entries, hasLength(20));

      final list = find.descendant(of: find.byKey(const Key('rankings_list')), matching: find.byType(Scrollable));
      for (var page = 1; page < 5; page++) {
        await tester.scrollUntilVisible(find.byKey(const Key('rankings_load_more')), 600, scrollable: list.first);
        await tester.tap(find.byKey(const Key('rankings_load_more')));
        await tester.pumpAndSettle();
      }
      expect(notifier.entries, hasLength(100));
      expect(notifier.entries.last.rankPosition, 100);
      expect(notifier.hasMore, isFalse, reason: 'o backend publica só o Top 100');
      await tester.scrollUntilVisible(find.text('Os rankings mostram até as 100 primeiras posições.'), 600,
          scrollable: list.first);
      expect(find.byKey(const Key('rankings_load_more')), findsNothing);
      expect(find.text('100º'), findsOneWidget);
      expect(repository.requests.map((r) => r.page), [0, 1, 2, 3, 4]);
    });

    testWidgets('erro de rede com nova tentativa', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (_) => RankingPage(entries: [_entry(1, 'Ana', 3)], pageNumber: 0, totalElements: 1, isLast: true),
      )..nextError = const NetworkException('Sem conexão');
      await _pumpRankings(tester, auth, repository);
      await _chooseState(tester, 'PR');

      expect(find.byKey(const Key('rankings_error')), findsOneWidget);
      expect(find.textContaining('Sem conexão'), findsOneWidget);
      await tester.tap(find.byKey(const Key('rankings_retry')));
      await tester.pumpAndSettle();
      expect(_positions(tester), ['1º']);
    });

    testWidgets('resposta de filtro antigo não sobrescreve a seleção atual', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (request) => RankingPage(
          entries: [_entry(1, 'Topo ${request.filters.state}', 5)],
          pageNumber: 0,
          totalElements: 1,
          isLast: true,
        ),
      );
      final slowFirst = Completer<void>();
      repository.gates[1] = slowFirst;
      await _pumpRankings(tester, auth, repository);

      await _chooseState(tester, 'PR', settle: false);
      expect(find.byKey(const Key('rankings_loading')), findsOneWidget);
      await _chooseState(tester, 'SP');
      expect(find.text('Topo SP'), findsOneWidget);

      slowFirst.complete();
      await tester.pumpAndSettle();
      expect(find.text('Topo SP'), findsOneWidget);
      expect(find.text('Topo PR'), findsNothing);
    });

    testWidgets('gerenciar participação abre as configurações e recarrega os rankings na volta', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(auth: auth);
      await _pumpRankings(tester, auth, repository);
      await _chooseState(tester, 'PR');

      await tester.tap(find.byKey(const Key('rankings_manage_participation')));
      await tester.pumpAndSettle();
      expect(find.byType(AccountSettingsScreen), findsOneWidget);
      expect(find.text('Participar dos Rankings da Região'), findsOneWidget);
      await tester.tap(find.byKey(const Key('ranking_participation_switch')));
      await tester.pumpAndSettle();
      expect(repository.saves, [true]);

      Navigator.of(tester.element(find.byType(AccountSettingsScreen))).pop();
      await tester.pumpAndSettle();
      expect(repository.requests, hasLength(2), reason: 'os dados voltam do backend depois da mudança');
      expect(repository.requests.last.filters, const RankingFilters(state: 'PR'));
    });

    testWidgets('fim da sessão descarta a resposta em curso e fecha a tela', (tester) async {
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (_) => RankingPage(entries: [_entry(1, 'Ana', 5)], pageNumber: 0, totalElements: 1, isLast: true),
      );
      final pending = Completer<void>();
      repository.gates[1] = pending;
      _tallScreen(tester);
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => RankingsScreen(repository: repository, authNotifier: auth),
            )),
            child: const Text('abrir'),
          ),
        ),
      ));
      await tester.tap(find.text('abrir'));
      await tester.pumpAndSettle();
      await _chooseState(tester, 'PR', settle: false);
      expect(find.byKey(const Key('rankings_loading')), findsOneWidget);

      await auth.logout();
      await tester.pumpAndSettle();
      expect(find.byType(RankingsScreen), findsNothing);

      pending.complete();
      await tester.pumpAndSettle();
      expect(find.text('Ana'), findsNothing);
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Participação nos Rankings da Região', () {
    Future<_FakeRankingRepository> pumpSettings(WidgetTester tester,
        {bool? initial = false, Completer<void>? loadGate}) async {
      _tallScreen(tester);
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(auth: auth)
        ..participation = initial
        ..loadGate = loadGate;
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: AccountSettingsScreen(authNotifier: auth, rankingRepository: repository),
      ));
      return repository;
    }

    SwitchListTile switchTile(WidgetTester tester) =>
        tester.widget<SwitchListTile>(find.byKey(const Key('ranking_participation_switch')));

    testWidgets('começa desativada e bloqueada até o backend responder', (tester) async {
      final loadGate = Completer<void>();
      await pumpSettings(tester, loadGate: loadGate);
      await tester.pump();
      expect(switchTile(tester).value, isFalse);
      expect(switchTile(tester).onChanged, isNull, reason: 'antes da resposta nada é presumido');
      expect(find.text('Carregando...'), findsOneWidget);

      loadGate.complete();
      await tester.pumpAndSettle();
      expect(switchTile(tester).value, isFalse);
      expect(switchTile(tester).onChanged, isNotNull);
      expect(find.textContaining('não remove suas avaliações públicas do feed'), findsOneWidget);
    });

    testWidgets('ativar e desativar só mudam depois da confirmação do backend', (tester) async {
      final repository = await pumpSettings(tester);
      await tester.pumpAndSettle();
      repository.saveGate = Completer<void>();

      await tester.tap(find.byKey(const Key('ranking_participation_switch')));
      await tester.pump();
      expect(switchTile(tester).value, isFalse, reason: 'aguardando o backend');
      expect(find.text('Salvando...'), findsOneWidget);

      repository.saveGate!.complete();
      repository.saveGate = null;
      await tester.pumpAndSettle();
      expect(switchTile(tester).value, isTrue);

      await tester.tap(find.byKey(const Key('ranking_participation_switch')));
      await tester.pumpAndSettle();
      expect(switchTile(tester).value, isFalse);
      expect(repository.saves, [true, false]);
    });

    testWidgets('falha ao salvar mantém o valor confirmado e permite tentar de novo', (tester) async {
      final repository = await pumpSettings(tester);
      await tester.pumpAndSettle();
      repository.nextSaveError = const NetworkException('Sem conexão');

      await tester.tap(find.byKey(const Key('ranking_participation_switch')));
      await tester.pumpAndSettle();
      expect(switchTile(tester).value, isFalse);
      expect(find.byKey(const Key('ranking_participation_save_error')), findsOneWidget);

      await tester.tap(find.byKey(const Key('ranking_participation_retry')));
      await tester.pumpAndSettle();
      expect(switchTile(tester).value, isTrue);
      expect(find.byKey(const Key('ranking_participation_save_error')), findsNothing);
      expect(repository.saves, [true, true]);
    });

    testWidgets('falha ao carregar não presume participação; nova tentativa carrega o valor real', (tester) async {
      _tallScreen(tester);
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(auth: auth)
        ..participation = true
        ..participationLoadError = _problem(503, 'UNAVAILABLE', 'Serviço indisponível');
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: AccountSettingsScreen(authNotifier: auth, rankingRepository: repository),
      ));
      await tester.pumpAndSettle();

      expect(switchTile(tester).value, isFalse);
      expect(switchTile(tester).onChanged, isNull);
      expect(find.byKey(const Key('ranking_participation_load_error')), findsOneWidget);

      await tester.tap(find.byKey(const Key('ranking_participation_reload')));
      await tester.pumpAndSettle();
      expect(switchTile(tester).value, isTrue);
      expect(repository.saves, isEmpty);
    });

    test('outra sessão ativa: nada é gravado', () async {
      String? current = 'ana';
      final repository = _FakeRankingRepository();
      final notifier =
          RankingParticipationNotifier(repository: repository, userId: 'ana', currentUserId: () => current);
      await notifier.load();
      current = 'bruno';

      expect(await notifier.setParticipation(true), isFalse);
      expect(repository.saves, isEmpty);
      expect(notifier.confirmed, isFalse);
      expect(notifier.saveError, contains('sessão mudou'));
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Integração com o app', () {
    testWidgets('Home mostra a entrada Rankings da Região sem mudar as cinco abas', (tester) async {
      _tallScreen(tester);
      final auth = await _signedIn();
      final repository = _FakeRankingRepository(auth: auth);
      final router = AppRouter(authNotifier: auth, rankingRepository: repository);
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.home,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      final labels = tester
          .widgetList<NavigationDestination>(find.byType(NavigationDestination))
          .map((d) => d.label)
          .toList();
      expect(labels, ['Início', 'Buscar', 'Avaliar', 'Check-in', 'Perfil']);

      await tester.tap(find.byKey(const Key('home_rankings_entry')));
      await tester.pumpAndSettle();
      expect(find.byType(RankingsScreen), findsOneWidget);

      Navigator.of(tester.element(find.byType(RankingsScreen))).pop();
      await tester.pumpAndSettle();
      await tester.tap(find.text('Perfil'));
      await tester.pumpAndSettle();
      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 4);
    });

    testWidgets('sessão expirada e troca de conta: a nova conta começa sem os dados da anterior', (tester) async {
      _tallScreen(tester);
      final authRepository = _FakeAuthRepository(loginAs: 'ana');
      final auth = AuthNotifier(authRepository: authRepository);
      await auth.checkAuthStatus();
      final repository = _FakeRankingRepository(
        auth: auth,
        responder: (request) => RankingPage(
          entries: [_entry(1, 'Topo de ${request.sessionUser}', 5, me: true)],
          pageNumber: 0,
          totalElements: 1,
          isLast: true,
        ),
      );
      await tester.pumpWidget(RewitApp(
        config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
        router: AppRouter(authNotifier: auth, rankingRepository: repository),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('home_rankings_entry')));
      await tester.pumpAndSettle();
      await _chooseState(tester, 'PR');
      expect(find.text('Topo de ana (você)'), findsOneWidget);

      await auth.handleSessionExpired();
      await tester.pumpAndSettle();
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(find.byType(RankingsScreen), findsNothing);

      authRepository.loginAs = 'bruno';
      await auth.login('bruno@rewit.test', 'Senha@123');
      await tester.pumpAndSettle();
      expect(find.byType(HomeScreen), findsOneWidget);

      await tester.tap(find.byKey(const Key('home_rankings_entry')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('rankings_state_required')), findsOneWidget, reason: 'filtros e dados recomeçam');
      expect(find.textContaining('Topo de ana'), findsNothing);

      await _chooseState(tester, 'PR');
      expect(find.text('Topo de bruno (você)'), findsOneWidget);
      expect(repository.requests.map((r) => r.sessionUser), ['ana', 'bruno']);
    });
  });
}

ApiException _problem(int status, String code, String detail) => ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Erro',
      status: status,
      detail: detail,
      code: code,
    ));
