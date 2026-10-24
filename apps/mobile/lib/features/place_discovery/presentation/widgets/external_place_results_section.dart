import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/services/url_launcher_service.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import '../../domain/entities/discovery_region.dart';
import '../../domain/entities/external_place_candidate.dart';
import '../../domain/repositories/place_discovery_repository.dart';

/// Como o usuário quer delimitar a busca externa.
enum ExternalRegionMode { city, nearby, anywhere }

/// Resultados de fonte externa, separados do catálogo Rewit.
///
/// A busca só parte de um toque explícito (respeita a cota gratuita do provedor), numa região escolhida pelo
/// usuário: uma cidade com UF, a localização atual (pedida só nesse toque, sem rastreamento) ou nenhuma restrição,
/// avisada na tela. Nada é gravado até o usuário escolher "Adotar e selecionar", que usa o fluxo oficial de adoção
/// com deduplicação por referência externa.
class ExternalPlaceResultsSection extends StatefulWidget {
  final PlaceDiscoveryRepository repository;
  final String query;
  final bool enabled;

  /// Recebe o local adotado (novo ou já existente) como alvo PLACE.
  final ValueChanged<SearchResultItem> onAdopted;

  /// Oferece o cadastro manual quando o candidato não pode ser adotado.
  final VoidCallback? onRegisterManually;

  /// Com ele, a opção "Perto de mim" fica disponível; a posição é lida uma vez, no toque de busca.
  final LocationService? locationService;
  final UrlLauncherService urlLauncher;

  const ExternalPlaceResultsSection({
    super.key,
    required this.repository,
    required this.query,
    required this.onAdopted,
    this.enabled = true,
    this.onRegisterManually,
    this.locationService,
    this.urlLauncher = const UrlLauncherServiceImpl(),
  });

  @override
  State<ExternalPlaceResultsSection> createState() => _ExternalPlaceResultsSectionState();
}

class _ExternalPlaceResultsSectionState extends State<ExternalPlaceResultsSection> {
  final TextEditingController _cityController = TextEditingController();
  ExternalRegionMode _mode = ExternalRegionMode.city;
  String? _stateCode;

  int _sequence = 0;
  bool _isLoading = false;
  String? _errorMessage;
  List<ExternalPlaceCandidate>? _results;
  DiscoveryRegion? _resultsRegion;
  String? _resultsQuery;
  String? _adoptingId;

  @override
  void didUpdateWidget(covariant ExternalPlaceResultsSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Resultados de outra consulta não são reaproveitados
    if (oldWidget.query.trim() != widget.query.trim() && _resultsQuery != widget.query.trim()) {
      _discardResults();
    }
  }

  @override
  void dispose() {
    _cityController.dispose();
    super.dispose();
  }

  void _discardResults() {
    _sequence++;
    _isLoading = false;
    _errorMessage = null;
    _results = null;
    _resultsRegion = null;
    _resultsQuery = null;
  }

  bool get _regionReady => switch (_mode) {
        ExternalRegionMode.city => _cityController.text.trim().length >= 2 && _stateCode != null,
        ExternalRegionMode.nearby => widget.locationService != null,
        ExternalRegionMode.anywhere => true,
      };

  bool get _canSearch => widget.enabled && !_isLoading && widget.query.trim().length >= 2 && _regionReady;

  Future<void> _search() async {
    final query = widget.query.trim();
    if (!_canSearch) return;
    final seq = ++_sequence;
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    try {
      final region = await _resolveRegion();
      if (!mounted || seq != _sequence) return;
      if (region == null) {
        setState(() => _isLoading = false);
        return;
      }
      final results = await widget.repository.search(query: query, region: region);
      if (!mounted || seq != _sequence) return;
      setState(() {
        _isLoading = false;
        _results = results;
        _resultsRegion = region;
        _resultsQuery = query;
      });
    } catch (e) {
      if (!mounted || seq != _sequence) return;
      setState(() {
        _isLoading = false;
        _errorMessage = _messageFor(e);
      });
    }
  }

  /// Região da busca. "Perto de mim" pede a posição só agora (o sistema pede a permissão) e não a guarda.
  Future<DiscoveryRegion?> _resolveRegion() async {
    switch (_mode) {
      case ExternalRegionMode.city:
        return CityRegion(city: _cityController.text.trim(), state: _stateCode!);
      case ExternalRegionMode.anywhere:
        return const UnrestrictedRegion();
      case ExternalRegionMode.nearby:
        final result = await widget.locationService!.getCurrentLocation();
        switch (result) {
          case LocationSuccess(:final location):
            return NearbyRegion(latitude: location.latitude, longitude: location.longitude);
          case LocationFailure(:final message):
            if (mounted) {
              setState(() => _errorMessage = '$message Escolha uma cidade para buscar sem usar a localização.');
            }
            return null;
        }
    }
  }

