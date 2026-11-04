import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Contrato de persistência local da preferência de aparência do dispositivo.
///
/// A preferência pertence ao dispositivo/aplicativo (e não à conta autenticada),
/// sobrevivendo a reinicializações, logout e troca de sessão sem chamadas ao backend.
abstract class ThemePreferenceStorage {
  Future<ThemeMode?> readThemeMode();
  Future<void> writeThemeMode(ThemeMode mode);
}

/// Implementação em memória para testes e fallback resiliente.
class InMemoryThemePreferenceStorage implements ThemePreferenceStorage {
  ThemeMode? _storedMode;

  InMemoryThemePreferenceStorage({ThemeMode? initialMode})
      : _storedMode = initialMode;

  @override
  Future<ThemeMode?> readThemeMode() async => _storedMode;

  @override
  Future<void> writeThemeMode(ThemeMode mode) async {
    _storedMode = mode;
  }
}

/// Implementação persistente reutilizando `FlutterSecureStorage` já presente no projeto,
/// com chave global separada das chaves de sessão (`TokenStorage.clearTokens()` não a afeta).
class SecureThemePreferenceStorage implements ThemePreferenceStorage {
  static const String storageKey = 'rewit_app_theme_mode';

  final FlutterSecureStorage _storage;
  final InMemoryThemePreferenceStorage _fallback =
      InMemoryThemePreferenceStorage();
  bool _useFallback = false;

  SecureThemePreferenceStorage({FlutterSecureStorage? storage})
      : _storage = storage ??
            const FlutterSecureStorage(
              aOptions: AndroidOptions(resetOnError: true),
              iOptions: IOSOptions(
                accessibility: KeychainAccessibility.first_unlock,
              ),
            );

  @override
  Future<ThemeMode?> readThemeMode() async {
    if (_useFallback) return _fallback.readThemeMode();
    try {
      final raw = await _storage.read(key: storageKey);
      final parsed = _parseThemeMode(raw);
      return parsed ?? await _fallback.readThemeMode();
    } catch (_) {
      _useFallback = true;
      return _fallback.readThemeMode();
    }
  }

  @override
  Future<void> writeThemeMode(ThemeMode mode) async {
    await _fallback.writeThemeMode(mode);
    if (_useFallback) return;
    try {
      await _storage.write(key: storageKey, value: _serializeThemeMode(mode));
    } catch (_) {
      _useFallback = true;
    }
  }

  static String _serializeThemeMode(ThemeMode mode) {
    switch (mode) {
      case ThemeMode.light:
        return 'light';
      case ThemeMode.dark:
        return 'dark';
      case ThemeMode.system:
        return 'system';
    }
  }

  static ThemeMode? _parseThemeMode(String? raw) {
    switch (raw) {
      case 'light':
        return ThemeMode.light;
      case 'dark':
        return ThemeMode.dark;
      case 'system':
        return ThemeMode.system;
      default:
        return null;
    }
  }
}

/// Controlador reativo de aparência do aplicativo (`ThemeMode.system` por padrão).
class ThemeController extends ChangeNotifier {
  final ThemePreferenceStorage _storage;
  ThemeMode _themeMode;
  int _writeRevision = 0;

  ThemeController({
    ThemePreferenceStorage? storage,
    ThemeMode initialThemeMode = ThemeMode.system,
  })  : _storage = storage ?? InMemoryThemePreferenceStorage(),
        _themeMode = initialThemeMode;

  ThemeMode get themeMode => _themeMode;

  /// Carrega a preferência salva localmente. Caso não haja valor prévio,
  /// preserva [ThemeMode.system] como padrão inicial.
  Future<void> load() async {
    final revisionBeforeLoad = _writeRevision;
    final saved = await _storage.readThemeMode();
    if (_writeRevision != revisionBeforeLoad) {
      return;
    }
    if (saved != null && saved != _themeMode) {
      _themeMode = saved;
      notifyListeners();
    }
  }

  /// Atualiza imediatamente a aparência da interface e persiste a escolha localmente.
  Future<void> setThemeMode(ThemeMode mode) async {
    _writeRevision++;
    if (_themeMode != mode) {
      _themeMode = mode;
      notifyListeners();
    }
    await _storage.writeThemeMode(mode);
  }
}

/// Escopo na árvore de widgets para acesso desacoplado ao [ThemeController].
class ThemeControllerScope extends InheritedNotifier<ThemeController> {
  const ThemeControllerScope({
    super.key,
    required ThemeController controller,
    required super.child,
  }) : super(notifier: controller);

  static ThemeController? maybeOf(BuildContext context) {
    return context
        .dependOnInheritedWidgetOfExactType<ThemeControllerScope>()
        ?.notifier;
  }
}