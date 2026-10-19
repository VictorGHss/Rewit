import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/repositories/place_repository.dart';

/// Resultado retornado após o cadastro manual de um local com fotografia.
class ManualPlaceRegistrationResult {
  final PlaceDetail place;
  final File photoFile;

  const ManualPlaceRegistrationResult({
    required this.place,
    required this.photoFile,
  });
}

/// Modal para cadastro manual de um estabelecimento quando ele não é encontrado na busca.
///
/// Coleta nome, endereço e fotografia obrigatória, registra o local no backend
/// (`POST /api/v1/places`) e retorna o [PlaceDetail] com seu UUID canônico e a fotografia
/// para início imediato da avaliação.
class ManualPlaceRegistrationSheet extends StatefulWidget {
  final PlaceRepository placeRepository;
  final String initialName;
  final double? initialLatitude;
  final double? initialLongitude;
  final Future<File?> Function(ImageSource source)? onPickPhoto;

  const ManualPlaceRegistrationSheet({
    super.key,
    required this.placeRepository,
    this.initialName = '',
    this.initialLatitude,
    this.initialLongitude,
    this.onPickPhoto,
  });

  static Future<ManualPlaceRegistrationResult?> show(
    BuildContext context, {
    required PlaceRepository placeRepository,
    String initialName = '',
    double? initialLatitude,
    double? initialLongitude,
    Future<File?> Function(ImageSource source)? onPickPhoto,
  }) {
    return showModalBottomSheet<ManualPlaceRegistrationResult>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => ManualPlaceRegistrationSheet(
        placeRepository: placeRepository,
        initialName: initialName,
        initialLatitude: initialLatitude,
        initialLongitude: initialLongitude,
        onPickPhoto: onPickPhoto,
      ),
    );
  }

  @override
  State<ManualPlaceRegistrationSheet> createState() =>
      _ManualPlaceRegistrationSheetState();
}

