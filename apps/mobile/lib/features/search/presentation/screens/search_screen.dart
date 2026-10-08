import 'package:flutter/material.dart';
import '../../domain/entities/search_entities.dart';
import '../../domain/entities/search_filter.dart';
import '../../domain/repositories/search_repository.dart';
import '../state/search_notifier.dart';
import '../state/search_state.dart';
import '../widgets/search_result_card.dart';

/// Fallback sem efeito colateral para ambientes de teste onde o SearchRepository não é injetado.
class _FallbackSearchRepository implements SearchRepository {
  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async {
    return const SearchPage(
      items: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }
}

/// Tela de Busca Global unificada de locais e produtos no catálogo Rewit (C5.1).
class SearchScreen extends StatefulWidget {
  final SearchRepository? searchRepository;
  final SearchNotifier? searchNotifier;
  final void Function(SearchResultItem item)? onTargetTap;

  const SearchScreen({
    super.key,
    this.searchRepository,
    this.searchNotifier,
    this.onTargetTap,
  });

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen>
    with AutomaticKeepAliveClientMixin {
  late final SearchNotifier _notifier;
  bool _ownsNotifier = false;

  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    if (widget.searchNotifier != null) {
      _notifier = widget.searchNotifier!;
    } else if (widget.searchRepository != null) {
      _notifier = SearchNotifier(searchRepository: widget.searchRepository!);
      _ownsNotifier = true;
    } else {
      _notifier = SearchNotifier(searchRepository: _FallbackSearchRepository());
      _ownsNotifier = true;
    }

    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    _searchController.dispose();
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final maxScroll = _scrollController.position.maxScrollExtent;
    final currentScroll = _scrollController.position.pixels;

    // Dispara carregamento da próxima página ao chegar próximo do final (200px)
    if (maxScroll - currentScroll <= 200) {
      _notifier.loadMore();
    }
  }

  void _clearSearch() {
    _searchController.clear();
    _notifier.clear();
  }

  void _handleItemTap(SearchResultItem item) {
    if (widget.onTargetTap != null) {
      widget.onTargetTap!(item);
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('${item.name} selecionado (detalhes em breve).'),
          duration: const Duration(seconds: 1),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Buscar no Catálogo'),
      ),
      body: Column(
        children: [
          // 1. Campo de Busca com debounce e botão de limpar
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: ListenableBuilder(
              listenable: _notifier,
              builder: (context, _) {
                final state = _notifier.state;
                final isLoading = state is SearchLoading;

                return TextFormField(
                  controller: _searchController,
                  decoration: InputDecoration(
                    hintText: 'Buscar estabelecimentos ou produtos...',
                    prefixIcon: const Icon(Icons.search_rounded),
                    suffixIcon: isLoading
                        ? const Padding(
                            padding: EdgeInsets.all(12.0),
                            child: SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          )
                        : _searchController.text.isNotEmpty
                            ? IconButton(
                                icon: const Icon(Icons.clear, size: 20),
                                tooltip: 'Limpar busca',
                                onPressed: _clearSearch,
                              )
                            : null,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    isDense: true,
                  ),
                  onChanged: (val) => _notifier.onQueryChanged(val),
                );
              },
            ),
          ),

          // 2. Chips de Filtro (Todos / Locais / Produtos)
          ListenableBuilder(
            listenable: _notifier,
            builder: (context, _) {
              final activeFilter = _notifier.state.filter;

              return SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                child: Row(
                  children: [
                    _buildFilterChip(
                      theme: theme,
                      label: SearchFilter.all.displayName,
                      selected: activeFilter == SearchFilter.all,
                      onSelected: () => _notifier.setFilter(SearchFilter.all),
                    ),
                    const SizedBox(width: 8),
                    _buildFilterChip(
                      theme: theme,
                      label: SearchFilter.places.displayName,
                      selected: activeFilter == SearchFilter.places,
                      icon: Icons.storefront_rounded,
                      onSelected: () => _notifier.setFilter(SearchFilter.places),
                    ),
                    const SizedBox(width: 8),
                    _buildFilterChip(
                      theme: theme,
                      label: SearchFilter.products.displayName,
                      selected: activeFilter == SearchFilter.products,
                      icon: Icons.inventory_2_outlined,
                      onSelected: () => _notifier.setFilter(SearchFilter.products),
                    ),
                  ],
                ),
              );
            },
          ),

          const Divider(height: 1),

          // 3. Conteúdo Dinâmico por Estado
          Expanded(
            child: ListenableBuilder(
              listenable: _notifier,
              builder: (context, _) {
                final state = _notifier.state;

                if (state is SearchInitial) {
                  return _buildInitialState(theme);
                }

                if (state is SearchLoading) {
                  return _buildLoadingState(theme);
                }

                if (state is SearchEmpty) {
                  return _buildEmptyState(theme, state.query);
                }

                if (state is SearchError) {
                  return _buildErrorState(theme, state);
                }

                if (state is SearchResults) {
                  return _buildResultsState(theme, state);
                }

                return const SizedBox.shrink();
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFilterChip({
    required ThemeData theme,
    required String label,
    required bool selected,
    required VoidCallback onSelected,
    IconData? icon,
  }) {
    return ChoiceChip(
      label: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 16, color: selected ? theme.colorScheme.onPrimary : null),
            const SizedBox(width: 6),
          ],
          Text(label),
        ],
      ),
      selected: selected,
      onSelected: (_) => onSelected(),
      selectedColor: theme.colorScheme.primary,
      labelStyle: TextStyle(
        color: selected ? theme.colorScheme.onPrimary : theme.colorScheme.onSurface,
        fontWeight: selected ? FontWeight.bold : FontWeight.normal,
      ),
      visualDensity: VisualDensity.compact,
    );
  }

