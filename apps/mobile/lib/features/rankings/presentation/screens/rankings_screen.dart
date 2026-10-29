import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/place_discovery/domain/entities/discovery_region.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

import '../../domain/entities/ranking_entities.dart';
import '../../domain/repositories/ranking_repository.dart';
import '../state/rankings_notifier.dart';

/// Rankings da Região: três classificações independentes (check-ins verificados, locais explorados e votos úteis),
/// sem campeão geral, filtradas por período, estado, cidade e categoria. Só aparecem perfis que escolheram participar.
class RankingsScreen extends StatefulWidget {
  final RankingRepository? repository;

  /// Catálogo canônico de categorias (o mesmo do questionário de interesses); sem ele, não há filtro de categoria.
  final InterestRepository? interestRepository;
  final AuthNotifier authNotifier;

  /// Injeção para testes; sem ele, a tela cria e descarta o próprio notifier.
  final RankingsNotifier? notifier;

  const RankingsScreen({
    super.key,
    required this.repository,
    required this.authNotifier,
    this.interestRepository,
    this.notifier,
  });

  @override
  State<RankingsScreen> createState() => _RankingsScreenState();
}

class _RankingsScreenState extends State<RankingsScreen> {
  RankingsNotifier? _notifier;
  bool _ownsNotifier = false;
  String? _sessionUserId;
  final TextEditingController _cityController = TextEditingController();

  List<InterestOption>? _categories;
  bool _categoriesUnavailable = false;

  String? _authenticatedUserId() {
    final state = widget.authNotifier.state;
    return state is Authenticated ? state.user.id : null;
  }

  @override
  void initState() {
    super.initState();
    _sessionUserId = _authenticatedUserId();
    if (widget.notifier != null) {
      _notifier = widget.notifier;
    } else if (widget.repository != null && _sessionUserId != null) {
      _notifier = RankingsNotifier(
        repository: widget.repository!,
        userId: _sessionUserId!,
        currentUserId: _authenticatedUserId,
      );
      _ownsNotifier = true;
    }
    widget.authNotifier.addListener(_onAuthChanged);
    _loadCategories();
  }

  @override
  void dispose() {
    widget.authNotifier.removeListener(_onAuthChanged);
    if (_ownsNotifier) _notifier?.dispose();
    _cityController.dispose();
    super.dispose();
  }

  /// Logout, expiração ou outra conta: descarta os dados desta sessão e sai da tela.
  void _onAuthChanged() {
    final state = widget.authNotifier.state;
    if (state is Authenticating) return;
    if (_authenticatedUserId() == _sessionUserId) return;
    _notifier?.invalidate();
    // A sessão expirada já volta à raiz (RewitApp): só fecha esta tela se ela ainda for a rota atual
    if (mounted && (ModalRoute.of(context)?.isCurrent ?? false)) Navigator.of(context).pop();
  }

  Future<void> _loadCategories() async {
    final repository = widget.interestRepository;
    if (repository == null) {
      setState(() => _categoriesUnavailable = true);
      return;
    }
    try {
      final catalog = await repository.getCatalog();
      if (!mounted) return;
      setState(() => _categories = catalog.options);
    } catch (_) {
      if (!mounted) return;
      setState(() => _categoriesUnavailable = true);
    }
  }

  Future<void> _manageParticipation() async {
    await Navigator.of(context).pushNamed(AppRouter.accountSettings);
    // A participação pode ter mudado: os dados voltam do backend, sem manter o estado anterior
    if (mounted) _notifier?.refresh();
  }

  void _applyCity() {
    final notifier = _notifier!;
    final city = _cityController.text.trim();
    notifier.updateFilters(notifier.filters.copyWith(city: () => city.isEmpty ? null : city));
    FocusScope.of(context).unfocus();
  }

  @override
  Widget build(BuildContext context) {
    final notifier = _notifier;
    return Scaffold(
      appBar: AppBar(title: const Text('Rankings da Região')),
      body: notifier == null
          ? const Center(child: Text('Rankings indisponíveis nesta versão do app.'))
          : AnimatedBuilder(
              animation: notifier,
              builder: (context, _) => ListView(
                key: const Key('rankings_list'),
                padding: const EdgeInsets.all(16),
                children: [
                  ..._buildHeader(context),
                  const SizedBox(height: 12),
                  _buildMetricSelector(notifier),
                  const SizedBox(height: 12),
                  ..._buildFilters(context, notifier),
                  const SizedBox(height: 16),
                  ..._buildResults(context, notifier),
                ],
              ),
            ),
    );
  }

