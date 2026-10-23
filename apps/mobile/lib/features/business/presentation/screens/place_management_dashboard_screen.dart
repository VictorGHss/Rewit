import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_media_gallery_screen.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';

/// Argumentos para inicialização do painel de gestão do estabelecimento.
class PlaceManagementArgs {
  final String placeId;
  final String businessAccountId;
  final String placeName;
  final String? city;
  final String? state;
  final String? status;

  const PlaceManagementArgs({
    required this.placeId,
    required this.businessAccountId,
    required this.placeName,
    this.city,
    this.state,
    this.status,
  });

  bool get isApproved => status == null || status == 'APPROVED';
}

/// Tela do Hub de Gestão Individual de um Estabelecimento Reivindicado e Aprovado.
class PlaceManagementDashboardScreen extends StatelessWidget {
  final PlaceManagementArgs args;
  final PlaceRepository? placeRepository;
  final ReviewMediaRepository? reviewMediaRepository;

  const PlaceManagementDashboardScreen({
    super.key,
    required this.args,
    this.placeRepository,
    this.reviewMediaRepository,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Gestão do Estabelecimento'),
      ),
      body: !args.isApproved
          ? _buildUnauthorizedView(context, theme)
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _buildContextCard(context, theme),
                  const SizedBox(height: 24),
                  Text(
                    'Opções de Gerenciamento',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 12),
                  _buildActionCard(
                    context,
                    key: const Key('manage_media_option'),
                    icon: Icons.photo_library_outlined,
                    title: 'Fotografias e capa',
                    subtitle: 'Gerencie a imagem principal e a galeria de fotos do estabelecimento',
                    onTap: () {
                      Navigator.of(context).pushNamed(
                        AppRouter.placeMediaGallery,
                        arguments: PlaceMediaGalleryArgs(
                          placeId: args.placeId,
                          businessAccountId: args.businessAccountId,
                          placeName: args.placeName,
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 12),
                  _buildActionCard(
                    context,
                    key: const Key('view_public_profile_option'),
                    icon: Icons.storefront_outlined,
                    title: 'Ver perfil público',
                    subtitle: 'Veja como seu estabelecimento é visualizado por clientes na comunidade',
                    onTap: () {
                      Navigator.of(context).pushNamed(
                        AppRouter.placeDetail,
                        arguments: args.placeId,
                      );
                    },
                  ),
                  const SizedBox(height: 24),
                  _buildNextStepsNotice(context, theme),
                ],
              ),
            ),
    );
  }

  Widget _buildUnauthorizedView(BuildContext context, ThemeData theme) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.lock_outline, size: 64, color: theme.colorScheme.error),
            const SizedBox(height: 16),
            Text(
              'Acesso Restrito',
              style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Apenas estabelecimentos com reivindicação aprovada podem ser gerenciados.',
              textAlign: TextAlign.center,
              style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Voltar'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildContextCard(BuildContext context, ThemeData theme) {
    final locationText = [
      if (args.city != null && args.city!.trim().isNotEmpty) args.city!.trim(),
      if (args.state != null && args.state!.trim().isNotEmpty) args.state!.trim(),
    ].join(' / ');

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primary.withAlpha(20),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    Icons.storefront,
                    size: 32,
                    color: theme.colorScheme.primary,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        args.placeName,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      if (locationText.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          locationText,
                          style: TextStyle(
                            color: theme.colorScheme.onSurface.withAlpha(150),
                            fontSize: 13,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.green.withAlpha(25),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.green.withAlpha(100)),
              ),
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.verified, size: 16, color: Colors.green),
                  SizedBox(width: 6),
                  Text(
                    'Reivindicação Aprovada',
                    style: TextStyle(
                      color: Colors.green,
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionCard(
    BuildContext context, {
    required Key key,
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    final theme = Theme.of(context);

    return Card(
      key: key,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(icon, color: theme.colorScheme.primary),
        ),
        title: Text(
          title,
          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        subtitle: Text(
          subtitle,
          style: TextStyle(
            fontSize: 12,
            color: theme.colorScheme.onSurface.withAlpha(160),
          ),
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: onTap,
      ),
    );
  }

  Widget _buildNextStepsNotice(BuildContext context, ThemeData theme) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withAlpha(50),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(60)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, size: 20, color: theme.colorScheme.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Edição de horários, comodidades e ofertas de produtos serão integradas nos próximos passos de evolução do painel.',
              style: TextStyle(
                fontSize: 12,
                color: theme.colorScheme.onSurface.withAlpha(160),
                height: 1.3,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

