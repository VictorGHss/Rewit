import 'package:flutter/material.dart';
import '../../domain/repositories/place_commercial_repository.dart';
import '../state/place_business_profile_notifier.dart';
import '../../../../shared/widgets/error_view.dart';
import '../../../../shared/widgets/loading_indicator.dart';

/// Argumentos para inicialização da tela de edição do perfil comercial.
class PlaceBusinessProfileEditArgs {
  final String placeId;
  final String businessAccountId;
  final String placeName;
  final bool isApproved;

  const PlaceBusinessProfileEditArgs({
    required this.placeId,
    required this.businessAccountId,
    required this.placeName,
    this.isApproved = true,
  });
}

/// Tela para edição do perfil comercial e contatos públicos de um estabelecimento reivindicado (F2.4).
class PlaceBusinessProfileEditScreen extends StatefulWidget {
  final PlaceBusinessProfileEditArgs args;
  final PlaceCommercialRepository? commercialRepository;
  final PlaceBusinessProfileNotifier? notifier;

  const PlaceBusinessProfileEditScreen({
    super.key,
    required this.args,
    this.commercialRepository,
    this.notifier,
  });

  @override
  State<PlaceBusinessProfileEditScreen> createState() => _PlaceBusinessProfileEditScreenState();
}

class _PlaceBusinessProfileEditScreenState extends State<PlaceBusinessProfileEditScreen> {
  late final PlaceBusinessProfileNotifier _notifier;
  bool _ownsNotifier = false;

  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _descriptionController;
  late final TextEditingController _phoneController;
  late final TextEditingController _websiteController;
  late final TextEditingController _instagramController;
  late final TextEditingController _facebookController;
  bool _initializedFromProfile = false;

  @override
  void initState() {
    super.initState();
    _descriptionController = TextEditingController();
    _phoneController = TextEditingController();
    _websiteController = TextEditingController();
    _instagramController = TextEditingController();
    _facebookController = TextEditingController();

    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.commercialRepository != null) {
      _notifier = PlaceBusinessProfileNotifier(repository: widget.commercialRepository!);
      _ownsNotifier = true;
    } else {
      throw StateError('PlaceCommercialRepository ou PlaceBusinessProfileNotifier deve ser fornecido.');
    }

    _notifier.addListener(_onNotifierChanged);
    _onNotifierChanged();

