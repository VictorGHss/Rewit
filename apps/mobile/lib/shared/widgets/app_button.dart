import 'package:flutter/material.dart';

/// Botão padronizado com suporte a estado de carregamento (loading).
class AppButton extends StatelessWidget {
  final String label;
  final VoidCallback? onPressed;
  final bool isLoading;
  final bool isOutlined;
  final IconData? icon;

  const AppButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.isLoading = false,
    this.isOutlined = false,
    this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final effectiveOnPressed = isLoading ? null : onPressed;

    final outlinedSpinnerColor = theme.outlinedButtonTheme.style?.foregroundColor
            ?.resolve(const <WidgetState>{}) ??
        theme.colorScheme.primary;
    final filledBackground = theme.elevatedButtonTheme.style?.backgroundColor
            ?.resolve(const <WidgetState>{}) ??
        theme.colorScheme.primary;
    final filledSpinnerColor = theme.elevatedButtonTheme.style?.foregroundColor
            ?.resolve(const <WidgetState>{}) ??
        theme.colorScheme.onPrimary;

    final spinnerColor =
        isOutlined ? outlinedSpinnerColor : filledSpinnerColor;

    final Widget childWidget = isLoading
        ? SizedBox(
            height: 20,
            width: 20,
            child: CircularProgressIndicator(
              strokeWidth: 2.5,
              valueColor: AlwaysStoppedAnimation<Color>(spinnerColor),
            ),
          )
        : (icon != null
            ? Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(icon, size: 20),
                  const SizedBox(width: 8),
                  Text(label),
                ],
              )
            : Text(label));

    if (isOutlined) {
      return OutlinedButton(
        onPressed: effectiveOnPressed,
        child: childWidget,
      );
    }

    return ElevatedButton(
      onPressed: effectiveOnPressed,
      style: isLoading
          ? ElevatedButton.styleFrom(
              disabledBackgroundColor: filledBackground,
              disabledForegroundColor: filledSpinnerColor,
            )
          : null,
      child: childWidget,
    );
  }
}