  Future<void> _adopt(ExternalPlaceCandidate candidate) async {
    if (_adoptingId != null || !widget.enabled || !candidate.isAdoptable) return;
    setState(() => _adoptingId = candidate.externalId);
    try {
      final PlaceDetail place = await widget.repository.adopt(candidate);
      if (!mounted) return;
      setState(() => _adoptingId = null);
      widget.onAdopted(SearchResultItem(
        id: place.id,
        name: place.name,
        slug: place.slug,
        category: place.category,
        targetType: TargetType.place,
        rawTargetType: 'PLACE',
        status: place.status,
      ));
    } catch (e) {
      if (!mounted) return;
      setState(() => _adoptingId = null);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Não foi possível adotar "${candidate.displayName}": ${_messageFor(e)}')),
      );
    }
  }

  static String _messageFor(Object error) {
    if (error is ApiException) {
      switch (error.errorCode) {
        case 'EXTERNAL_SERVICE_NOT_CONFIGURED':
          return 'A busca em fontes externas não está disponível neste ambiente.';
        case 'REGION_NOT_FOUND':
          return 'Cidade não encontrada nessa UF. Confira o nome da cidade e a UF.';
        case 'INVALID_REGION':
          return 'Região inválida: informe a cidade e uma UF válida.';
        case 'REGION_FILTER_UNSUPPORTED':
          return 'A fonte externa ativa não permite restringir por cidade. Use "Qualquer lugar" ou "Perto de mim".';
      }
      if (error.isRateLimited) {
        return error.retryAfterSeconds != null
            ? 'Muitas buscas seguidas. Tente novamente em ${error.retryAfterSeconds} segundos.'
            : 'Muitas buscas seguidas. Tente novamente em instantes.';
      }
      if (error.isServerError) {
        return 'A fonte externa não respondeu agora. Tente novamente mais tarde.';
      }
      return error.detail;
    }
    if (error is NetworkException) return error.message;
    return 'Não foi possível consultar a fonte externa.';
  }

  /// Atribuições únicas exigidas pelas fontes dos resultados exibidos.
  List<ExternalPlaceAttribution> get _attributions {
    final seen = <ExternalPlaceAttribution>{};
    for (final candidate in _results ?? const <ExternalPlaceCandidate>[]) {
      seen.addAll(candidate.attributions);
    }
    return seen.toList();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final results = _results;
    final mutedStyle = TextStyle(fontSize: 11, color: theme.colorScheme.onSurface.withAlpha(150));

    return Container(
      key: const Key('external_place_results_section'),
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: theme.colorScheme.tertiaryContainer.withAlpha(60),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: theme.colorScheme.tertiary.withAlpha(90)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(Icons.travel_explore_rounded, size: 18, color: theme.colorScheme.tertiary),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  results != null && results.isNotEmpty
                      ? 'Resultados externos • ${results.first.providerLabel}'
                      : 'Resultados externos',
                  key: const Key('external_place_results_title'),
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: theme.colorScheme.tertiary),
                ),
              ),
            ],
          ),
          const SizedBox(height: 2),
          Text('Fora do catálogo Rewit. O local só entra no catálogo quando você o adota.', style: mutedStyle),
          const SizedBox(height: 8),
          _buildRegionSelector(theme, mutedStyle),
          const SizedBox(height: 6),
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton.icon(
              key: const Key('external_place_search_button'),
              onPressed: _canSearch ? _search : null,
              icon: Icon(_mode == ExternalRegionMode.nearby ? Icons.my_location_rounded : Icons.public_rounded,
                  size: 16),
              label: Text(results == null ? 'Buscar em fontes externas' : 'Buscar de novo nesta região',
                  style: const TextStyle(fontSize: 12)),
            ),
          ),
          if (_isLoading)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 10),
              child: Center(child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))),
            ),
          if (_errorMessage != null) ...[
            const SizedBox(height: 6),
            Text(
              _errorMessage!,
              key: const Key('external_place_error_text'),
              style: TextStyle(fontSize: 11, color: theme.colorScheme.error, fontWeight: FontWeight.w500),
            ),
          ],
          if (_resultsRegion != null && results != null) ...[
            const SizedBox(height: 6),
            Text(
              _resultsRegion!.label,
              key: const Key('external_region_label'),
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: _resultsRegion is UnrestrictedRegion ? Colors.orange.shade900 : theme.colorScheme.tertiary,
              ),
            ),
          ],
          if (results != null && results.isEmpty) ...[
            const SizedBox(height: 6),
            Text('Nenhum estabelecimento encontrado na fonte externa.',
                key: const Key('external_place_empty_text'), style: mutedStyle),
          ],
          if (results != null && results.isNotEmpty) ...[
            const SizedBox(height: 4),
            ...results.map((candidate) => _buildCandidateTile(theme, candidate)),
          ],
          if (_attributions.isNotEmpty) ...[
            const SizedBox(height: 4),
            Wrap(
              key: const Key('external_place_attributions'),
              spacing: 8,
              children: _attributions
                  .map((a) => InkWell(
                        onTap: a.url == null ? null : () => widget.urlLauncher.launchWebUrl(a.url!),
                        child: Text(
                          a.text,
                          style: TextStyle(
                            fontSize: 10,
                            color: theme.colorScheme.onSurface.withAlpha(160),
                            decoration: a.url == null ? null : TextDecoration.underline,
                          ),
                        ),
                      ))
                  .toList(),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildRegionSelector(ThemeData theme, TextStyle mutedStyle) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          spacing: 6,
          children: [
            ChoiceChip(
              key: const Key('external_region_mode_city'),
              label: const Text('Cidade', style: TextStyle(fontSize: 12)),
              selected: _mode == ExternalRegionMode.city,
              onSelected: widget.enabled ? (_) => _selectMode(ExternalRegionMode.city) : null,
            ),
            if (widget.locationService != null)
              ChoiceChip(
                key: const Key('external_region_mode_nearby'),
                label: const Text('Perto de mim', style: TextStyle(fontSize: 12)),
                selected: _mode == ExternalRegionMode.nearby,
                onSelected: widget.enabled ? (_) => _selectMode(ExternalRegionMode.nearby) : null,
              ),
            ChoiceChip(
              key: const Key('external_region_mode_anywhere'),
              label: const Text('Qualquer lugar', style: TextStyle(fontSize: 12)),
              selected: _mode == ExternalRegionMode.anywhere,
              onSelected: widget.enabled ? (_) => _selectMode(ExternalRegionMode.anywhere) : null,
            ),
          ],
        ),
        const SizedBox(height: 6),
        switch (_mode) {
          ExternalRegionMode.city => Row(
              children: [
                Expanded(
                  child: TextField(
                    key: const Key('external_region_city_field'),
                    controller: _cityController,
                    enabled: widget.enabled,
                    textCapitalization: TextCapitalization.words,
                    maxLength: 100,
                    decoration: const InputDecoration(
                      labelText: 'Cidade',
                      hintText: 'ex.: Ponta Grossa',
                      isDense: true,
                      counterText: '',
                      border: OutlineInputBorder(),
                    ),
                    onChanged: (_) => setState(() {}),
                  ),
                ),
                const SizedBox(width: 8),
                SizedBox(
                  width: 108,
                  child: DropdownButtonFormField<String>(
                    key: const Key('external_region_state_field'),
                    initialValue: _stateCode,
                    isDense: true,
                    decoration: const InputDecoration(labelText: 'UF', isDense: true, border: OutlineInputBorder()),
                    items: brazilianStateCodes
                        .map((uf) => DropdownMenuItem(value: uf, child: Text(uf)))
                        .toList(),
                    onChanged: widget.enabled ? (uf) => setState(() => _stateCode = uf) : null,
                  ),
                ),
              ],
            ),
          ExternalRegionMode.nearby => Text(
              'A localização é pedida só ao tocar em buscar, usada nesta busca (raio de '
              '${(NearbyRegion.defaultRadiusMeters / 1000).round()} km) e não é guardada.',
              key: const Key('external_region_nearby_hint'),
              style: mutedStyle,
            ),
          ExternalRegionMode.anywhere => Text(
              'Sem restrição geográfica: os resultados podem ser de qualquer cidade.',
              key: const Key('external_region_anywhere_hint'),
              style: TextStyle(fontSize: 11, color: Colors.orange.shade900, fontWeight: FontWeight.w500),
            ),
        },
      ],
    );
  }

  void _selectMode(ExternalRegionMode mode) {
    if (mode == _mode) return;
    setState(() {
      _mode = mode;
      _discardResults();
    });
  }

  Widget _buildCandidateTile(ThemeData theme, ExternalPlaceCandidate candidate) {
    final adopting = _adoptingId == candidate.externalId;
    final blockReason = candidate.adoptionBlockReason;
    final address = candidate.formattedAddress.isEmpty ? 'Sem endereço' : candidate.formattedAddress;
    return ListTile(
      key: Key('external_candidate_${candidate.externalId}'),
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(Icons.public_rounded, size: 20, color: theme.colorScheme.tertiary),
      title: Text(candidate.displayName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold)),
      subtitle: Text(
        blockReason == null ? address : '$address • $blockReason, cadastre manualmente',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11, color: theme.colorScheme.onSurface.withAlpha(150)),
      ),
      trailing: adopting
          ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
          : blockReason == null
              ? TextButton(
                  key: Key('adopt_external_candidate_${candidate.externalId}'),
                  onPressed: widget.enabled && _adoptingId == null ? () => _adopt(candidate) : null,
                  child: const Text('Adotar e selecionar', style: TextStyle(fontSize: 12)),
                )
              : widget.onRegisterManually != null
                  ? TextButton(
                      key: Key('register_external_candidate_${candidate.externalId}'),
                      onPressed: widget.enabled ? widget.onRegisterManually : null,
                      child: const Text('Cadastrar', style: TextStyle(fontSize: 12)),
                    )
                  : null,
    );
  }
}
