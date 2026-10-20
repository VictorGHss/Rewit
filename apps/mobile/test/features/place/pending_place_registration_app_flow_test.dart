import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/manual_place_registration_sheet.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

/// MB-1c / MB-1d pela árvore REAL do app: MaterialApp -> AppRouter -> HomeScreen -> ReviewCreateScreen ->
/// TargetItemInputWidget -> TargetSearchSelectorWidget, com AuthNotifier real (repositório de autenticação
/// falso apenas na borda HTTP), logout pela interface e "reinício" do app sobre a mesma persistência.

const _userA = AuthUserDto(id: 'user-a', handle: 'ana', email: 'ana@rewit.test', displayName: 'Ana');
const _userB = AuthUserDto(id: 'user-b', handle: 'bruno', email: 'bruno@rewit.test', displayName: 'Bruno');
final _uuidV4 = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

class _FakeAuthRepository implements AuthRepository {
  @override
  Future<bool> hasStoredSession() async => false;

  @override
  Future<Authenticated> login({required String email, required String password}) async {
    final user = email == _userA.email ? _userA : _userB;
    return Authenticated(user: user, tokens: AuthTokens(accessToken: 'access-${user.id}', refreshToken: 'refresh-${user.id}'));
  }

  @override
  Future<void> logout() async {}

  @override
  Future<AuthUserDto> getMe() async => throw UnimplementedError();

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async => throw UnimplementedError();

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<void> changePassword({required String currentPassword, required String newPassword}) async {}
}

class _RecordingPlaceRepository implements PlaceRepository {
  final List<CreatePlaceInput> created = [];
  final List<Map<String, String?>> uploads = [];
  bool failUploads = true;

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) async {
    created.add(input);
    return PlaceDetail(
      id: '0000000${created.length}-0000-4000-8000-00000000000${created.length}',
      name: input.name,
      slug: 'slug-${created.length}',
      category: 'GERAL',
      addressText: input.addressText,
      origin: 'MANUAL',
      validationRadiusMeters: 100,
      isVerified: false,
      status: 'ACTIVE',
    );
  }

  @override
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? idempotencyKey,
  }) async {
    uploads.add({'placeId': placeId, 'idempotencyKey': idempotencyKey});
    if (failUploads) {
      throw const ApiException(ProblemDetail(
          type: 'about:blank', title: 'Falha', status: 500, code: 'STORAGE_ERR', detail: 'Falha no upload'));
    }
    return '/api/v1/places/$placeId/media/ok';
  }

  @override
  Future<PlaceDetail> getPlaceById(String id) async => throw UnimplementedError();

  @override
  Future<TargetStats> getTargetStats(String id) async => throw UnimplementedError();

  @override
  Future<TargetReviewsPage> getTargetReviews(String targetId,
          {int page = 0, int size = 10, String sort = 'newest', bool verifiedOnly = false}) async =>
      throw UnimplementedError();
}

class _EmptySearchRepository implements SearchRepository {
  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async => const SearchPage(
      items: [], pageNumber: 0, pageSize: 20, totalElements: 0, totalPages: 0, isLast: true);
}

/// Persistência que pode segurar leituras/gravações de um usuário para simular I/O lento.
class _GatedPersistence implements PendingPlaceRegistrationPersistence {
  final InMemoryPendingPlaceRegistrationPersistence inner = InMemoryPendingPlaceRegistrationPersistence();
  Completer<void>? readGate;
  Completer<void>? writeGate;

  @override
  Future<String?> read(String key) async {
    final gate = readGate;
    if (gate != null) await gate.future;
    return inner.read(key);
  }

  @override
  Future<void> write(String key, String value) async {
    final gate = writeGate;
    if (gate != null) await gate.future;
    return inner.write(key, value);
  }

  @override
  Future<void> delete(String key) => inner.delete(key);
}

/// Uma "execução" do app: notifier, store e router novos sobre a persistência compartilhada.
class _AppRun {
  final AuthNotifier auth;
  final PendingPlaceRegistrationStore store;
  final AppRouter router;

  _AppRun(this.auth, this.store, this.router);
}

