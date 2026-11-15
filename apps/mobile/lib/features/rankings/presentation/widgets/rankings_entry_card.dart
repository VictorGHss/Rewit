import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';

/// Atalho de descoberta para os Rankings da Região, sem ocupar uma aba da navegação inferior.
class RankingsEntryCard extends StatelessWidget {
  const RankingsEntryCard({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      elevation: 0.5,
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      clipBehavior: Clip.antiAlias,
      child: ListTile(
        key: const Key('home_rankings_entry'),
        dense: true,
        leading: Icon(Icons.leaderboard_outlined, color: theme.colorScheme.primary),
        title: const Text('Rankings da Região', style: TextStyle(fontWeight: FontWeight.w600)),
        subtitle: const Text('Check-ins, locais explorados e votos úteis da comunidade'),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => Navigator.of(context).pushNamed(AppRouter.rankings),
      ),
    );
  }
}
