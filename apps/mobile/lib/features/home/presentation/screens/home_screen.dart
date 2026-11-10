import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/check_ins/domain/repositories/check_in_repository.dart';
import 'package:rewit_mobile/features/check_ins/presentation/screens/check_in_screen.dart';
import 'package:rewit_mobile/features/collection/presentation/screens/collection_screen.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/screens/feed_view.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_state.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/notifications/presentation/state/notifications_notifier.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/pending_registration_notices.dart';
import 'package:rewit_mobile/features/place_discovery/domain/repositories/place_discovery_repository.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/rankings/domain/repositories/ranking_repository.dart';
import 'package:rewit_mobile/features/rankings/presentation/screens/rankings_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/screens/search_screen.dart';

/// Tela principal autenticada do aplicativo Rewit (Navigation & Home V2).
class HomeScreen extends StatefulWidget {
  final AuthNotifier authNotifier;
  final FeedNotifier? feedNotifier;
  final ReviewCreationRepository? reviewCreationRepository;
  final ReviewMediaRepository? mediaRepository;
  final MediaPickerService? mediaPickerService;
  final LocationService? locationService;
  final SearchRepository? searchRepository;
  final PlaceRepository? placeRepository;
  final ProductRepository? productRepository;
  final PlaceDiscoveryRepository? placeDiscoveryRepository;
  final UserProfileRepository? userProfileRepository;
  final NotificationRepository? notificationRepository;
  final NotificationsNotifier? notificationsNotifier;

  /// Operações pendentes de cadastro de local, com escopo por usuário (MB-1c/MB-1d).
  final PendingPlaceRegistrationStore? pendingRegistrationStore;

  /// Locais próximos e histórico da aba Check-in; sem ele, a aba informa que o check-in está indisponível.
  final CheckInRepository? checkInRepository;

  /// Rankings da Região para a aba Rankings.
  final RankingRepository? rankingRepository;

  /// Catálogo de interesses para filtro por categoria nos Rankings.
  final InterestRepository? interestRepository;

  /// Seletor da fotografia do cadastro manual; nulo usa a câmera/galeria do dispositivo.
  final Future<File?> Function(ImageSource source)? onPickManualPlacePhoto;