    if (widget.args.isApproved) {
      _notifier.loadProfile(widget.args.placeId);
    }
  }

  void _onNotifierChanged() {
    if (!_initializedFromProfile && _notifier.profile != null) {
      final p = _notifier.profile!;
      _descriptionController.text = p.description ?? '';
      _phoneController.text = p.phone ?? '';
      _websiteController.text = p.websiteUrl ?? '';
      _instagramController.text = p.instagramUrl ?? '';
      _facebookController.text = p.facebookUrl ?? '';
      _initializedFromProfile = true;
      if (mounted) setState(() {});
    }
  }

  @override
  void dispose() {
    _notifier.removeListener(_onNotifierChanged);
    _descriptionController.dispose();
    _phoneController.dispose();
    _websiteController.dispose();
    _instagramController.dispose();
    _facebookController.dispose();
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  Future<void> _handleSave() async {
    if (_notifier.isSaving) return;

    if (_formKey.currentState != null && !_formKey.currentState!.validate()) {
      return;
    }

    final scaffoldMessenger = ScaffoldMessenger.of(context);
    final success = await _notifier.saveProfile(
      placeId: widget.args.placeId,
      businessAccountId: widget.args.businessAccountId,
      description: _descriptionController.text,
      phone: _phoneController.text,
      websiteUrl: _websiteController.text,
      instagramUrl: _instagramController.text,
      facebookUrl: _facebookController.text,
    );

    if (!mounted) return;

    if (success) {
      scaffoldMessenger.showSnackBar(
        SnackBar(
          content: Text(_notifier.successMessage ?? 'Perfil comercial atualizado com sucesso.'),
          backgroundColor: Colors.green,
        ),
      );
    } else if (_notifier.errorMessage != null) {
      scaffoldMessenger.showSnackBar(
        SnackBar(
          content: Text(_notifier.errorMessage!),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (!widget.args.isApproved) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Perfil Comercial'),
        ),
        body: _buildUnauthorizedView(context, theme),
      );
    }

    return AnimatedBuilder(
      animation: _notifier,
      builder: (context, _) {
        return Scaffold(
          appBar: AppBar(
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Perfil Comercial'),
                Text(
                  widget.args.placeName,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurface.withAlpha(160),
                  ),
                ),
              ],
            ),
            actions: [
              Padding(
                padding: const EdgeInsets.only(right: 8.0),
                child: _notifier.isSaving
                    ? const Center(
                        child: SizedBox(
                          width: 22,
                          height: 22,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      )
                    : TextButton.icon(
                        key: const Key('save_business_profile_button'),
                        onPressed: _notifier.isLoading ? null : _handleSave,
                        icon: const Icon(Icons.check),
                        label: const Text('Salvar'),
                      ),
              ),
            ],
          ),
          body: _buildBody(context, theme),
        );
      },
    );
  }

  Widget _buildBody(BuildContext context, ThemeData theme) {
    if (_notifier.isLoading && _notifier.profile == null) {
      return const LoadingIndicator(message: 'Carregando perfil comercial...');
    }

    if (_notifier.profile == null && _notifier.errorMessage != null) {
      return ErrorView(
        title: 'Não foi possível carregar o perfil comercial',
        message: _notifier.errorMessage!,
        onRetry: () => _notifier.loadProfile(widget.args.placeId),
      );
    }

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16.0),
      child: Form(
        key: _formKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _buildPublicNotice(context, theme),
            if (_notifier.errorMessage != null) ...[
              const SizedBox(height: 12),
              _buildErrorBanner(context, theme, _notifier.errorMessage!),
            ],
            const SizedBox(height: 20),
            _buildSectionHeader(theme, 'Apresentação Institucional', Icons.description_outlined),
            const SizedBox(height: 12),
            _buildDescriptionField(theme),
            const SizedBox(height: 24),
            _buildSectionHeader(theme, 'Contatos Públicos', Icons.contact_phone_outlined),
            const SizedBox(height: 12),
            _buildPhoneField(theme),
            const SizedBox(height: 16),
            _buildWebsiteField(theme),
            const SizedBox(height: 16),
            _buildInstagramField(theme),
            const SizedBox(height: 16),
            _buildFacebookField(theme),
            const SizedBox(height: 32),
            ElevatedButton.icon(
              key: const Key('save_business_profile_bottom_button'),
              onPressed: _notifier.isSaving ? null : _handleSave,
              icon: _notifier.isSaving
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                    )
                  : const Icon(Icons.save),
              label: const Text('Salvar Alterações'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPublicNotice(BuildContext context, ThemeData theme) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.primary.withAlpha(15),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: theme.colorScheme.primary.withAlpha(50)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.public, size: 20, color: theme.colorScheme.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'A descrição institucional e os contatos públicos serão exibidos diretamente na página pública do estabelecimento no Rewit para todos os clientes.',
              style: TextStyle(
                fontSize: 12,
                color: theme.colorScheme.onSurface.withAlpha(170),
                height: 1.3,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildErrorBanner(BuildContext context, ThemeData theme, String message) {
    return Container(
      key: const Key('business_profile_error_banner'),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.error.withAlpha(20),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: theme.colorScheme.error.withAlpha(80)),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: theme.colorScheme.error, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                fontSize: 13,
                color: theme.colorScheme.error,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(ThemeData theme, String title, IconData icon) {
    return Row(
      children: [
        Icon(icon, size: 20, color: theme.colorScheme.primary),
        const SizedBox(width: 8),
        Text(
          title,
          style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }

  Widget _buildDescriptionField(ThemeData theme) {
    final length = _descriptionController.text.length;
    final isOverLimit = length > 2000;

    return TextFormField(
      key: const Key('business_profile_description_input'),
      controller: _descriptionController,
      maxLines: 5,
      maxLength: 2000,
      decoration: InputDecoration(
        labelText: 'Descrição institucional',
        hintText: 'Conte sobre seu espaço, história, especialidades e diferenciais para a comunidade...',
        alignLabelWithHint: true,
        border: const OutlineInputBorder(),
        helperText: 'Compartilhada na página pública do estabelecimento',
        counterText: '$length / 2000',
        counterStyle: TextStyle(
          color: isOverLimit ? theme.colorScheme.error : null,
          fontWeight: isOverLimit ? FontWeight.bold : null,
        ),
      ),
      validator: PlaceBusinessProfileNotifier.validateDescription,
      onChanged: (_) => setState(() {}),
    );
  }

  Widget _buildPhoneField(ThemeData theme) {
    return TextFormField(
      key: const Key('business_profile_phone_input'),
      controller: _phoneController,
      keyboardType: TextInputType.phone,
      decoration: const InputDecoration(
        labelText: 'Telefone comercial / WhatsApp',
        hintText: 'Ex: +55 (11) 91234-5678',
        prefixIcon: Icon(Icons.phone_outlined),
        border: OutlineInputBorder(),
        helperText: '8 a 15 dígitos com DDD ou DDI (+ opcional)',
      ),
      validator: PlaceBusinessProfileNotifier.validatePhone,
    );
  }

  Widget _buildWebsiteField(ThemeData theme) {
    return TextFormField(
      key: const Key('business_profile_website_input'),
      controller: _websiteController,
      keyboardType: TextInputType.url,
      decoration: const InputDecoration(
        labelText: 'Site oficial',
        hintText: 'https://seusite.com.br',
        prefixIcon: Icon(Icons.language_outlined),
        border: OutlineInputBorder(),
        helperText: 'Link absoluto iniciando com http:// ou https://',
      ),
      validator: (v) => PlaceBusinessProfileNotifier.validateUrl(v, 'do site'),
    );
  }

  Widget _buildInstagramField(ThemeData theme) {
    return TextFormField(
      key: const Key('business_profile_instagram_input'),
      controller: _instagramController,
      keyboardType: TextInputType.url,
      decoration: const InputDecoration(
        labelText: 'Perfil no Instagram',
        hintText: 'https://instagram.com/seu_perfil',
        prefixIcon: Icon(Icons.camera_alt_outlined),
        border: OutlineInputBorder(),
        helperText: 'Link completo do perfil (https://...)',
      ),
      validator: (v) => PlaceBusinessProfileNotifier.validateUrl(v, 'do Instagram'),
    );
  }

  Widget _buildFacebookField(ThemeData theme) {
    return TextFormField(
      key: const Key('business_profile_facebook_input'),
      controller: _facebookController,
      keyboardType: TextInputType.url,
      decoration: const InputDecoration(
        labelText: 'Página no Facebook',
        hintText: 'https://facebook.com/sua_pagina',
        prefixIcon: Icon(Icons.facebook_outlined),
        border: OutlineInputBorder(),
        helperText: 'Link completo da página (https://...)',
      ),
      validator: (v) => PlaceBusinessProfileNotifier.validateUrl(v, 'do Facebook'),
    );
  }

  Widget _buildUnauthorizedView(BuildContext context, ThemeData theme) {
    return Center(
      child: Padding(
        key: const Key('business_profile_unauthorized_view'),
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
              'Apenas estabelecimentos com reivindicação aprovada podem gerenciar o perfil comercial.',
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
}
