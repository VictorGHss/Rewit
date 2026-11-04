import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';
import 'package:rewit_mobile/app/theme/theme_controller.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/rankings/domain/repositories/ranking_repository.dart';
import 'package:rewit_mobile/features/rankings/presentation/widgets/ranking_participation_section.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

/// Tela de configurações e gerenciamento de conta do usuário autenticado.
class AccountSettingsScreen extends StatefulWidget {
  final AuthNotifier authNotifier;
  final VoidCallback? onDeactivated;

  /// Participação nos Rankings da Região; sem ele, a seção não aparece.
  final RankingRepository? rankingRepository;

  /// Controlador de aparência do aplicativo (sistema, claro ou escuro).
  final ThemeController? themeController;

  const AccountSettingsScreen({
    super.key,
    required this.authNotifier,
    this.onDeactivated,
    this.rankingRepository,
    this.themeController,
  });

  @override
  State<AccountSettingsScreen> createState() => _AccountSettingsScreenState();
}

class _AccountSettingsScreenState extends State<AccountSettingsScreen> {
  bool _isDeactivating = false;
  String? _errorMessage;
  int? _retryAfterSeconds;
  ThemeController? _localThemeController;

  ThemeController _resolveThemeController(BuildContext context) {
    return widget.themeController ??
        ThemeControllerScope.maybeOf(context) ??
        (_localThemeController ??= ThemeController());
  }

  @override
  void dispose() {
    _localThemeController?.dispose();
    super.dispose();
  }

