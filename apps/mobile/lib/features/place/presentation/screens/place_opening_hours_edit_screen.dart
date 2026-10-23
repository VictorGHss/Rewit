import 'package:flutter/material.dart';
import '../../domain/repositories/place_commercial_repository.dart';
import '../state/place_opening_hours_edit_notifier.dart';
import '../../../../shared/widgets/error_view.dart';
import '../../../../shared/widgets/loading_indicator.dart';

/// Argumentos para inicialização da tela de edição de horários de funcionamento.
class PlaceOpeningHoursEditArgs {
  final String placeId;
  final String businessAccountId;
  final String placeName;
  final bool isApproved;

  const PlaceOpeningHoursEditArgs({
    required this.placeId,
    required this.businessAccountId,
    required this.placeName,
    this.isApproved = true,
  });
}

/// Tela de edição e configuração dos horários semanais de funcionamento de um estabelecimento (F2.1).
class PlaceOpeningHoursEditScreen extends StatefulWidget {
  final PlaceOpeningHoursEditArgs args;
  final PlaceCommercialRepository? commercialRepository;
  final PlaceOpeningHoursEditNotifier? notifier;

  const PlaceOpeningHoursEditScreen({
    super.key,
    required this.args,
    this.commercialRepository,
    this.notifier,
  });

  @override
  State<PlaceOpeningHoursEditScreen> createState() => _PlaceOpeningHoursEditScreenState();
}

class _PlaceOpeningHoursEditScreenState extends State<PlaceOpeningHoursEditScreen> {
  late final PlaceOpeningHoursEditNotifier _notifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.commercialRepository != null) {
      _notifier = PlaceOpeningHoursEditNotifier(repository: widget.commercialRepository!);
      _ownsNotifier = true;
    } else {
      throw StateError('PlaceCommercialRepository ou PlaceOpeningHoursEditNotifier deve ser fornecido.');
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
          content: Text(_notifier.successMessage ?? 'Horários atualizados com sucesso.'),
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

  Future<void> _pickTime({
    required int dayOfWeek,
    required int intervalIndex,
    required bool isOpenTime,
    required String currentTime,
  }) async {
    final parts = currentTime.split(':');
    final initialHour = parts.isNotEmpty ? int.tryParse(parts[0]) ?? 8 : 8;
    final initialMinute = parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0;

    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: initialHour, minute: initialMinute),
    );

    if (picked != null && mounted) {
      final formatted =
          '${picked.hour.toString().padLeft(2, '0')}:${picked.minute.toString().padLeft(2, '0')}';
      if (isOpenTime) {
        _notifier.updateInterval(dayOfWeek, intervalIndex, openTime: formatted);
      } else {
        _notifier.updateInterval(dayOfWeek, intervalIndex, closeTime: formatted);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (!widget.args.isApproved) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Horários de Funcionamento'),
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
                const Text('Horários de Funcionamento'),
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
                        key: const Key('save_opening_hours_button'),
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
      return const LoadingIndicator(message: 'Carregando horários...');
    }

    if (_notifier.days.isEmpty && _notifier.errorMessage != null) {
      return ErrorView(
        title: 'Não foi possível carregar os horários',
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
        const SizedBox(height: 16),
        ..._notifier.days.map((day) => _buildDayCard(context, theme, day)),
        const SizedBox(height: 24),
        ElevatedButton.icon(
          key: const Key('save_opening_hours_bottom_button'),
          onPressed: _notifier.isSaving ? null : _handleSave,
          icon: _notifier.isSaving
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                )
              : const Icon(Icons.save),
          label: const Text('Salvar Alterações'),
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
              'Configure a grade semanal de atendimento. Dias fechados não precisam de horários. Para dias abertos, informe ao menos um intervalo sem sobreposição.',
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

  Widget _buildErrorBanner(BuildContext context, ThemeData theme, String message) {
    return Container(
      key: const Key('opening_hours_error_banner'),
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

  Widget _buildDayCard(BuildContext context, ThemeData theme, dynamic day) {
    final int dayOfWeek = day.dayOfWeek as int;
    final bool closed = day.closed as bool;
    final intervals = day.intervals as List;

    return Card(
      key: Key('day_card_$dayOfWeek'),
      margin: const EdgeInsets.only(bottom: 12),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: closed
              ? theme.colorScheme.outlineVariant.withAlpha(40)
              : theme.colorScheme.primary.withAlpha(80),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    day.localizedDayName as String,
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 15,
                      color: closed ? theme.colorScheme.onSurface.withAlpha(130) : null,
                    ),
                  ),
                ),
                Text(
                  closed ? 'Fechado' : 'Aberto',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: closed
                        ? theme.colorScheme.onSurface.withAlpha(120)
                        : theme.colorScheme.primary,
                  ),
                ),
                const SizedBox(width: 8),
                Switch.adaptive(
                  key: Key('day_switch_$dayOfWeek'),
                  value: !closed,
                  onChanged: (val) => _notifier.setDayClosed(dayOfWeek, !val),
                ),
              ],
            ),
            if (closed) ...[
              const SizedBox(height: 6),
              Text(
                'Nenhum atendimento ao público neste dia.',
                style: TextStyle(
                  fontSize: 12,
                  fontStyle: FontStyle.italic,
                  color: theme.colorScheme.onSurface.withAlpha(110),
                ),
              ),
            ] else ...[
              const Divider(height: 16),
              if (intervals.isEmpty) ...[
                Text(
                  'Nenhum horário definido. Adicione pelo menos um intervalo.',
                  style: TextStyle(
                    fontSize: 12,
                    color: theme.colorScheme.error,
                  ),
                ),
              ] else ...[
                ...List.generate(intervals.length, (index) {
                  final interval = intervals[index];
                  return Padding(
                    key: Key('interval_row_${dayOfWeek}_$index'),
                    padding: const EdgeInsets.symmetric(vertical: 4.0),
                    child: Row(
                      children: [
                        OutlinedButton(
                          key: Key('open_time_button_${dayOfWeek}_$index'),
                          onPressed: () => _pickTime(
                            dayOfWeek: dayOfWeek,
                            intervalIndex: index,
                            isOpenTime: true,
                            currentTime: interval.openTime as String,
                          ),
                          child: Text(interval.openTime as String),
                        ),
                        const Padding(
                          padding: EdgeInsets.symmetric(horizontal: 8.0),
                          child: Text('às'),
                        ),
                        OutlinedButton(
                          key: Key('close_time_button_${dayOfWeek}_$index'),
                          onPressed: () => _pickTime(
                            dayOfWeek: dayOfWeek,
                            intervalIndex: index,
                            isOpenTime: false,
                            currentTime: interval.closeTime as String,
                          ),
                          child: Text(interval.closeTime as String),
                        ),
                        const Spacer(),
                        IconButton(
                          key: Key('remove_interval_button_${dayOfWeek}_$index'),
                          icon: const Icon(Icons.delete_outline, size: 20),
                          color: theme.colorScheme.error,
                          tooltip: 'Remover intervalo',
                          onPressed: () => _notifier.removeInterval(dayOfWeek, index),
                        ),
                      ],
                    ),
                  );
                }),
              ],
              const SizedBox(height: 4),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: Key('add_interval_button_$dayOfWeek'),
                  onPressed: () => _notifier.addInterval(dayOfWeek),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Adicionar intervalo'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildUnauthorizedView(BuildContext context, ThemeData theme) {
    return Center(
      key: const Key('opening_hours_unauthorized_view'),
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
              'Apenas estabelecimentos com reivindicação aprovada podem gerenciar horários de funcionamento.',
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
