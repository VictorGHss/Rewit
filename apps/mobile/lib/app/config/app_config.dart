/// Configurações globais do aplicativo Rewit Mobile.
class AppConfig {
  final String apiBaseUrl;
  final Duration timeout;
  final String appName;

  const AppConfig({
    required this.apiBaseUrl,
    this.timeout = const Duration(seconds: 15),
    this.appName = 'Rewit',
  });

  /// Configuração padrão baseada em variáveis de ambiente de compilação (--dart-define)
  /// ou fallback seguro para desenvolvimento local.
  factory AppConfig.fromEnvironment() {
    const defaultUrl = String.fromEnvironment(
      'API_BASE_URL',
      defaultValue: 'http://localhost:8080',
    );
    return const AppConfig(apiBaseUrl: defaultUrl);
  }
}
