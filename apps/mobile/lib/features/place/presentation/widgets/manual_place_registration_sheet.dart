import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/repositories/place_repository.dart';
import '../../domain/services/pending_place_registration_store.dart';

import 'dart:math';

/// Gera um UUID v4 criptograficamente seguro e em conformidade estrita com o RFC 4122.
String generateUuidV4() {
  final Random random = Random.secure();
  final values = List<int>.generate(16, (i) => random.nextInt(256));
  values[6] = (values[6] & 0x0f) | 0x40;
  values[8] = (values[8] & 0x3f) | 0x80;
  final hex = values.map((b) => b.toRadixString(16).padLeft(2, '0')).toList();
  return '${hex.sublist(0, 4).join()}-${hex.sublist(4, 6).join()}-${hex.sublist(6, 8).join()}-${hex.sublist(8, 10).join()}-${hex.sublist(10, 16).join()}';
}

/// Registro de local criado retornado para preservação pelo chamador ou gerenciador de estado
/// da feature quando a fotografia fica pendente de upload.
class PendingPlaceRegistration {
  final PlaceDetail place;
  final String uploadIdempotencyKey;
  final File? photoFile;
  final String? userId;

  /// O arquivo local da fotografia não existe mais: a retomada exige nova foto para o MESMO local.
  final bool photoUnavailable;

  const PendingPlaceRegistration({
    required this.place,
    required this.uploadIdempotencyKey,
    this.photoFile,
    this.userId,
    this.photoUnavailable = false,
  });
}

/// Resultado retornado após o cadastro manual de um local com fotografia.
class ManualPlaceRegistrationResult {
  final PlaceDetail place;
  final File? photoFile;
  final bool isPhotoPending;
  final String? uploadIdempotencyKey;

  const ManualPlaceRegistrationResult({
    required this.place,
    this.photoFile,
    this.isPhotoPending = false,
    this.uploadIdempotencyKey,
  });
}

/// Modal para cadastro manual de um estabelecimento quando ele não é encontrado na busca.
///
/// Coleta nome, endereço e fotografia obrigatória, registra o local no backend
/// (`POST /api/v1/places`), vincula a fotografia diretamente ao local
/// (`POST /api/v1/places/{placeId}/media`) e retorna o [PlaceDetail] com seu UUID canônico.
/// Se a criação do local funcionar e o upload da fotografia falhar, devolve o local
/// criado e a chave da operação no resultado para permitir retomada explícita sem criar duplicatas.
class ManualPlaceRegistrationSheet extends StatefulWidget {
  final PlaceRepository placeRepository;
  final String initialName;
  final double? initialLatitude;
  final double? initialLongitude;
  final Future<File?> Function(ImageSource source)? onPickPhoto;
  final PlaceDetail? initialPendingPlace;
  final String? initialUploadIdempotencyKey;
  final File? initialPhoto;
  final String? currentUserId;

  /// Quando informado, a operação pendente é gravada para [currentUserId] no momento em que o upload falha
  /// (e removida quando conclui), independentemente de o chamador ainda estar montado (P2-3).
  final PendingPlaceRegistrationStore? pendingRegistrationStore;

  /// Quando informado, um 401 aguarda o resultado da verificação de sessão que ele disparou, para a mensagem
  /// dizer se a sessão foi renovada, expirou ou não pôde ser verificada.
  final AuthNotifier? authNotifier;

  const ManualPlaceRegistrationSheet({
    super.key,
    required this.placeRepository,
    this.initialName = '',
    this.initialLatitude,
    this.initialLongitude,
    this.onPickPhoto,
    this.initialPendingPlace,
    this.initialUploadIdempotencyKey,
    this.initialPhoto,
    this.currentUserId,
    this.pendingRegistrationStore,
    this.authNotifier,
  });

  @visibleForTesting
  static void clearPendingRegistrations() {}

