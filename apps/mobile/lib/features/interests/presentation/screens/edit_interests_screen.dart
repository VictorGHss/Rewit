import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/interests/presentation/screens/interests_onboarding_screen.dart';
import 'package:rewit_mobile/features/interests/presentation/state/interests_notifier.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Edição dos interesses pelo perfil, depois do questionário inicial: todas as perguntas numa só tela.
class EditInterestsScreen extends StatefulWidget {
  final InterestRepository? repository;
  final String userId;
  final String? Function() currentUserId;

  /// Chamado depois que o backend aceitou os interesses novos (ex.: recarregar o Feed V2, ordenado por eles).
  final VoidCallback? onSaved;

  /// Injeção para testes; sem ele, a tela cria e descarta o próprio notifier.
  final InterestsNotifier? notifier;

  const EditInterestsScreen({
    super.key,
    required this.repository,
    required this.userId,
    required this.currentUserId,
    this.onSaved,
    this.notifier,
  });

  @override
  State<EditInterestsScreen> createState() => _EditInterestsScreenState();
}

class _EditInterestsScreenState extends State<EditInterestsScreen> {
  InterestsNotifier? _notifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier;
    } else if (widget.repository != null) {
      _notifier = InterestsNotifier(
        repository: widget.repository!,
        userId: widget.userId,
        currentUserId: widget.currentUserId,
      );
      _ownsNotifier = true;
    }
    _notifier?.load();
  }

  @override
  void dispose() {
    if (_ownsNotifier) {
      _notifier?.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    final notifier = _notifier!;
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    final onSaved = widget.onSaved;
    final saved = await notifier.complete();
    // Gravado é gravado: o aviso vale mesmo se a tela já tiver sido fechada
    if (saved) onSaved?.call();
    if (saved && mounted) {
      messenger.showSnackBar(const SnackBar(content: Text('Interesses atualizados.')));
      if (navigator.canPop()) navigator.pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    final notifier = _notifier;
    if (notifier == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Meus interesses')),
        body: const Center(child: Text('Interesses indisponíveis nesta versão do app.')),
      );
    }

    return AnimatedBuilder(
      animation: notifier,
      builder: (context, _) {
        final catalog = notifier.catalog;
        return Scaffold(
          appBar: AppBar(
            title: const Text('Meus interesses'),
            actions: [
              if (notifier.isReady)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: notifier.isSaving
                      ? const Center(
                          child: SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2)),
                        )
                      : TextButton(
                          key: const Key('edit_interests_save'),
                          onPressed: notifier.meetsMinimum ? _save : null,
                          child: const Text('Salvar'),
                        ),
                ),
            ],
          ),
          body: () {
            if (notifier.isReady && catalog != null) {
              return ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (notifier.errorMessage != null)
                    ErrorBanner(
                      key: const Key('edit_interests_error'),
                      margin: const EdgeInsets.only(bottom: 16),
                      message: notifier.errorMessage!,
                    ),
                  SelectionCounter(notifier: notifier),
                  for (final question in catalog.questions) ...[
                    const SizedBox(height: 24),
                    Semantics(
                      header: true,
                      child: Text(question.title, style: Theme.of(context).textTheme.titleMedium),
                    ),
                    const SizedBox(height: 4),
                    Text(question.question, style: Theme.of(context).textTheme.bodySmall),
                    const SizedBox(height: 12),
                    InterestChoiceChips(options: question.options, notifier: notifier),
                  ],
                ],
              );
            }
            if (notifier.loadError != null && !notifier.isLoading) {
              return ErrorView(
                title: 'Não foi possível carregar seus interesses',
                message: notifier.loadError!,
                onRetry: notifier.load,
              );
            }
            return const LoadingIndicator(message: 'Carregando seus interesses...');
          }(),
        );
      },
    );
  }
}
