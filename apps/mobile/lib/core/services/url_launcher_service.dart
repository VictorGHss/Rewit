import 'package:url_launcher/url_launcher.dart';

/// Resultado estruturado de tentativa de abertura de link ou chamada.
enum LaunchResult {
  success,
  invalidScheme,
  invalidFormat,
  launchFailed,
}

/// Contrato de serviço para abertura segura de URLs externas e chamadas telefônicas.
abstract class UrlLauncherService {
  /// Abre uma URL web (somente esquemas http ou https).
  Future<LaunchResult> launchWebUrl(String url);

  /// Inicia uma chamada telefônica via esquema tel:.
  Future<LaunchResult> launchPhone(String phone);
}

/// Implementação padrão consumindo o pacote oficial [url_launcher].
class UrlLauncherServiceImpl implements UrlLauncherService {
  const UrlLauncherServiceImpl();

  @override
  Future<LaunchResult> launchWebUrl(String url) async {
    final trimmed = url.trim();
    if (trimmed.isEmpty) {
      return LaunchResult.invalidFormat;
    }

    final uri = Uri.tryParse(trimmed);
    if (uri == null) {
      return LaunchResult.invalidFormat;
    }

    final scheme = uri.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') {
      return LaunchResult.invalidScheme;
    }

    if (uri.host.isEmpty) {
      return LaunchResult.invalidFormat;
    }

    try {
      final launched = await launchUrl(
        uri,
        mode: LaunchMode.externalApplication,
      );
      return launched ? LaunchResult.success : LaunchResult.launchFailed;
    } catch (_) {
      return LaunchResult.launchFailed;
    }
  }

  @override
  Future<LaunchResult> launchPhone(String phone) async {
    final trimmed = phone.trim();
    if (trimmed.isEmpty) {
      return LaunchResult.invalidFormat;
    }

    // Preserva apenas dígitos e '+' inicial para o destino tel:
    final cleanDigits = trimmed.replaceAll(RegExp(r'[^0-9+]'), '');
    if (cleanDigits.isEmpty || cleanDigits.replaceAll('+', '').isEmpty) {
      return LaunchResult.invalidFormat;
    }

    final uri = Uri(scheme: 'tel', path: cleanDigits);
    try {
      final launched = await launchUrl(uri);
      return launched ? LaunchResult.success : LaunchResult.launchFailed;
    } catch (_) {
      return LaunchResult.launchFailed;
    }
  }
}
