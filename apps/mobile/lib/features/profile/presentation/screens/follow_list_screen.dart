import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/profile/domain/entities/follow_user_summary.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';

/// Aba selecionada inicialmente na tela de conexões sociais.
enum FollowListTab {
  followers,
  following,
}

/// Argumentos para abertura da tela de listas sociais.
class FollowListArgs {
  final String userId;
  final String? userName;
  final FollowListTab initialTab;

  const FollowListArgs({
    required this.userId,
    this.userName,
    this.initialTab = FollowListTab.followers,
  });
}

/// Tela que exibe as listas paginadas de Seguidores e Quem o usuário segue (Step 15.0).
class FollowListScreen extends StatefulWidget {
  final String userId;
  final String? userName;
  final FollowListTab initialTab;
  final UserProfileRepository? repository;

  const FollowListScreen({
    super.key,
    required this.userId,
    this.userName,
    this.initialTab = FollowListTab.followers,
    this.repository,
  });

  @override
  State<FollowListScreen> createState() => _FollowListScreenState();
}

class _FollowListScreenState extends State<FollowListScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(
      length: 2,
      vsync: this,
      initialIndex: widget.initialTab == FollowListTab.followers ? 0 : 1,
    );
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.userName != null ? widget.userName! : 'Conexões'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: 'Seguidores'),
            Tab(text: 'Seguindo'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _FollowListView(
            userId: widget.userId,
            isFollowersTab: true,
            repository: widget.repository,
          ),
          _FollowListView(
            userId: widget.userId,
            isFollowersTab: false,
            repository: widget.repository,
          ),
        ],
      ),
    );
  }
}

class _FollowListView extends StatefulWidget {
  final String userId;
  final bool isFollowersTab;
  final UserProfileRepository? repository;

  const _FollowListView({
    required this.userId,
    required this.isFollowersTab,
    this.repository,
  });

  @override
  State<_FollowListView> createState() => _FollowListViewState();
}

class _FollowListViewState extends State<_FollowListView> {
  final ScrollController _scrollController = ScrollController();
  final List<FollowUserSummary> _items = [];
  bool _isLoading = true;
  bool _isLoadingMore = false;
  bool _isLast = false;
  int _currentPage = 0;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadInitialPage();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scrollController.position.pixels >=
            _scrollController.position.maxScrollExtent - 200 &&
        !_isLoading &&
        !_isLoadingMore &&
        !_isLast) {
      _loadMore();
    }
  }

  Future<void> _loadInitialPage() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
      _currentPage = 0;
    });

    if (widget.repository == null) {
      setState(() {
        _isLoading = false;
      });
      return;
    }

    try {
      final paged = widget.isFollowersTab
          ? await widget.repository!.getFollowers(widget.userId, page: 0)
          : await widget.repository!.getFollowing(widget.userId, page: 0);

      if (mounted) {
        setState(() {
          _items.clear();
          _items.addAll(paged.items);
          _currentPage = paged.pageNumber;
          _isLast = paged.isLast;
          _isLoading = false;
        });
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = e.detail;
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
          _errorMessage = 'Erro ao carregar a lista.';
        });
      }
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore || _isLast || widget.repository == null) return;

    setState(() {
      _isLoadingMore = true;
    });

    try {
      final nextPage = _currentPage + 1;
      final paged = widget.isFollowersTab
          ? await widget.repository!.getFollowers(widget.userId, page: nextPage)
          : await widget.repository!.getFollowing(widget.userId, page: nextPage);

      if (mounted) {
        setState(() {
          _items.addAll(paged.items);
          _currentPage = paged.pageNumber;
          _isLast = paged.isLast;
          _isLoadingMore = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isLoadingMore = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_errorMessage != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline, size: 48, color: theme.colorScheme.error),
              const SizedBox(height: 16),
              Text(
                _errorMessage!,
                textAlign: TextAlign.center,
                style: TextStyle(color: theme.colorScheme.error),
              ),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: _loadInitialPage,
                child: const Text('Tentar novamente'),
              ),
            ],
          ),
        ),
      );
    }

    if (_items.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.people_outline, size: 64, color: Colors.grey.shade400),
              const SizedBox(height: 16),
              Text(
                widget.isFollowersTab
                    ? 'Nenhum seguidor ainda'
                    : 'Não está seguindo ninguém ainda',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: Colors.grey.shade700,
                ),
              ),
            ],
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _loadInitialPage,
      child: ListView.separated(
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(),
        itemCount: _items.length + (_isLoadingMore ? 1 : 0),
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          if (index >= _items.length) {
            return const Padding(
              padding: EdgeInsets.symmetric(vertical: 16.0),
              child: Center(
                child: SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            );
          }

          final user = _items[index];
          final isDeleted = user.displayName == 'Usuário excluído';
          final canNavigate = !isDeleted && user.id.isNotEmpty;

          return ListTile(
            leading: CircleAvatar(
              backgroundColor: theme.colorScheme.primary.withAlpha(30),
              backgroundImage: user.avatarUrl != null && user.avatarUrl!.isNotEmpty
                  ? NetworkImage(user.avatarUrl!)
                  : null,
              child: user.avatarUrl == null || user.avatarUrl!.isEmpty
                  ? Text(
                      user.displayName.isNotEmpty
                          ? user.displayName[0].toUpperCase()
                          : 'U',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: theme.colorScheme.primary,
                      ),
                    )
                  : null,
            ),
            title: Text(
              user.displayName,
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            subtitle: !isDeleted && user.handle.isNotEmpty
                ? Text('@${user.handle}')
                : null,
            trailing: canNavigate
                ? const Icon(Icons.chevron_right, size: 20)
                : null,
            onTap: canNavigate
                ? () {
                    Navigator.of(context).pushNamed(
                      AppRouter.profile,
                      arguments: user.id,
                    );
                  }
                : null,
          );
        },
      ),
    );
  }
}
