import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/storage/session_token_coordinator.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';

class _ControlledTokenStorage implements TokenStorage {
  String? _accessToken;
  String? _refreshToken;
  final List<String> ops = <String>[];
  Future<void> Function(String access, String refresh)? onSave;
  Future<void> Function()? onClear;

  _ControlledTokenStorage({String? initialAccess, String? initialRefresh})
      : _accessToken = initialAccess,
        _refreshToken = initialRefresh;

  @override
  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    ops.add('save:$accessToken/$refreshToken');
    if (onSave != null) {
      await onSave!(accessToken, refreshToken);
    }
    _accessToken = accessToken;
    _refreshToken = refreshToken;
  }

  @override
  Future<String?> getAccessToken() async => _accessToken;

  @override
  Future<String?> getRefreshToken() async => _refreshToken;

  @override
  Future<bool> hasAccessToken() async =>
      _accessToken != null && _accessToken!.isNotEmpty;

  @override
  Future<void> clearTokens() async {
    ops.add('clear');
    if (onClear != null) {
      await onClear!();
    }
    _accessToken = null;
    _refreshToken = null;
  }
}

void main() {
  group('SessionTokenCoordinator', () {
    test('serializa mutações em FIFO quando saveTokens fica suspensa no meio do await e garante limpeza antes da sessão seguinte', () async {
      final storage = _ControlledTokenStorage(
        initialAccess: 'acc-a0',
        initialRefresh: 'ref-a0',
      );
      final coordinator = SessionTokenCoordinator(storage: storage);
      final genA = await coordinator.saveNewSessionTokens(
        accessToken: 'acc-a1',
        refreshToken: 'ref-a1',
      );

      final staleSaveStarted = Completer<void>();
      final staleSaveRelease = Completer<void>();
      storage.onSave = (access, _) async {
        if (access == 'acc-a-stale-refresh') {
          staleSaveStarted.complete();
          await staleSaveRelease.future;
        }
      };

      // Refresh A entra na fila e suspende no meio de storage.saveTokens
      final staleRefreshSave = coordinator.saveRefreshedTokens(
        accessToken: 'acc-a-stale-refresh',
        refreshToken: 'ref-a-stale-refresh',
        expectedGeneration: genA,
      );
      await staleSaveStarted.future;

      // ACCOUNT_DISABLED invalida geração sincronamente e enfileira clearTokens
      final disabledClear = coordinator.invalidateAndClear();
      // Em seguida Login B persiste novos tokens na fila
      final loginBSave = coordinator.saveNewSessionTokens(
        accessToken: 'acc-b1',
        refreshToken: 'ref-b1',
      );

      // Nem clear nem loginB executam antes da escrita A suspensa concluir
      expect(storage.ops, <String>[
        'save:acc-a1/ref-a1',
        'save:acc-a-stale-refresh/ref-a-stale-refresh',
      ]);

      staleSaveRelease.complete();
      final results = await Future.wait([
        staleRefreshSave,
        disabledClear.then((_) => null),
        loginBSave,
      ]);

      expect(results[0], isTrue);
      expect(results[2], isA<int>());
      expect(storage.ops, <String>[
        'save:acc-a1/ref-a1',
        'save:acc-a-stale-refresh/ref-a-stale-refresh',
        'clear',
        'save:acc-b1/ref-b1',
      ]);
      expect(await storage.getAccessToken(), 'acc-b1');
      expect(await storage.getRefreshToken(), 'ref-b1');
    });

    test('rejeita saveRefreshedTokens sem tocar storage quando geração foi invalidada antes de executar', () async {
      final storage = _ControlledTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final genA = await coordinator.saveNewSessionTokens(
        accessToken: 'acc-a',
        refreshToken: 'ref-a',
      );

      coordinator.invalidateSession();
      final saved = await coordinator.saveRefreshedTokens(
        accessToken: 'acc-a2',
        refreshToken: 'ref-a2',
        expectedGeneration: genA,
      );

      expect(saved, isFalse);
      expect(storage.ops, <String>['save:acc-a/ref-a']);
      expect(await storage.getAccessToken(), 'acc-a');
    });

    test('clearTokensForGeneration tardio é ignorado se geração mais nova já substituiu a sessão', () async {
      final storage = _ControlledTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final oldInvalidGen = coordinator.invalidateSession();

      await coordinator.saveNewSessionTokens(
        accessToken: 'acc-b',
        refreshToken: 'ref-b',
      );

      // Mesmo enfileirando clear de uma geração anterior, o coordenador preserva B
      await coordinator.clearTokensForGeneration(oldInvalidGen);

      expect(await storage.getAccessToken(), 'acc-b');
      expect(await storage.getRefreshToken(), 'ref-b');
    });

    test('falha em uma operação da fila não trava operações subsequentes', () async {
      final storage = _ControlledTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      storage.onSave = (access, _) async {
        if (access == 'bad') {
          throw StateError('erro simulado de escrita');
        }
      };

      await expectLater(
        coordinator.saveNewSessionTokens(
          accessToken: 'bad',
          refreshToken: 'bad-r',
        ),
        throwsStateError,
      );

      await coordinator.saveNewSessionTokens(
        accessToken: 'good-acc',
        refreshToken: 'good-ref',
      );

      expect(await storage.getAccessToken(), 'good-acc');
      expect(await storage.getRefreshToken(), 'good-ref');
    });

    test('prevalece a intenção de autenticação mais recente: tentativa A antiga na mesma geração falha mesmo respondendo antes ou depois de B', () async {
      final storage = _ControlledTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);

      final attemptA = coordinator.beginAuthenticationAttempt();
      final attemptB = coordinator.beginAuthenticationAttempt();
      expect(attemptB, greaterThan(attemptA));
      expect(coordinator.currentGeneration, 0);
      expect(coordinator.activeAuthenticationAttemptId, attemptB);

      // Mesmo respondendo primeiro, A já perdeu propriedade para B e não toca storage
      await expectLater(
        coordinator.saveNewSessionTokens(
          accessToken: 'acc-a',
          refreshToken: 'ref-a',
          expectedAttemptId: attemptA,
        ),
        throwsA(isA<StaleAuthenticationAttemptException>()),
      );
      expect(storage.ops, isEmpty);
      expect(coordinator.currentGeneration, 0);

      // B persiste com sucesso
      final genB = await coordinator.saveNewSessionTokens(
        accessToken: 'acc-b',
        refreshToken: 'ref-b',
        expectedAttemptId: attemptB,
      );
      expect(genB, 1);
      expect(coordinator.currentGeneration, 1);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
      expect(await storage.getAccessToken(), 'acc-b');
      expect(await storage.getRefreshToken(), 'ref-b');
    });

    test('abandonAuthenticationAttempt em B impede ressurreição tardia de A sem alterar geração', () async {
      final storage = _ControlledTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);

      final attemptA = coordinator.beginAuthenticationAttempt();
      final attemptB = coordinator.beginAuthenticationAttempt();
      coordinator.abandonAuthenticationAttempt(attemptB);

      expect(coordinator.activeAuthenticationAttemptId, isNull);
      expect(coordinator.currentGeneration, 0);

      await expectLater(
        coordinator.saveNewSessionTokens(
          accessToken: 'acc-a',
          refreshToken: 'ref-a',
          expectedAttemptId: attemptA,
        ),
        throwsA(isA<StaleAuthenticationAttemptException>()),
      );
      expect(storage.ops, isEmpty);
      expect(coordinator.currentGeneration, 0);
    });

    test('saveTokens(A) suspenso no meio de I/O quando B inicia executa compensação FIFO antes de B gravar e preserva B', () async {
      final storage = _ControlledTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);

      final attemptA = coordinator.beginAuthenticationAttempt();
      final writeAStarted = Completer<void>();
      final writeARelease = Completer<void>();
      storage.onSave = (access, _) async {
        if (access == 'acc-a') {
          writeAStarted.complete();
          await writeARelease.future;
        }
      };

      final persistA = coordinator.saveNewSessionTokens(
        accessToken: 'acc-a',
        refreshToken: 'ref-a',
        expectedAttemptId: attemptA,
      );
      await writeAStarted.future;

      // Enquanto saveTokens(A) está suspenso no meio de I/O, tentativa B inicia e vence
      final attemptB = coordinator.beginAuthenticationAttempt();
      final persistB = coordinator.saveNewSessionTokens(
        accessToken: 'acc-b',
        refreshToken: 'ref-b',
        expectedAttemptId: attemptB,
      );

      writeARelease.complete();

      await expectLater(
        persistA,
        throwsA(isA<StaleAuthenticationAttemptException>()),
      );
      final genB = await persistB;

      expect(storage.ops, <String>[
        'save:acc-a/ref-a',
        'clear',
        'save:acc-b/ref-b',
      ]);
      expect(await storage.getAccessToken(), 'acc-b');
      expect(await storage.getRefreshToken(), 'ref-b');
      expect(coordinator.currentGeneration, genB);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
    });

    test('beginAuthenticationAttempt não invalida refresh legítimo da sessão vigente antes do commit', () async {
      final storage = _ControlledTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final genS = await coordinator.saveNewSessionTokens(
        accessToken: 'acc-s1',
        refreshToken: 'ref-s1',
      );

      // Tentativa concorrente só reserva identidade de autenticação; não derruba refresh vigente
      coordinator.beginAuthenticationAttempt();
      final refreshed = await coordinator.saveRefreshedTokens(
        accessToken: 'acc-s2',
        refreshToken: 'ref-s2',
        expectedGeneration: genS,
      );

      expect(refreshed, isTrue);
      expect(await storage.getAccessToken(), 'acc-s2');
      expect(await storage.getRefreshToken(), 'ref-s2');
      expect(coordinator.currentGeneration, genS);
    });
  });
}
