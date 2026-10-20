import 'dart:convert';
import 'dart:io';

import '../entities/place_detail.dart';
import '../../presentation/widgets/manual_place_registration_sheet.dart';

/// Persistência chave/valor usada pelo [PendingPlaceRegistrationStore].
///
/// Em produção é o armazenamento seguro do dispositivo (ver
/// `SecurePendingPlaceRegistrationPersistence`); em testes, [InMemoryPendingPlaceRegistrationPersistence].
abstract class PendingPlaceRegistrationPersistence {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Persistência em memória: sobrevive a logout/login dentro do processo, não a reinícios do app.
class InMemoryPendingPlaceRegistrationPersistence implements PendingPlaceRegistrationPersistence {
  final Map<String, String> _values = {};

  @override
  Future<String?> read(String key) async => _values[key];

  @override
  Future<void> write(String key, String value) async => _values[key] = value;

  @override
  Future<void> delete(String key) async => _values.remove(key);

  /// Chaves gravadas (diagnóstico em testes).
  Iterable<String> get keys => _values.keys;
}

/// Cadastros manuais de local cuja fotografia ficou pendente, com escopo estrito por usuário (MB-1c, MB-1d).
///
/// Invariantes:
/// * cada usuário autenticado tem o seu próprio registro (chave derivada do `userId`), e a leitura confere
///   que o registro gravado pertence ao usuário pedido — outra conta nunca vê nem retoma a operação;
/// * uma operação é identificada pelo local canônico (`place.id`) e pela chave UUID v4 do upload, nunca por
///   nome ou endereço; retomar a operação reaproveita o local já criado e a mesma chave;
/// * só o necessário para a retomada é gravado: identificador e dados de exibição do local, chave da operação
///   e caminho do arquivo local;
/// * se o arquivo local não existe mais, a operação volta com [PendingPlaceRegistration.photoUnavailable]
///   (recuperação indisponível) — nunca é redirecionada a outro local.
class PendingPlaceRegistrationStore {
  static const int _schemaVersion = 1;
  static const String _keyPrefix = 'rewit_pending_place_registration_v1_';
  static const int _maxOperationsPerUser = 10;
  static final RegExp _uuidV4 =
      RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

  final PendingPlaceRegistrationPersistence _persistence;
  final bool Function(String path) _fileExists;
  final DateTime Function() _now;

  PendingPlaceRegistrationStore({
    PendingPlaceRegistrationPersistence? persistence,
    bool Function(String path)? fileExists,
    DateTime Function()? now,
  })  : _persistence = persistence ?? InMemoryPendingPlaceRegistrationPersistence(),
        _fileExists = fileExists ?? ((path) => File(path).existsSync()),
        _now = now ?? DateTime.now;

  /// Store de processo usado apenas quando nenhum é injetado (widgets montados isoladamente).
  /// O app real injeta um store com persistência segura a partir de `main.dart`.
  static final PendingPlaceRegistrationStore processLocal = PendingPlaceRegistrationStore();

  static bool isValidOperationKey(String? key) => key != null && _uuidV4.hasMatch(key);

  static String _storageKey(String userId) => '$_keyPrefix${base64Url.encode(utf8.encode(userId))}';

  static String? _normalizeUser(String? userId) {
    final trimmed = userId?.trim();
    return (trimmed == null || trimmed.isEmpty) ? null : trimmed;
  }

  /// Operações pendentes do usuário, da mais recente para a mais antiga.
  Future<List<PendingPlaceRegistration>> loadAll(String? userId) async {
    final user = _normalizeUser(userId);
    if (user == null) return const [];
    final records = await _readRecords(user);
    return records.map((r) => _toRegistration(user, r)).toList(growable: false);
  }

  /// Operação pendente mais recente do usuário, ou `null`.
  Future<PendingPlaceRegistration?> loadLatest(String? userId) async {
    final all = await loadAll(userId);
    return all.isEmpty ? null : all.first;
  }

