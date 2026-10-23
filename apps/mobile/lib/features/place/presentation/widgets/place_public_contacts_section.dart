import 'package:flutter/material.dart';
import '../../../../core/services/url_launcher_service.dart';
import '../../domain/entities/place_detail.dart';

/// Seção visual que exibe os contatos comerciais e redes sociais públicas de um estabelecimento (F2.5).
class PlacePublicContactsSection extends StatelessWidget {
  final PlaceDetail place;
  final UrlLauncherService urlLauncherService;

  const PlacePublicContactsSection({
    super.key,
    required this.place,
    this.urlLauncherService = const UrlLauncherServiceImpl(),
  });

  @override
  Widget build(BuildContext context) {
    if (!place.hasPublicContacts) {
      return const SizedBox.shrink();
    }

    final theme = Theme.of(context);

    final phone = place.phone?.trim();
    final website = place.websiteUrl?.trim();
    final instagram = place.instagramUrl?.trim();
    final facebook = place.facebookUrl?.trim();

    final hasPhone = phone != null && phone.isNotEmpty;
    final hasWebsite = website != null && website.isNotEmpty;
    final hasInstagram = instagram != null && instagram.isNotEmpty;
    final hasFacebook = facebook != null && facebook.isNotEmpty;

    return Card(
      key: const Key('place_public_contacts_section'),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.contact_phone_outlined,
                  size: 20,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Text(
                  'Contatos e Redes Sociais',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (hasPhone) ...[
              _buildContactTile(
                context: context,
                key: const Key('place_contact_phone_item'),
                icon: Icons.phone_outlined,
                title: 'Telefone',
                value: phone,
                actionIcon: Icons.call,
                onTap: () => _handlePhoneTap(context, phone),
              ),
            ],
            if (hasWebsite) ...[
              if (hasPhone) const Divider(height: 16),
              _buildContactTile(
                context: context,
                key: const Key('place_contact_website_item'),
                icon: Icons.language_outlined,
                title: 'Site oficial',
                value: website,
                actionIcon: Icons.open_in_new,
                onTap: () => _handleWebTap(context, website, 'site oficial'),
              ),
            ],
            if (hasInstagram) ...[
              if (hasPhone || hasWebsite) const Divider(height: 16),
              _buildContactTile(
                context: context,
                key: const Key('place_contact_instagram_item'),
                icon: Icons.camera_alt_outlined,
                title: 'Instagram',
                value: instagram,
                actionIcon: Icons.open_in_new,
                onTap: () => _handleWebTap(context, instagram, 'Instagram'),
              ),
            ],
            if (hasFacebook) ...[
              if (hasPhone || hasWebsite || hasInstagram) const Divider(height: 16),
              _buildContactTile(
                context: context,
                key: const Key('place_contact_facebook_item'),
                icon: Icons.facebook_outlined,
                title: 'Facebook',
                value: facebook,
                actionIcon: Icons.open_in_new,
                onTap: () => _handleWebTap(context, facebook, 'Facebook'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildContactTile({
    required BuildContext context,
    required Key key,
    required IconData icon,
    required String title,
    required String value,
    required IconData actionIcon,
    required VoidCallback onTap,
  }) {
    final theme = Theme.of(context);

    return InkWell(
      key: key,
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8.0, horizontal: 4.0),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: theme.colorScheme.primaryContainer.withAlpha(80),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(icon, size: 20, color: theme.colorScheme.primary),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 12,
                      color: theme.colorScheme.onSurface.withAlpha(160),
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    value,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                      color: theme.colorScheme.onSurface,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(
              actionIcon,
              size: 18,
              color: theme.colorScheme.primary.withAlpha(180),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _handlePhoneTap(BuildContext context, String rawPhone) async {
    final result = await urlLauncherService.launchPhone(rawPhone);
    if (!context.mounted) return;

    if (result == LaunchResult.invalidFormat) {
      _showFeedback(context, 'Número de telefone inválido para chamada.');
    } else if (result == LaunchResult.launchFailed) {
      _showFeedback(context, 'Não foi possível iniciar a chamada telefônica no dispositivo.');
    }
  }

  Future<void> _handleWebTap(BuildContext context, String rawUrl, String label) async {
    final result = await urlLauncherService.launchWebUrl(rawUrl);
    if (!context.mounted) return;

    if (result == LaunchResult.invalidScheme) {
      _showFeedback(context, 'Endereço inválido: apenas links http ou https são permitidos.');
    } else if (result == LaunchResult.invalidFormat) {
      _showFeedback(context, 'Endereço web de $label inválido.');
    } else if (result == LaunchResult.launchFailed) {
      _showFeedback(context, 'Não foi possível abrir o link no navegador.');
    }
  }

  void _showFeedback(BuildContext context, String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        duration: const Duration(seconds: 3),
      ),
    );
  }
}
