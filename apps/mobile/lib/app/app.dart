import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';

/// Widget raiz do aplicativo Rewit.
class RewitApp extends StatelessWidget {
  final AppConfig config;
  final AppRouter router;

  const RewitApp({
    super.key,
    required this.config,
    required this.router,
  });

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: config.appName,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.lightTheme,
      initialRoute: AppRouter.root,
      onGenerateRoute: router.onGenerateRoute,
    );
  }
}
