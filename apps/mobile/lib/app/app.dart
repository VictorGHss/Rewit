import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';

/// Widget raiz do aplicativo Rewit.
///
/// Quando a sessão expira de fato, volta a pilha à rota raiz. Antes, a rota raiz trocava para o login por baixo
/// das telas abertas e o usuário só descobria ao pressionar voltar, como se o app o tivesse deslogado ali.
class RewitApp extends StatefulWidget {
  final AppConfig config;
  final AppRouter router;

  const RewitApp({
    super.key,
    required this.config,
    required this.router,
  });

  @override
  State<RewitApp> createState() => _RewitAppState();
}

class _RewitAppState extends State<RewitApp> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  @override
  void initState() {
    super.initState();
    widget.router.authNotifier.addListener(_onAuthChanged);
  }

  @override
  void didUpdateWidget(covariant RewitApp oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.router.authNotifier != widget.router.authNotifier) {
      oldWidget.router.authNotifier.removeListener(_onAuthChanged);
      widget.router.authNotifier.addListener(_onAuthChanged);
    }
  }

  @override
  void dispose() {
    widget.router.authNotifier.removeListener(_onAuthChanged);
    super.dispose();
  }

  void _onAuthChanged() {
    final state = widget.router.authNotifier.state;
    if (state is Unauthenticated && state.errorCode == 'SESSION_EXPIRED') {
      _navigatorKey.currentState?.popUntil((route) => route.isFirst);
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      title: widget.config.appName,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.lightTheme,
      initialRoute: AppRouter.root,
      onGenerateRoute: widget.router.onGenerateRoute,
    );
  }
}
