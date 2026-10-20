import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';

/// Persistência das operações pendentes de cadastro de local no armazenamento seguro do dispositivo
/// (KeyStore no Android, Keychain no iOS), o mesmo já usado pelos tokens de sessão.
///
/// Se o armazenamento seguro falhar, passa a operar em memória (como `SecureTokenStorage`): a retomada
/// continua válida dentro do processo, sem derrubar o fluxo de cadastro.
class SecurePendingPlaceRegistrationPersistence implements PendingPlaceRegistrationPersistence {
  final FlutterSecureStorage _storage;
  final InMemoryPendingPlaceRegistrationPersistence _fallback = InMemoryPendingPlaceRegistrationPersistence();
  bool _useFallback = false;

  SecurePendingPlaceRegistrationPersistence({FlutterSecureStorage? storage})
      : _storage = storage ??
            const FlutterSecureStorage(
              aOptions: AndroidOptions(resetOnError: true),
              iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
            );

  @override
  Future<String?> read(String key) async {
    if (_useFallback) return _fallback.read(key);
    try {
      return await _storage.read(key: key) ?? await _fallback.read(key);
    } catch (_) {
      _useFallback = true;
      return _fallback.read(key);
    }
  }

  @override
  Future<void> write(String key, String value) async {
    await _fallback.write(key, value);
    if (_useFallback) return;
    try {
      await _storage.write(key: key, value: value);
    } catch (_) {
      _useFallback = true;
    }
  }

  @override
  Future<void> delete(String key) async {
    await _fallback.delete(key);
    if (_useFallback) return;
    try {
      await _storage.delete(key: key);
    } catch (_) {
      _useFallback = true;
    }
  }
}
