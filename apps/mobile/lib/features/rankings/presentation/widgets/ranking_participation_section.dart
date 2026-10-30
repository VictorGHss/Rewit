import 'package:flutter/material.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

import '../../domain/repositories/ranking_repository.dart';
import '../state/ranking_participation_notifier.dart';

/// Seção "Participar dos Rankings da Região" das Configurações da Conta. Mostra só o valor confirmado pelo backend:
/// enquanto carrega, após falha ou durante a gravação, o interruptor fica bloqueado no último valor confirmado.
class RankingParticipationSection extends StatefulWidget {
  final RankingRepository repository;
  final String userId;
  final String? Function() currentUserId;

  /// Injeção para testes; sem ele, a seção cria e descarta o próprio notifier.
  final RankingParticipationNotifier? notifier;

  const RankingParticipationSection({
    super.key,
    required this.repository,
    required this.userId,
    required this.currentUserId,
    this.notifier,
  });

  @override
  State<RankingParticipationSection> createState() => _RankingParticipationSectionState();
}

class _RankingParticipationSectionState extends State<RankingParticipationSection> {
  late final RankingParticipationNotifier _notifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else {
      _notifier = RankingParticipationNotifier(
        repository: widget.repository,
        userId: widget.userId,
        currentUserId: widget.currentUserId,
      );
      _ownsNotifier = true;
    }
    _notifier.load();
  }

  @override
  void dispose() {
    if (_ownsNotifier) _notifier.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AnimatedBuilder(
      animation: _notifier,
      builder: (context, _) {
        final confirmed = _notifier.confirmed;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SwitchListTile(
              key: const Key('ranking_participation_switch'),
              contentPadding: EdgeInsets.zero,
              secondary: const Icon(Icons.emoji_events_outlined),
              title: const Text('Participar dos Rankings da Região',
                  style: TextStyle(fontWeight: FontWeight.w600)),
              subtitle: const Text(
                'Ativando, seu perfil público pode aparecer nos rankings da comunidade, com base nas suas '
                'contribuições elegíveis. Você pode desligar quando quiser; isso não remove suas avaliações '
                'públicas do feed.',
                style: TextStyle(fontSize: 12),
              ),
              value: confirmed ?? false,
              onChanged: confirmed == null || _notifier.isSaving || _notifier.isLoading
                  ? null
                  : (value) => _notifier.setParticipation(value),
            ),
            if (_notifier.isLoading || _notifier.isSaving)
              Padding(
                padding: const EdgeInsets.only(left: 56, bottom: 8),
                child: Row(
                  children: [
                    const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                    const SizedBox(width: 8),
                    Text(
                      _notifier.isSaving ? 'Salvando...' : 'Carregando...',
                      key: const Key('ranking_participation_progress'),
                      style: theme.textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
            if (_notifier.loadError != null) ...[
              ErrorBanner(key: const Key('ranking_participation_load_error'), message: _notifier.loadError!),
              TextButton(
                key: const Key('ranking_participation_reload'),
                onPressed: _notifier.load,
                child: const Text('Tentar novamente'),
              ),
            ],
            if (_notifier.saveError != null) ...[
              ErrorBanner(key: const Key('ranking_participation_save_error'), message: _notifier.saveError!),
              if (_notifier.canRetrySave)
                TextButton(
                  key: const Key('ranking_participation_retry'),
                  onPressed: _notifier.retrySave,
                  child: const Text('Tentar novamente'),
                ),
            ],
          ],
        );
      },
    );
  }
}