  const HomeScreen({
    super.key,
    required this.authNotifier,
    this.feedNotifier,
    this.reviewCreationRepository,
    this.mediaRepository,
    this.mediaPickerService,
    this.locationService,
    this.searchRepository,
    this.placeRepository,
    this.productRepository,
    this.placeDiscoveryRepository,
    this.userProfileRepository,
    this.notificationRepository,
    this.notificationsNotifier,
    this.pendingRegistrationStore,
    this.onPickManualPlacePhoto,
    this.checkInRepository,
    this.rankingRepository,
    this.interestRepository,
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin {
  static const int _feedTabIndex = 0;
  static const int _checkInTabIndex = 3;

  int _currentIndex = _feedTabIndex;
  late final TabController _feedTabController;
  NotificationsNotifier? _notificationsNotifier;
  bool _internalNotificationsNotifier = false;
  String? _sessionUserId;
  bool _avatarLoadFailed = false;
  String? _lastAvatarUrl;

  String? _authenticatedUserId() {
    final state = widget.authNotifier.state;
    return state is Authenticated ? state.user.id : null;
  }

  /// Avisos de pendência pertencem à conta que os exibiu: o ScaffoldMessenger do app sobrevive à troca de
  /// conta, então eles são fechados quando a identidade muda ou a Home autenticada é desmontada.
  void _onAuthChanged() {
    final userId = _authenticatedUserId();
    if (userId != _sessionUserId) {
      _sessionUserId = userId;
      PendingRegistrationNotices.closeAllExcept(userId);
    }
    final state = widget.authNotifier.state;
    final avatarUrl = state is Authenticated ? state.user.avatarUrl : null;
    if (avatarUrl != _lastAvatarUrl) {
      _lastAvatarUrl = avatarUrl;
      _avatarLoadFailed = false;
    }
  }

  void _onFeedTabChanged() {
    final targetType = _feedTabController.index == 1
        ? FeedType.following
        : FeedType.forYou;
    final notifier = widget.feedNotifier;
    if (notifier != null && notifier.activeFeedType != targetType) {
      notifier.setActiveFeedType(targetType);
    }
  }

  void _onFeedNotifierChanged() {
    final notifier = widget.feedNotifier;
    if (notifier == null || _feedTabController.indexIsChanging) return;
    final expectedIndex =
        notifier.activeFeedType == FeedType.following ? 1 : 0;
    if (_feedTabController.index != expectedIndex) {
      _feedTabController.index = expectedIndex;
    }
  }

  @override
  void initState() {
    super.initState();
    _sessionUserId = _authenticatedUserId();
    final state = widget.authNotifier.state;
    _lastAvatarUrl = state is Authenticated ? state.user.avatarUrl : null;

    final initialFeedType =
        widget.feedNotifier?.activeFeedType ?? FeedType.forYou;
    _feedTabController = TabController(
      length: 2,
      vsync: this,
      initialIndex: initialFeedType == FeedType.following ? 1 : 0,
    )..addListener(_onFeedTabChanged);

    widget.authNotifier.addListener(_onAuthChanged);
    widget.feedNotifier?.addListener(_onFeedNotifierChanged);

    if (widget.notificationsNotifier != null) {
      _notificationsNotifier = widget.notificationsNotifier;
      _internalNotificationsNotifier = false;
    } else if (widget.notificationRepository != null) {
      _notificationsNotifier =
          NotificationsNotifier(repository: widget.notificationRepository!);
      _internalNotificationsNotifier = true;
    }
    _notificationsNotifier?.fetchUnreadCount();
  }

  @override
  void didUpdateWidget(covariant HomeScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.authNotifier != widget.authNotifier) {
      oldWidget.authNotifier.removeListener(_onAuthChanged);
      widget.authNotifier.addListener(_onAuthChanged);
      _onAuthChanged();
    }
    if (oldWidget.feedNotifier != widget.feedNotifier) {
      oldWidget.feedNotifier?.removeListener(_onFeedNotifierChanged);
      widget.feedNotifier?.addListener(_onFeedNotifierChanged);
      _onFeedNotifierChanged();
    }
  }

  @override
  void dispose() {
    widget.authNotifier.removeListener(_onAuthChanged);
    widget.feedNotifier?.removeListener(_onFeedNotifierChanged);
    _feedTabController.removeListener(_onFeedTabChanged);
    _feedTabController.dispose();
    PendingRegistrationNotices.closeAllExcept(null);
    if (_internalNotificationsNotifier) {
      _notificationsNotifier?.dispose();
    }
    super.dispose();
  }

  void _openMyProfile() {
    Navigator.of(context).pushNamed(AppRouter.profile);
  }

  void _showMessagesUnavailableNotice() {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        const SnackBar(
          content: Text(
            'O Chat de mensagens ainda não foi implementado nesta versão do Rewit.',
          ),
          duration: Duration(seconds: 3),
        ),
      );
  }

  Future<void> _showQuickActionsSheet() async {
    final selectedAction = await showModalBottomSheet<_QuickAction>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) {
        final theme = Theme.of(sheetContext);
        final scheme = theme.colorScheme;
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
              RewitSpacing.md,
              0,
              RewitSpacing.md,
              RewitSpacing.lg,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: RewitSpacing.md,
                    vertical: RewitSpacing.sm,
                  ),
                  child: Text(
                    'O que você quer registrar?',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                ListTile(
                  key: const Key('home_quick_action_review_create'),
                  leading: CircleAvatar(
                    backgroundColor: scheme.secondaryContainer,
                    child: Icon(
                      Icons.rate_review_outlined,
                      color: scheme.onSecondaryContainer,
                    ),
                  ),
                  title: const Text(
                    'Nova Avaliação',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                  subtitle: const Text(
                    'Avalie um local ou produto com nota, texto e fotos.',
                  ),
                  onTap: () =>
                      Navigator.of(sheetContext).pop(_QuickAction.reviewCreate),
                ),
                ListTile(
                  key: const Key('home_quick_action_check_in'),
                  leading: CircleAvatar(
                    backgroundColor: scheme.secondaryContainer,
                    child: Icon(
                      Icons.where_to_vote_outlined,
                      color: scheme.onSecondaryContainer,
                    ),
                  ),
                  title: const Text(
                    'Fazer Check-in',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                  subtitle: const Text(
                    'Registre presença rápida em um estabelecimento próximo.',
                  ),
                  onTap: () =>
                      Navigator.of(sheetContext).pop(_QuickAction.checkIn),
                ),
              ],
            ),
          ),
        );
      },
    );

    if (!mounted || selectedAction == null) return;

    switch (selectedAction) {
      case _QuickAction.reviewCreate:
        Navigator.of(context).pushNamed(AppRouter.reviewCreate);
      case _QuickAction.checkIn:
        setState(() {
          _currentIndex = _checkInTabIndex;
        });
    }
  }

  Widget _buildAvatarButton(BuildContext context, AuthUserDto user) {
    final scheme = Theme.of(context).colorScheme;
    final avatarUrl = user.avatarUrl;
    final hasAvatarImage =
        avatarUrl != null && avatarUrl.isNotEmpty && !_avatarLoadFailed;
    final initial =
        user.displayName.isNotEmpty ? user.displayName[0].toUpperCase() : 'U';

    return Padding(
      padding: const EdgeInsets.all(8.0),
      child: Tooltip(
        message: 'Meu perfil',
        child: Semantics(
          button: true,
          label: 'Meu perfil',
          child: InkWell(
            key: const Key('home_avatar_button'),
            customBorder: const CircleBorder(),
            onTap: _openMyProfile,
            child: CircleAvatar(
              radius: 18,
              backgroundColor: scheme.secondaryContainer,
              backgroundImage:
                  hasAvatarImage ? NetworkImage(avatarUrl) : null,
              onBackgroundImageError: hasAvatarImage
                  ? (_, __) {
                      if (mounted && !_avatarLoadFailed) {
                        setState(() {
                          _avatarLoadFailed = true;
                        });
                      }
                    }
                  : null,
              child: hasAvatarImage
                  ? null
                  : Text(
                      initial,
                      key: const Key('home_avatar_fallback_initial'),
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.bold,
                        color: scheme.onSecondaryContainer,
                      ),
                    ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSingleFeedChannel(BuildContext context, FeedType feedType) {
    return FeedView(
      key: ValueKey('home_feed_view_${feedType.name}'),
      feedNotifier: widget.feedNotifier!,
      feedType: feedType,
      mediaRepository: widget.mediaRepository,
      onReviewTap: (review) async {
        final result = await Navigator.of(context).pushNamed(
          AppRouter.reviewDetail,
          arguments: review,
        );
        if (result is FeedReview) {
          widget.feedNotifier?.updateReview(result);
        } else if (result is Map && result['deleted'] == true) {
          final id = result['reviewId'] as String? ?? review.id;
          widget.feedNotifier?.removeReview(id);
        }
      },
      onAuthorTap: (authorId) {
        Navigator.of(context).pushNamed(
          AppRouter.profile,
          arguments: authorId,
        );
      },
      onTargetTap: (targetId, targetType) {
        if (targetType == 'PLACE') {
          Navigator.of(context).pushNamed(
            AppRouter.placeDetail,
            arguments: targetId,
          );
        } else if (targetType == 'PRODUCT') {
          Navigator.of(context).pushNamed(
            AppRouter.productDetail,
            arguments: targetId,
          );
        }
      },
    );
  }

  Widget _buildFeedView(BuildContext context) {
    if (widget.feedNotifier != null) {
      return Column(
        children: [
          Material(
            color: Theme.of(context).colorScheme.surface,
            child: TabBar(
              key: const Key('home_feed_tab_bar'),
              controller: _feedTabController,
              tabs: const [
                Tab(
                  key: Key('home_feed_tab_for_you'),
                  text: 'Para você',
                ),
                Tab(
                  key: Key('home_feed_tab_following'),
                  text: 'Seguindo',
                ),
              ],
            ),
          ),
          Expanded(
            child: TabBarView(
              controller: _feedTabController,
              children: [
                _buildSingleFeedChannel(context, FeedType.forYou),
                _buildSingleFeedChannel(context, FeedType.following),
              ],
            ),
          ),
        ],
      );
    }

    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return SingleChildScrollView(
      padding: RewitSpacing.screenPadding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'Feed de Atividades',
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: 20.0,
                vertical: 36.0,
              ),
              child: Column(
                children: [
                  Icon(
                    Icons.dynamic_feed_outlined,
                    size: 48,
                    color: scheme.primary,
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Feed em Construção',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'A fundação técnica de rede e autenticação está ativa. Em breve você verá aqui avaliações de lugares ao seu redor.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: scheme.onSurfaceVariant,
                      fontSize: 14,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.authNotifier,
      builder: (context, _) {
        final state = widget.authNotifier.state;

        if (state is! Authenticated) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }

        final scheme = Theme.of(context).colorScheme;

        final pages = [
          _buildFeedView(context),
          SearchScreen(
            embedded: true,
            searchRepository: widget.searchRepository,
            placeRepository: widget.placeRepository,
            authNotifier: widget.authNotifier,
            pendingRegistrationStore: widget.pendingRegistrationStore,
            onPickManualPlacePhoto: widget.onPickManualPlacePhoto,
          ),
          RankingsScreen(
            embedded: true,
            repository: widget.rankingRepository,
            interestRepository: widget.interestRepository,
            authNotifier: widget.authNotifier,
          ),
          CheckInScreen(
            embedded: true,
            reviewCreationRepository: widget.reviewCreationRepository,
            locationService: widget.locationService,
            checkInRepository: widget.checkInRepository,
            onOpenHistory: () =>
                Navigator.of(context).pushNamed(AppRouter.myCheckIns),
            onReviewCreated: (_) => widget.feedNotifier?.refresh(),
          ),
          const CollectionScreen(embedded: true),
        ];

        return Scaffold(
          appBar: AppBar(
            centerTitle: true,
            leading: _buildAvatarButton(context, state.user),
            title: Semantics(
              header: true,
              child: const Text('Rewit'),
            ),
            actions: [
              IconButton(
                key: const Key('home_messages_button'),
                icon: Icon(
                  Icons.chat_bubble_outline,
                  color: scheme.onSurfaceVariant.withAlpha(120),
                ),
                tooltip: 'Mensagens (em breve)',
                onPressed: _showMessagesUnavailableNotice,
              ),
              if (_notificationsNotifier != null)
                ListenableBuilder(
                  listenable: _notificationsNotifier!,
                  builder: (context, _) {
                    final unreadCount = _notificationsNotifier!.unreadCount;
                    return IconButton(
                      key: const Key('home_notifications_button'),
                      icon: Badge(
                        isLabelVisible: unreadCount > 0,
                        label: Text(
                          unreadCount > 99 ? '99+' : '$unreadCount',
                        ),
                        child: const Icon(Icons.notifications_outlined),
                      ),
                      tooltip: 'Notificações',
                      onPressed: () async {
                        await Navigator.of(context)
                            .pushNamed(AppRouter.notifications);
                        if (mounted) {
                          _notificationsNotifier?.fetchUnreadCount();
                        }
                      },
                    );
                  },
                )
              else
                IconButton(
                  key: const Key('home_notifications_button'),
                  icon: const Icon(Icons.notifications_outlined),
                  tooltip: 'Notificações',
                  onPressed: () {
                    Navigator.of(context).pushNamed(AppRouter.notifications);
                  },
                ),
            ],
          ),
          floatingActionButton: FloatingActionButton(
            key: const Key('home_fab_button'),
            tooltip: 'Criar',
            onPressed: _showQuickActionsSheet,
            child: const Icon(Icons.add),
          ),
          // Voltar fora da aba Início retorna a ela, em vez de fechar o app
          body: PopScope(
            canPop: _currentIndex == _feedTabIndex,
            onPopInvokedWithResult: (didPop, _) {
              if (!didPop) {
                setState(() {
                  _currentIndex = _feedTabIndex;
                });
              }
            },
            child: IndexedStack(
              index: _currentIndex,
              children: pages,
            ),
          ),
          bottomNavigationBar: NavigationBar(
            selectedIndex: _currentIndex,
            onDestinationSelected: (index) {
              setState(() {
                _currentIndex = index;
              });
            },
            destinations: const [
              NavigationDestination(
                icon: Icon(Icons.home_outlined),
                selectedIcon: Icon(Icons.home),
                label: 'Início',
              ),
              NavigationDestination(
                icon: Icon(Icons.explore_outlined),
                selectedIcon: Icon(Icons.explore),
                label: 'Explorar',
              ),
              NavigationDestination(
                icon: Icon(Icons.leaderboard_outlined),
                selectedIcon: Icon(Icons.leaderboard),
                label: 'Rankings',
              ),
              NavigationDestination(
                key: Key('home_check_in_tab'),
                icon: Icon(Icons.where_to_vote_outlined),
                selectedIcon: Icon(Icons.where_to_vote),
                label: 'Check-in',
              ),
              NavigationDestination(
                icon: Icon(Icons.bookmarks_outlined),
                selectedIcon: Icon(Icons.bookmarks),
                label: 'Coleção',
              ),
            ],
          ),
        );
      },
    );
  }
}

enum _QuickAction { reviewCreate, checkIn }
