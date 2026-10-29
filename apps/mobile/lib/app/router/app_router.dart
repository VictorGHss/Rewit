import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/business/domain/repositories/business_repository.dart';
import 'package:rewit_mobile/features/business/domain/repositories/business_response_repository.dart';
import 'package:rewit_mobile/features/business/presentation/screens/my_business_accounts_screen.dart';
import 'package:rewit_mobile/features/business/presentation/screens/place_management_dashboard_screen.dart';
import 'package:rewit_mobile/features/check_ins/domain/repositories/check_in_repository.dart';
import 'package:rewit_mobile/features/check_ins/presentation/screens/my_check_ins_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_media_gallery_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_amenities_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_opening_hours_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_offers_management_screen.dart';

import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/interests/presentation/screens/edit_interests_screen.dart';
import 'package:rewit_mobile/features/interests/presentation/widgets/interests_gate.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/notifications/presentation/screens/notifications_screen.dart';
import 'package:rewit_mobile/features/notifications/presentation/state/notifications_notifier.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_detail_screen.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_business_profile_edit_screen.dart';
import 'package:rewit_mobile/features/place_discovery/domain/repositories/place_discovery_repository.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/product/presentation/screens/product_detail_screen.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/change_password_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/follow_list_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/my_reviews_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_screen.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';
import 'package:rewit_mobile/features/scanner/presentation/screens/scanner_screen.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/screens/search_screen.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Definição de rotas nomeadas e gerador centralizado de navegação.
class AppRouter {
  static const String root = '/';
  static const String login = '/login';
  static const String home = '/home';
  static const String notifications = '/notifications';
  static const String search = '/search';
  static const String placeDetail = '/place/detail';
  static const String productDetail = '/product/detail';
  static const String reviewCreate = '/review/create';
  static const String profile = '/profile';
  static const String myReviews = '/profile/my-reviews';
  static const String myCheckIns = '/profile/my-check-ins';
  static const String followList = '/profile/follows';
  static const String reviewDetail = '/review/detail';
  static const String accountSettings = '/settings';
  static const String changePassword = '/settings/change-password';
  static const String businessAccounts = '/settings/business-accounts';
  static const String editInterests = '/settings/interests';
  static const String placeManagement = '/business/place-management';
  static const String placeMediaGallery = '/place/media-gallery';
  static const String placeOpeningHoursEdit = '/business/place-opening-hours-edit';
  static const String placeAmenitiesEdit = '/business/place-amenities-edit';
  static const String placeOffersManagement = '/business/place-offers-management';
  static const String placeBusinessProfileEdit = '/business/place-business-profile-edit';

  static const String scanner = '/scanner';

  final AuthNotifier authNotifier;
  final FeedNotifier? feedNotifier;
  final FeedRepository? feedRepository;
  final ReviewMediaRepository? reviewMediaRepository;
  final MediaPickerService? mediaPickerService;
  final LocationService? locationService;
  final DiscussionRepository? discussionRepository;
  final ReviewCreationRepository? reviewCreationRepository;
  final SearchRepository? searchRepository;
  final UserProfileRepository? userProfileRepository;
  final PlaceRepository? placeRepository;
  final PlaceCommercialRepository? placeCommercialRepository;
  final ProductRepository? productRepository;
  final PlaceDiscoveryRepository? placeDiscoveryRepository;
  final NotificationRepository? notificationRepository;
  final NotificationsNotifier? notificationsNotifier;
  final BusinessRepository? businessRepository;
  final BusinessResponseRepository? businessResponseRepository;

  /// Interesses do usuário; com ele, a sessão autenticada passa pelo questionário inicial antes do app.
  final InterestRepository? interestRepository;

  /// Operações pendentes de cadastro de local, com escopo por usuário (MB-1c/MB-1d).
  final PendingPlaceRegistrationStore? pendingRegistrationStore;

  /// Locais próximos da aba Check-in e histórico de check-ins verificados.
  final CheckInRepository? checkInRepository;

  /// Seletor da fotografia do cadastro manual; nulo usa a câmera/galeria do dispositivo.
  final Future<File?> Function(ImageSource source)? onPickManualPlacePhoto;

  const AppRouter({
    required this.authNotifier,
    this.feedNotifier,
    this.feedRepository,
    this.reviewMediaRepository,
    this.mediaPickerService,
    this.locationService,
    this.discussionRepository,
    this.reviewCreationRepository,
    this.searchRepository,
    this.userProfileRepository,
    this.placeRepository,
    this.placeCommercialRepository,
    this.productRepository,
    this.placeDiscoveryRepository,
    this.notificationRepository,
    this.notificationsNotifier,
    this.businessRepository,
    this.businessResponseRepository,
    this.interestRepository,
    this.pendingRegistrationStore,
    this.onPickManualPlacePhoto,
    this.checkInRepository,
  });

