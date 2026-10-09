import 'package:flutter/material.dart';

/// Componente visual reutilizável para exibição de banners contextuais de erro
/// ou sucesso, suporte a detalhes de erro (RFC 7807), aviso de espera por
/// rate limiting (retry-after) e acessibilidade com leitores de tela.
class ErrorBanner extends StatelessWidget {
  final String message;
  final int? retryAfterSeconds;
  final bool isSuccess;
  final IconData? icon;
  final Widget? footer;
  final EdgeInsetsGeometry? margin;
  final EdgeInsetsGeometry padding;

  const ErrorBanner({
    super.key,
    required this.message,
    this.retryAfterSeconds,
    this.isSuccess = false,
    this.icon,
    this.footer,
    this.margin,
    this.padding = const EdgeInsets.all(12),
  });

  /// Formata a mensagem padronizada de espera para rate limiting (HTTP 429).
  static String formatRetryAfter(int seconds) {
    if (seconds <= 1) {
      return 'Aguarde 1 segundo antes de tentar novamente.';
    }
    return 'Aguarde $seconds segundos antes de tentar novamente.';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final bannerColor = isSuccess ? Colors.green : theme.colorScheme.error;
    final bannerIcon = icon ?? (isSuccess ? Icons.check_circle_outline : Icons.error_outline);
    final backgroundColor = isSuccess
        ? Colors.green.withAlpha(25)
        : theme.colorScheme.errorContainer.withAlpha(120);
    final borderColor = isSuccess
        ? Colors.green.withAlpha(100)
        : theme.colorScheme.error.withAlpha(80);
    final textColor = isSuccess
        ? Colors.green.shade800
        : theme.colorScheme.onErrorContainer;

    return Semantics(
      container: true,
      liveRegion: true,
      label: isSuccess ? 'Mensagem de sucesso' : 'Mensagem de erro',
      child: Container(
        margin: margin,
        padding: padding,
        decoration: BoxDecoration(
          color: backgroundColor,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: borderColor),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Icon(
                  bannerIcon,
                  color: bannerColor,
                  size: 20,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    message,
                    style: TextStyle(
                      color: textColor,
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ],
            ),
            if (retryAfterSeconds != null) ...[
              const SizedBox(height: 6),
              Semantics(
                liveRegion: true,
                child: Text(
                  formatRetryAfter(retryAfterSeconds!),
                  style: TextStyle(
                    color: bannerColor,
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
            if (footer != null) footer!,
          ],
        ),
      ),
    );
  }
}
