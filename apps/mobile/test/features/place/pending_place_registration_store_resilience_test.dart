import 'dart:async';
import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/place/data/services/secure_pending_place_registration_persistence.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/manual_place_registration_sheet.dart';

/// S3-A.13: fila do store por conta, com prazo, e persistência pela classe real sobre o FlutterSecureStorage.
/// Cada teste cria o próprio store: nenhuma operação herda `Future` de outro teste ou de outra zona.

const _placeA1 = '0000000a-0000-4000-8000-0000000000a1';
const _placeA2 = '0000000a-0000-4000-8000-0000000000a2';
const _placeB1 = '0000000b-0000-4000-8000-0000000000b1';

PendingPlaceRegistration _pending(String placeId, String key, String user) => PendingPlaceRegistration(
      place: PlaceDetail(
        id: placeId,
        name: 'Local $placeId',
        slug: 'local',
        category: 'GERAL',
        addressText: 'Rua',
        origin: 'MANUAL',
        validationRadiusMeters: 0,
        isVerified: false,
        status: 'ACTIVE',
      ),
      uploadIdempotencyKey: key,
      userId: user,
    );

String _key(int n) => '1b1f8c2e-4d5a-4b6c-8d7e-${n.toString().padLeft(12, '0')}';

/// Persistência com controle explícito de cada escrita (simula I/O lento, travado ou com falha).
class _ControlledPersistence implements PendingPlaceRegistrationPersistence {
  final InMemoryPendingPlaceRegistrationPersistence inner = InMemoryPendingPlaceRegistrationPersistence();
  final Map<String, Completer<void>> writeGates = {};
  int failNextWrites = 0;
  final List<String> appliedWrites = [];

  @override
  Future<String?> read(String key) => inner.read(key);

  @override
  Future<void> write(String key, String value) async {
    final gate = writeGates[key];
    if (gate != null) await gate.future;
    if (failNextWrites > 0) {
      failNextWrites--;
      throw StateError('falha simulada do armazenamento');
    }
    appliedWrites.add(key);
    await inner.write(key, value);
  }

  @override
  Future<void> delete(String key) => inner.delete(key);
}

/// Desfecho capturado no momento da chamada: um erro (ex.: prazo vencido) nunca fica sem tratador.
Future<Object?> _outcome(Future<void> operation) => operation.then<Object?>((_) => 'ok', onError: (Object e) => e);

String _storageKeyOf(String user) => 'rewit_pending_place_registration_v1_${base64Url.encode(utf8.encode(user))}';

/// Secure storage cujas operações falham (KeyStore/Keychain indisponível).
class _BrokenSecureStorage implements FlutterSecureStorage {
  @override
  dynamic noSuchMethod(Invocation invocation) => Future<Never>.error(StateError('keystore'));
}

