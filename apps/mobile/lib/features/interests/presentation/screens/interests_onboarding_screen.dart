import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';
import 'package:rewit_mobile/features/interests/presentation/state/interests_notifier.dart';
import 'package:rewit_mobile/shared/widgets/app_button.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

/// Questionário inicial de interesses: uma pergunta por etapa, resumo para confirmar e tela de conclusão.
///
/// Cada avanço grava um rascunho no backend; o carregamento e a retomada ficam com o [InterestsNotifier], criado
/// pelo portão do app (`InterestsGate`).
class InterestsOnboardingScreen extends StatelessWidget {
  final InterestsNotifier notifier;

  /// Sair da conta sem concluir (ex.: entrou na conta errada). Nulo oculta a ação.
  final VoidCallback? onLogout;

  const InterestsOnboardingScreen({super.key, required this.notifier, this.onLogout});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: notifier,
      builder: (context, _) {
        if (notifier.justCompleted) {
          return _CompletionView(onContinue: notifier.acknowledgeCompletion);
        }

        final question = notifier.currentQuestion;
        final totalSteps = notifier.questionCount + 1;
        return Scaffold(
          appBar: AppBar(
            title: const Text('Seus interesses'),
            automaticallyImplyLeading: false,
            actions: [
              if (onLogout != null)
                TextButton(
                  key: const Key('interests_logout'),
                  onPressed: notifier.isSaving ? null : onLogout,
                  child: const Text('Sair'),
                ),
            ],
          ),
          body: SafeArea(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                LinearProgressIndicator(
                  value: (notifier.step + 1) / totalSteps,
                  semanticsLabel: 'Progresso do questionário',
                ),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(24, 24, 24, 16),
                    child: question != null
                        ? _QuestionStep(
                            question: question,
                            index: notifier.step,
                            total: notifier.questionCount,
                            notifier: notifier,
                          )
                        : _SummaryStep(notifier: notifier),
                  ),
                ),
                if (notifier.errorMessage != null)
                  ErrorBanner(
                    key: const Key('interests_error'),
                    margin: const EdgeInsets.symmetric(horizontal: 24),
                    message: notifier.errorMessage!,
                  ),
                _NavigationBar(notifier: notifier),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _QuestionStep extends StatelessWidget {
  final InterestQuestion question;
  final int index;
  final int total;
  final InterestsNotifier notifier;

  const _QuestionStep({
    required this.question,
    required this.index,
    required this.total,
    required this.notifier,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Pergunta ${index + 1} de $total', style: theme.textTheme.labelLarge),
        const SizedBox(height: 8),
        Semantics(
          header: true,
          child: Text(question.question, style: theme.textTheme.headlineSmall),
        ),
        const SizedBox(height: 8),
        Text(
          '${question.title}: escolha quantas opções quiser.',
          style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 20),
        InterestChoiceChips(options: question.options, notifier: notifier),
        const SizedBox(height: 20),
        SelectionCounter(notifier: notifier),
      ],
    );
  }
}

class _SummaryStep extends StatelessWidget {
  final InterestsNotifier notifier;

  const _SummaryStep({required this.notifier});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final selected = notifier.selectedOptions;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          header: true,
          child: Text('Confirme seus interesses', style: theme.textTheme.headlineSmall),
        ),
        const SizedBox(height: 8),
        Text(
          'Você poderá alterá-los depois em Configurações da Conta.',
          style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 20),
        if (selected.isEmpty)
          const Text('Você ainda não escolheu nenhum interesse.')
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final option in selected)
                Chip(key: Key('interest_summary_${option.code}'), label: Text(option.label)),
            ],
          ),
        const SizedBox(height: 20),
        if (!notifier.meetsMinimum)
          Text(
            'Escolha pelo menos ${notifier.minInterests} interesses para concluir. Use Voltar para revisar as perguntas.',
            key: const Key('interests_minimum_hint'),
            style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.error),
          ),
      ],
    );
  }
}

class _NavigationBar extends StatelessWidget {
  final InterestsNotifier notifier;

  const _NavigationBar({required this.notifier});

  @override
  Widget build(BuildContext context) {
    final saving = notifier.isSaving;
    final onSummary = notifier.isOnSummary;
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
      child: Row(
        children: [
          Expanded(
            child: AppButton(
              key: const Key('interests_back'),
              label: 'Voltar',
              isOutlined: true,
              onPressed: notifier.step > 0 && !saving ? notifier.back : null,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: onSummary
                ? AppButton(
                    key: const Key('interests_confirm'),
                    label: 'Confirmar',
                    isLoading: saving,
                    onPressed: notifier.meetsMinimum ? notifier.complete : null,
                  )
                : AppButton(
                    key: const Key('interests_next'),
                    label: 'Avançar',
                    isLoading: saving,
                    onPressed: notifier.next,
                  ),
          ),
        ],
      ),
    );
  }
}

class _CompletionView extends StatelessWidget {
  final VoidCallback onContinue;

  const _CompletionView({required this.onContinue});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Icon(Icons.check_circle_outline, size: 72, color: theme.colorScheme.primary),
              const SizedBox(height: 16),
              Semantics(
                header: true,
                child: Text('Tudo pronto!', textAlign: TextAlign.center, style: theme.textTheme.headlineSmall),
              ),
              const SizedBox(height: 8),
              Text(
                'Seus interesses foram salvos. Você pode alterá-los quando quiser em Configurações da Conta.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 32),
              AppButton(
                key: const Key('interests_start_app'),
                label: 'Começar a explorar',
                onPressed: onContinue,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Opções de interesse como chips de múltipla escolha. O estado selecionado é anunciado pelo leitor de tela.
class InterestChoiceChips extends StatelessWidget {
  final List<InterestOption> options;
  final InterestsNotifier notifier;

  const InterestChoiceChips({super.key, required this.options, required this.notifier});

  @override
  Widget build(BuildContext context) {
    final selected = notifier.selectedCodes;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final option in options)
          FilterChip(
            key: Key('interest_option_${option.code}'),
            label: Text(option.label),
            selected: selected.contains(option.code),
            onSelected: notifier.isSaving ? null : (_) => notifier.toggle(option.code),
          ),
      ],
    );
  }
}

/// Quantidade escolhida e mínimo para concluir, anunciada quando muda.
class SelectionCounter extends StatelessWidget {
  final InterestsNotifier notifier;

  const SelectionCounter({super.key, required this.notifier});

  @override
  Widget build(BuildContext context) {
    final count = notifier.selectedCount;
    final label = count == 1 ? '1 interesse escolhido' : '$count interesses escolhidos';
    return Semantics(
      liveRegion: true,
      child: Text(
        '$label · mínimo de ${notifier.minInterests} para concluir',
        key: const Key('interests_counter'),
        style: Theme.of(context).textTheme.bodySmall,
      ),
    );
  }
}