  String? _currentUserId() {
    final state = authNotifier.state;
    return state is Authenticated ? state.user.id : null;
  }

  /// App da sessão autenticada; com [interestRepository], passa antes pelo questionário inicial de interesses. O
  /// portão tem uma chave por usuário: outra conta começa do zero, sem herdar estado nem respostas.
  Widget _authenticatedHome(BuildContext context, Authenticated session, WidgetBuilder home) {
    final repository = interestRepository;
    if (repository == null) return home(context);
    return InterestsGate(
      key: ValueKey('interests-gate-${session.user.id}'),
      userId: session.user.id,
      repository: repository,
      currentUserId: _currentUserId,
      homeBuilder: home,
      onLogout: authNotifier.logout,
    );
  }

  Route<dynamic> onGenerateRoute(RouteSettings settings) {
    switch (settings.name) {
      case root:
        return MaterialPageRoute(
          builder: (context) => ListenableBuilder(
            listenable: authNotifier,
            builder: (context, _) {
              final state = authNotifier.state;
              if (state is AuthInitial) {
                return const Scaffold(
                  body: LoadingIndicator(message: 'Iniciando Rewit...'),
                );
              }
              if (state is Authenticated) {
                return _authenticatedHome(
                  context,
                  state,
                  (context) => HomeScreen(
                    authNotifier: authNotifier,
                    feedNotifier: feedNotifier,
                    reviewCreationRepository: reviewCreationRepository,
                    mediaRepository: reviewMediaRepository,
                    mediaPickerService: mediaPickerService,
                    locationService: locationService,
                    searchRepository: searchRepository,
                    placeRepository: placeRepository,
                    productRepository: productRepository,
                    placeDiscoveryRepository: placeDiscoveryRepository,
                    userProfileRepository: userProfileRepository,
                    notificationRepository: notificationRepository,
                    notificationsNotifier: notificationsNotifier,
                    pendingRegistrationStore: pendingRegistrationStore,
                    onPickManualPlacePhoto: onPickManualPlacePhoto,
                    checkInRepository: checkInRepository,
                  ),
                );
              }
              return LoginScreen(authNotifier: authNotifier);
            },
          ),
          settings: settings,
        );

      case login:
        return MaterialPageRoute(
          builder: (context) => LoginScreen(authNotifier: authNotifier),
          settings: settings,
        );

      case home:
        Widget buildHome(BuildContext context) => HomeScreen(
              authNotifier: authNotifier,
              feedNotifier: feedNotifier,
              reviewCreationRepository: reviewCreationRepository,
              mediaRepository: reviewMediaRepository,
              mediaPickerService: mediaPickerService,
              locationService: locationService,
              searchRepository: searchRepository,
              placeRepository: placeRepository,
              productRepository: productRepository,
              placeDiscoveryRepository: placeDiscoveryRepository,
              userProfileRepository: userProfileRepository,
              notificationRepository: notificationRepository,
              notificationsNotifier: notificationsNotifier,
              checkInRepository: checkInRepository,
            );
        return MaterialPageRoute(
          builder: (context) {
            final state = authNotifier.state;
            return state is Authenticated ? _authenticatedHome(context, state, buildHome) : buildHome(context);
          },
          settings: settings,
        );

      case notifications:
        final passedNotifier = settings.arguments as NotificationsNotifier?;
        return MaterialPageRoute(
          builder: (context) => NotificationsScreen(
            repository: notificationRepository,
            notifier: passedNotifier ?? notificationsNotifier,
          ),
          settings: settings,
        );

      case reviewDetail:
        final args = settings.arguments;
        final currentUserId = (authNotifier.state is Authenticated)
            ? (authNotifier.state as Authenticated).user.id
            : null;
        if (args is ReviewDetailArgs) {
          return MaterialPageRoute(
            builder: (context) => ReviewDetailScreen(
              reviewId: args.reviewId,
              initialReview: args.initialReview,
              targetDiscussionId: args.targetDiscussionId,
              rootDiscussionId: args.rootDiscussionId,
              isReplyTarget: args.isReplyTarget,
              feedRepository: feedRepository,
              mediaRepository: reviewMediaRepository,
              discussionRepository: discussionRepository,
              feedNotifier: feedNotifier,
              businessResponseRepository: businessResponseRepository,
              businessRepository: businessRepository,
              currentUserId: currentUserId,
            ),
            settings: settings,
          );
        } else if (args is FeedReview) {
          return MaterialPageRoute(
            builder: (context) => ReviewDetailScreen(
              reviewId: args.id,
              initialReview: args,
              feedRepository: feedRepository,
              mediaRepository: reviewMediaRepository,
              discussionRepository: discussionRepository,
              feedNotifier: feedNotifier,
              businessResponseRepository: businessResponseRepository,
              businessRepository: businessRepository,
              currentUserId: currentUserId,
            ),
            settings: settings,
          );
        } else if (args is String) {
          return MaterialPageRoute(
            builder: (context) => ReviewDetailScreen(
              reviewId: args,
              feedRepository: feedRepository,
              mediaRepository: reviewMediaRepository,
              discussionRepository: discussionRepository,
              feedNotifier: feedNotifier,
              businessResponseRepository: businessResponseRepository,
              businessRepository: businessRepository,
              currentUserId: currentUserId,
            ),
            settings: settings,
          );
        }

        return MaterialPageRoute(
          builder: (context) => const Scaffold(
            body: Center(child: Text('Identificador de avaliação ausente.')),
          ),
          settings: settings,
        );

      case search:
        return MaterialPageRoute(
          builder: (context) => SearchScreen(
            searchRepository: searchRepository,
            placeRepository: placeRepository,
            authNotifier: authNotifier,
            pendingRegistrationStore: pendingRegistrationStore,
            onPickManualPlacePhoto: onPickManualPlacePhoto,
          ),
          settings: settings,
        );

      case placeDetail:
        final placeId = settings.arguments as String?;
        if (placeId == null || placeId.isEmpty) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Identificador de local ausente.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => PlaceDetailScreen(
            placeId: placeId,
            repository: placeRepository,
            productRepository: productRepository,
            businessRepository: businessRepository,
            commercialRepository: placeCommercialRepository,
            reviewMediaRepository: reviewMediaRepository,
          ),
          settings: settings,
        );

      case productDetail:
        final args = settings.arguments;
        String? productId;
        PlaceDetail? contextPlace;
        if (args is String) {
          productId = args;
        } else if (args is Map<String, dynamic>) {
          productId = args['productId'] as String?;
          contextPlace = args['contextPlace'] as PlaceDetail?;
        }

        if (productId == null || productId.isEmpty) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Identificador de produto ausente.')),
            ),
            settings: settings,
          );
        }

        final validProductId = productId;
        return MaterialPageRoute(
          builder: (context) => ProductDetailScreen(
            productId: validProductId,
            contextPlace: contextPlace,
            repository: productRepository,
          ),
          settings: settings,
        );

      case reviewCreate:
        final args = settings.arguments;
        String? initialTargetId;
        String? initialTargetName;
        String? initialTargetType;
        String? initialCategory;
        File? initialPhoto;
        if (args is Map<String, dynamic>) {
          initialTargetId = args['targetId'] as String?;
          initialTargetName = args['targetName'] as String?;
          initialTargetType = args['targetType'] as String?;
          initialCategory = args['category'] as String?;
          initialPhoto = args['initialPhoto'] as File?;
        }

        return MaterialPageRoute(
          builder: (context) => ReviewCreateScreen(
            repository: reviewCreationRepository,
            mediaRepository: reviewMediaRepository,
            mediaPickerService: mediaPickerService,
            locationService: locationService,
            searchRepository: searchRepository,
            placeRepository: placeRepository,
            productRepository: productRepository,
            placeDiscoveryRepository: placeDiscoveryRepository,
            initialTargetId: initialTargetId,
            initialTargetName: initialTargetName,
            initialTargetType: initialTargetType,
            initialCategory: initialCategory,
            initialPhoto: initialPhoto,
            authNotifier: authNotifier,
            pendingRegistrationStore: pendingRegistrationStore,
            onPickManualPlacePhoto: onPickManualPlacePhoto,
            onReviewCreated: (createdReview) {
              feedNotifier?.refresh();
              Navigator.of(context).pushReplacementNamed(
                AppRouter.reviewDetail,
                arguments: createdReview,
              );
            },
          ),
          settings: settings,
        );

      case profile:
        final userId = settings.arguments as String?;
        return MaterialPageRoute(
          builder: (context) => UserProfileScreen(
            userId: userId,
            userProfileRepository: userProfileRepository,
            authNotifier: authNotifier,
          ),
          settings: settings,
        );

      case myReviews:
        if (userProfileRepository == null) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Repositório de perfil indisponível.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => MyReviewsScreen(
            repository: userProfileRepository!,
            mediaRepository: reviewMediaRepository,
          ),
          settings: settings,
        );

      case myCheckIns:
        return MaterialPageRoute(
          builder: (context) => MyCheckInsScreen(
            repository: checkInRepository,
            onOpenReview: (reviewId) => Navigator.of(context).pushNamed(reviewDetail, arguments: reviewId),
          ),
          settings: settings,
        );

      case followList:
        final args = settings.arguments;
        if (args is FollowListArgs) {
          return MaterialPageRoute(
            builder: (context) => FollowListScreen(
              userId: args.userId,
              userName: args.userName,
              initialTab: args.initialTab,
              repository: userProfileRepository,
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => const Scaffold(
            body: Center(child: Text('Argumentos de conexões sociais ausentes.')),
          ),
          settings: settings,
        );

      case accountSettings:
        return MaterialPageRoute(
          builder: (context) => AccountSettingsScreen(
            authNotifier: authNotifier,
          ),
          settings: settings,
        );

      case editInterests:
        return MaterialPageRoute(
          builder: (context) {
            final userId = _currentUserId() ?? '';
            return EditInterestsScreen(
              repository: interestRepository,
              userId: userId,
              currentUserId: _currentUserId,
              // O Feed V2 é ordenado pelos interesses: a volta ao app já mostra o feed com a seleção nova. Se a
              // sessão terminou enquanto gravava, o feed já foi descartado e não é recarregado para outra sessão
              onSaved: () {
                if (_currentUserId() == userId) feedNotifier?.refresh();
              },
            );
          },
          settings: settings,
        );

      case changePassword:
        return MaterialPageRoute(
          builder: (context) => ChangePasswordScreen(
            authNotifier: authNotifier,
          ),
          settings: settings,
        );

      case scanner:
        return MaterialPageRoute(
          builder: (context) => ScannerScreen(
            productRepository: productRepository,
          ),
          settings: settings,
        );

      case placeManagement:
        final args = settings.arguments;
        if (args is! PlaceManagementArgs) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Argumentos de gestão de estabelecimento ausentes.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => PlaceManagementDashboardScreen(
            args: args,
            placeRepository: placeRepository,
            reviewMediaRepository: reviewMediaRepository,
            commercialRepository: placeCommercialRepository,
            searchRepository: searchRepository,
          ),
          settings: settings,
        );

      case placeMediaGallery:
        final args = settings.arguments;
        if (args is! PlaceMediaGalleryArgs) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Argumentos de galeria ausentes.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => PlaceMediaGalleryScreen(
            args: args,
            placeRepository: placeRepository,
            reviewMediaRepository: reviewMediaRepository,
            onPickPhoto: onPickManualPlacePhoto != null
                ? (source) async {
                    final file = await onPickManualPlacePhoto!(source);
                    if (file == null) return null;
                    final bytes = await file.readAsBytes();
                    final length = await file.length();
                    final name = file.path.split(Platform.pathSeparator).last;
                    final mime = name.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';
                    return SelectedMediaItem(
                      id: DateTime.now().microsecondsSinceEpoch.toString(),
                      name: name,
                      sizeBytes: length,
                      mimeType: mime,
                      bytes: bytes,
                    );
                  }
                : null,
          ),
          settings: settings,
        );

      case placeOpeningHoursEdit:
        final args = settings.arguments;
        if (args is! PlaceOpeningHoursEditArgs) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Argumentos de horários ausentes.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => PlaceOpeningHoursEditScreen(
            args: args,
            commercialRepository: placeCommercialRepository,
          ),
          settings: settings,
        );

      case placeAmenitiesEdit:
        final args = settings.arguments;
        if (args is! PlaceAmenitiesEditArgs) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Argumentos de comodidades ausentes.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => PlaceAmenitiesEditScreen(
            args: args,
            commercialRepository: placeCommercialRepository,
          ),
          settings: settings,
        );

      case placeOffersManagement:
        final args = settings.arguments;
        if (args is! PlaceOffersManagementArgs) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Argumentos de ofertas ausentes.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => PlaceOffersManagementScreen(
            args: args,
            commercialRepository: placeCommercialRepository,
            searchRepository: searchRepository,
          ),
          settings: settings,
        );

      case placeBusinessProfileEdit:
        final args = settings.arguments;
        if (args is! PlaceBusinessProfileEditArgs) {
          return MaterialPageRoute(
            builder: (context) => const Scaffold(
              body: Center(child: Text('Argumentos de perfil comercial ausentes.')),
            ),
            settings: settings,
          );
        }
        return MaterialPageRoute(
          builder: (context) => PlaceBusinessProfileEditScreen(
            args: args,
            commercialRepository: placeCommercialRepository,
          ),
          settings: settings,
        );

      case businessAccounts:
        return MaterialPageRoute(
          builder: (context) => MyBusinessAccountsScreen(
            repository: businessRepository,
          ),
          settings: settings,
        );

      default:
        return MaterialPageRoute(
          builder: (context) => Scaffold(
            appBar: AppBar(title: const Text('Página não encontrada')),
            body: Center(
              child: Text('Rota inexistente: ${settings.name}'),
            ),
          ),
          settings: settings,
        );
    }
  }
}
