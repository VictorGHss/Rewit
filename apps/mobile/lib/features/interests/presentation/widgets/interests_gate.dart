import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/interests/presentation/screens/interests_onboarding_screen.dart';
import 'package:rewit_mobile/features/interests/presentation/state/interests_notifier.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Decide, depois da autenticação, entre o questionário inicial de interesses e o app.
///
/// Consulta o backend a cada sessão (nunca uma marca local): questionário concluído leva direto ao app; não iniciado
/// ou em andamento abre o questionário onde ele parou. A rota raiz cria o portão com uma chave por usuário, então
/// outra conta nunca reaproveita o estado (nem as respostas) da anterior.
class InterestsGate extends StatefulWidget {
  final String userId;
  final InterestRepository repository;

  /// Usuário da sessão ativa agora; conferido antes de cada gravação.
  final String? Function() currentUserId;

  /// O app depois do questionário.
  final WidgetBuilder homeBuilder;

  final VoidCallback? onLogout;

  /// Injeção para testes; sem ele, o portão cria e descarta o próprio notifier.
  final InterestsNotifier? notifier;

  const InterestsGate({
    super.key,
    required this.userId,
    required this.repository,
    required this.currentUserId,
    required this.homeBuilder,
    this.onLogout,
    this.notifier,
  });

  @override
  State<InterestsGate> createState() => _InterestsGateState();
}

class _InterestsGateState extends State<InterestsGate> {
  late final InterestsNotifier _notifier;
  late final bool _ownsNotifier;

  @override
  void initState() {
    super.initState();
    _ownsNotifier = widget.notifier == null;
    _notifier = widget.notifier ??
        InterestsNotifier(
          repository: widget.repository,
          userId: widget.userId,
          currentUserId: widget.currentUserId,
        );
    _notifier.load();
  }

  @override
  void dispose() {
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _notifier,
      builder: (context, _) {
        if (_notifier.isCompleted && !_notifier.justCompleted) {
          return widget.homeBuilder(context);
        }
        if (_notifier.isReady) {
          return InterestsOnboardingScreen(notifier: _notifier, onLogout: widget.onLogout);
        }
        if (_notifier.loadError != null && !_notifier.isLoading) {
          return Scaffold(
            body: SafeArea(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  ErrorView(
                    key: const Key('interests_gate_error'),
                    title: 'Não foi possível continuar',
                    message: _notifier.loadError!,
                    onRetry: _notifier.load,
                  ),
                  if (widget.onLogout != null)
                    TextButton(
                      key: const Key('interests_gate_logout'),
                      onPressed: widget.onLogout,
                      child: const Text('Sair da conta'),
                    ),
                ],
              ),
            ),
          );
        }
        return const Scaffold(body: LoadingIndicator(message: 'Carregando seus interesses...'));
      },
    );
  }
}