void main() {
  late Directory tempDir;
  late File photo1;
  late File photo2;
  late _RecordingPlaceRepository places;
  late _GatedPersistence persistence;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('rewit_flow_');
    photo1 = File('${tempDir.path}/photo_1.jpg')..writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 1]));
    photo2 = File('${tempDir.path}/photo_2.jpg')..writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 2]));
    places = _RecordingPlaceRepository();
    persistence = _GatedPersistence();
  });

  tearDown(() {
    try {
      tempDir.deleteSync(recursive: true);
    } catch (_) {}
  });

  Future<_AppRun> startApp(WidgetTester tester, {File? Function()? pickPhoto}) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final auth = AuthNotifier(authRepository: _FakeAuthRepository());
    final store = PendingPlaceRegistrationStore(persistence: persistence);
    final router = AppRouter(
      authNotifier: auth,
      searchRepository: _EmptySearchRepository(),
      placeRepository: places,
      pendingRegistrationStore: store,
      onPickManualPlacePhoto: (_) async => (pickPhoto ?? () => photo1)(),
    );
    await auth.checkAuthStatus();
    // Nova árvore inteira (chave nova) = processo novo do app
    await tester.pumpWidget(MaterialApp(
      key: UniqueKey(),
      theme: AppTheme.lightTheme,
      initialRoute: AppRouter.root,
      onGenerateRoute: router.onGenerateRoute,
    ));
    await tester.pumpAndSettle();
    return _AppRun(auth, store, router);
  }

  Future<void> login(WidgetTester tester, _AppRun app, AuthUserDto user) async {
    await app.auth.login(user.email, 'senha');
    await tester.pumpAndSettle();
    expect(find.text('Avaliar'), findsOneWidget, reason: 'Home autenticada de ${user.id}');
  }

  Future<void> logoutThroughUi(WidgetTester tester) async {
    await tester.tap(find.byIcon(Icons.logout));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ElevatedButton, 'Sair'));
    await tester.pumpAndSettle();
    expect(find.text('Avaliar'), findsNothing, reason: 'Home desmontada após logout');
  }

  Future<void> openReviewTab(WidgetTester tester) async {
    await tester.tap(find.text('Avaliar'));
    await tester.pumpAndSettle();
  }

  Finder searchField() => find.widgetWithText(TextFormField, 'O que você quer avaliar? *');

  Future<void> registerPlaceWithFailingUpload(WidgetTester tester, String name, String address) async {
    await tester.enterText(searchField(), name);
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('register_manual_place_button')));
    await tester.tap(find.byKey(const Key('register_manual_place_button')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('manual_place_address_field')), address);
    await tester.tap(find.text('Câmera'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('manual_place_submit_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('manual_place_close_button')));
    await tester.pumpAndSettle();
  }

  Finder pendingBanner() => find.byKey(const Key('target_pending_registration_banner'));

  testWidgets(
      'Login, logout pela UI, troca de usuário, reinício e retomada: isolamento por conta, sem novo createPlace e mesma chave UUID v4',
      (tester) async {
    // ---- A inicia cadastro; upload falha
    var app = await startApp(tester);
    await login(tester, app, _userA);
    await openReviewTab(tester);
    await registerPlaceWithFailingUpload(tester, 'Café da Ana', 'Rua A, 1');
    // No fluxo real o local criado vira o alvo da avaliação; a pendência é anunciada e fica retomável
    expect(find.textContaining('o envio da fotografia ficou pendente'), findsOneWidget);
    expect(find.widgetWithText(SnackBarAction, 'Retomar'), findsNothing, reason: 'Sem ação ligada a um seletor que será desmontado');
    await tester.tap(find.widgetWithText(OutlinedButton, 'Trocar'));
    await tester.pumpAndSettle();
    expect(pendingBanner(), findsOneWidget);
    expect(places.created, hasLength(1));
    final pendingA = await app.store.loadLatest(_userA.id);
    expect(pendingA, isNotNull);
    final keyA = pendingA!.uploadIdempotencyKey;
    final placeA = pendingA.place.id;
    expect(keyA, matches(_uuidV4));
    expect(places.uploads.single['idempotencyKey'], keyA);

    // ---- A sai da conta pela interface; B entra e não vê nem retoma a operação de A
    await logoutThroughUi(tester);
    await login(tester, app, _userB);
    await openReviewTab(tester);
    expect(pendingBanner(), findsNothing);
    expect(find.textContaining('Café da Ana'), findsNothing);
    expect(await app.store.loadLatest(_userB.id), isNull);

    // ---- B inicia uma operação independente: chave própria
    await registerPlaceWithFailingUpload(tester, 'Bar do Bruno', 'Rua B, 2');
    final pendingB = await app.store.loadLatest(_userB.id);
    expect(pendingB, isNotNull);
    expect(pendingB!.uploadIdempotencyKey, matches(_uuidV4));
    expect(pendingB.uploadIdempotencyKey, isNot(keyA));
    expect(find.textContaining('Bar do Bruno'), findsWidgets);
    await logoutThroughUi(tester);

    // ---- "Reinício" do app: notifier, store e router novos sobre a mesma persistência
    app = await startApp(tester);
    await login(tester, app, _userA);
    await openReviewTab(tester);
    expect(pendingBanner(), findsOneWidget);
    expect(find.textContaining('Café da Ana'), findsWidgets);
    expect(find.textContaining('Bar do Bruno'), findsNothing);

    // ---- A retoma: reabre o mesmo local, nenhum createPlace novo e a MESMA chave em cada retry
    await tester.tap(find.byKey(const Key('target_resume_pending_registration_button')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('manual_place_partial_created_banner')), findsOneWidget);
    await tester.tap(find.byKey(const Key('manual_place_submit_button')));
    await tester.pumpAndSettle();
    places.failUploads = false;
    await tester.tap(find.byKey(const Key('manual_place_submit_button')));
    await tester.pumpAndSettle();

    expect(places.created, hasLength(2), reason: 'Só os cadastros originais de A e B; a retomada não cria local');
    final uploadsA = places.uploads.where((u) => u['placeId'] == placeA).toList();
    expect(uploadsA, hasLength(3));
    expect(uploadsA.map((u) => u['idempotencyKey']).toSet(), {keyA});
    expect(await app.store.loadLatest(_userA.id), isNull);
    expect(pendingBanner(), findsNothing);
    expect(await app.store.loadLatest(_userB.id), isNotNull, reason: 'A operação de B continua sua');

    // ---- Nova pesquisa e novo cadastro de A: operação independente, novo local e chave nova
    await tester.tap(find.widgetWithText(OutlinedButton, 'Trocar'));
    await tester.pumpAndSettle();
    places.failUploads = true;
    await registerPlaceWithFailingUpload(tester, 'Sorveteria da Ana', 'Rua F, 6');
    expect(places.created, hasLength(3));
    final second = await app.store.loadLatest(_userA.id);
    expect(second!.place.name, 'Sorveteria da Ana');
    expect(second.place.id, isNot(placeA));
    expect(second.uploadIdempotencyKey, matches(_uuidV4));
    expect(second.uploadIdempotencyKey, isNot(keyA));
  });

  testWidgets('Arquivo local ausente: estado explícito de recuperação indisponível, mesmo local, sem novo createPlace',
      (tester) async {
    final lost = File('${tempDir.path}/lost.jpg')..writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 3]));
    var app = await startApp(tester, pickPhoto: () => lost);
    await login(tester, app, _userA);
    await openReviewTab(tester);
    await registerPlaceWithFailingUpload(tester, 'Bistrô Perdido', 'Rua C, 3');
    final original = (await app.store.loadLatest(_userA.id))!;
    await logoutThroughUi(tester);

    lost.deleteSync();
    app = await startApp(tester, pickPhoto: () => photo2);
    await login(tester, app, _userA);
    await openReviewTab(tester);

    expect(find.byKey(const Key('target_pending_photo_unavailable_text')), findsOneWidget);
    final reloaded = await app.store.loadLatest(_userA.id);
    expect(reloaded!.photoUnavailable, isTrue);
    expect(reloaded.place.id, original.place.id);

    await tester.tap(find.byKey(const Key('target_resume_pending_registration_button')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('manual_place_partial_created_banner')), findsOneWidget);
    expect(find.byKey(const Key('manual_place_photo_preview')), findsNothing, reason: 'Foto perdida não é reaproveitada');

    places.failUploads = false;
    await tester.tap(find.text('Galeria'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('manual_place_submit_button')));
    await tester.pumpAndSettle();

    expect(places.created, hasLength(1), reason: 'Retomada sem foto não cria outro local');
    expect(places.uploads.last['placeId'], original.place.id, reason: 'Nunca redirecionada a outro local');
    expect(places.uploads.last['idempotencyKey'], matches(_uuidV4));
    expect(places.uploads.last['idempotencyKey'], isNot(original.uploadIdempotencyKey),
        reason: 'Nova fotografia é nova operação lógica de upload');
    expect(await app.store.loadLatest(_userA.id), isNull);
  });

  testWidgets('Leitura assíncrona da sessão anterior concluída após troca de conta não restaura estado na conta atual',
      (tester) async {
    var app = await startApp(tester);
    const seededPlace = PlaceDetail(
      id: '0000000a-0000-4000-8000-00000000000a',
      name: 'Padaria da Ana',
      slug: 'padaria',
      category: 'GERAL',
      addressText: 'Rua D, 4',
      origin: 'MANUAL',
      validationRadiusMeters: 100,
      isVerified: false,
      status: 'ACTIVE',
    );
    await app.store.save(_userA.id, PendingPlaceRegistration(
        place: seededPlace, uploadIdempotencyKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', photoFile: photo1, userId: _userA.id));

    // Leitura de A fica presa enquanto A sai e B entra
    persistence.readGate = Completer<void>();
    await login(tester, app, _userA);
    await openReviewTab(tester);
    expect(pendingBanner(), findsNothing);
    await logoutThroughUi(tester);
    final gate = persistence.readGate!;
    persistence.readGate = null;
    await login(tester, app, _userB);
    await openReviewTab(tester);
    gate.complete();
    await tester.pumpAndSettle();
    expect(pendingBanner(), findsNothing);
    expect(find.textContaining('Padaria da Ana'), findsNothing);

    // Gravação de A presa enquanto a conta muda: persiste sob A, nunca aparece para B
    await logoutThroughUi(tester);
    await login(tester, app, _userA);
    await openReviewTab(tester);
    expect(pendingBanner(), findsOneWidget);
    persistence.writeGate = Completer<void>();
    await registerPlaceWithFailingUpload(tester, 'Mercado da Ana', 'Rua E, 5');
    await logoutThroughUi(tester);
    final writeGate = persistence.writeGate!;
    persistence.writeGate = null;
    await login(tester, app, _userB);
    await openReviewTab(tester);
    writeGate.complete();
    await tester.pumpAndSettle();
    expect(find.textContaining('Mercado da Ana'), findsNothing);
    expect(pendingBanner(), findsNothing);
    final allA = await app.store.loadAll(_userA.id);
    expect(allA.map((p) => p.place.name), containsAll(['Mercado da Ana', 'Padaria da Ana']));
    expect(await app.store.loadAll(_userB.id), isEmpty);
  });

  group('PendingPlaceRegistrationStore', () {
    test('rejeita registros de outra conta, chave não UUID v4 e dados corrompidos', () async {
      final raw = InMemoryPendingPlaceRegistrationPersistence();
      final store = PendingPlaceRegistrationStore(persistence: raw);
      const place = PlaceDetail(
          id: 'p-1', name: 'L', slug: 's', category: 'c', addressText: 'a', origin: 'MANUAL',
          validationRadiusMeters: 0, isVerified: false, status: 'ACTIVE');
      expect(() => store.save('u1', const PendingPlaceRegistration(place: place, uploadIdempotencyKey: 'not-a-uuid')),
          throwsArgumentError);
      expect(
          () => store.save('u1',
              const PendingPlaceRegistration(place: place, uploadIdempotencyKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', userId: 'u2')),
          throwsArgumentError);

      await store.save('u1', const PendingPlaceRegistration(place: place, uploadIdempotencyKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'));
      final key = raw.keys.single;
      // Registro gravado sob a chave de u1, mas declarando outra conta: descartado
      final tampered = (await raw.read(key))!.replaceFirst('"userId":"u1"', '"userId":"u2"');
      await raw.write(key, tampered);
      expect(await store.loadLatest('u1'), isNull);
      expect(raw.keys, isEmpty);

      await raw.write(key, '{nao-json');
      expect(await store.loadLatest('u1'), isNull);
      expect(await store.loadLatest(null), isNull);
      expect(await store.loadLatest('  '), isNull);
    });

    test('uma operação por local canônico; limpar por local não afeta as demais', () async {
      final store = PendingPlaceRegistrationStore(fileExists: (_) => true);
      PlaceDetail place(String id) => PlaceDetail(
          id: id, name: 'L$id', slug: 's', category: 'c', addressText: 'a', origin: 'MANUAL',
          validationRadiusMeters: 0, isVerified: false, status: 'ACTIVE');
      await store.save('u1', PendingPlaceRegistration(place: place('x'), uploadIdempotencyKey: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'));
      await store.save('u1', PendingPlaceRegistration(place: place('y'), uploadIdempotencyKey: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'));
      await store.save('u1', PendingPlaceRegistration(place: place('x'), uploadIdempotencyKey: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'));
      final all = await store.loadAll('u1');
      expect(all.map((p) => p.place.id), ['x', 'y']);
      expect(all.first.uploadIdempotencyKey, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc');
      await store.clear('u1', placeId: 'x');
      expect((await store.loadAll('u1')).map((p) => p.place.id), ['y']);
      expect(await store.loadAll('u2'), isEmpty);
    });
  });
}
