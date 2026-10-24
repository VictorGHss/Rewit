import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/screens/feed_view.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/notifications/presentation/state/notifications_notifier.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/pending_registration_notices.dart';
import 'package:rewit_mobile/features/place_discovery/domain/repositories/place_discovery_repository.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/profile_placeholder_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_screen.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/screens/search_screen.dart';

/// Tela principal autenticada do aplicativo Rewit.
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
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const int _feedTabIndex = 0;
  static const int _profileTabIndex = 3;

  int _currentIndex = _feedTabIndex;
  NotificationsNotifier? _notificationsNotifier;
  bool _internalNotificationsNotifier = false;
  String? _sessionUserId;

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
  }

  @override
  void initState() {
    super.initState();
    _sessionUserId = _authenticatedUserId();
    widget.authNotifier.addListener(_onAuthChanged);
    if (widget.notificationsNotifier != null) {
      _notificationsNotifier = widget.notificationsNotifier;
      _internalNotificationsNotifier = false;
    } else if (widget.notificationRepository != null) {
      _notificationsNotifier = NotificationsNotifier(repository: widget.notificationRepository!);
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
  }

  @override
  void dispose() {
    widget.authNotifier.removeListener(_onAuthChanged);
    PendingRegistrationNotices.closeAllExcept(null);
    if (_internalNotificationsNotifier) {
      _notificationsNotifier?.dispose();
    }
    super.dispose();
  }

  Future<void> _confirmLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Encerrar Sessão'),
        content: const Text('Deseja realmente sair da sua conta?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: ElevatedButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('Sair'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      await widget.authNotifier.logout();
    }
  }

  void _openMyProfile() {
    setState(() {
      _currentIndex = _profileTabIndex;
    });
  }

  /// O cartão inteiro leva à aba Perfil, como o toque no nome do usuário sugere.
  Widget _buildUserHeaderCard(BuildContext context, Authenticated auth) {
    final user = auth.user;
    final theme = Theme.of(context);

    return Card(
      elevation: 0.5,
      margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: const Key('home_user_header_card'),
        onTap: _openMyProfile,
        child: Semantics(
          button: true,
          hint: 'Abrir meu perfil',
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 22,
                  backgroundColor: theme.colorScheme.primary.withAlpha(30),
                  child: Text(
                    user.displayName.isNotEmpty ? user.displayName[0].toUpperCase() : 'U',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: theme.colorScheme.primary,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Flexible(
                            child: Text(
                              user.displayName,
                              style: theme.textTheme.titleSmall?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (user.isVerified) ...[
                            const SizedBox(width: 4),
                            Icon(
                              Icons.verified,
                              size: 14,
                              color: theme.colorScheme.primary,
                            ),
                          ],
                        ],
                      ),
                      Text(
                        '@${user.handle}',
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(160),
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Flexible(
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.star, size: 14, color: Colors.amber[700]),
                      const SizedBox(width: 4),
                      Flexible(
                        child: Text(
                          'Reputação: ${user.reputationScore}',
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: theme.colorScheme.onSurface.withAlpha(200),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 4),
                Icon(Icons.chevron_right, size: 18, color: theme.colorScheme.onSurface.withAlpha(120)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFeedView(BuildContext context, Authenticated auth) {
    if (widget.feedNotifier != null) {
      return Column(
        children: [
          _buildUserHeaderCard(context, auth),
          Expanded(
            child: FeedView(
              feedNotifier: widget.feedNotifier!,
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
            ),
          ),
        ],
      );
    }

    final theme = Theme.of(context);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildUserHeaderCard(context, auth),
          const SizedBox(height: 24),

          // Seção de Feed Inicial
          Text(
            'Feed de Atividades',
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 12),

          // Estado vazio / Placeholder do Feed
          Card(
            elevation: 0,
            color: theme.colorScheme.surface,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: BorderSide(color: Colors.grey.withAlpha(50)),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 36.0),
              child: Column(
                children: [
                  Icon(
                    Icons.dynamic_feed_outlined,
                    size: 48,
                    color: theme.colorScheme.primary.withAlpha(150),
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
                      color: theme.colorScheme.onSurface.withAlpha(160),
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

        final pages = [
          _buildFeedView(context, state),
          SearchScreen(
            searchRepository: widget.searchRepository,
            placeRepository: widget.placeRepository,
            authNotifier: widget.authNotifier,
            pendingRegistrationStore: widget.pendingRegistrationStore,
            onPickManualPlacePhoto: widget.onPickManualPlacePhoto,
          ),
          ReviewCreateScreen(
            repository: widget.reviewCreationRepository,
            mediaRepository: widget.mediaRepository,
            mediaPickerService: widget.mediaPickerService,
            locationService: widget.locationService,
            searchRepository: widget.searchRepository,
            placeRepository: widget.placeRepository,
            productRepository: widget.productRepository,
            placeDiscoveryRepository: widget.placeDiscoveryRepository,
            authNotifier: widget.authNotifier,
            pendingRegistrationStore: widget.pendingRegistrationStore,
            onPickManualPlacePhoto: widget.onPickManualPlacePhoto,
            onReviewCreated: (createdReview) async {
              widget.feedNotifier?.refresh();
              final result = await Navigator.of(context).pushNamed(
                AppRouter.reviewDetail,
                arguments: createdReview,
              );
              if (result is FeedReview) {
                widget.feedNotifier?.updateReview(result);
              } else if (result is Map && result['deleted'] == true) {
                final id = result['reviewId'] as String? ?? createdReview.id;
                widget.feedNotifier?.removeReview(id);
              }
            },

          ),
          ProfilePlaceholderScreen(
            authNotifier: widget.authNotifier,
            userProfileRepository: widget.userProfileRepository,
          ),
        ];

        return Scaffold(
          appBar: AppBar(
            // O perfil fica no cartão do usuário e na aba inferior; o AppBar mantém só o scanner à esquerda
            centerTitle: true,
            title: Semantics(
              header: true,
              child: const Text('Rewit'),
            ),
            leading: IconButton(
              key: const Key('home_scanner_button'),
              icon: const Icon(Icons.qr_code_scanner),
              tooltip: 'Escanear Código',
              onPressed: () {
                Navigator.of(context).pushNamed(AppRouter.scanner);
              },
            ),
            actions: [
              if (_notificationsNotifier != null)
                ListenableBuilder(
                  listenable: _notificationsNotifier!,
                  builder: (context, _) {
                    final unreadCount = _notificationsNotifier!.unreadCount;
                    return IconButton(
                      icon: Badge(
                        isLabelVisible: unreadCount > 0,
                        label: Text(unreadCount > 99 ? '99+' : '$unreadCount'),
                        child: const Icon(Icons.notifications_outlined),
                      ),
                      tooltip: 'Notificações',
                      onPressed: () async {
                        await Navigator.of(context).pushNamed(AppRouter.notifications);
                        if (mounted) {
                          _notificationsNotifier?.fetchUnreadCount();
                        }
                      },
                    );
                  },
                ),
              PopupMenuButton<_HomeMenuAction>(
                key: const Key('home_more_options_button'),
                icon: const Icon(Icons.more_vert),
                tooltip: 'Mais opções',
                onSelected: (action) {
                  switch (action) {
                    case _HomeMenuAction.settings:
                      Navigator.of(context).pushNamed(AppRouter.accountSettings);
                    case _HomeMenuAction.logout:
                      _confirmLogout();
                  }
                },
                itemBuilder: (context) => const [
                  PopupMenuItem(
                    value: _HomeMenuAction.settings,
                    child: ListTile(
                      leading: Icon(Icons.settings_outlined),
                      title: Text('Configurações da Conta'),
                      contentPadding: EdgeInsets.zero,
                    ),
                  ),
                  PopupMenuItem(
                    value: _HomeMenuAction.logout,
                    child: ListTile(
                      leading: Icon(Icons.logout),
                      title: Text('Sair da Conta'),
                      contentPadding: EdgeInsets.zero,
                    ),
                  ),
                ],
              ),
            ],
          ),
          floatingActionButton: _currentIndex == 0
              ? FloatingActionButton(
                  tooltip: 'Nova Avaliação',
                  onPressed: () {
                    Navigator.of(context).pushNamed(AppRouter.reviewCreate);
                  },
                  child: const Icon(Icons.add_comment_outlined),
                )
              : null,
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
                icon: Icon(Icons.search_outlined),
                selectedIcon: Icon(Icons.search),
                label: 'Buscar',
              ),
              NavigationDestination(
                icon: Icon(Icons.add_circle_outline),
                selectedIcon: Icon(Icons.add_circle),
                label: 'Avaliar',
              ),
              NavigationDestination(
                icon: Icon(Icons.person_outline),
                selectedIcon: Icon(Icons.person),
                label: 'Perfil',
              ),
            ],
          ),
        );
      },
    );
  }
}

enum _HomeMenuAction { settings, logout }
