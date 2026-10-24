import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/services/url_launcher_service.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import '../../domain/entities/external_place_candidate.dart';
import '../../domain/repositories/place_discovery_repository.dart';

/// Resultados de fonte externa, separados do catálogo Rewit.
///
/// A busca só parte de um toque explícito (respeita a cota gratuita do provedor) e nada é gravado até o usuário
/// escolher "Adotar e selecionar", que usa o fluxo oficial de adoção com deduplicação por referência externa.
class ExternalPlaceResultsSection extends StatefulWidget {
  final PlaceDiscoveryRepository repository;
  final String query;
  final bool enabled;

  /// Recebe o local adotado (novo ou já existente) como alvo PLACE.
  final ValueChanged<SearchResultItem> onAdopted;

  /// Oferece o cadastro manual quando o candidato não tem endereço completo.
  final VoidCallback? onRegisterManually;
  final UrlLauncherService urlLauncher;

  const ExternalPlaceResultsSection({
    super.key,
    required this.repository,
    required this.query,
    required this.onAdopted,
    this.enabled = true,
    this.onRegisterManually,
    this.urlLauncher = const UrlLauncherServiceImpl(),
  });

  @override
  State<ExternalPlaceResultsSection> createState() => _ExternalPlaceResultsSectionState();
}

class _ExternalPlaceResultsSectionState extends State<ExternalPlaceResultsSection> {
  int _sequence = 0;
  bool _isLoading = false;
  String? _errorMessage;
  List<ExternalPlaceCandidate>? _results;
  String? _resultsQuery;
  String? _adoptingId;

  @override
  void didUpdateWidget(covariant ExternalPlaceResultsSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Resultados de outra consulta não são reaproveitados
    if (oldWidget.query.trim() != widget.query.trim() && _resultsQuery != widget.query.trim()) {
      _sequence++;
      _isLoading = false;
      _errorMessage = null;
      _results = null;
      _resultsQuery = null;
    }
  }

  Future<void> _search() async {
    final query = widget.query.trim();
    if (query.length < 2 || _isLoading) return;
    final seq = ++_sequence;
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    try {
      final results = await widget.repository.search(query: query);
      if (!mounted || seq != _sequence) return;
      setState(() {
        _isLoading = false;
        _results = results;
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

  Future<void> _adopt(ExternalPlaceCandidate candidate) async {
    if (_adoptingId != null || !widget.enabled) return;
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
      if (error.errorCode == 'EXTERNAL_SERVICE_NOT_CONFIGURED') {
        return 'A busca em fontes externas não está disponível neste ambiente.';
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
          Text(
            'Fora do catálogo Rewit. O local só entra no catálogo quando você o adota.',
            style: TextStyle(fontSize: 11, color: theme.colorScheme.onSurface.withAlpha(150)),
          ),
          if (results == null && !_isLoading) ...[
            const SizedBox(height: 6),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton.icon(
                key: const Key('external_place_search_button'),
                onPressed: widget.enabled && widget.query.trim().length >= 2 ? _search : null,
                icon: const Icon(Icons.public_rounded, size: 16),
                label: const Text('Buscar em fontes externas', style: TextStyle(fontSize: 12)),
              ),
            ),
          ],
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
          if (results != null && results.isEmpty) ...[
            const SizedBox(height: 6),
            Text(
              'Nenhum estabelecimento encontrado na fonte externa.',
              key: const Key('external_place_empty_text'),
              style: TextStyle(fontSize: 11, color: theme.colorScheme.onSurface.withAlpha(150)),
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

  Widget _buildCandidateTile(ThemeData theme, ExternalPlaceCandidate candidate) {
    final adopting = _adoptingId == candidate.externalId;
    final adoptable = candidate.isAdoptable;
    return ListTile(
      key: Key('external_candidate_${candidate.externalId}'),
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(Icons.public_rounded, size: 20, color: theme.colorScheme.tertiary),
      title: Text(candidate.displayName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold)),
      subtitle: Text(
        adoptable
            ? candidate.formattedAddress
            : '${candidate.formattedAddress.isEmpty ? 'Sem endereço' : candidate.formattedAddress} • '
                'endereço incompleto, cadastre manualmente',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11, color: theme.colorScheme.onSurface.withAlpha(150)),
      ),
      trailing: adopting
          ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
          : adoptable
              ? TextButton(
                  key: Key('adopt_external_candidate_${candidate.externalId}'),
                  onPressed: widget.enabled && _adoptingId == null ? () => _adopt(candidate) : null,
                  child: const Text('Adotar e selecionar', style: TextStyle(fontSize: 12)),
                )
              : widget.onRegisterManually != null
                  ? TextButton(
                      onPressed: widget.enabled ? widget.onRegisterManually : null,
                      child: const Text('Cadastrar', style: TextStyle(fontSize: 12)),
                    )
                  : null,
    );
  }
}
