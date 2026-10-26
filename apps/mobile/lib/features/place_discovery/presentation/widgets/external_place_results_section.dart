import 'dart:async';
import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/services/url_launcher_service.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/device_location.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import '../../domain/entities/discovery_region.dart';
import '../../domain/entities/external_place_candidate.dart';
import '../../domain/repositories/place_discovery_repository.dart';

/// Como o usuário quer delimitar a busca externa.
enum ExternalRegionMode { city, nearby, anywhere }

/// Resultados de fonte externa, separados do catálogo Rewit.
///
/// A busca externa respeita a cota gratuita do provedor: parte de um toque explícito, ou uma única vez ao escolher
/// "Perto de mim" (a posição é pedida nessa escolha, sem rastreamento), e nunca a cada tecla. A região é escolhida pelo
/// usuário: uma cidade com UF, a localização atual ou nenhuma restrição, avisada na tela. O mesmo termo na mesma
/// região não é buscado de novo. Nada é gravado até o usuário escolher "Adotar e selecionar", que usa o fluxo oficial de adoção
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
  int _locationSequence = 0;
  bool _isLoading = false;
  String? _errorMessage;
  List<ExternalPlaceCandidate>? _results;
  DiscoveryRegion? _resultsRegion;
  String? _resultsQuery;
  String? _adoptingId;

  /// Busca concluída (termo + região): repetir o mesmo pedido não gasta cota de novo.
  String? _lastSearchKey;

  /// Posição obtida ao escolher "Perto de mim"; vale só enquanto essa opção estiver ativa e nunca é gravada.
  DeviceLocation? _nearbyLocation;
  bool _locating = false;
  _LocationIssue? _locationIssue;

  @override
  void didUpdateWidget(covariant ExternalPlaceResultsSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Resultados de outra consulta não são reaproveitados; a nova consulta espera um toque (não busca a cada tecla)
    if (oldWidget.query.trim() != widget.query.trim() && _resultsQuery != widget.query.trim()) {
      _discardResults();
    }
  }

  @override
  void dispose() {
    _sequence++;
    _locationSequence++;
    _cityController.dispose();
    super.dispose();
  }

  void _discardResults() {
    _sequence++;
    _locationSequence++;
    _isLoading = false;
    _errorMessage = null;
    _results = null;
    _resultsRegion = null;
    _resultsQuery = null;
    _lastSearchKey = null;
  }

  bool get _hasQuery => widget.query.trim().length >= 2;

  /// Região atual, ou `null` enquanto faltar dado (cidade/UF ou posição).
  DiscoveryRegion? get _currentRegion => switch (_mode) {
        ExternalRegionMode.city => _cityController.text.trim().length >= 2 && _stateCode != null
            ? CityRegion(city: _cityController.text.trim(), state: _stateCode!)
            : null,
        ExternalRegionMode.nearby => _nearbyLocation == null
            ? null
            : NearbyRegion(latitude: _nearbyLocation!.latitude, longitude: _nearbyLocation!.longitude),
        ExternalRegionMode.anywhere => const UnrestrictedRegion(),
      };

  static String _regionKey(DiscoveryRegion region) => switch (region) {
        CityRegion(:final city, :final state) => 'city:${city.toLowerCase()}|${state.toUpperCase()}',
        NearbyRegion(:final latitude, :final longitude, :final radiusMeters) =>
          'near:${latitude.toStringAsFixed(4)},${longitude.toStringAsFixed(4)},$radiusMeters',
        UnrestrictedRegion() => 'anywhere',
      };

  String? get _currentSearchKey {
    final region = _currentRegion;
    return region == null ? null : '${widget.query.trim().toLowerCase()}|${_regionKey(region)}';
  }

  /// O mesmo termo já foi buscado na mesma região e o resultado está na tela.
  bool get _alreadySearched => _results != null && _lastSearchKey != null && _lastSearchKey == _currentSearchKey;

  bool get _canSearch {
    if (!widget.enabled || _isLoading || _locating || !_hasQuery || _alreadySearched) return false;
    return switch (_mode) {
      // Sem posição ainda, o toque pede a localização e busca em seguida
      ExternalRegionMode.nearby => widget.locationService != null,
      _ => _currentRegion != null,
    };
  }

  Future<void> _onSearchPressed() async {
    if (_mode == ExternalRegionMode.nearby && _nearbyLocation == null) {
      await _locateAndSearch();
    } else {
      await _search();
    }
  }

  Future<void> _search() async {
    final query = widget.query.trim();
    final region = _currentRegion;
    final key = _currentSearchKey;
    if (!_hasQuery || region == null || _isLoading || _alreadySearched) return;
    final seq = ++_sequence;
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    try {
      final results = await widget.repository.search(query: query, region: region);
      if (!mounted || seq != _sequence) return;
      setState(() {
        _isLoading = false;
        _results = results;
        _resultsRegion = region;
        _resultsQuery = query;
        _lastSearchKey = key;
      });
    } catch (e) {
      if (!mounted || seq != _sequence) return;
      setState(() {
        _isLoading = false;
        _errorMessage = _messageFor(e);
      });
    }
  }

  /// Pede a posição uma vez (o sistema pede a permissão, se preciso) e, com termo utilizável, faz uma única busca.
  Future<void> _locateAndSearch() async {
    final service = widget.locationService;
    if (service == null || _locating) return;
    final locSeq = ++_locationSequence;
    setState(() {
      _locating = true;
      _locationIssue = null;
      _errorMessage = null;
    });
    try {
      final result = await service.getCurrentLocation(
        timeout: const Duration(seconds: 5),
      );
      // Opção trocada ou nova leitura disparada durante a leitura: a posição é descartada
      if (!mounted || locSeq != _locationSequence || _mode != ExternalRegionMode.nearby) return;
      switch (result) {
        case LocationSuccess(:final location):
          if (!_isUsableLocation(location)) {
            setState(() {
              _locating = false;
              _locationIssue = const _LocationIssue.invalid();
            });
            return;
          }
          setState(() {
            _locating = false;
            _nearbyLocation = location;
          });
          if (_hasQuery) await _search();
        case LocationFailure(:final reason):
          setState(() {
            _locating = false;
            _locationIssue = _LocationIssue(reason);
          });
      }
    } catch (_) {
      if (!mounted || locSeq != _locationSequence || _mode != ExternalRegionMode.nearby) return;
      setState(() {
        _locating = false;
        _locationIssue = const _LocationIssue(LocationFailureReason.error);
      });
    }
  }

  static bool _isUsableLocation(DeviceLocation location) {
    final lat = location.latitude;
    final lng = location.longitude;
    if (!lat.isFinite || !lng.isFinite || lat < -90 || lat > 90 || lng < -180 || lng > 180) return false;
    // 0,0 é o valor de quem não tem posição, não um lugar plausível para a busca
    return !(lat == 0 && lng == 0);
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
    if (error is TimeoutException) {
      return 'Tempo limite esgotado ao consultar a fonte externa. Tente novamente.';
    }
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
      final detail = error.detail.trim();
      if (detail.startsWith('<') || detail.toLowerCase().contains('<html') || detail.toLowerCase().contains('<!doctype')) {
        return 'Não foi possível consultar a fonte externa neste momento. Tente novamente mais tarde.';
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
              onPressed: _canSearch ? _onSearchPressed : null,
              icon: Icon(_mode == ExternalRegionMode.nearby ? Icons.my_location_rounded : Icons.public_rounded,
                  size: 16),
              label: Text(_alreadySearched ? 'Busca feita para este termo e região' : 'Buscar em fontes externas',
                  style: const TextStyle(fontSize: 12)),
            ),
          ),
          if (_locating)
            Padding(
              key: const Key('external_locating'),
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                  const SizedBox(width: 8),
                  Text('Obtendo sua localização…', style: mutedStyle),
                ],
              ),
            ),
          if (_locationIssue != null) _buildLocationIssue(theme, _locationIssue!),
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
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                key: const Key('external_search_retry_button'),
                onPressed: widget.enabled && !_isLoading ? _onSearchPressed : null,
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('Tentar novamente', style: TextStyle(fontSize: 12)),
              ),
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
            Text(
              _resultsRegion is NearbyRegion
                  ? 'Nenhum estabelecimento encontrado perto da sua localização atual. No Android Emulator, a posição '
                      'é a simulada (⋯ Extended controls › Location), não a do computador.'
                  : 'Nenhum estabelecimento encontrado na fonte externa.',
              key: const Key('external_place_empty_text'),
              style: mutedStyle,
            ),
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
              'A localização é pedida ao escolher esta opção, usada só nesta busca (raio de '
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
      // Sair de "Perto de mim" descarta a posição; voltar pede de novo
      _nearbyLocation = null;
      _locationIssue = null;
      _locating = false;
    });
    if (mode == ExternalRegionMode.nearby) _locateAndSearch();
  }

  Widget _buildLocationIssue(ThemeData theme, _LocationIssue issue) {
    return Padding(
      key: const Key('external_location_issue'),
      padding: const EdgeInsets.only(top: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(issue.message,
              style: TextStyle(fontSize: 11, color: theme.colorScheme.error, fontWeight: FontWeight.w500)),
          if (issue.showsEmulatorHint)
            Text(
              'No Android Emulator, defina uma posição em ⋯ Extended controls › Location › Set location.',
              key: const Key('external_location_emulator_hint'),
              style: TextStyle(fontSize: 10, color: theme.colorScheme.onSurface.withAlpha(150)),
            ),
          Wrap(
            spacing: 4,
            children: [
              TextButton.icon(
                key: const Key('external_location_retry_button'),
                onPressed: widget.enabled && !_locating ? _locateAndSearch : null,
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('Tentar novamente', style: TextStyle(fontSize: 12)),
              ),
              if (issue.reason == LocationFailureReason.permissionDeniedForever)
                TextButton.icon(
                  key: const Key('external_location_settings_button'),
                  onPressed: () => widget.locationService?.openAppSettings(),
                  icon: const Icon(Icons.settings_outlined, size: 16),
                  label: const Text('Abrir configurações', style: TextStyle(fontSize: 12)),
                ),
            ],
          ),
        ],
      ),
    );
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

/// Falha ao obter a posição, com mensagem própria para cada caso; `reason` nulo é posição recebida mas inválida.
class _LocationIssue {
  final LocationFailureReason? reason;

  const _LocationIssue(this.reason);

  const _LocationIssue.invalid() : reason = null;

  String get message => switch (reason) {
        LocationFailureReason.permissionDenied =>
          'Permissão de localização negada. Para buscar perto de você, permita o acesso à localização.',
        LocationFailureReason.permissionDeniedForever =>
          'A localização está bloqueada para o Rewit. Libere a permissão nas configurações do aparelho.',
        LocationFailureReason.serviceDisabled =>
          'A localização (GPS) do aparelho está desativada. Ative-a e tente novamente.',
        LocationFailureReason.timeout => 'A localização não ficou disponível a tempo.',
        LocationFailureReason.error => 'Não foi possível obter a localização do aparelho.',
        null => 'A localização recebida é inválida. Nenhuma busca foi feita.',
      };

  /// Casos em que, num emulador, o mais provável é não haver posição simulada.
  bool get showsEmulatorHint =>
      reason == null ||
      reason == LocationFailureReason.serviceDisabled ||
      reason == LocationFailureReason.timeout ||
      reason == LocationFailureReason.error;
}