  List<Widget> _buildHeader(BuildContext context) {
    final theme = Theme.of(context);
    return [
      Text(
        'Três rankings independentes das contribuições da comunidade na região escolhida. Só aparecem perfis que '
        'escolheram participar; não há pontuação geral.',
        style: theme.textTheme.bodyMedium,
      ),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
          key: const Key('rankings_manage_participation'),
          onPressed: _manageParticipation,
          icon: const Icon(Icons.manage_accounts_outlined, size: 18),
          label: const Text('Gerenciar minha participação'),
        ),
      ),
    ];
  }

  Widget _buildMetricSelector(RankingsNotifier notifier) {
    return Wrap(
      spacing: 8,
      runSpacing: 4,
      children: [
        for (final metric in RankingMetric.values)
          ChoiceChip(
            key: Key('ranking_metric_${metric.name}'),
            label: Text(metric.title),
            selected: notifier.metric == metric,
            onSelected: (_) => notifier.selectMetric(metric),
          ),
      ],
    );
  }

  List<Widget> _buildFilters(BuildContext context, RankingsNotifier notifier) {
    final filters = notifier.filters;
    final categories = _categories;
    return [
      SegmentedButton<RankingPeriod>(
        key: const Key('ranking_period'),
        segments: [
          for (final period in RankingPeriod.values) ButtonSegment(value: period, label: Text(period.label)),
        ],
        selected: {filters.period},
        onSelectionChanged: (selected) => notifier.updateFilters(filters.copyWith(period: selected.first)),
      ),
      const SizedBox(height: 12),
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 108,
            child: DropdownButtonFormField<String>(
              key: const Key('ranking_state_field'),
              initialValue: filters.state,
              isDense: true,
              decoration: const InputDecoration(labelText: 'UF *', isDense: true, border: OutlineInputBorder()),
              items: brazilianStateCodes.map((uf) => DropdownMenuItem(value: uf, child: Text(uf))).toList(),
              onChanged: (uf) => notifier.updateFilters(filters.copyWith(state: () => uf)),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              key: const Key('ranking_city_field'),
              controller: _cityController,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _applyCity(),
              decoration: InputDecoration(
                labelText: 'Cidade (opcional)',
                isDense: true,
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  key: const Key('ranking_city_apply'),
                  tooltip: 'Aplicar cidade',
                  icon: const Icon(Icons.search),
                  onPressed: _applyCity,
                ),
              ),
            ),
          ),
        ],
      ),
      const SizedBox(height: 12),
      if (categories != null)
        DropdownButtonFormField<String?>(
          key: const Key('ranking_category_field'),
          initialValue: filters.category,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Categoria', isDense: true, border: OutlineInputBorder()),
          items: [
            const DropdownMenuItem<String?>(value: null, child: Text('Todas as categorias')),
            for (final option in categories)
              DropdownMenuItem<String?>(value: option.code, child: Text(option.label)),
          ],
          onChanged: (code) => notifier.updateFilters(filters.copyWith(category: () => code)),
        )
      else if (_categoriesUnavailable)
        const Text(
          'Filtro por categoria indisponível no momento.',
          key: Key('ranking_category_unavailable'),
          style: TextStyle(fontSize: 12),
        ),
    ];
  }

  List<Widget> _buildResults(BuildContext context, RankingsNotifier notifier) {
    final theme = Theme.of(context);
    if (notifier.sessionEnded) {
      return const [Text('Sua sessão terminou.', key: Key('rankings_session_ended'))];
    }
    if (notifier.needsState) {
      return [
        Text(
          'Escolha o estado (UF) para ver os rankings.',
          key: const Key('rankings_state_required'),
          style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600),
        ),
      ];
    }
    if (notifier.isLoading) {
      return const [
        Padding(
          padding: EdgeInsets.all(24),
          child: Center(child: CircularProgressIndicator(key: Key('rankings_loading'))),
        ),
      ];
    }
    if (notifier.errorMessage != null) {
      return [
        ErrorBanner(key: const Key('rankings_error'), message: notifier.errorMessage!),
        const SizedBox(height: 8),
        OutlinedButton(
          key: const Key('rankings_retry'),
          onPressed: notifier.refresh,
          child: const Text('Tentar novamente'),
        ),
      ];
    }
    return [
      if (notifier.partialPeriod) _PartialPeriodNotice(start: notifier.periodStart),
      Text('Ranking: ${notifier.metric.title}', style: theme.textTheme.titleMedium),
      const SizedBox(height: 8),
      if (notifier.isEmpty)
        const Padding(
          key: Key('rankings_empty'),
          padding: EdgeInsets.symmetric(vertical: 24),
          child: Text(
            'Ainda não há participantes com contribuições neste período e região.',
            textAlign: TextAlign.center,
          ),
        )
      else ...[
        for (var index = 0; index < notifier.entries.length; index++)
          _RankingTile(index: index, entry: notifier.entries[index], metric: notifier.metric),
        _buildFooter(notifier),
      ],
    ];
  }

  Widget _buildFooter(RankingsNotifier notifier) {
    if (notifier.isLoadingMore) {
      return const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()));
    }
    if (notifier.loadMoreError != null) {
      return Column(
        children: [
          const SizedBox(height: 8),
          Text(notifier.loadMoreError!, textAlign: TextAlign.center),
          TextButton(
            key: const Key('rankings_load_more_retry'),
            onPressed: notifier.loadMore,
            child: const Text('Tentar novamente'),
          ),
        ],
      );
    }
    if (notifier.hasMore) {
      return TextButton(
        key: const Key('rankings_load_more'),
        onPressed: notifier.loadMore,
        child: const Text('Carregar mais'),
      );
    }
    return const Padding(
      padding: EdgeInsets.only(top: 8),
      child: Text('Os rankings mostram até as 100 primeiras posições.', style: TextStyle(fontSize: 12)),
    );
  }
}

