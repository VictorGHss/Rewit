import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/check_ins/data/repositories/check_in_repository_impl.dart';
import 'package:rewit_mobile/features/check_ins/domain/entities/check_in_entities.dart';
import 'package:rewit_mobile/features/check_ins/domain/repositories/check_in_repository.dart';
import 'package:rewit_mobile/features/check_ins/presentation/screens/check_in_screen.dart';
import 'package:rewit_mobile/features/check_ins/presentation/screens/my_check_ins_screen.dart';
import 'package:rewit_mobile/features/feed/data/models/feed_models.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/device_location.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';

// ---------------------------------------------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------------------------------------------

const _cafeId = '11111111-1111-4111-8111-111111111111';
const _bookstoreId = '22222222-2222-4222-8222-222222222222';

class _FakeLocationService implements LocationService {
  LocationResult result;
  int requests = 0;
  int settingsOpened = 0;

  _FakeLocationService(this.result);

  _FakeLocationService.at({double accuracy = 8})
      : result = LocationSuccess(DeviceLocation(latitude: -25.4384, longitude: -49.2849, accuracyMeters: accuracy));

  @override
  Future<LocationResult> getCurrentLocation({Duration timeout = const Duration(seconds: 10)}) async {
    requests++;
    return result;
  }

  @override
  Future<bool> openAppSettings() async {
    settingsOpened++;
    return true;
  }
}

class _FakeCheckInRepository implements CheckInRepository {
  List<NearbyPlace> nearby = const [
    NearbyPlace(id: _cafeId, name: 'Café Central', category: 'CAFETERIA', distanceMeters: 15.4),
    NearbyPlace(id: _bookstoreId, name: 'Livraria do Centro', distanceMeters: 1240),
  ];
  Object? nearbyError;
  final List<({double latitude, double longitude})> nearbyRequests = [];

  List<MyCheckInsPage> pages = const [];
  Object? historyError;
  final List<int> historyRequests = [];

  @override
  Future<List<NearbyPlace>> getNearbyPlaces({required double latitude, required double longitude}) async {
    nearbyRequests.add((latitude: latitude, longitude: longitude));
    final error = nearbyError;
    if (error != null) {
      nearbyError = null;
      throw error;
    }
    return nearby;
  }

  @override
  Future<MyCheckInsPage> getMyCheckIns({int page = 0, int size = 20}) async {
    historyRequests.add(page);
    final error = historyError;
    if (error != null) {
      historyError = null;
      throw error;
    }
    return pages[page];
  }
}

/// Backend simulado da criação: responde com o resultado de presença configurado, ou falha uma vez.
class _FakeReviewCreationRepository implements ReviewCreationRepository {
  final List<CreateReviewInput> inputs = [];
  CheckInResult result = const CheckInResult(status: CheckInResultStatus.verified);
  Object? nextError;
  Completer<void>? gate;

  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    inputs.add(input);
    if (gate != null) await gate!.future;
    final error = nextError;
    if (error != null) {
      nextError = null;
      throw error;
    }
    return FeedReview(
      id: 'review-${inputs.length}',
      author: const FeedAuthor(displayName: 'Ana'),
      contextPlaceId: input.contextPlaceId,
      isVerifiedOnSite: result.isVerified,
      visibility: 'PUBLIC',
      status: 'ACTIVE',
      createdAt: DateTime.utc(2026, 10, 29),
      checkIn: result,
    );
  }
}

ApiException _problem(int status, String code, String detail) => ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Erro',
      status: status,
      detail: detail,
      code: code,
    ));

class _FakeAuthRepository implements AuthRepository {
  static const user = AuthUserDto(id: 'user-ana', email: 'ana@rewit.test', handle: 'ana', displayName: 'Ana');

  @override
  Future<bool> hasStoredSession() async => true;
  @override
  Future<AuthUserDto> getMe() async => user;
  @override
  Future<Authenticated> login({required String email, required String password}) async =>
      const Authenticated(user: user, tokens: AuthTokens(accessToken: 'a', refreshToken: 'r'));
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
  Future<Authenticated> refreshTokens() => throw UnimplementedError();
  @override
  Future<void> logout() async {}
}

