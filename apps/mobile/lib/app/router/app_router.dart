import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/profile_placeholder_screen.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_placeholder_screen.dart';
import 'package:rewit_mobile/features/search/presentation/screens/search_placeholder_screen.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Definição de rotas nomeadas e gerador centralizado de navegação.
class AppRouter {
  static const String root = '/';
  static const String login = '/login';
  static const String home = '/home';
  static const String search = '/search';
  static const String reviewCreate = '/review/create';
  static const String profile = '/profile';

  final AuthNotifier authNotifier;

  const AppRouter({required this.authNotifier});

  Route<dynamic> onGenerateRoute(RouteSettings settings) {
    switch (settings.name) {
      case root:
        return MaterialPageRoute(
          builder: (context) => ListenableBuilder(
            listenable: authNotifier,
            builder: (context, _) {
              final state = authNotifier.state;
              if (state is AuthInitial) {
                return const Scaffold(
                  body: LoadingIndicator(message: 'Iniciando Rewit...'),
                );
              }
              if (state is Authenticated) {
                return HomeScreen(authNotifier: authNotifier);
              }
              return LoginScreen(authNotifier: authNotifier);
            },
          ),
          settings: settings,
        );

      case login:
        return MaterialPageRoute(
          builder: (context) => LoginScreen(authNotifier: authNotifier),
          settings: settings,
        );

      case home:
        return MaterialPageRoute(
          builder: (context) => HomeScreen(authNotifier: authNotifier),
          settings: settings,
        );

      case search:
        return MaterialPageRoute(
          builder: (context) => const SearchPlaceholderScreen(),
          settings: settings,
        );

      case reviewCreate:
        return MaterialPageRoute(
          builder: (context) => const ReviewCreatePlaceholderScreen(),
          settings: settings,
        );

      case profile:
        return MaterialPageRoute(
          builder: (context) => const ProfilePlaceholderScreen(),
          settings: settings,
        );

      default:
        return MaterialPageRoute(
          builder: (context) => Scaffold(
            appBar: AppBar(title: const Text('Página não encontrada')),
            body: Center(
              child: Text('Rota inexistente: ${settings.name}'),
            ),
          ),
          settings: settings,
        );
    }
  }
}
