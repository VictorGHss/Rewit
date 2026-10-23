import 'package:flutter/material.dart';
import '../../domain/repositories/place_commercial_repository.dart';
import '../state/place_amenities_edit_notifier.dart';
import '../../../../shared/widgets/error_view.dart';
import '../../../../shared/widgets/loading_indicator.dart';

/// Argumentos para inicialização da tela de edição de comodidades.
class PlaceAmenitiesEditArgs {
  final String placeId;
  final String businessAccountId;
  final String placeName;
  final bool isApproved;

  const PlaceAmenitiesEditArgs({
    required this.placeId,
    required this.businessAccountId,
    required this.placeName,
    this.isApproved = true,
  });
}

/// Tela de consulta e configuração das comodidades de um estabelecimento comercial (F2.2).
class PlaceAmenitiesEditScreen extends StatefulWidget {
  final PlaceAmenitiesEditArgs args;
  final PlaceCommercialRepository? commercialRepository;
  final PlaceAmenitiesEditNotifier? notifier;

  const PlaceAmenitiesEditScreen({
    super.key,
    required this.args,
    this.commercialRepository,
    this.notifier,
  });

  @override
  State<PlaceAmenitiesEditScreen> createState() => _PlaceAmenitiesEditScreenState();
}

class _PlaceAmenitiesEditScreenState extends State<PlaceAmenitiesEditScreen> {
  late final PlaceAmenitiesEditNotifier _notifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.commercialRepository != null) {
      _notifier = PlaceAmenitiesEditNotifier(repository: widget.commercialRepository!);
      _ownsNotifier = true;
    } else {
      throw StateError('PlaceCommercialRepository ou PlaceAmenitiesEditNotifier deve ser fornecido.');
    }

    if (widget.args.isApproved) {
      _notifier.load(widget.args.placeId);
    }
  }

  @override
  void dispose() {
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  Future<void> _handleSave() async {
    final scaffoldMessenger = ScaffoldMessenger.of(context);
    final success = await _notifier.save(
      placeId: widget.args.placeId,
      businessAccountId: widget.args.businessAccountId,
    );

    if (!mounted) return;

    if (success) {
      scaffoldMessenger.showSnackBar(
        SnackBar(
          content: Text(_notifier.successMessage ?? 'Comodidades atualizadas com sucesso.'),
          backgroundColor: Colors.green,
        ),
      );
    } else if (_notifier.errorMessage != null) {
      scaffoldMessenger.showSnackBar(
        SnackBar(
          content: Text(_notifier.errorMessage!),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (!widget.args.isApproved) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Comodidades'),
        ),
        body: _buildUnauthorizedView(context, theme),
      );
    }

    return AnimatedBuilder(
      animation: _notifier,
      builder: (context, _) {
        return Scaffold(
          appBar: AppBar(
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Comodidades'),
                Text(
                  widget.args.placeName,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurface.withAlpha(160),
                  ),
                ),
              ],
            ),
            actions: [
              Padding(
                padding: const EdgeInsets.only(right: 8.0),
                child: _notifier.isSaving
                    ? const Center(
                        child: SizedBox(
                          width: 22,
                          height: 22,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      )
                    : TextButton.icon(
                        key: const Key('save_amenities_button'),
                        onPressed: _notifier.isLoading ? null : _handleSave,
                        icon: const Icon(Icons.check),
                        label: const Text('Salvar'),
                      ),
              ),
            ],
          ),
          body: _buildBody(context, theme),
        );
      },
    );
  }

  Widget _buildBody(BuildContext context, ThemeData theme) {
    if (_notifier.isLoading) {
      return const LoadingIndicator(message: 'Carregando comodidades...');
    }

    if (_notifier.catalogOptions.isEmpty && _notifier.errorMessage != null) {
      return ErrorView(
        title: 'Não foi possível carregar as opções',
        message: _notifier.errorMessage!,
        onRetry: () => _notifier.load(widget.args.placeId),
      );
    }

    return ListView(
      padding: const EdgeInsets.all(16.0),
      children: [
        _buildInstructionCard(context, theme),
        if (_notifier.errorMessage != null) ...[
          const SizedBox(height: 12),
          _buildErrorBanner(context, theme, _notifier.errorMessage!),
        ],
        const SizedBox(height: 12),
        _buildActionRow(context, theme),
        const SizedBox(height: 12),
        Card(
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(60)),
          ),
          child: Column(
            children: [
              ..._notifier.catalogOptions.asMap().entries.map((entry) {
                final index = entry.key;
                final option = entry.value;
                final isSelected = _notifier.selectedCodes.contains(option.code);

                return Column(
                  children: [
                    CheckboxListTile(
                      key: Key('amenity_checkbox_${option.code}'),
                      title: Text(
                        option.label,
                        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
                      ),
                      subtitle: Text(
                        option.code,
                        style: TextStyle(
                          fontSize: 12,
                          color: theme.colorScheme.onSurface.withAlpha(140),
                        ),
                      ),
                      value: isSelected,
                      onChanged: _notifier.isSaving
                          ? null
                          : (_) => _notifier.toggleAmenity(option.code),
                    ),
                    if (index < _notifier.catalogOptions.length - 1)
                      const Divider(height: 1),
                  ],
                );
              }),
            ],
          ),
        ),
        const SizedBox(height: 24),
        ElevatedButton.icon(
          key: const Key('save_amenities_bottom_button'),
          onPressed: _notifier.isSaving ? null : _handleSave,
          icon: _notifier.isSaving
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                )
              : const Icon(Icons.save),
          label: const Text('Salvar Comodidades'),
          style: ElevatedButton.styleFrom(
            padding: const EdgeInsets.symmetric(vertical: 14),
          ),
        ),
      ],
    );
  }

  Widget _buildInstructionCard(BuildContext context, ThemeData theme) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.primary.withAlpha(15),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: theme.colorScheme.primary.withAlpha(50)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, size: 20, color: theme.colorScheme.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Selecione as facilidades oferecidas aos seus clientes. Caso nenhuma comodidade seja selecionada, a configuração registrará formalmente que o local não possui comodidades declaradas.',
              style: TextStyle(
                fontSize: 12,
                color: theme.colorScheme.onSurface.withAlpha(170),
                height: 1.3,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActionRow(BuildContext context, ThemeData theme) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          '${_notifier.selectedCodes.length} selecionada(s)',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            fontSize: 13,
            color: theme.colorScheme.primary,
          ),
        ),
        Row(
          children: [
            TextButton(
              key: const Key('select_all_amenities_button'),
              onPressed: _notifier.isSaving ? null : () => _notifier.selectAll(),
              child: const Text('Marcar todas'),
            ),
            const SizedBox(width: 4),
            TextButton(
              key: const Key('clear_all_amenities_button'),
              onPressed: _notifier.isSaving ? null : () => _notifier.clearAll(),
              child: const Text('Limpar seleção'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildErrorBanner(BuildContext context, ThemeData theme, String message) {
    return Container(
      key: const Key('amenities_error_banner'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.error.withAlpha(20),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: theme.colorScheme.error.withAlpha(80)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.warning_amber_rounded, size: 20, color: theme.colorScheme.error),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                color: theme.colorScheme.error,
                fontSize: 13,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildUnauthorizedView(BuildContext context, ThemeData theme) {
    return Center(
      key: const Key('amenities_unauthorized_view'),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.lock_outline, size: 64, color: theme.colorScheme.error),
            const SizedBox(height: 16),
            Text(
              'Acesso Restrito',
              style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Apenas estabelecimentos com reivindicação aprovada podem gerenciar comodidades.',
              textAlign: TextAlign.center,
              style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Voltar'),
            ),
          ],
        ),
      ),
    );
  }
}
