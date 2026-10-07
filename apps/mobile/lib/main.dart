import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 1. Carregar configuração de ambiente
  final config = AppConfig.fromEnvironment();

  // 2. Inicializar armazenamento de tokens
  final tokenStorage = InMemoryTokenStorage();

  // 3. Inicializar repositório e gerenciador de autenticação
  late final AuthNotifier authNotifier;

  final httpClient = RewitHttpClient(
    baseUrl: config.apiBaseUrl,
    timeout: config.timeout,
    tokenStorage: tokenStorage,
    onSessionExpired: () {
      authNotifier.handleSessionExpired();
    },
  );

  final authRepository = AuthRepositoryImpl(
    httpClient: httpClient,
    tokenStorage: tokenStorage,
  );

  authNotifier = AuthNotifier(authRepository: authRepository);

  // 4. Verificar se há sessão previamente salva
  await authNotifier.checkAuthStatus();

  // 5. Configurar roteamento e iniciar app
  final router = AppRouter(authNotifier: authNotifier);

  runApp(
    RewitApp(
      config: config,
      router: router,
    ),
  );
}