class _FakeProfileRepository implements UserProfileRepository {
  final UserProfile profile;

  _FakeProfileRepository(this.profile);

  @override
  Future<UserProfile> getUserProfile(String userId) async => profile;

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError(invocation.memberName.toString());
}

MyCheckIn _checkIn(String id, String place) => MyCheckIn(
      id: id,
      reviewId: 'review-$id',
      placeId: 'place-$id',
      placeName: place,
      placeCity: 'Curitiba',
      placeState: 'PR',
      verifiedAt: DateTime.utc(2026, 10, 28, 15, 30),
    );

Future<void> _tap(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Future<void> _pumpCheckIn(
  WidgetTester tester, {
  required _FakeLocationService location,
  required _FakeCheckInRepository checkIns,
  required _FakeReviewCreationRepository reviews,
  VoidCallback? onOpenHistory,
}) async {
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.lightTheme,
    home: CheckInScreen(
      reviewCreationRepository: reviews,
      locationService: location,
      checkInRepository: checkIns,
      onOpenHistory: onOpenHistory,
    ),
  ));
  await tester.pumpAndSettle();
}

/// Localização, local próximo e nota: o formulário fica pronto para publicar.
Future<void> _reachForm(WidgetTester tester, {String placeId = _cafeId}) async {
  await _tap(tester, find.byKey(const Key('checkin_locate_button')));
  await _tap(tester, find.byKey(Key('checkin_place_$placeId')));
  await _tap(tester, find.byIcon(Icons.star_border).at(3));
}

FilledButton _publishButton(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const Key('checkin_publish_button')));

