import 'dart:math';
import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/edit_profile_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/follow_list_screen.dart';

/// Tela de Perfil Público e factual do usuário no Rewit (Step 18.0).
/// Distingue entre o perfil do usuário autenticado ("meu perfil") e o perfil de terceiros.
class UserProfileScreen extends StatefulWidget {
  final String? userId;
  final UserProfileRepository? userProfileRepository;
  final AuthNotifier authNotifier;
  final UserProfile? initialProfile;

  const UserProfileScreen({
    super.key,
    this.userId,
    this.userProfileRepository,
    required this.authNotifier,
    this.initialProfile,
  });

  @override
  State<UserProfileScreen> createState() => _UserProfileScreenState();
}

class _UserProfileScreenState extends State<UserProfileScreen> {
  UserProfile? _profile;
  bool _isLoading = true;
  bool _isActionLoading = false;
  bool _isNotFound = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    if (widget.initialProfile != null) {
      _profile = widget.initialProfile;
      _isLoading = false;
    } else {
      _loadProfile();
    }
  }

  bool get _isMyProfile {
    final state = widget.authNotifier.state;
    final myId = state is Authenticated ? state.user.id : null;
    if (widget.userId == null) return true;
    if (myId != null && myId == widget.userId) return true;
    return false;
  }

  String? get _effectiveUserId {
    if (widget.userId != null) return widget.userId;
    final state = widget.authNotifier.state;
    return state is Authenticated ? state.user.id : null;
  }

  Future<void> _loadProfile() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
      _isNotFound = false;
    });

    final targetId = _effectiveUserId;
    if (targetId == null || targetId.isEmpty) {
      setState(() {
        _isLoading = false;
        _isNotFound = true;
      });
      return;
    }

    if (widget.userProfileRepository == null) {
      final state = widget.authNotifier.state;
      if (state is Authenticated) {
        setState(() {
          _profile = UserProfile(
            id: state.user.id,
            handle: state.user.handle,
            displayName: state.user.displayName,
            stats: const UserStats(),
          );
          _isLoading = false;
        });
      } else {
        setState(() {
          _isLoading = false;
          _isNotFound = true;
        });
      }
      return;
    }

    try {
      final profile = await widget.userProfileRepository!.getUserProfile(targetId);
      if (mounted) {
        setState(() {
          _profile = profile;
          _isLoading = false;
        });
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          if (e.isNotFound) {
            _isNotFound = true;
          } else {
            _errorMessage = e.detail;
          }
        });
      }
    } on NetworkException catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = e.message;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = 'Não foi possível carregar as informações do perfil.';
        });
      }
    }
  }

  Future<void> _toggleFollow() async {
    if (_profile == null || _isActionLoading || widget.userProfileRepository == null) return;

    final targetId = _profile!.id;
    final currentlyFollowing = _profile!.isFollowing;

    setState(() {
      _isActionLoading = true;
    });

    try {
      if (currentlyFollowing) {
        await widget.userProfileRepository!.unfollowUser(targetId);
        if (mounted) {
          setState(() {
            _profile = _profile!.copyWith(
              isFollowing: false,
              stats: _profile!.stats.copyWith(
                followersCount: max(0, _profile!.stats.followersCount - 1),
              ),
            );
          });
        }
      } else {
        await widget.userProfileRepository!.followUser(targetId);
        if (mounted) {
          setState(() {
            _profile = _profile!.copyWith(
              isFollowing: true,
              stats: _profile!.stats.copyWith(
                followersCount: _profile!.stats.followersCount + 1,
              ),
            );
          });
        }
      }
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.detail),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } on NetworkException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(e.message),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('Ocorreu um erro ao atualizar o relacionamento.'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isActionLoading = false;
        });
      }
    }
  }

  void _openFollowList(FollowListTab initialTab) {
    if (_profile == null) return;
    Navigator.of(context).pushNamed(
      AppRouter.followList,
      arguments: FollowListArgs(
        userId: _profile!.id,
        userName: _profile!.displayName,
        initialTab: initialTab,
      ),
    );
  }

  Future<void> _openEditProfile() async {
    if (widget.userProfileRepository == null) return;
    final updated = await Navigator.of(context).push<UserProfile>(
      MaterialPageRoute(
        builder: (context) => EditProfileScreen(
          initialProfile: _profile,
          repository: widget.userProfileRepository!,
          authNotifier: widget.authNotifier,
        ),
      ),
    );

    if (updated != null && mounted) {
      setState(() {
        _profile = updated.copyWith(
          stats: _profile?.stats ?? updated.stats,
        );
      });
    }
  }

  Widget _buildStatItem({
    required BuildContext context,
    required String label,
    required int count,
    VoidCallback? onTap,
  }) {
    final theme = Theme.of(context);
    final content = Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          count.toString(),
          style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          label,
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurface.withAlpha(160),
          ),
        ),
      ],
    );

    if (onTap != null) {
      return InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: content,
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: content,
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: Text(_isMyProfile ? 'Perfil de Usuário' : (_profile?.displayName ?? 'Perfil')),
        actions: [
          if (_isMyProfile)
            IconButton(
              icon: const Icon(Icons.settings_outlined),
              tooltip: 'Configurações da Conta',
              onPressed: () {
                Navigator.of(context).pushNamed(AppRouter.accountSettings);
              },
            ),
        ],
      ),
      body: _buildBody(context, theme),
    );
  }

  Widget _buildBody(BuildContext context, ThemeData theme) {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_isNotFound) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.person_off_outlined, size: 64, color: Colors.grey.shade500),
              const SizedBox(height: 16),
              Text(
                'Perfil Indisponível',
                style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Text(
                'Usuário não encontrado ou inativo.',
                textAlign: TextAlign.center,
                style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
              ),
              const SizedBox(height: 24),
              if (Navigator.of(context).canPop())
                OutlinedButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Voltar'),
                ),
            ],
          ),
        ),
      );
    }

    if (_errorMessage != null || _profile == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline, size: 48, color: theme.colorScheme.error),
              const SizedBox(height: 16),
              Text(
                _errorMessage ?? 'Erro ao carregar perfil',
                textAlign: TextAlign.center,
                style: TextStyle(color: theme.colorScheme.error),
              ),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: _loadProfile,
                child: const Text('Tentar novamente'),
              ),
            ],
          ),
        ),
      );
    }

    final profile = _profile!;
    final isDeleted = profile.displayName == 'Usuário excluído';

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Header com Avatar e Nome
          Row(
            children: [
              CircleAvatar(
                radius: 36,
                backgroundColor: theme.colorScheme.primary.withAlpha(30),
                backgroundImage: profile.avatarUrl != null && profile.avatarUrl!.isNotEmpty
                    ? NetworkImage(profile.avatarUrl!)
                    : null,
                child: profile.avatarUrl == null || profile.avatarUrl!.isEmpty
                    ? Text(
                        profile.displayName.isNotEmpty
                            ? profile.displayName[0].toUpperCase()
                            : 'U',
                        style: TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.bold,
                          color: theme.colorScheme.primary,
                        ),
                      )
                    : null,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      profile.displayName,
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    if (!isDeleted && profile.handle.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        '@${profile.handle}',
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(160),
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),

          if (profile.bio != null && profile.bio!.isNotEmpty) ...[
            const SizedBox(height: 16),
            Text(
              profile.bio!,
              style: TextStyle(
                fontSize: 14,
                color: theme.colorScheme.onSurface.withAlpha(200),
                height: 1.4,
              ),
            ),
          ],

          if (_isMyProfile && profile.isAnonymousDefault) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                Icon(
                  Icons.visibility_off_outlined,
                  size: 16,
                  color: theme.colorScheme.onSurface.withAlpha(150),
                ),
                const SizedBox(width: 6),
                Text(
                  'Avaliações anônimas por padrão ativado',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurface.withAlpha(150),
                  ),
                ),
              ],
            ),
          ],

          const SizedBox(height: 24),

          // Painel de Estatísticas Factuais
          Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 12.0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  _buildStatItem(
                    context: context,
                    label: 'Seguidores',
                    count: profile.stats.followersCount,
                    onTap: !isDeleted ? () => _openFollowList(FollowListTab.followers) : null,
                  ),
                  _buildStatItem(
                    context: context,
                    label: 'Seguindo',
                    count: profile.stats.followingCount,
                    onTap: !isDeleted ? () => _openFollowList(FollowListTab.following) : null,
                  ),
                  _buildStatItem(
                    context: context,
                    label: 'Avaliações',
                    count: profile.stats.totalReviews,
                    onTap: _isMyProfile
                        ? () => Navigator.of(context).pushNamed(AppRouter.myReviews)
                        : null,
                  ),
                  _buildStatItem(
                    context: context,
                    label: 'Votos Úteis',
                    count: profile.stats.helpfulVotesReceived,
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 20),

          // Ações do Perfil
          if (!_isMyProfile && !isDeleted) ...[
            SizedBox(
              height: 44,
              child: profile.isFollowing
                  ? OutlinedButton.icon(
                      onPressed: _isActionLoading ? null : _toggleFollow,
                      icon: _isActionLoading
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.check, size: 18),
                      label: const Text('Seguindo'),
                    )
                  : ElevatedButton.icon(
                      onPressed: _isActionLoading ? null : _toggleFollow,
                      icon: _isActionLoading
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          : const Icon(Icons.person_add_outlined, size: 18),
                      label: const Text('Seguir'),
                    ),
            ),
          ],

          if (_isMyProfile) ...[
            FilledButton.icon(
              key: const Key('edit_profile_button'),
              onPressed: _openEditProfile,
              icon: const Icon(Icons.edit_outlined),
              label: const Text('Editar Perfil'),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              key: const Key('my_reviews_button'),
              onPressed: () {
                Navigator.of(context).pushNamed(AppRouter.myReviews);
              },
              icon: const Icon(Icons.rate_review_outlined),
              label: const Text('Minhas avaliações'),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () {
                Navigator.of(context).pushNamed(AppRouter.accountSettings);
              },
              icon: const Icon(Icons.manage_accounts_outlined),
              label: const Text('Configurações da Conta'),
            ),
          ],
        ],
      ),
    );
  }
}
