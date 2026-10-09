import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/profile/domain/entities/update_profile_input.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';

/// Tela dedicada para edição do perfil do usuário autenticado (C5.8).
/// Consome PATCH /api/v1/me/profile no backend sem aceitar userId arbitrário.
class EditProfileScreen extends StatefulWidget {
  final UserProfile? initialProfile;
  final UserProfileRepository repository;
  final AuthNotifier authNotifier;

  const EditProfileScreen({
    super.key,
    this.initialProfile,
    required this.repository,
    required this.authNotifier,
  });

  @override
  State<EditProfileScreen> createState() => _EditProfileScreenState();
}

class _EditProfileScreenState extends State<EditProfileScreen> {
  final _formKey = GlobalKey<FormState>();

  late final TextEditingController _handleController;
  late final TextEditingController _displayNameController;
  late final TextEditingController _bioController;
  late bool _isAnonymousDefault;

  bool _isSaving = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();

    final authUser = widget.authNotifier.state is Authenticated
        ? (widget.authNotifier.state as Authenticated).user
        : null;

    final initialHandle = widget.initialProfile?.handle ?? authUser?.handle ?? '';
    final initialDisplayName = widget.initialProfile?.displayName ?? authUser?.displayName ?? '';
    final initialBio = widget.initialProfile?.bio ?? '';
    final initialAnonymous = widget.initialProfile?.isAnonymousDefault ??
        authUser?.isAnonymousDefault ??
        false;