void main() {
  // -------------------------------------------------------------------------------------------------------------
  group('CheckInRepositoryImpl', () {
    Future<(CheckInRepositoryImpl, List<http.BaseRequest>)> build(Object body) async {
      final requests = <http.BaseRequest>[];
      final storage = InMemoryTokenStorage();
      await storage.saveTokens(accessToken: 'token', refreshToken: 'refresh');
      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        tokenStorage: storage,
        client: _Handler((request) async {
          requests.add(request);
          return http.StreamedResponse(Stream.value(utf8.encode(jsonEncode(body))), 200,
              headers: {'content-type': 'application/json; charset=utf-8'});
        }),
      );
      return (CheckInRepositoryImpl(httpClient: client), requests);
    }

    test('locais próximos: GET /api/v1/places/nearby com a posição, raio de busca e limite', () async {
      final (repo, requests) = await build({
        'items': [
          {'id': _cafeId, 'name': 'Café Central', 'category': 'CAFETERIA', 'distanceMeters': 15.4,
            'latitude': -25.4385, 'longitude': -49.285},
        ],
        'limit': 20,
      });

      final places = await repo.getNearbyPlaces(latitude: -25.4384, longitude: -49.2849);

      expect(places.single.name, 'Café Central');
      expect(places.single.distanceMeters, 15.4);
      final uri = requests.single.url;
      expect(uri.path, '/api/v1/places/nearby');
      expect(uri.queryParameters, {
        'latitude': '-25.4384',
        'longitude': '-49.2849',
        'radiusMeters': '${CheckInRepositoryImpl.nearbySearchRadiusMeters}',
        'limit': '${CheckInRepositoryImpl.nearbySearchLimit}',
      });
      expect(requests.single.headers['authorization'], 'Bearer token');
    });

    test('histórico: GET /api/v1/me/check-ins paginado, sem enviar a conta', () async {
      final (repo, requests) = await build({
        'content': [
          {'id': 'c1', 'reviewId': 'r1', 'placeId': 'p1', 'placeName': 'Café', 'placeCategory': 'CAFETERIA',
            'placeCity': 'Curitiba', 'placeState': 'PR', 'verifiedAt': '2026-10-28T15:30:00Z'},
        ],
        'pageNumber': 1,
        'pageSize': 20,
        'totalElements': 21,
        'totalPages': 2,
        'isLast': true,
      });

      final page = await repo.getMyCheckIns(page: 1);

      expect(page.items.single.placeName, 'Café');
      expect(page.items.single.verifiedAt, DateTime.utc(2026, 10, 28, 15, 30));
      expect(page.totalElements, 21);
      expect(page.isLast, isTrue);
      expect(requests.single.url.path, '/api/v1/me/check-ins');
      expect(requests.single.url.queryParameters, {'page': '1', 'size': '20'});
    });

    test('resposta inválida vira FormatException, não lista vazia', () async {
      expect((await build({'items': 'x'})).$1.getNearbyPlaces(latitude: 0, longitude: 0),
          throwsA(isA<FormatException>()));
      expect((await build({'content': [{'id': 'c1'}]})).$1.getMyCheckIns(), throwsA(isA<FormatException>()));
    });

    test('resultado de presença da criação: status conhecido, motivo e valor desconhecido nunca verificado', () {
      final verified = FeedReviewDto.fromJson({
        'id': 'r1',
        'isVerifiedOnSite': true,
        'checkIn': {'status': 'VERIFIED', 'reason': null},
      }).toEntity();
      expect(verified.checkIn!.isVerified, isTrue);

      final repeated = FeedReviewDto.fromJson({
        'id': 'r2',
        'checkIn': {'status': 'NOT_VERIFIED', 'reason': 'RECENT_CHECK_IN'},
      }).toEntity();
      expect(repeated.checkIn!.status, CheckInResultStatus.notVerified);
      expect(repeated.checkIn!.reason, 'RECENT_CHECK_IN');

      expect(CheckInResult.fromJson({'status': 'ALGO_NOVO'})!.isVerified, isFalse);
      expect(FeedReviewDto.fromJson({'id': 'r3'}).toEntity().checkIn, isNull, reason: 'consultas não trazem checkIn');
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Aba Check-in', () {
    testWidgets('aparece na navegação principal e abre o fluxo de check-in', (tester) async {
      tester.view.physicalSize = const Size(400, 900);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final auth = AuthNotifier(authRepository: _FakeAuthRepository());
      await auth.checkAuthStatus();

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: HomeScreen(
          authNotifier: auth,
          reviewCreationRepository: _FakeReviewCreationRepository(),
          locationService: _FakeLocationService.at(),
          checkInRepository: _FakeCheckInRepository(),
        ),
      ));
      await tester.pumpAndSettle();

      final labels = tester
          .widgetList<NavigationDestination>(find.byType(NavigationDestination))
          .map((d) => d.label)
          .toList();
      expect(labels, ['Início', 'Buscar', 'Avaliar', 'Check-in', 'Perfil']);

      await tester.tap(find.byKey(const Key('home_check_in_tab')));
      await tester.pumpAndSettle();
      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 3);
      expect(find.byKey(const Key('checkin_locate_button')), findsOneWidget);
    });

    testWidgets('pede a localização só no toque, lista locais próximos e exige a nota antes de publicar',
        (tester) async {
      final location = _FakeLocationService.at();
      final checkIns = _FakeCheckInRepository();
      await _pumpCheckIn(tester, location: location, checkIns: checkIns, reviews: _FakeReviewCreationRepository());

      expect(location.requests, 0, reason: 'nada é pedido ao abrir a aba');
      expect(find.textContaining('Nada é acompanhado em segundo plano'), findsOneWidget);

      await _tap(tester, find.byKey(const Key('checkin_locate_button')));
      expect(location.requests, 1);
      expect(checkIns.nearbyRequests.single, (latitude: -25.4384, longitude: -49.2849));
      expect(find.text('Café Central'), findsOneWidget);
      expect(find.text('CAFETERIA · a 15 m'), findsOneWidget);
      expect(find.text('a 1,2 km'), findsOneWidget);

      await _tap(tester, find.byKey(const Key('checkin_place_$_cafeId')));
      expect(find.text('Sua nota (obrigatória)'), findsOneWidget);
      expect(_publishButton(tester).onPressed, isNull, reason: 'sem estrelas escolhidas');

      await _tap(tester, find.byIcon(Icons.star_border).at(3));
      expect(_publishButton(tester).onPressed, isNotNull);
    });

    testWidgets('publica a avaliação mínima com local, posição e precisão; presença só depois da resposta',
        (tester) async {
      final reviews = _FakeReviewCreationRepository()..gate = Completer<void>();
      await _pumpCheckIn(tester,
          location: _FakeLocationService.at(accuracy: 12),
          checkIns: _FakeCheckInRepository(),
          reviews: reviews);
      await _reachForm(tester);
      await tester.enterText(find.byKey(const Key('checkin_comment')), '  Café ótimo  ');

      await tester.tap(find.byKey(const Key('checkin_publish_button')));
      await tester.pump();
      expect(find.text('Presença verificada'), findsNothing, reason: 'nada é confirmado antes do servidor responder');
      expect(find.byKey(const Key('checkin_result_verified')), findsNothing);

      reviews.gate!.complete();
      await tester.pumpAndSettle();

      final input = reviews.inputs.single;
      expect(input.contextPlaceId, _cafeId);
      expect(input.targets.single.rateableTargetId, _cafeId);
      expect(input.targets.single.rating, 4.0);
      expect(input.userLatitude, -25.4384);
      expect(input.userLongitude, -49.2849);
      expect(input.locationAccuracyMeters, 12);
      expect(input.experienceText, 'Café ótimo');
      expect(find.byKey(const Key('checkin_result_verified')), findsOneWidget);
      expect(find.text('Presença verificada'), findsOneWidget);
    });

    testWidgets('publicação sem presença verificada mostra o motivo devolvido pelo backend', (tester) async {
      final cases = {
        'OUTSIDE_RADIUS': 'fora da área do local',
        'ACCURACY_TOO_LOW': 'precisão do GPS estava baixa demais',
        'ACCURACY_UNAVAILABLE': 'não informou a precisão',
        'RECENT_CHECK_IN': 'check-in verificado recente neste local',
      };
      for (final entry in cases.entries) {
        final reviews = _FakeReviewCreationRepository()
          ..result = CheckInResult(status: CheckInResultStatus.notVerified, reason: entry.key);
        await _pumpCheckIn(tester,
            location: _FakeLocationService.at(), checkIns: _FakeCheckInRepository(), reviews: reviews);
        await _reachForm(tester);
        await _tap(tester, find.byKey(const Key('checkin_publish_button')));

        expect(find.byKey(const Key('checkin_result_not_verified')), findsOneWidget, reason: entry.key);
        expect(find.text('Avaliação publicada sem check-in verificado'), findsOneWidget);
        expect(find.textContaining(entry.value), findsOneWidget, reason: entry.key);
        expect(find.text('Presença verificada'), findsNothing);
        await tester.pumpWidget(const SizedBox.shrink());
      }
    });

    testWidgets('precisão insuficiente: aviso neutro antes e o resultado real do backend depois', (tester) async {
      final reviews = _FakeReviewCreationRepository()
        ..result = const CheckInResult(status: CheckInResultStatus.notVerified, reason: 'ACCURACY_TOO_LOW');
      await _pumpCheckIn(tester,
          location: _FakeLocationService.at(accuracy: 850),
          checkIns: _FakeCheckInRepository(),
          reviews: reviews);

      await _tap(tester, find.byKey(const Key('checkin_locate_button')));
      expect(find.byKey(const Key('checkin_approximate_hint')), findsOneWidget);

      await _tap(tester, find.byKey(const Key('checkin_place_$_cafeId')));
      await _tap(tester, find.byIcon(Icons.star_border).at(4));
      await _tap(tester, find.byKey(const Key('checkin_publish_button')));

      expect(reviews.inputs.single.locationAccuracyMeters, 850, reason: 'o backend decide, o app só envia');
      expect(find.textContaining('precisão do GPS estava baixa demais'), findsOneWidget);
    });

    testWidgets('permissão negada não publica nem busca locais; negada para sempre oferece as configurações',
        (tester) async {
      final location = _FakeLocationService(const LocationFailure(
          reason: LocationFailureReason.permissionDenied, message: 'Permissão negada'));
      final checkIns = _FakeCheckInRepository();
      await _pumpCheckIn(tester, location: location, checkIns: checkIns, reviews: _FakeReviewCreationRepository());

      await _tap(tester, find.byKey(const Key('checkin_locate_button')));
      expect(find.byKey(const Key('checkin_location_error')), findsOneWidget);
      expect(find.textContaining('Sem permissão de localização'), findsOneWidget);
      expect(find.byKey(const Key('checkin_open_settings')), findsNothing);
      expect(checkIns.nearbyRequests, isEmpty);

      location.result = const LocationFailure(
          reason: LocationFailureReason.permissionDeniedForever, message: 'Permissão negada');
      await _tap(tester, find.byKey(const Key('checkin_locate_button')));
      await _tap(tester, find.byKey(const Key('checkin_open_settings')));
      expect(location.settingsOpened, 1);

      // Permissão concedida depois: nova tentativa segue o fluxo
      location.result =
          const LocationSuccess(DeviceLocation(latitude: -25.4384, longitude: -49.2849, accuracyMeters: 8));
      await _tap(tester, find.byKey(const Key('checkin_locate_button')));
      expect(find.text('Café Central'), findsOneWidget);
    });

    testWidgets('sem locais próximos e falha de rede ao buscá-los, com nova tentativa', (tester) async {
      final checkIns = _FakeCheckInRepository()
        ..nearbyError = const NetworkException('Sem conexão');
      await _pumpCheckIn(tester,
          location: _FakeLocationService.at(), checkIns: checkIns, reviews: _FakeReviewCreationRepository());

      await _tap(tester, find.byKey(const Key('checkin_locate_button')));
      expect(find.byKey(const Key('checkin_nearby_error')), findsOneWidget);

      checkIns.nearby = const [];
      await _tap(tester, find.byKey(const Key('checkin_nearby_retry')));
      expect(find.byKey(const Key('checkin_nearby_empty')), findsOneWidget);
      expect(checkIns.nearbyRequests, hasLength(2));
    });

    testWidgets('falha de rede ao publicar mantém o formulário; a nova tentativa publica uma vez', (tester) async {
      final reviews = _FakeReviewCreationRepository()..nextError = const NetworkException('Sem conexão com a internet.');
      await _pumpCheckIn(tester,
          location: _FakeLocationService.at(), checkIns: _FakeCheckInRepository(), reviews: reviews);
      await _reachForm(tester);

      await _tap(tester, find.byKey(const Key('checkin_publish_button')));
      expect(find.byKey(const Key('checkin_submit_error')), findsOneWidget);
      expect(find.text('Tentar novamente'), findsOneWidget);
      expect(find.byKey(const Key('checkin_result_verified')), findsNothing);

      await _tap(tester, find.byKey(const Key('checkin_publish_button')));
      expect(reviews.inputs, hasLength(2));
      expect(reviews.inputs.last.userLatitude, isNotNull, reason: 'a posição capturada continua valendo');
      expect(find.byKey(const Key('checkin_result_verified')), findsOneWidget);
    });

    testWidgets('novo check-in no mesmo local: o app pede de novo a posição e mostra o que o backend decidiu',
        (tester) async {
      final location = _FakeLocationService.at();
      final reviews = _FakeReviewCreationRepository();
      var historyOpened = 0;
      await _pumpCheckIn(tester,
          location: location,
          checkIns: _FakeCheckInRepository(),
          reviews: reviews,
          onOpenHistory: () => historyOpened++);
      await _reachForm(tester);
      await _tap(tester, find.byKey(const Key('checkin_publish_button')));
      expect(find.byKey(const Key('checkin_result_verified')), findsOneWidget);
      await _tap(tester, find.byKey(const Key('checkin_result_history')));
      expect(historyOpened, 1);

      reviews.result = const CheckInResult(status: CheckInResultStatus.notVerified, reason: 'RECENT_CHECK_IN');
      await _tap(tester, find.byKey(const Key('checkin_start_over')));
      expect(find.byKey(const Key('checkin_locate_button')), findsOneWidget, reason: 'a posição anterior foi descartada');
      await _reachForm(tester);
      await _tap(tester, find.byKey(const Key('checkin_publish_button')));

      expect(location.requests, 2);
      expect(reviews.inputs, hasLength(2));
      expect(find.byKey(const Key('checkin_result_not_verified')), findsOneWidget);
      expect(find.textContaining('check-in verificado recente'), findsOneWidget);
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Histórico de check-ins', () {
    Future<void> pumpHistory(WidgetTester tester, _FakeCheckInRepository repository) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: MyCheckInsScreen(repository: repository),
      ));
    }

    testWidgets('carregando, lista e próxima página', (tester) async {
      final repository = _FakeCheckInRepository()
        ..pages = [
          MyCheckInsPage(items: [_checkIn('c1', 'Café Central')], pageNumber: 0, totalElements: 2, isLast: false),
          MyCheckInsPage(items: [_checkIn('c2', 'Livraria do Centro')], pageNumber: 1, totalElements: 2, isLast: true),
        ];
      await pumpHistory(tester, repository);
      expect(find.text('Carregando seus check-ins...'), findsOneWidget);

      await tester.pumpAndSettle();
      expect(find.text('Café Central'), findsOneWidget);
      expect(find.textContaining('Curitiba - PR'), findsOneWidget);

      await _tap(tester, find.byKey(const Key('my_check_ins_load_more')));
      expect(find.text('Livraria do Centro'), findsOneWidget);
      expect(find.byKey(const Key('my_check_ins_load_more')), findsNothing);
      expect(repository.historyRequests, [0, 1]);
    });

    testWidgets('estado vazio explica como os check-ins aparecem', (tester) async {
      final repository = _FakeCheckInRepository()
        ..pages = const [MyCheckInsPage(items: [], pageNumber: 0, totalElements: 0, isLast: true)];
      await pumpHistory(tester, repository);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('my_check_ins_empty')), findsOneWidget);
    });

    testWidgets('erro com nova tentativa', (tester) async {
      final repository = _FakeCheckInRepository()
        ..historyError = _problem(503, 'SERVICE_UNAVAILABLE', 'Serviço indisponível')
        ..pages = [MyCheckInsPage(items: [_checkIn('c1', 'Café Central')], pageNumber: 0, totalElements: 1, isLast: true)];
      await pumpHistory(tester, repository);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('my_check_ins_error')), findsOneWidget);
      expect(find.text('Serviço indisponível'), findsOneWidget);

      await _tap(tester, find.text('Tentar novamente'));
      expect(find.text('Café Central'), findsOneWidget);
    });
  });

  // -------------------------------------------------------------------------------------------------------------
  group('Perfil com contadores do backend', () {
    testWidgets('próprio perfil mostra avaliações, check-ins verificados e votos úteis, e abre o histórico',
        (tester) async {
      tester.view.physicalSize = const Size(400, 1400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final semantics = tester.ensureSemantics();
      final auth = AuthNotifier(authRepository: _FakeAuthRepository());
      await auth.checkAuthStatus();
      final checkIns = _FakeCheckInRepository()
        ..pages = const [MyCheckInsPage(items: [], pageNumber: 0, totalElements: 0, isLast: true)];

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        onGenerateRoute: AppRouter(authNotifier: auth, checkInRepository: checkIns).onGenerateRoute,
        home: UserProfileScreen(
          userId: 'user-ana',
          authNotifier: auth,
          userProfileRepository: _FakeProfileRepository(const UserProfile(
            id: 'user-ana',
            handle: 'ana',
            displayName: 'Ana',
            stats: UserStats(totalReviews: 7, verifiedReviewsCount: 3, helpfulVotesReceived: 11),
          )),
        ),
      ));
      await tester.pumpAndSettle();

      Finder stat(String label, String count) => find.bySemanticsLabel('$count $label');
      expect(stat('Avaliações publicadas', '7'), findsOneWidget);
      expect(stat('Check-ins verificados', '3'), findsOneWidget);
      expect(stat('Votos úteis recebidos', '11'), findsOneWidget);
      expect(find.textContaining('Reputação'), findsNothing);
      semantics.dispose();

      await _tap(tester, find.byKey(const Key('my_check_ins_button')));
      expect(find.byType(MyCheckInsScreen), findsOneWidget);
      expect(checkIns.historyRequests, [0]);
    });
  });
}

class _Handler extends http.BaseClient {
  final Future<http.StreamedResponse> Function(http.BaseRequest request) handler;

  _Handler(this.handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) => handler(request);
}
