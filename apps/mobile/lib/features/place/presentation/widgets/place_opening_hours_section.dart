import 'package:flutter/material.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../state/place_commercial_state.dart';

/// Seção visual que exibe a grade semanal de horários de funcionamento do estabelecimento (F2.1).
class PlaceOpeningHoursSection extends StatelessWidget {
  final CommercialSectionState<PlaceOpeningHours> state;
  final VoidCallback? onRetry;

  const PlaceOpeningHoursSection({
    super.key,
    required this.state,
    this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.schedule_outlined,
                  size: 20,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Text(
                  'Horários de Funcionamento',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _buildBody(context, theme),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context, ThemeData theme) {
    final currentState = state;

    if (currentState is SectionLoading || currentState is SectionInitial) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 12.0),
        child: Row(
          children: [
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            SizedBox(width: 12),
            Text(
              'Carregando horários...',
              style: TextStyle(fontSize: 13),
            ),
          ],
        ),
      );
    }

    if (currentState is SectionError<PlaceOpeningHours>) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            currentState.message,
            style: TextStyle(
              color: theme.colorScheme.error,
              fontSize: 13,
            ),
          ),
          if (onRetry != null) ...[
            const SizedBox(height: 4),
            TextButton(
              onPressed: onRetry,
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
              ),
              child: const Text('Tentar novamente'),
            ),
          ],
        ],
      );
    }

    if (currentState is SectionLoaded<PlaceOpeningHours>) {
      final hours = currentState.data;

      if (!hours.configured) {
        return Text(
          'Horários não informados pelo estabelecimento.',
          style: TextStyle(
            color: theme.colorScheme.onSurface.withAlpha(160),
            fontSize: 14,
          ),
        );
      }

      // Ordena de Segunda-feira (1) a Domingo (7)
      final sortedDays = List<DaySchedule>.from(hours.days)
        ..sort((a, b) => a.dayOfWeek.compareTo(b.dayOfWeek));

      if (sortedDays.isEmpty) {
        return Text(
          'Nenhum horário cadastrado.',
          style: TextStyle(
            color: theme.colorScheme.onSurface.withAlpha(160),
            fontSize: 14,
          ),
        );
      }

      return Column(
        children: sortedDays.map((day) {
          final isClosed = day.closed || day.intervals.isEmpty;
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 4.0),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: 110,
                  child: Text(
                    day.localizedDayName,
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 13,
                      color: theme.colorScheme.onSurface,
                    ),
                  ),
                ),
                Expanded(
                  child: Text(
                    day.formattedSchedule,
                    style: TextStyle(
                      fontSize: 13,
                      color: isClosed
                          ? theme.colorScheme.error.withAlpha(200)
                          : theme.colorScheme.onSurface.withAlpha(180),
                      fontWeight: isClosed ? FontWeight.w500 : FontWeight.normal,
                    ),
                  ),
                ),
              ],
            ),
          );
        }).toList(),
      );
    }

    return const SizedBox.shrink();
  }
}
