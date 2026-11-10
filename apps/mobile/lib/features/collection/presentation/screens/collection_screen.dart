import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';

/// Tela da aba Coleção (Navigation & Home V2).
///
/// Como o backend de coleções e listas salvas ainda não foi implementado,
/// apresenta um estado vazio honesto, sem subtabs ou itens simulados.
class CollectionScreen extends StatelessWidget {
  final bool embedded;

  const CollectionScreen({
    super.key,
    this.embedded = false,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    final body = Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(
          RewitSpacing.xxl,
          RewitSpacing.xxl,
          RewitSpacing.xxl,
          96,
        ),
        child: Column(
          key: const Key('collection_empty_state'),
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(RewitSpacing.xl),
              decoration: BoxDecoration(
                color: scheme.secondaryContainer,
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.bookmarks_outlined,
                size: 48,
                color: scheme.onSecondaryContainer,
              ),
            ),
            const SizedBox(height: RewitSpacing.lg),
            Text(
              'Sua Coleção em breve',
              textAlign: TextAlign.center,
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.bold,
                color: scheme.onSurface,
              ),
            ),
            const SizedBox(height: RewitSpacing.sm),
            Text(
              'O recurso de Coleção ainda não está disponível nesta versão do Rewit. '
              'Quando estiver pronto, você poderá organizar lugares e produtos salvos aqui.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: scheme.onSurfaceVariant,
                height: 1.4,
              ),
            ),
          ],
        ),
      ),
    );

    if (embedded) {
      return body;
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Coleção'),
      ),
      body: body,
    );
  }
}