  /// Grava (ou substitui) a operação pendente do local `pending.place.id` para o usuário.
  Future<void> save(String userId, PendingPlaceRegistration pending) async {
    final user = _normalizeUser(userId);
    if (user == null) return;
    if (pending.userId != null && pending.userId != user) {
      throw ArgumentError('Operação pendente de outro usuário não pode ser gravada para $user');
    }
    if (!isValidOperationKey(pending.uploadIdempotencyKey)) {
      throw ArgumentError('A chave da operação precisa ser um UUID v4');
    }
    if (pending.place.id.trim().isEmpty) {
      throw ArgumentError('A operação precisa do identificador canônico do local');
    }
    final records = (await _readRecords(user))
        .where((r) => r['placeId'] != pending.place.id && r['operationKey'] != pending.uploadIdempotencyKey)
        .toList();
    records.insert(0, {
      'operationKey': pending.uploadIdempotencyKey,
      'placeId': pending.place.id,
      'placeName': pending.place.name,
      'placeSlug': pending.place.slug,
      'placeCategory': pending.place.category,
      'placeAddress': pending.place.addressText,
      'placeStatus': pending.place.status,
      'photoPath': pending.photoFile?.path,
      'savedAt': _now().toUtc().toIso8601String(),
    });
    await _writeRecords(user, records.take(_maxOperationsPerUser).toList());
  }

  /// Remove a operação do usuário identificada pela chave e/ou pelo local canônico.
  Future<void> clear(String? userId, {String? operationKey, String? placeId}) async {
    final user = _normalizeUser(userId);
    if (user == null || (operationKey == null && placeId == null)) return;
    final records = await _readRecords(user);
    final remaining = records
        .where((r) =>
            !((operationKey != null && r['operationKey'] == operationKey) ||
                (placeId != null && r['placeId'] == placeId)))
        .toList();
    if (remaining.length != records.length) {
      await _writeRecords(user, remaining);
    }
  }

  /// Remove todas as operações do usuário.
  Future<void> clearUser(String? userId) async {
    final user = _normalizeUser(userId);
    if (user == null) return;
    await _persistence.delete(_storageKey(user));
  }

  Future<List<Map<String, dynamic>>> _readRecords(String user) async {
    final key = _storageKey(user);
    final raw = await _persistence.read(key);
    if (raw == null || raw.isEmpty) return [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic> ||
          decoded['v'] != _schemaVersion ||
          decoded['userId'] != user ||
          decoded['operations'] is! List) {
        await _persistence.delete(key);
        return [];
      }
      final valid = <Map<String, dynamic>>[];
      for (final op in decoded['operations'] as List) {
        if (op is Map<String, dynamic> &&
            isValidOperationKey(op['operationKey'] as String?) &&
            op['placeId'] is String &&
            (op['placeId'] as String).trim().isNotEmpty &&
            op['placeName'] is String) {
          valid.add(op);
        }
      }
      return valid;
    } on FormatException {
      await _persistence.delete(key);
      return [];
    }
  }

  Future<void> _writeRecords(String user, List<Map<String, dynamic>> records) async {
    final key = _storageKey(user);
    if (records.isEmpty) {
      await _persistence.delete(key);
      return;
    }
    await _persistence.write(key, jsonEncode({'v': _schemaVersion, 'userId': user, 'operations': records}));
  }

  PendingPlaceRegistration _toRegistration(String user, Map<String, dynamic> r) {
    final path = r['photoPath'] as String?;
    final available = path != null && path.isNotEmpty && _fileExists(path);
    return PendingPlaceRegistration(
      place: PlaceDetail(
        id: r['placeId'] as String,
        name: r['placeName'] as String,
        slug: (r['placeSlug'] as String?) ?? '',
        category: (r['placeCategory'] as String?) ?? '',
        addressText: (r['placeAddress'] as String?) ?? '',
        validationRadiusMeters: 0,
        origin: 'MANUAL',
        isVerified: false,
        status: (r['placeStatus'] as String?) ?? 'ACTIVE',
      ),
      uploadIdempotencyKey: r['operationKey'] as String,
      photoFile: available ? File(path) : null,
      userId: user,
      photoUnavailable: !available,
    );
  }
}
