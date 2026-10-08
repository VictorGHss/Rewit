import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/screens/feed_view.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/profile_placeholder_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
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
  final SearchRepository? searchRepository;
  final UserProfileRepository? userProfileRepository;

  const HomeScreen({
    super.key,
    required this.authNotifier,
    this.feedNotifier,
    this.reviewCreationRepository,
    this.mediaRepository,
    this.mediaPickerService,
    this.searchRepository,
    this.userProfileRepository,
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _currentIndex = 0;

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

  Widget _buildUserHeaderCard(BuildContext context, Authenticated auth) {
    final user = auth.user;
    final theme = Theme.of(context);

    return Card(
      elevation: 0.5,
      margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
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
                    style: TextStyle(
                      color: theme.colorScheme.onSurface.withAlpha(160),
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
            Row(
              children: [
                Icon(Icons.star, size: 14, color: Colors.amber[700]),
                const SizedBox(width: 4),
                Text(
                  'Reputação: ${user.reputationScore}',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: theme.colorScheme.onSurface.withAlpha(200),
                  ),
                ),
              ],
            ),
          ],
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
              onReviewTap: (review) {
                Navigator.of(context).pushNamed(
                  AppRouter.reviewDetail,
                  arguments: review,
                );
              },
              onAuthorTap: (authorId) {
                Navigator.of(context).pushNamed(
                  AppRouter.profile,
                  arguments: authorId,
                );
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
          SearchScreen(searchRepository: widget.searchRepository),
          ReviewCreateScreen(
            repository: widget.reviewCreationRepository,
            mediaRepository: widget.mediaRepository,
            mediaPickerService: widget.mediaPickerService,
            searchRepository: widget.searchRepository,
            onReviewCreated: (createdReview) {
              widget.feedNotifier?.refresh();
              Navigator.of(context).pushNamed(
                AppRouter.reviewDetail,
                arguments: createdReview,
              );
            },
          ),
          ProfilePlaceholderScreen(
            authNotifier: widget.authNotifier,
            userProfileRepository: widget.userProfileRepository,
          ),
        ];

        return Scaffold(
          appBar: AppBar(
            title: const Text('Rewit'),
            actions: [
              IconButton(
                icon: const Icon(Icons.settings_outlined),
                tooltip: 'Configurações da Conta',
                onPressed: () {
                  Navigator.of(context).pushNamed(AppRouter.accountSettings);
                },
              ),
              IconButton(
                icon: const Icon(Icons.logout),
                tooltip: 'Sair da Conta',
                onPressed: _confirmLogout,
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
          body: IndexedStack(
            index: _currentIndex,
            children: pages,
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