  Widget _buildInitialState(ThemeData theme) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.search, size: 64, color: Colors.grey),
            const SizedBox(height: 16),
            Text(
              'Busca de Lugares',
              style: theme.textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            const Text(
              'Busca geográfica de estabelecimentos e avaliações físicas.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 16),
            Text(
              'Digite ao menos 2 caracteres para pesquisar locais ou produtos.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                color: theme.colorScheme.onSurface.withAlpha(140),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildLoadingState(ThemeData theme) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const CircularProgressIndicator(),
          const SizedBox(height: 16),
          Text(
            'Buscando no catálogo...',
            style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyState(ThemeData theme, String query) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search_off_rounded, size: 56, color: Colors.grey.shade400),
            const SizedBox(height: 16),
            Text(
              'Nenhum resultado encontrado',
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Nenhum item encontrado para "$query". Verifique a digitação ou tente outro termo.',
              textAlign: TextAlign.center,
              style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(150)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildErrorState(ThemeData theme, SearchError error) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, size: 48, color: theme.colorScheme.error),
            const SizedBox(height: 16),
            Text(
              'Não foi possível concluir a busca',
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              error.message,
              textAlign: TextAlign.center,
              style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
            ),
            if (error.retryAfterSeconds != null) ...[
              const SizedBox(height: 8),
              Text(
                'Aguarde ${error.retryAfterSeconds} segundos antes de tentar novamente.',
                style: TextStyle(
                  fontSize: 12,
                  color: theme.colorScheme.error,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
            const SizedBox(height: 16),
            ElevatedButton.icon(
              onPressed: _notifier.retry,
              icon: const Icon(Icons.refresh, size: 18),
              label: const Text('Tentar novamente'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildResultsState(ThemeData theme, SearchResults state) {
    final filtered = state.filteredItems;

    if (filtered.isEmpty) {
      final categoryName = state.filter == SearchFilter.places
          ? 'local'
          : state.filter == SearchFilter.products
              ? 'produto'
              : 'item';

      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.filter_list_off, size: 48, color: Colors.grey.shade400),
              const SizedBox(height: 12),
              Text(
                'Nenhum $categoryName encontrado nos resultados carregados.',
                textAlign: TextAlign.center,
                style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(150)),
              ),
              const SizedBox(height: 12),
              TextButton(
                onPressed: () => _notifier.setFilter(SearchFilter.all),
                child: const Text('Ver todos os resultados'),
              ),
            ],
          ),
        ),
      );
    }

    final hasMoreFooter = state.isLoadingMore || state.loadMoreError != null;

    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: filtered.length + (hasMoreFooter ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == filtered.length) {
          return _buildLoadMoreFooter(theme, state);
        }

        final item = filtered[index];
        return SearchResultCard(
          item: item,
          onTap: () => _handleItemTap(item),
        );
      },
    );
  }

  Widget _buildLoadMoreFooter(ThemeData theme, SearchResults state) {
    if (state.isLoadingMore) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 16.0),
        child: Center(
          child: SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      );
    }

    if (state.loadMoreError != null) {
      return Container(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: theme.colorScheme.errorContainer.withAlpha(80),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          children: [
            Icon(Icons.error_outline, size: 18, color: theme.colorScheme.error),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                state.loadMoreError!,
                style: TextStyle(
                  fontSize: 12,
                  color: theme.colorScheme.onErrorContainer,
                ),
              ),
            ),
            TextButton(
              onPressed: _notifier.retry,
              child: const Text('Tentar novamente'),
            ),
          ],
        ),
      );
    }

    return const SizedBox.shrink();
  }
}
