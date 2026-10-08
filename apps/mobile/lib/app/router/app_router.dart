import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/notifications/presentation/screens/notifications_screen.dart';
import 'package:rewit_mobile/features/notifications/presentation/state/notifications_notifier.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_detail_screen.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/product/presentation/screens/product_detail_screen.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/follow_list_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
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
  static const String followList = '/profile/follows';
  static const String reviewDetail = '/review/detail';
  static const String accountSettings = '/settings';
  static const String scanner = '/scanner';

  final AuthNotifier authNotifier;
  final FeedNotifier? feedNotifier;
  final FeedRepository? feedRepository;
  final ReviewMediaRepository? reviewMediaRepository;
  final MediaPickerService? mediaPickerService;
  final DiscussionRepository? discussionRepository;
  final ReviewCreationRepository? reviewCreationRepository;
  final SearchRepository? searchRepository;
  final UserProfileRepository? userProfileRepository;
  final PlaceRepository? placeRepository;
  final ProductRepository? productRepository;
  final NotificationRepository? notificationRepository;
  final NotificationsNotifier? notificationsNotifier;

  const AppRouter({
    required this.authNotifier,
    this.feedNotifier,
    this.feedRepository,
    this.reviewMediaRepository,
    this.mediaPickerService,
    this.discussionRepository,
    this.reviewCreationRepository,
    this.searchRepository,
    this.userProfileRepository,
    this.placeRepository,
    this.productRepository,
    this.notificationRepository,
    this.notificationsNotifier,
  });

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
                return HomeScreen(
                  authNotifier: authNotifier,
                  feedNotifier: feedNotifier,
                  reviewCreationRepository: reviewCreationRepository,
                  mediaRepository: reviewMediaRepository,
                  mediaPickerService: mediaPickerService,
                  searchRepository: searchRepository,
                  userProfileRepository: userProfileRepository,
                  notificationRepository: notificationRepository,
                  notificationsNotifier: notificationsNotifier,
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
        return MaterialPageRoute(
          builder: (context) => HomeScreen(
            authNotifier: authNotifier,
            feedNotifier: feedNotifier,
            reviewCreationRepository: reviewCreationRepository,
            mediaRepository: reviewMediaRepository,
            mediaPickerService: mediaPickerService,
            searchRepository: searchRepository,
            userProfileRepository: userProfileRepository,
            notificationRepository: notificationRepository,
            notificationsNotifier: notificationsNotifier,
          ),
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
        if (args is FeedReview) {
          return MaterialPageRoute(
            builder: (context) => ReviewDetailScreen(
              reviewId: args.id,
              initialReview: args,
              feedRepository: feedRepository,
              mediaRepository: reviewMediaRepository,
              discussionRepository: discussionRepository,
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
        if (args is Map<String, dynamic>) {
          initialTargetId = args['targetId'] as String?;
          initialTargetName = args['targetName'] as String?;
          initialTargetType = args['targetType'] as String?;
          initialCategory = args['category'] as String?;
        }

        return MaterialPageRoute(
          builder: (context) => ReviewCreateScreen(
            repository: reviewCreationRepository,
            mediaRepository: reviewMediaRepository,
            mediaPickerService: mediaPickerService,
            searchRepository: searchRepository,
            initialTargetId: initialTargetId,
            initialTargetName: initialTargetName,
            initialTargetType: initialTargetType,
            initialCategory: initialCategory,
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

      case scanner:
        return MaterialPageRoute(
          builder: (context) => ScannerScreen(
            productRepository: productRepository,
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