  Future<void> _confirmAndDeactivate() async {
    if (_isDeactivating) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Desativar Conta'),
        content: const Text(
          'Sua conta será desativada e você será desconectado.\n\n'
          'Enquanto estiver desativada, você não poderá usar normalmente os recursos da conta.\n\n'
          'Para voltar, use o fluxo "Reativar conta" com seu e-mail e senha.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: ElevatedButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
              foregroundColor: Theme.of(context).colorScheme.onError,
            ),
            child: const Text('Desativar Conta'),
          ),
        ],
      ),
    );

    if (confirmed == true && mounted) {
      setState(() {
        _isDeactivating = true;
        _errorMessage = null;
        _retryAfterSeconds = null;
      });

      try {
        await widget.authNotifier.deactivateAccount();
        if (mounted) {
          final warningTone = RewitSemanticColors.of(context).warning;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                'Sua conta foi desativada e a sessão encerrada. Para voltar, use o fluxo "Reativar conta".',
                style: TextStyle(color: warningTone.onContainer),
              ),
              backgroundColor: warningTone.container,
            ),
          );
          widget.onDeactivated?.call();
          if (Navigator.of(context).canPop()) {
            Navigator.of(context).pop();
          }
        }
      } on ApiException catch (e) {
        if (mounted) {
          setState(() {
            _isDeactivating = false;
            _errorMessage = e.detail;
            _retryAfterSeconds = e.retryAfterSeconds;
          });
        }
      } on NetworkException catch (e) {
        if (mounted) {
          setState(() {
            _isDeactivating = false;
            _errorMessage = e.message;
          });
        }
      } catch (_) {
        if (mounted) {
          setState(() {
            _isDeactivating = false;
            _errorMessage =
                'Ocorreu um erro ao desativar sua conta. Tente novamente.';
          });
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final state = widget.authNotifier.state;
    final authenticatedUser = state is Authenticated ? state.user : null;
    final themeController = _resolveThemeController(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Configurações da Conta'),
      ),
      body: SingleChildScrollView(
        padding: RewitSpacing.screenPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Banner de erro RFC 7807 caso ocorra falha na desativação
            if (_errorMessage != null) ...[
              ErrorBanner(
                margin: const EdgeInsets.only(bottom: RewitSpacing.lg),
                message: _errorMessage!,
                retryAfterSeconds: _retryAfterSeconds,
              ),
            ],

            // Seção: Informações da Conta
            if (authenticatedUser != null) ...[
              Card(
                child: Padding(
                  padding: RewitSpacing.cardPadding,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Informações da Conta',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: RewitSpacing.md),
                      Row(
                        children: [
                          CircleAvatar(
                            radius: 24,
                            backgroundColor: scheme.secondaryContainer,
                            child: Icon(
                              Icons.person,
                              color: scheme.onSecondaryContainer,
                            ),
                          ),
                          const SizedBox(width: RewitSpacing.md),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  authenticatedUser.displayName,
                                  style: TextStyle(
                                    fontWeight: FontWeight.bold,
                                    fontSize: 15,
                                    color: scheme.onSurface,
                                  ),
                                ),
                                Text(
                                  '@${authenticatedUser.handle}',
                                  style: TextStyle(
                                    color: scheme.onSurfaceVariant,
                                    fontSize: 13,
                                  ),
                                ),
                                Text(
                                  authenticatedUser.email,
                                  style: TextStyle(
                                    color: scheme.onSurfaceVariant,
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: RewitSpacing.lg),
            ],

            // Seção: Preferências (interesses do questionário inicial)
            Card(
              child: Padding(
                padding: RewitSpacing.cardPadding,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.tune, color: scheme.primary),
                        const SizedBox(width: RewitSpacing.sm),
                        Text(
                          'Preferências',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: RewitSpacing.sm),
                    ListTile(
                      key: const Key('edit_interests_tile'),
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.interests_outlined),
                      title: const Text(
                        'Meus interesses',
                        style: TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: const Text(
                        'Altere os interesses que você escolheu no primeiro acesso.',
                        style: TextStyle(fontSize: 12),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () {
                        Navigator.of(context).pushNamed(AppRouter.editInterests);
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: RewitSpacing.lg),

            // Seção: Rankings da Região (participação voluntária, desativada até a pessoa ativar)
            if (widget.rankingRepository != null &&
                authenticatedUser != null) ...[
              Card(
                key: const Key('ranking_participation_card'),
                child: Padding(
                  padding: RewitSpacing.cardPadding,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            Icons.leaderboard_outlined,
                            color: scheme.primary,
                          ),
                          const SizedBox(width: RewitSpacing.sm),
                          Text(
                            'Rankings da Região',
                            style: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: RewitSpacing.sm),
                      RankingParticipationSection(
                        repository: widget.rankingRepository!,
                        userId: authenticatedUser.id,
                        currentUserId: () {
                          final current = widget.authNotifier.state;
                          return current is Authenticated
                              ? current.user.id
                              : null;
                        },
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: RewitSpacing.lg),
            ],

            // Seção: Segurança (C5.11)
            Card(
              child: Padding(
                padding: RewitSpacing.cardPadding,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.security_outlined, color: scheme.primary),
                        const SizedBox(width: RewitSpacing.sm),
                        Text(
                          'Segurança',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: RewitSpacing.sm),
                    ListTile(
                      key: const Key('change_password_tile'),
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.lock_reset_outlined),
                      title: const Text(
                        'Alterar senha',
                        style: TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: const Text(
                        'Atualize sua senha de acesso. As sessões ativas serão encerradas.',
                        style: TextStyle(fontSize: 12),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () {
                        Navigator.of(context)
                            .pushNamed(AppRouter.changePassword);
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: RewitSpacing.lg),

            // Seção: Aparência (preferência local do dispositivo)
            Card(
              key: const Key('appearance_settings_card'),
              child: Padding(
                padding: RewitSpacing.cardPadding,
                child: ListenableBuilder(
                  listenable: themeController,
                  builder: (context, _) {
                    final selectedMode = themeController.themeMode;
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(Icons.palette_outlined, color: scheme.primary),
                            const SizedBox(width: RewitSpacing.sm),
                            Expanded(
                              child: Text(
                                'Aparência',
                                style: theme.textTheme.titleMedium?.copyWith(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: RewitSpacing.xs),
                        Text(
                          'Escolha como o Rewit é exibido neste aparelho.',
                          style: TextStyle(
                            fontSize: 12,
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: RewitSpacing.md),
                        _AppearanceOptionTile(
                          optionKey: const Key('theme_mode_option_system'),
                          icon: Icons.brightness_auto_outlined,
                          title: 'Seguir o sistema',
                          subtitle: 'Ajusta automaticamente conforme o aparelho.',
                          isSelected: selectedMode == ThemeMode.system,
                          onTap: () =>
                              themeController.setThemeMode(ThemeMode.system),
                        ),
                        const SizedBox(height: RewitSpacing.sm),
                        _AppearanceOptionTile(
                          optionKey: const Key('theme_mode_option_light'),
                          icon: Icons.light_mode_outlined,
                          title: 'Claro',
                          subtitle: 'Fundo suave em lavanda e detalhes em roxo.',
                          isSelected: selectedMode == ThemeMode.light,
                          onTap: () =>
                              themeController.setThemeMode(ThemeMode.light),
                        ),
                        const SizedBox(height: RewitSpacing.sm),
                        _AppearanceOptionTile(
                          optionKey: const Key('theme_mode_option_dark'),
                          icon: Icons.dark_mode_outlined,
                          title: 'Escuro',
                          subtitle:
                              'Predominância de preto e grafite para baixa luz.',
                          isSelected: selectedMode == ThemeMode.dark,
                          onTap: () =>
                              themeController.setThemeMode(ThemeMode.dark),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
            const SizedBox(height: RewitSpacing.lg),

            // Seção: Gerenciamento da Conta
            Card(
              child: Padding(
                padding: RewitSpacing.cardPadding,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          Icons.manage_accounts_outlined,
                          color: scheme.primary,
                        ),
                        const SizedBox(width: RewitSpacing.sm),
                        Text(
                          'Gerenciamento da Conta',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: RewitSpacing.md),
                    Text(
                      'Desativação de Conta',
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Sua conta será desativada e você será desconectado. '
                      'Enquanto estiver desativada, você não poderá usar normalmente os recursos da conta. '
                      'Para voltar, use o fluxo "Reativar conta" com seu e-mail e senha.',
                      style: TextStyle(
                        fontSize: 13,
                        color: scheme.onSurfaceVariant,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: RewitSpacing.lg),

                    // Botão explícito para desativação
                    SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        onPressed:
                            _isDeactivating ? null : _confirmAndDeactivate,
                        style: OutlinedButton.styleFrom(
                          foregroundColor: scheme.error,
                          side: BorderSide(color: scheme.error),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          shape: const RoundedRectangleBorder(
                            borderRadius: RewitRadius.borderMd,
                          ),
                        ),
                        icon: _isDeactivating
                            ? SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: scheme.error,
                                ),
                              )
                            : const Icon(Icons.pause_circle_outline, size: 20),
                        label: Text(
                          _isDeactivating
                              ? 'Desativando conta...'
                              : 'Desativar minha conta',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: RewitSpacing.lg),

            // Seção: Área Comercial (C9)
            Card(
              child: Padding(
                padding: RewitSpacing.cardPadding,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          Icons.business_center_outlined,
                          color: scheme.primary,
                        ),
                        const SizedBox(width: RewitSpacing.sm),
                        Text(
                          'Área Comercial',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: RewitSpacing.sm),
                    ListTile(
                      key: const Key('my_business_accounts_tile'),
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.storefront_outlined),
                      title: const Text(
                        'Minhas empresas',
                        style: TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: const Text(
                        'Gerencie suas contas comerciais e solicitações de reivindicação de locais.',
                        style: TextStyle(fontSize: 12),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () {
                        Navigator.of(context)
                            .pushNamed(AppRouter.businessAccounts);
                      },
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AppearanceOptionTile extends StatelessWidget {
  final Key optionKey;
  final IconData icon;
  final String title;
  final String subtitle;
  final bool isSelected;
  final VoidCallback onTap;

  const _AppearanceOptionTile({
    required this.optionKey,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final backgroundColor =
        isSelected ? scheme.secondaryContainer : scheme.surfaceContainerLowest;
    final foregroundColor =
        isSelected ? scheme.onSecondaryContainer : scheme.onSurface;
    final subtitleColor =
        isSelected ? scheme.onSecondaryContainer : scheme.onSurfaceVariant;
    final borderColor = isSelected ? scheme.primary : scheme.outlineVariant;

    return Semantics(
      button: true,
      selected: isSelected,
      child: Material(
        color: backgroundColor,
        shape: RoundedRectangleBorder(
          borderRadius: RewitRadius.borderMd,
          side: BorderSide(
            color: borderColor,
            width: isSelected ? 1.5 : 1.0,
          ),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: optionKey,
          onTap: onTap,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 52),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: RewitSpacing.md,
                vertical: 10,
              ),
              child: Row(
                children: [
                  Icon(icon, size: 20, color: foregroundColor),
                  const SizedBox(width: RewitSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          style: TextStyle(
                            fontWeight:
                                isSelected ? FontWeight.w700 : FontWeight.w600,
                            fontSize: 14,
                            color: foregroundColor,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          subtitle,
                          style: TextStyle(
                            fontSize: 12,
                            color: subtitleColor,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: RewitSpacing.sm),
                  Icon(
                    isSelected
                        ? Icons.check_circle
                        : Icons.radio_button_unchecked,
                    size: 20,
                    color: isSelected ? scheme.primary : scheme.outline,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