    _handleController = TextEditingController(text: initialHandle);
    _displayNameController = TextEditingController(text: initialDisplayName);
    _bioController = TextEditingController(text: initialBio);
    _isAnonymousDefault = initialAnonymous;
  }

  @override
  void dispose() {
    _handleController.dispose();
    _displayNameController.dispose();
    _bioController.dispose();
    super.dispose();
  }

  String _cleanHandle(String raw) {
    var cleaned = raw.trim();
    if (cleaned.startsWith('@')) {
      cleaned = cleaned.substring(1).trim();
    }
    return cleaned;
  }

  Future<void> _saveProfile() async {
    if (_isSaving) return;
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isSaving = true;
      _errorMessage = null;
    });

    final handle = _cleanHandle(_handleController.text);
    final displayName = _displayNameController.text.trim();
    final bioText = _bioController.text.trim();
    final bio = bioText;

    final input = UpdateProfileInput(
      handle: handle,
      displayName: displayName,
      bio: bio,
      isAnonymousDefault: _isAnonymousDefault,
    );

    try {
      final updatedProfile = await widget.repository.updateMyProfile(input);

      if (mounted) {
        // Atualiza a representação autenticada na sessão local
        widget.authNotifier.updateCurrentUser(
          handle: updatedProfile.handle,
          displayName: updatedProfile.displayName,
          isAnonymousDefault: updatedProfile.isAnonymousDefault,
        );

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Perfil atualizado com sucesso!'),
            backgroundColor: Colors.green,
          ),
        );

        Navigator.of(context).pop(updatedProfile);
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isSaving = false;
          if (e.statusCode == 409) {
            _errorMessage = e.detail.isNotEmpty
                ? e.detail
                : 'Este nome de usuário já está em uso por outra pessoa.';
          } else if (e.statusCode == 400) {
            _errorMessage = e.detail.isNotEmpty
                ? e.detail
                : 'Dados inválidos. Verifique as informações fornecidas.';
          } else if (e.statusCode == 401) {
            _errorMessage = 'Sessão expirada. Faça login novamente.';
          } else if (e.statusCode == 429) {
            _errorMessage = 'Muitas tentativas. Aguarde um momento antes de tentar novamente.';
          } else {
            _errorMessage = e.detail.isNotEmpty
                ? e.detail
                : 'Não foi possível salvar o perfil.';
          }
        });
      }
    } on NetworkException catch (e) {
      if (mounted) {
        setState(() {
          _isSaving = false;
          _errorMessage = e.message.isNotEmpty
              ? e.message
              : 'Sem conexão com a internet. Verifique sua rede.';
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isSaving = false;
          _errorMessage = 'Ocorreu um erro inesperado ao atualizar seu perfil.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Editar Perfil'),
        actions: [
          TextButton(
            key: const Key('edit_profile_save_button'),
            onPressed: _isSaving ? null : _saveProfile,
            child: _isSaving
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text(
                    'Salvar',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20.0),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Banner de Erro em caso de falha no salvamento
              if (_errorMessage != null) ...[
                Card(
                  color: theme.colorScheme.errorContainer,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  child: Padding(
                    padding: const EdgeInsets.all(12.0),
                    child: Row(
                      children: [
                        Icon(Icons.error_outline, color: theme.colorScheme.error),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            _errorMessage!,
                            style: TextStyle(
                              color: theme.colorScheme.onErrorContainer,
                              fontSize: 13,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
              ],

              // 1. Campo Handle
              TextFormField(
                key: const Key('edit_profile_handle_field'),
                controller: _handleController,
                enabled: !_isSaving,
                decoration: const InputDecoration(
                  labelText: 'Nome de usuário (@handle)',
                  hintText: 'seu_handle',
                  prefixText: '@',
                  border: OutlineInputBorder(),
                  helperText: 'De 3 a 30 caracteres alfanuméricos ou sublinhado (_)',
                ),
                validator: (val) {
                  if (val == null || val.trim().isEmpty) {
                    return 'Informe seu nome de usuário';
                  }
                  final cleaned = _cleanHandle(val);
                  if (cleaned.length < 3) {
                    return 'O handle deve ter no mínimo 3 caracteres';
                  }
                  if (cleaned.length > 30) {
                    return 'O handle deve ter no máximo 30 caracteres';
                  }
                  if (!RegExp(r'^[a-zA-Z0-9_]+$').hasMatch(cleaned)) {
                    return 'Use apenas letras, números e sublinhado (_)';
                  }
                  return null;
                },
              ),

              const SizedBox(height: 20),

              // 2. Campo Nome de Exibição
              TextFormField(
                key: const Key('edit_profile_display_name_field'),
                controller: _displayNameController,
                enabled: !_isSaving,
                decoration: const InputDecoration(
                  labelText: 'Nome de exibição',
                  hintText: 'Seu Nome ou Apelido',
                  border: OutlineInputBorder(),
                  helperText: 'De 2 a 100 caracteres',
                ),
                validator: (val) {
                  final trimmed = val?.trim() ?? '';
                  if (trimmed.isEmpty) {
                    return 'Informe seu nome de exibição';
                  }
                  if (trimmed.length < 2) {
                    return 'O nome deve ter no mínimo 2 caracteres';
                  }
                  if (trimmed.length > 100) {
                    return 'O nome deve ter no máximo 100 caracteres';
                  }
                  return null;
                },
              ),

              const SizedBox(height: 20),

              // 3. Campo Biografia
              TextFormField(
                key: const Key('edit_profile_bio_field'),
                controller: _bioController,
                enabled: !_isSaving,
                maxLines: 4,
                maxLength: 500,
                decoration: const InputDecoration(
                  labelText: 'Biografia',
                  hintText: 'Conte um pouco sobre suas preferências gastronômicas...',
                  border: OutlineInputBorder(),
                  alignLabelWithHint: true,
                ),
                validator: (val) {
                  if (val != null && val.length > 500) {
                    return 'A bio deve ter no máximo 500 caracteres';
                  }
                  return null;
                },
              ),

              const SizedBox(height: 12),

              // 4. Switch Avaliações Anônimas por Padrão
              Card(
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                  side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(100)),
                ),
                child: SwitchListTile(
                  key: const Key('edit_profile_anonymous_switch'),
                  title: const Text(
                    'Avaliações anônimas por padrão',
                    style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
                  ),
                  subtitle: const Text(
                    'Suas novas publicações serão criadas como anônimas por padrão.',
                    style: TextStyle(fontSize: 13),
                  ),
                  value: _isAnonymousDefault,
                  onChanged: _isSaving
                      ? null
                      : (value) {
                          setState(() {
                            _isAnonymousDefault = value;
                          });
                        },
                ),
              ),

              const SizedBox(height: 28),

              // Botão Salvar Principal
              FilledButton.icon(
                key: const Key('edit_profile_submit_button'),
                onPressed: _isSaving ? null : _saveProfile,
                icon: _isSaving
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.check),
                label: Text(_isSaving ? 'Salvando...' : 'Salvar Alterações'),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