class _PartialPeriodNotice extends StatelessWidget {
  final DateTime? start;

  const _PartialPeriodNotice({required this.start});

  @override
  Widget build(BuildContext context) {
    final local = start?.toLocal();
    final since = local == null
        ? ''
        : ' A contagem começou em ${local.day.toString().padLeft(2, '0')}/${local.month.toString().padLeft(2, '0')}.';
    return Card(
      key: const Key('rankings_partial_period'),
      elevation: 0,
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Text(
          'Período parcial: os rankings começaram a contar depois do início deste período, então ele ainda não '
          'cobre o intervalo inteiro.$since',
        ),
      ),
    );
  }
}

class _RankingTile extends StatelessWidget {
  final int index;
  final RankingEntry entry;
  final RankingMetric metric;

  const _RankingTile({required this.index, required this.entry, required this.metric});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final user = entry.user;
    final avatarUrl = user.avatarUrl;
    return Card(
      key: Key('ranking_entry_$index'),
      elevation: 0,
      color: entry.isCurrentUser ? theme.colorScheme.primaryContainer.withAlpha(90) : null,
      child: ListTile(
        leading: SizedBox(
          width: 88,
          child: Row(
            children: [
              SizedBox(
                width: 40,
                child: Text(
                  '${entry.rankPosition}º',
                  key: Key('ranking_position_$index'),
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                ),
              ),
              CircleAvatar(
                radius: 20,
                backgroundImage: avatarUrl != null && avatarUrl.isNotEmpty ? NetworkImage(avatarUrl) : null,
                child: avatarUrl == null || avatarUrl.isEmpty
                    ? Text(user.displayName.isNotEmpty ? user.displayName[0].toUpperCase() : '?')
                    : null,
              ),
            ],
          ),
        ),
        title: Text(entry.isCurrentUser ? '${user.displayName} (você)' : user.displayName),
        subtitle: Text(
          [if (user.handle != null && user.handle!.isNotEmpty) '@${user.handle}', metric.scoreLabel(entry.score)]
              .join('\n'),
          key: Key('ranking_score_$index'),
        ),
        isThreeLine: user.handle != null && user.handle!.isNotEmpty,
      ),
    );
  }
}
