import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';

class FakeFlutterSecureStorage extends FlutterSecureStorage {
  final Map<String, String> _data = {};
  bool shouldThrow = false;

  FakeFlutterSecureStorage() : super();

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
    if (shouldThrow) throw Exception('KeyStore unavailable');
    return _data[key];
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
    if (shouldThrow) throw Exception('KeyStore write error');
    if (value != null) {
      _data[key] = value;
    } else {
      _data.remove(key);
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
    if (shouldThrow) throw Exception('KeyStore delete error');
    _data.remove(key);
  }
}

void main() {
  group('SecureTokenStorage', () {
    test('salva e recupera tokens com sucesso', () async {
      final fakeStorage = FakeFlutterSecureStorage();
      final storage = SecureTokenStorage(storage: fakeStorage);

      expect(await storage.hasAccessToken(), isFalse);

      await storage.saveTokens(
        accessToken: 'access-jwt-123',
        refreshToken: 'refresh-jwt-456',
      );

      expect(await storage.hasAccessToken(), isTrue);
      expect(await storage.getAccessToken(), 'access-jwt-123');
      expect(await storage.getRefreshToken(), 'refresh-jwt-456');
    });

    test('limpa tokens com sucesso', () async {
      final fakeStorage = FakeFlutterSecureStorage();
      final storage = SecureTokenStorage(storage: fakeStorage);

      await storage.saveTokens(
        accessToken: 'access-jwt-123',
        refreshToken: 'refresh-jwt-456',
      );
      expect(await storage.hasAccessToken(), isTrue);

      await storage.clearTokens();

      expect(await storage.hasAccessToken(), isFalse);
      expect(await storage.getAccessToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
    });

    test('recupera graciosamente usando fallback em caso de falha do storage nativo', () async {
      final fakeStorage = FakeFlutterSecureStorage();
      final storage = SecureTokenStorage(storage: fakeStorage);

      // Salva antes do erro
      await storage.saveTokens(
        accessToken: 'cached-access-token',
        refreshToken: 'cached-refresh-token',
      );

      // Simula falha do KeyStore/Keychain
      fakeStorage.shouldThrow = true;

      // Não deve explodir exceção não tratada, deve usar fallback em memória
      expect(await storage.getAccessToken(), 'cached-access-token');
      expect(await storage.getRefreshToken(), 'cached-refresh-token');
      expect(await storage.hasAccessToken(), isTrue);
    });
  });
}
