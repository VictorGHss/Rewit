import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/discussions/data/repositories/discussion_repository_impl.dart';
import 'package:rewit_mobile/features/feed/data/repositories/feed_repository_impl.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/review_creation/data/repositories/review_creation_repository_impl.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 1. Carregar configuração de ambiente
  final config = AppConfig.fromEnvironment();

  // 2. Inicializar armazenamento seguro de tokens (KeyStore / Keychain com fallback resiliente)
  final tokenStorage = SecureTokenStorage();

  // 3. Inicializar cliente HTTP e repositórios
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

  final feedRepository = FeedRepositoryImpl(httpClient: httpClient);
  final discussionRepository = DiscussionRepositoryImpl(httpClient: httpClient);
  final reviewCreationRepository = ReviewCreationRepositoryImpl(httpClient: httpClient);

  authNotifier = AuthNotifier(authRepository: authRepository);
  final feedNotifier = FeedNotifier(feedRepository: feedRepository);

  // 4. Verificar se há sessão previamente salva
  await authNotifier.checkAuthStatus();

  // 5. Configurar roteamento e iniciar app
  final router = AppRouter(
    authNotifier: authNotifier,
    feedNotifier: feedNotifier,
    feedRepository: feedRepository,
    discussionRepository: discussionRepository,
    reviewCreationRepository: reviewCreationRepository,
  );


  runApp(
    RewitApp(
      config: config,
      router: router,
    ),
  );
}