  static Future<ManualPlaceRegistrationResult?> show(
    BuildContext context, {
    required PlaceRepository placeRepository,
    String initialName = '',
    double? initialLatitude,
    double? initialLongitude,
    Future<File?> Function(ImageSource source)? onPickPhoto,
    PlaceDetail? initialPendingPlace,
    String? initialUploadIdempotencyKey,
    File? initialPhoto,
    String? currentUserId,
    PendingPlaceRegistrationStore? pendingRegistrationStore,
    AuthNotifier? authNotifier,
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
        initialPendingPlace: initialPendingPlace,
        initialUploadIdempotencyKey: initialUploadIdempotencyKey,
        initialPhoto: initialPhoto,
        currentUserId: currentUserId,
        pendingRegistrationStore: pendingRegistrationStore,
        authNotifier: authNotifier,
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
  PlaceDetail? _pendingCreatedPlace;
  String? _uploadIdempotencyKey;
  bool _isSubmitting = false;
  bool _isPickingPhoto = false;
  bool _canPopNow = false;
  String? _errorMessage;

  // Conta e store capturados na abertura: a gravação da pendência não depende do estado posterior da
  // sessão nem de este widget (ou o chamador) continuar montado.
  late final String? _ownerUserId;
  late final PendingPlaceRegistrationStore? _store;

  @override
  void initState() {
    super.initState();
    final owner = widget.currentUserId?.trim();
    _ownerUserId = (owner == null || owner.isEmpty) ? null : owner;
    _store = widget.pendingRegistrationStore;
    final initialNameTrimmed = widget.initialName.trim();
    _nameController = TextEditingController(text: initialNameTrimmed);
    _addressController = TextEditingController();

    if (widget.initialPendingPlace != null) {
      _pendingCreatedPlace = widget.initialPendingPlace;
      _uploadIdempotencyKey = widget.initialUploadIdempotencyKey ?? generateUuidV4();
      if (_nameController.text.isEmpty) {
        _nameController.text = _pendingCreatedPlace!.name;
      }
      if (_addressController.text.isEmpty) {
        _addressController.text = _pendingCreatedPlace!.addressText;
      }
      if (widget.initialPhoto != null && widget.initialPhoto!.existsSync()) {
        _selectedPhoto = widget.initialPhoto;
        try {
          _selectedPhotoBytes = widget.initialPhoto!.readAsBytesSync();
        } catch (_) {}
      }
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _addressController.dispose();
    super.dispose();
  }

  void _handleClose() {
    if (_isSubmitting) return;
    _canPopNow = true;
    if (_pendingCreatedPlace != null) {
      Navigator.of(context).pop(
        ManualPlaceRegistrationResult(
          place: _pendingCreatedPlace!,
          photoFile: _selectedPhoto,
          isPhotoPending: true,
          uploadIdempotencyKey: _uploadIdempotencyKey ?? generateUuidV4(),
        ),
      );
    } else {
      Navigator.of(context).pop();
    }
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
        _uploadIdempotencyKey = null; // Nova fotografia gera nova chave de upload lógica
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

    if (_pendingCreatedPlace == null) {
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

    Uint8List photoBytes;
    try {
      photoBytes = _selectedPhotoBytes ?? _selectedPhoto!.readAsBytesSync();
      _selectedPhotoBytes = photoBytes;
    } on FileSystemException {
      setState(() {
        _errorMessage = 'Não foi possível ler o arquivo de fotografia selecionado.';
      });
      return;
    }

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    final sessionCheckBefore = widget.authNotifier?.sessionCheckSequence;
    try {
      PlaceDetail place = _pendingCreatedPlace ??
          await widget.placeRepository.createPlace(
            CreatePlaceInput(
              name: name,
              addressText: address,
              latitude: widget.initialLatitude,
              longitude: widget.initialLongitude,
            ),
          );
      _pendingCreatedPlace = place;

      _uploadIdempotencyKey ??= generateUuidV4();
      final operationKey = _uploadIdempotencyKey!;
      final photo = _selectedPhoto!;
      // O local já existe no servidor: a retomada é gravada ANTES do upload, para sobreviver também ao
      // encerramento do processo enquanto o envio está em andamento (S3-A.13). Só sai do store quando o
      // envio conclui.
      await _persistPendingOperation(place, operationKey, photo);
      final filename = photo.path.split(RegExp(r'[/\\]')).last;
      final String uploadedPhotoUrl;
      try {
        uploadedPhotoUrl = await widget.placeRepository.uploadPlacePhoto(
          placeId: place.id,
          fileBytes: photoBytes,
          filename: filename.isNotEmpty ? filename : 'place.jpg',
          idempotencyKey: operationKey,
        );
      } catch (_) {
        // Segunda tentativa idempotente, caso a gravação anterior tenha falhado. Um 401 aqui expira a sessão e
        // desmonta a Home; a pendência continua sob a conta que iniciou a operação.
        unawaited(_persistPendingOperation(place, operationKey, photo));
        rethrow;
      }
      await _clearPendingOperation(place);
      final completedPlace = place.copyWith(photoUrl: uploadedPhotoUrl);

      if (!mounted) return;
      _canPopNow = true;
      Navigator.of(context).pop(
        ManualPlaceRegistrationResult(
          place: completedPlace,
          photoFile: _selectedPhoto!,
          isPhotoPending: false,
        ),
      );
    } on ApiException catch (e) {
      if (!mounted) return;
      String message;
      if (e.isConflict && _pendingCreatedPlace == null) {
        message = e.detail.isNotEmpty
            ? e.detail
            : 'Este local já está cadastrado com este nome e endereço.';
      } else if (e.isUnauthorized) {
        message = await _unauthorizedMessage(e, sessionCheckBefore);
        if (!mounted) return;
      } else if (e.isValidationError && e.hasFieldErrors) {
        message = e.allFieldErrors.values.join(' ');
      } else if (_pendingCreatedPlace != null) {
        message = e.detail.isNotEmpty
            ? e.detail
            : 'O local foi registrado, mas não foi possível enviar a fotografia. Tente reenviar a foto.';
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
            : (_pendingCreatedPlace != null
                ? 'O local foi registrado, mas houve falha de conexão ao enviar a fotografia. Tente reenviar.'
                : 'Falha de conexão ao cadastrar o local. Verifique sua internet e tente novamente.');
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
        _errorMessage = _pendingCreatedPlace != null
            ? 'O local foi registrado, mas não foi possível enviar a fotografia. Tente reenviar.'
            : 'Falha de conexão ao cadastrar o local. Verifique sua internet e tente novamente.';
        _isSubmitting = false;
      });
    }
  }

  /// O 401 dispara a verificação da sessão em paralelo (onSessionExpired) e chega aqui antes do resultado dela.
  /// A mensagem espera essa verificação: um 401 sozinho não prova que a sessão expirou.
  Future<String> _unauthorizedMessage(ApiException e, int? sessionCheckBefore) async {
    const expired = 'Sua sessão expirou. Faça login novamente.';
    final check = sessionCheckBefore == null ? null : widget.authNotifier?.sessionCheckSince(sessionCheckBefore);
    if (check == null) return expired;
    switch (await check) {
      case SessionRefreshOutcome.refreshed:
        if (e.problemDetail.code != TokenRefreshingClient.tokenRejectedCode) {
          // A sessão vale; a recusa teve outra causa
          return e.detail.isNotEmpty ? e.detail : 'Não foi possível concluir a operação. Tente novamente.';
        }
        return _pendingCreatedPlace != null
            ? 'A sessão foi renovada. Toque em "Reenviar Fotografia e Concluir" para enviar a foto novamente.'
            : 'A sessão foi renovada. Toque em "Cadastrar e Avaliar Local" para tentar novamente.';
      case SessionRefreshOutcome.unavailable:
        return 'Não foi possível verificar sua sessão agora. Tente novamente.';
      case SessionRefreshOutcome.rejected:
        return expired;
    }
  }

  Future<void> _persistPendingOperation(PlaceDetail place, String operationKey, File photo) async {
    final store = _store;
    final owner = _ownerUserId;
    if (store == null || owner == null) return;
    try {
      await store.save(
        owner,
        PendingPlaceRegistration(place: place, uploadIdempotencyKey: operationKey, photoFile: photo, userId: owner),
      );
    } catch (_) {
      // Falha local de armazenamento não substitui o erro do upload exibido ao usuário
    }
  }

  Future<void> _clearPendingOperation(PlaceDetail place) async {
    final store = _store;
    final owner = _ownerUserId;
    if (store == null || owner == null) return;
    try {
      await store.clear(owner, placeId: place.id);
    } catch (_) {
      // A retomada remanescente reaproveita o mesmo local e a mesma chave; nada é duplicado
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    final isPlaceAlreadyCreated = _pendingCreatedPlace != null;

    return PopScope(
      canPop: _canPopNow,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (!_isSubmitting) {
          _handleClose();
        }
      },
      child: Padding(
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
                  key: const Key('manual_place_close_button'),
                  icon: const Icon(Icons.close),
                  tooltip: 'Fechar',
                  onPressed: _isSubmitting ? null : _handleClose,
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
            if (isPlaceAlreadyCreated) ...[
              const SizedBox(height: 12),
              Container(
                key: const Key('manual_place_partial_created_banner'),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: theme.colorScheme.secondaryContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  children: [
                    Icon(
                      Icons.info_outline,
                      color: theme.colorScheme.onSecondaryContainer,
                      size: 20,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Local "${_pendingCreatedPlace!.name}" registrado. Envie a fotografia para concluir.',
                        style: TextStyle(
                          color: theme.colorScheme.onSecondaryContainer,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 16),
            TextFormField(
              key: const Key('manual_place_name_field'),
              controller: _nameController,
              enabled: !_isSubmitting && !isPlaceAlreadyCreated,
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
              enabled: !_isSubmitting && !isPlaceAlreadyCreated,
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
                                _uploadIdempotencyKey = null;
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
                    ? (isPlaceAlreadyCreated
                        ? 'Enviando fotografia...'
                        : 'Cadastrando local...')
                    : (isPlaceAlreadyCreated
                        ? 'Reenviar Fotografia e Concluir'
                        : 'Cadastrar e Avaliar Local'),
              ),
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
