import 'dart:async';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/data/services/platform_social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/business/data/repositories/business_repository_impl.dart';
import 'package:rewit_mobile/features/business/data/repositories/business_response_repository_impl.dart';
import 'package:rewit_mobile/features/discussions/data/repositories/discussion_repository_impl.dart';
import 'package:rewit_mobile/features/feed/data/repositories/feed_repository_impl.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/place/data/repositories/place_commercial_repository_impl.dart';
import 'package:rewit_mobile/features/place/data/repositories/place_repository_impl.dart';
import 'package:rewit_mobile/features/place/data/services/secure_pending_place_registration_persistence.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place_discovery/data/repositories/place_discovery_repository_impl.dart';
import 'package:rewit_mobile/features/product/data/repositories/product_repository_impl.dart';
import 'package:rewit_mobile/features/profile/data/repositories/user_profile_repository_impl.dart';
import 'package:rewit_mobile/features/notifications/data/repositories/notification_repository_impl.dart';
import 'package:rewit_mobile/features/review_creation/data/repositories/review_creation_repository_impl.dart';
import 'package:rewit_mobile/features/review_creation/data/services/geolocator_location_service.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_detail/data/repositories/review_media_repository_impl.dart';
import 'package:rewit_mobile/features/search/data/repositories/search_repository_impl.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 1. Carregar configuração de ambiente
  final config = AppConfig.fromEnvironment();

  // 2. Inicializar armazenamento seguro de tokens (KeyStore / Keychain com fallback resiliente)
  final tokenStorage = SecureTokenStorage();

  // 3. Inicializar cliente HTTP e repositórios
  late final AuthNotifier authNotifier;
  late final AuthRepositoryImpl authRepository;

  // Renovação única e compartilhada do access token (15 min): evita encerrar a sessão enquanto o refresh vale
  final sessionRefresher = SessionRefresher(
    refreshAccessToken: () async => (await authRepository.refreshTokens()).tokens.accessToken,
  );

  final httpClient = RewitHttpClient(
    baseUrl: config.apiBaseUrl,
    timeout: config.timeout,
    client: TokenRefreshingClient(
      inner: http.Client(),
      sessionRefresher: sessionRefresher,
    ),
    tokenStorage: tokenStorage,
    onSessionExpired: () {
      unawaited(authNotifier.handleSessionExpired());
    },
  );

  authRepository = AuthRepositoryImpl(
    httpClient: httpClient,
    tokenStorage: tokenStorage,
  );

  final feedRepository = FeedRepositoryImpl(httpClient: httpClient);
  final discussionRepository = DiscussionRepositoryImpl(httpClient: httpClient);
  final reviewCreationRepository = ReviewCreationRepositoryImpl(httpClient: httpClient);
  final searchRepository = SearchRepositoryImpl(httpClient: httpClient);
  final userProfileRepository = UserProfileRepositoryImpl(httpClient: httpClient);
  final placeRepository = PlaceRepositoryImpl(httpClient: httpClient);
  final placeCommercialRepository = PlaceCommercialRepositoryImpl(httpClient: httpClient);
  final productRepository = ProductRepositoryImpl(httpClient: httpClient);
  final placeDiscoveryRepository = PlaceDiscoveryRepositoryImpl(httpClient: httpClient);
  final mediaRepository = ReviewMediaRepositoryImpl(client: httpClient);
  final mediaPickerService = ImagePickerMediaService();
  const locationService = GeolocatorLocationService();
  final notificationRepository = NotificationRepositoryImpl(httpClient: httpClient);
  final businessRepository = BusinessRepositoryImpl(httpClient: httpClient);
  final businessResponseRepository = BusinessResponseRepositoryImpl(httpClient: httpClient);
  // Cadastros de local com foto pendente sobrevivem a logout e reinício, com escopo por usuário
  final pendingRegistrationStore = PendingPlaceRegistrationStore(
    persistence: SecurePendingPlaceRegistrationPersistence(),
  );

  authNotifier = AuthNotifier(
    authRepository: authRepository,
    // Login social com os client IDs públicos definidos por build (--dart-define); sem eles, fica indisponível
    socialSignInService: PlatformSocialSignInService(config: SocialAuthConfig.fromEnvironment()),
    sessionRefresher: sessionRefresher,
    clearLocalSession: tokenStorage.clearTokens,
  );
  final feedNotifier = FeedNotifier(feedRepository: feedRepository);

  // 4. Verificar se há sessão previamente salva
  await authNotifier.checkAuthStatus();

  // 5. Configurar roteamento e iniciar app
  final router = AppRouter(
    authNotifier: authNotifier,
    feedNotifier: feedNotifier,
    feedRepository: feedRepository,
    reviewMediaRepository: mediaRepository,
    mediaPickerService: mediaPickerService,
    locationService: locationService,
    discussionRepository: discussionRepository,
    reviewCreationRepository: reviewCreationRepository,
    searchRepository: searchRepository,
    userProfileRepository: userProfileRepository,
    placeRepository: placeRepository,
    placeCommercialRepository: placeCommercialRepository,
    productRepository: productRepository,
    placeDiscoveryRepository: placeDiscoveryRepository,
    notificationRepository: notificationRepository,
    businessRepository: businessRepository,
    businessResponseRepository: businessResponseRepository,
    pendingRegistrationStore: pendingRegistrationStore,
  );


  runApp(
    RewitApp(
      config: config,
      router: router,
    ),
  );
}
