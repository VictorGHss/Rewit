import 'package:flutter/material.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../state/place_commercial_state.dart';

/// Seção visual que exibe as comodidades declaradas pelo estabelecimento (F2.2).
class PlaceAmenitiesSection extends StatelessWidget {
  final CommercialSectionState<PlaceAmenities> state;
  final VoidCallback? onRetry;

  const PlaceAmenitiesSection({
    super.key,
    required this.state,
    this.onRetry,
  });

  static IconData _getAmenityIcon(String code) {
    switch (code.toUpperCase()) {
      case 'WIFI':
        return Icons.wifi;
      case 'PARKING':
        return Icons.local_parking;
      case 'WHEELCHAIR_ACCESSIBLE':
        return Icons.accessible;
      case 'ACCESSIBLE_RESTROOM':
        return Icons.accessible_forward;
      case 'CUSTOMER_RESTROOM':
        return Icons.wc;
      case 'AIR_CONDITIONING':
        return Icons.ac_unit;
      case 'OUTDOOR_SEATING':
        return Icons.deck;
      case 'PET_FRIENDLY':
        return Icons.pets;
      default:
        return Icons.check_circle_outline;
    }
  }

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
                  Icons.room_service_outlined,
                  size: 20,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Text(
                  'Comodidades',
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
              'Carregando comodidades...',
              style: TextStyle(fontSize: 13),
            ),
          ],
        ),
      );
    }

    if (currentState is SectionError<PlaceAmenities>) {
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

    if (currentState is SectionLoaded<PlaceAmenities>) {
      final amenities = currentState.data;

      // 1. Configured = false: não informado
      if (!amenities.configured) {
        return Text(
          'Comodidades não informadas pelo estabelecimento.',
          style: TextStyle(
            color: theme.colorScheme.onSurface.withAlpha(160),
            fontSize: 14,
          ),
        );
      }

      // 2. Configured = true e lista vazia: declarou formalmente não possuir
      if (amenities.hasDeclaredNone) {
        return Text(
          'O estabelecimento declarou não oferecer comodidades deste catálogo.',
          style: TextStyle(
            color: theme.colorScheme.onSurface.withAlpha(160),
            fontSize: 14,
          ),
        );
      }

      // 3. Configured = true com comodidades declaradas
      return Wrap(
        spacing: 8,
        runSpacing: 8,
        children: amenities.amenities.map((item) {
          return Chip(
            avatar: Icon(
              _getAmenityIcon(item.code),
              size: 18,
              color: theme.colorScheme.primary,
            ),
            label: Text(
              item.label,
              style: const TextStyle(fontSize: 13),
            ),
            backgroundColor: theme.colorScheme.surfaceContainerHighest.withAlpha(120),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(8),
              side: BorderSide(
                color: theme.colorScheme.outlineVariant.withAlpha(60),
              ),
            ),
            visualDensity: VisualDensity.compact,
          );
        }).toList(),
      );
    }

    return const SizedBox.shrink();
  }
}