class _ManualPlaceRegistrationSheetState
    extends State<ManualPlaceRegistrationSheet> {
  static const int _maxPhotoBytes = 10 * 1024 * 1024; // 10 MB
  static const Set<String> _allowedExtensions = {
    'jpg',
    'jpeg',
    'png',
  };

  late final TextEditingController _nameController;
  late final TextEditingController _addressController;
  final ImagePicker _imagePicker = ImagePicker();

  File? _selectedPhoto;
  Uint8List? _selectedPhotoBytes;
  bool _isSubmitting = false;
  bool _isPickingPhoto = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.initialName.trim());
    _addressController = TextEditingController();
  }

  @override
  void dispose() {
    _nameController.dispose();
    _addressController.dispose();
    super.dispose();
  }

  Future<void> _pickPhoto(ImageSource source) async {
    if (_isSubmitting || _isPickingPhoto) return;

    setState(() {
      _isPickingPhoto = true;
      _errorMessage = null;
    });

    try {
      File? file;
      if (widget.onPickPhoto != null) {
        file = await widget.onPickPhoto!(source);
      } else {
        final XFile? picked = await _imagePicker.pickImage(
          source: source,
          imageQuality: 85,
          maxWidth: 2048,
          maxHeight: 2048,
        );
        if (picked != null) {
          file = File(picked.path);
        }
      }

      if (!mounted || file == null) return;

      final validationError = _validatePhotoFile(file);
      if (validationError != null) {
        setState(() {
          _errorMessage = validationError;
        });
        return;
      }

      final bytes = file.readAsBytesSync();
      setState(() {
        _selectedPhoto = file;
        _selectedPhotoBytes = bytes;
        _errorMessage = null;
      });
    } on PlatformException {
      if (!mounted) return;
      setState(() {
        _errorMessage =
            'Não foi possível acessar a câmera ou galeria. Verifique as permissões do aplicativo.';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _errorMessage = 'Falha ao carregar a fotografia selecionada.';
      });
    } finally {
      if (mounted) {
        setState(() {
          _isPickingPhoto = false;
        });
      }
    }
  }

  String? _validatePhotoFile(File file) {
    final path = file.path.trim();
    if (path.isEmpty) {
      return 'O arquivo de fotografia selecionado é inválido.';
    }

    final dotIndex = path.lastIndexOf('.');
    if (dotIndex != -1 && dotIndex < path.length - 1) {
      final ext = path.substring(dotIndex + 1).toLowerCase();
      if (!_allowedExtensions.contains(ext)) {
        return 'Formato de imagem não suportado (.$ext). Use JPG ou PNG.';
      }
    }

    try {
      if (!file.existsSync()) {
        return 'O arquivo de fotografia selecionado não foi encontrado.';
      }
      final length = file.lengthSync();
      if (length <= 0) {
        return 'O arquivo de fotografia selecionado está vazio.';
      }
      if (length > _maxPhotoBytes) {
        return 'A fotografia excede o tamanho máximo permitido de 10 MB.';
      }
    } on FileSystemException {
      return 'Não foi possível ler o arquivo de fotografia selecionado.';
    }

    return null;
  }

  Future<void> _submit() async {
    if (_isSubmitting) return;

    final name = _nameController.text.trim();
    final address = _addressController.text.trim();

    if (name.isEmpty) {
      setState(() {
        _errorMessage = 'Informe o nome do local.';
      });
      return;
    }
    if (name.length > 255) {
      setState(() {
        _errorMessage = 'O nome do local deve ter no máximo 255 caracteres.';
      });
      return;
    }
    if (address.isEmpty) {
      setState(() {
        _errorMessage = 'Informe o endereço do local.';
      });
      return;
    }
    if (address.length > 500) {
      setState(() {
        _errorMessage = 'O endereço deve ter no máximo 500 caracteres.';
      });
      return;
    }
    if (_selectedPhoto == null) {
      setState(() {
        _errorMessage =
            'Adicione uma fotografia do local para concluir o cadastro.';
      });
      return;
    }

    final photoError = _validatePhotoFile(_selectedPhoto!);
    if (photoError != null) {
      setState(() {
        _errorMessage = photoError;
      });
      return;
    }

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    try {
      final createdPlace = await widget.placeRepository.createPlace(
        CreatePlaceInput(
          name: name,
          addressText: address,
          latitude: widget.initialLatitude,
          longitude: widget.initialLongitude,
        ),
      );

      if (!mounted) return;
      Navigator.of(context).pop(
        ManualPlaceRegistrationResult(
          place: createdPlace,
          photoFile: _selectedPhoto!,
        ),
      );
    } on ApiException catch (e) {
      if (!mounted) return;
      String message;
      if (e.isConflict) {
        message = e.detail.isNotEmpty
            ? e.detail
            : 'Este local já está cadastrado com este nome e endereço.';
      } else if (e.isUnauthorized) {
        message = 'Sua sessão expirou. Faça login novamente.';
      } else if (e.isValidationError && e.hasFieldErrors) {
        message = e.allFieldErrors.values.join(' ');
      } else {
        message = e.detail.isNotEmpty
            ? e.detail
            : 'Não foi possível cadastrar o local.';
      }
      setState(() {
        _errorMessage = message;
        _isSubmitting = false;
      });
    } on NetworkException catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = e.message.isNotEmpty
            ? e.message
            : 'Falha de conexão ao cadastrar o local. Verifique sua internet e tente novamente.';
        _isSubmitting = false;
      });
    } on FormatException catch (e) {
      if (!mounted) return;
      setState(() {
        _errorMessage = e.message.isNotEmpty
            ? e.message
            : 'Resposta inválida do servidor ao cadastrar o local.';
        _isSubmitting = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _errorMessage =
            'Falha de conexão ao cadastrar o local. Verifique sua internet e tente novamente.';
        _isSubmitting = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;

    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 16,
        bottom: bottomInset + 20,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  Icons.add_location_alt_outlined,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Cadastrar Novo Local',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.close),
                  tooltip: 'Fechar',
                  onPressed: _isSubmitting
                      ? null
                      : () => Navigator.of(context).pop(),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Informe o nome, endereço e uma fotografia do estabelecimento para cadastrá-lo e iniciar sua avaliação.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
            TextFormField(
              key: const Key('manual_place_name_field'),
              controller: _nameController,
              enabled: !_isSubmitting,
              textCapitalization: TextCapitalization.words,
              decoration: const InputDecoration(
                labelText: 'Nome do estabelecimento *',
                hintText: 'Ex: Pastel da Dona Ana',
                prefixIcon: Icon(Icons.storefront_outlined),
                border: OutlineInputBorder(),
              ),
              onChanged: (_) {
                if (_errorMessage != null) {
                  setState(() => _errorMessage = null);
                }
              },
            ),
            const SizedBox(height: 12),
            TextFormField(
              key: const Key('manual_place_address_field'),
              controller: _addressController,
              enabled: !_isSubmitting,
              textCapitalization: TextCapitalization.sentences,
              maxLines: 2,
              minLines: 1,
              decoration: const InputDecoration(
                labelText: 'Endereço completo *',
                hintText: 'Ex: Rua XV de Novembro, 120 - Centro',
                prefixIcon: Icon(Icons.place_outlined),
                border: OutlineInputBorder(),
              ),
              onChanged: (_) {
                if (_errorMessage != null) {
                  setState(() => _errorMessage = null);
                }
              },
            ),
            const SizedBox(height: 16),
            Text(
              'Fotografia do local *',
              style: theme.textTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            if (_selectedPhoto != null)
              Container(
                key: const Key('manual_place_photo_preview'),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: theme.colorScheme.primaryContainer.withValues(
                    alpha: 0.25,
                  ),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                    color: theme.colorScheme.primary.withValues(alpha: 0.5),
                  ),
                ),
                child: Row(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: _selectedPhotoBytes != null && _selectedPhotoBytes!.isNotEmpty
                          ? Image.memory(
                              _selectedPhotoBytes!,
                              width: 56,
                              height: 56,
                              fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) => Container(
                                width: 56,
                                height: 56,
                                color: theme.colorScheme.surfaceContainerHighest,
                                child: const Icon(Icons.image_outlined),
                              ),
                            )
                          : Container(
                              width: 56,
                              height: 56,
                              color: theme.colorScheme.surfaceContainerHighest,
                              child: const Icon(Icons.image_outlined),
                            ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Fotografia anexada',
                            style: theme.textTheme.bodyMedium?.copyWith(
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          Text(
                            _selectedPhoto!.path.split(RegExp(r'[/\\]')).last,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      key: const Key('manual_place_remove_photo_button'),
                      icon: const Icon(Icons.delete_outline),
                      tooltip: 'Remover fotografia',
                      onPressed: _isSubmitting
                          ? null
                          : () => setState(() {
                                _selectedPhoto = null;
                                _selectedPhotoBytes = null;
                              }),
                    ),
                  ],
                ),
              )
            else
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const Key('manual_place_camera_button'),
                      onPressed: (_isSubmitting || _isPickingPhoto)
                          ? null
                          : () => _pickPhoto(ImageSource.camera),
                      icon: const Icon(Icons.photo_camera_outlined),
                      label: const Text('Câmera'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const Key('manual_place_gallery_button'),
                      onPressed: (_isSubmitting || _isPickingPhoto)
                          ? null
                          : () => _pickPhoto(ImageSource.gallery),
                      icon: const Icon(Icons.photo_library_outlined),
                      label: const Text('Galeria'),
                    ),
                  ),
                ],
              ),
            if (_errorMessage != null) ...[
              const SizedBox(height: 12),
              Container(
                key: const Key('manual_place_error_banner'),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: theme.colorScheme.errorContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  children: [
                    Icon(
                      Icons.error_outline,
                      color: theme.colorScheme.onErrorContainer,
                      size: 20,
                    ),
                    const SizedBox(width: 8),
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
            ],
            const SizedBox(height: 20),
            FilledButton.icon(
              key: const Key('manual_place_submit_button'),
              onPressed: _isSubmitting ? null : _submit,
              icon: _isSubmitting
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(Icons.check_circle_outline),
              label: Text(
                _isSubmitting
                    ? 'Cadastrando local...'
                    : 'Cadastrar e Avaliar Local',
              ),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