void main() {
  const timeout = Duration(milliseconds: 150);

  group('fila por conta com prazo', () {
    test('falha de persistência numa operação não impede as seguintes', () async {
      final p = _ControlledPersistence()..failNextWrites = 1;
      final store = PendingPlaceRegistrationStore(persistence: p, fileExists: (_) => true, operationTimeout: timeout);
      await expectLater(store.save('a', _pending(_placeA1, _key(1), 'a')), throwsA(isA<StateError>()));
      await store.save('a', _pending(_placeA2, _key(2), 'a'));
      expect((await store.loadAll('a')).map((e) => e.place.id), [_placeA2]);
    });

    test('escrita que não termina não bloqueia outra conta', () async {
      final p = _ControlledPersistence();
      final store = PendingPlaceRegistrationStore(persistence: p, fileExists: (_) => true, operationTimeout: timeout);
      p.writeGates[_storageKeyOf('a')] = Completer<void>();
      final stuck = _outcome(store.save('a', _pending(_placeA1, _key(1), 'a')));

      final watch = Stopwatch()..start();
      await store.save('b', _pending(_placeB1, _key(3), 'b'));
      expect((await store.loadLatest('b'))!.place.id, _placeB1);
      expect(watch.elapsed, lessThan(timeout), reason: 'B não espera a fila de A');

      expect(await stuck, isA<PendingRegistrationStoreUnavailable>());
      p.writeGates[_storageKeyOf('a')]!.complete();
    });

    test('na mesma conta, operações que vencem atrás de uma escrita travada falham e nunca executam depois', () async {
      final p = _ControlledPersistence();
      final store = PendingPlaceRegistrationStore(persistence: p, fileExists: (_) => true, operationTimeout: timeout);
      final gate = p.writeGates[_storageKeyOf('a')] = Completer<void>();

      final first = _outcome(store.save('a', _pending(_placeA1, _key(1), 'a')));
      final second = _outcome(store.save('a', _pending(_placeA2, _key(2), 'a')));
      final clear = _outcome(store.clear('a', placeId: _placeA1));
      expect(await first, isA<PendingRegistrationStoreUnavailable>());
      expect(await second, isA<PendingRegistrationStoreUnavailable>());
      expect(await clear, isA<PendingRegistrationStoreUnavailable>());
      // Leitura vencida: nada é exibido, nada é alterado
      expect(await store.loadAll('a'), isEmpty);

      // A escrita antiga termina depois: só ela é aplicada; as vencidas não reaparecem fora de ordem
      p.writeGates.remove(_storageKeyOf('a'));
      gate.complete();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      expect(p.appliedWrites, [_storageKeyOf('a')]);
      expect((await store.loadAll('a')).map((e) => e.place.id), [_placeA1]);

      // A fila se recupera
      await store.clear('a', placeId: _placeA1);
      expect(await store.loadAll('a'), isEmpty);
    });

    test('save seguido de clear respeita a ordem de chamada, mesmo com a escrita lenta', () async {
      final p = _ControlledPersistence();
      final store = PendingPlaceRegistrationStore(persistence: p, fileExists: (_) => true, operationTimeout: timeout);
      final gate = p.writeGates[_storageKeyOf('a')] = Completer<void>();
      unawaited(store.save('a', _pending(_placeA1, _key(1), 'a')));
      final cleared = store.clear('a', placeId: _placeA1);
      p.writeGates.remove(_storageKeyOf('a'));
      gate.complete();
      await cleared;
      expect(await store.loadLatest('a'), isNull, reason: 'A escrita antiga não restaura a pendência limpa depois');
    });

    test('clear concluído nunca é desfeito por um save anterior atrasado', () async {
      final p = _ControlledPersistence();
      final store = PendingPlaceRegistrationStore(persistence: p, fileExists: (_) => true, operationTimeout: timeout);
      await store.save('a', _pending(_placeA1, _key(1), 'a'));
      final gate = p.writeGates[_storageKeyOf('a')] = Completer<void>();
      final staleSave = _outcome(store.save('a', _pending(_placeA2, _key(2), 'a')));
      final clearOutcome = await _outcome(store.clear('a', placeId: _placeA2));
      p.writeGates.remove(_storageKeyOf('a'));
      gate.complete();
      await staleSave;
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final ids = (await store.loadAll('a')).map((e) => e.place.id).toSet();
      // Ou o clear não foi aplicado (e o chamador soube), ou foi aplicado depois do save: nunca o contrário
      expect(clearOutcome, isA<PendingRegistrationStoreUnavailable>());
      expect(ids, {_placeA1, _placeA2});
      await store.clear('a', placeId: _placeA2);
      expect((await store.loadAll('a')).map((e) => e.place.id), [_placeA1]);
    });

    test('troca de conta durante a fila: escrita atrasada de A só alcança o registro de A', () async {
      final p = _ControlledPersistence();
      final store = PendingPlaceRegistrationStore(persistence: p, fileExists: (_) => true, operationTimeout: timeout);
      final gate = p.writeGates[_storageKeyOf('a')] = Completer<void>();
      final saveA = _outcome(store.save('a', _pending(_placeA1, _key(1), 'a')));
      expect(await store.loadAll('b'), isEmpty);
      await store.save('b', _pending(_placeB1, _key(3), 'b'));
      p.writeGates.remove(_storageKeyOf('a'));
      gate.complete();
      await saveA;
      await Future<void>.delayed(const Duration(milliseconds: 20));
      expect((await store.loadAll('b')).map((e) => e.place.id), [_placeB1]);
      expect((await store.loadAll('a')).map((e) => e.place.id), [_placeA1]);
      expect(() => store.save('b', _pending(_placeA1, _key(1), 'a')), throwsArgumentError);
    });
  });

  group('persistência real sobre FlutterSecureStorage', () {
    test('a pendência sobrevive ao reinício (instâncias novas, mesmo armazenamento do dispositivo)', () async {
      final device = <String, String>{};
      FlutterSecureStorage.setMockInitialValues(device);
      final storeBefore = PendingPlaceRegistrationStore(
          persistence: SecurePendingPlaceRegistrationPersistence(), fileExists: (_) => true);
      await storeBefore.save('user-a', _pending(_placeA1, _key(1), 'user-a'));
      await storeBefore.save('user-b', _pending(_placeB1, _key(3), 'user-b'));

      // Gravado no armazenamento seguro, uma entrada por conta e sem identificação além do id da conta
      expect(device.keys, hasLength(2));
      final rawA = jsonDecode(device[_storageKeyOf('user-a')]!) as Map<String, dynamic>;
      expect(rawA['userId'], 'user-a');
      expect((rawA['operations'] as List).single['operationKey'], _key(1));

      // "Reinício": persistência e store novos; o fallback em memória da instância anterior é descartado
      final storeAfter = PendingPlaceRegistrationStore(
          persistence: SecurePendingPlaceRegistrationPersistence(), fileExists: (_) => true);
      final recovered = await storeAfter.loadLatest('user-a');
      expect(recovered!.place.id, _placeA1);
      expect(recovered.uploadIdempotencyKey, _key(1));
      expect((await storeAfter.loadLatest('user-b'))!.place.id, _placeB1);

      await storeAfter.clear('user-a', placeId: _placeA1);
      expect(device.containsKey(_storageKeyOf('user-a')), isFalse);
      expect(device.containsKey(_storageKeyOf('user-b')), isTrue);
    });

    test('registro de outra conta copiado para a chave de A é descartado', () async {
      final device = <String, String>{};
      FlutterSecureStorage.setMockInitialValues(device);
      final store = PendingPlaceRegistrationStore(
          persistence: SecurePendingPlaceRegistrationPersistence(), fileExists: (_) => true);
      await store.save('user-b', _pending(_placeB1, _key(3), 'user-b'));
      device[_storageKeyOf('user-a')] = device[_storageKeyOf('user-b')]!;
      final fresh = PendingPlaceRegistrationStore(
          persistence: SecurePendingPlaceRegistrationPersistence(), fileExists: (_) => true);
      expect(await fresh.loadLatest('user-a'), isNull);
      expect(device.containsKey(_storageKeyOf('user-a')), isFalse);
    });

    test('armazenamento seguro indisponível: opera em memória no processo, sem quebrar o fluxo', () async {
      final store = PendingPlaceRegistrationStore(
          persistence: SecurePendingPlaceRegistrationPersistence(storage: _BrokenSecureStorage()), fileExists: (_) => true);
      await store.save('user-a', _pending(_placeA1, _key(1), 'user-a'));
      expect((await store.loadLatest('user-a'))!.place.id, _placeA1);
    });
  });
}
