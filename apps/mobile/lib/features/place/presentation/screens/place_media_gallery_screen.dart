import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_media_notifier.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/review_detail/presentation/widgets/authenticated_image.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Argumentos para abertura da galeria de fotografias de um estabelecimento.
class PlaceMediaGalleryArgs {
  final String placeId;
  final String businessAccountId;
  final String placeName;

  const PlaceMediaGalleryArgs({
    required this.placeId,
    required this.businessAccountId,
    required this.placeName,
  });
}

/// Tela de gerenciamento funcional de fotografias e capa de um estabelecimento reivindicado.
class PlaceMediaGalleryScreen extends StatefulWidget {
  final PlaceMediaGalleryArgs args;
  final PlaceRepository? placeRepository;
  final PlaceMediaNotifier? notifier;
  final ReviewMediaRepository? reviewMediaRepository;
  final Future<SelectedMediaItem?> Function(ImageSource source)? onPickPhoto;

  const PlaceMediaGalleryScreen({
    super.key,
    required this.args,
    this.placeRepository,
    this.notifier,
    this.reviewMediaRepository,
    this.onPickPhoto,
  });

  @override
  State<PlaceMediaGalleryScreen> createState() => _PlaceMediaGalleryScreenState();
}

class _PlaceMediaGalleryScreenState extends State<PlaceMediaGalleryScreen> {
  late final PlaceMediaNotifier _notifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.placeRepository != null) {
      _notifier = PlaceMediaNotifier(repository: widget.placeRepository!);
      _ownsNotifier = true;
    } else {
      throw StateError('PlaceRepository ou PlaceMediaNotifier deve ser fornecido.');
    }

    _notifier.addListener(_handleNotifierFeedback);
    _notifier.loadMedia(widget.args.placeId);
  }

  @override
  void dispose() {
    _notifier.removeListener(_handleNotifierFeedback);
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  void _handleNotifierFeedback() {
    if (!mounted) return;

    if (_notifier.successMessage != null) {
      final msg = _notifier.successMessage!;
      _notifier.clearMessages();
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(msg),
          backgroundColor: Colors.green,
          duration: const Duration(seconds: 3),
        ),
      );
    } else if (_notifier.errorMessage != null && !_notifier.isLoading) {
      // Se for erro de carregamento inicial da página (sem fotos), a ErrorView cuida da renderização.
      if (!_notifier.hasInitialLoadError) {
        final err = _notifier.errorMessage!;
        final canRetry = _notifier.hasPendingRetry;
        _notifier.clearMessages();
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(err),
            backgroundColor: Colors.red,
            duration: const Duration(seconds: 5),
            action: canRetry
                ? SnackBarAction(
                    key: const Key('snackbar_retry_upload_action'),
                    label: 'Tentar novamente',
                    textColor: Colors.white,
                    onPressed: () => _notifier.retryUpload(),
                  )
                : null,
          ),
        );
      }
    }
  }

  Future<void> _showPhotoSourcePicker(BuildContext context) async {
    if (_notifier.hasPendingRetry) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Existe um envio pendente. Tente novamente ou descarte a operação antes de adicionar outra foto.',
          ),
          backgroundColor: Colors.orange,
        ),
      );
      return;
    }

    if (_notifier.isLimitReached) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Limite de 5 fotografias já foi atingido.',
          ),
          backgroundColor: Colors.orange,
        ),
      );
      return;
    }

    showModalBottomSheet<void>(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 16.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 8.0),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        'Adicionar Fotografia',
                        style: Theme.of(sheetContext).textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.close),
                        onPressed: () => Navigator.of(sheetContext).pop(),
                      ),
                    ],
                  ),
                ),
                const Divider(),
                ListTile(
                  key: const Key('pick_camera_option'),
                  leading: const Icon(Icons.camera_alt_outlined),
                  title: const Text('Tirar foto com a câmera'),
                  onTap: () {
                    Navigator.of(sheetContext).pop();
                    _pickAndUpload(ImageSource.camera);
                  },
                ),
                ListTile(
                  key: const Key('pick_gallery_option'),
                  leading: const Icon(Icons.photo_library_outlined),
                  title: const Text('Escolher da galeria'),
                  onTap: () {
                    Navigator.of(sheetContext).pop();
                    _pickAndUpload(ImageSource.gallery);
                  },
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Future<void> _pickAndUpload(ImageSource source) async {
    try {
      SelectedMediaItem? selectedItem;

      if (widget.onPickPhoto != null) {
        selectedItem = await widget.onPickPhoto!(source);
      } else {
        final picker = ImagePicker();
        final picked = await picker.pickImage(
          source: source,
          imageQuality: 85,
          maxWidth: 2048,
          maxHeight: 2048,
        );

        if (picked != null) {
          final bytes = await picked.readAsBytes();
          final length = await picked.length();
          final name = picked.name;
          String mime = picked.mimeType ?? '';
          if (mime.isEmpty) {
            mime = name.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';
          }

          selectedItem = SelectedMediaItem(
            id: DateTime.now().microsecondsSinceEpoch.toString(),
            name: name,
            sizeBytes: length,
            mimeType: mime,
            bytes: bytes,
          );
        }
      }

      if (selectedItem == null || !mounted) return;

      await _notifier.uploadMedia(
        placeId: widget.args.placeId,
        bytes: selectedItem.bytes,
        filename: selectedItem.name,
        mimeType: selectedItem.mimeType,
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Falha ao selecionar fotografia. Tente novamente.'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  void _confirmDelete(BuildContext context, PlaceMediaItem photo) {
    showDialog<void>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('Excluir fotografia'),
          content: const Text(
            'Tem certeza de que deseja remover esta fotografia do estabelecimento? Esta ação não pode ser desfeita.',
          ),
          actions: [
            TextButton(
              key: const Key('cancel_delete_button'),
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              key: Key('confirm_delete_button_${photo.id}'),
              style: FilledButton.styleFrom(
                backgroundColor: Theme.of(context).colorScheme.error,
              ),
              onPressed: () {
                Navigator.of(dialogContext).pop();
                _notifier.deleteMedia(
                  placeId: widget.args.placeId,
                  mediaId: photo.id,
                );
              },
              child: const Text('Excluir'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Fotografias e Capa', style: TextStyle(fontSize: 18)),
            Text(
              widget.args.placeName,
              style: TextStyle(
                fontSize: 12,
                color: theme.colorScheme.onSurface.withAlpha(160),
                fontWeight: FontWeight.normal,
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Atualizar',
            onPressed: () => _notifier.loadMedia(widget.args.placeId),
          ),
        ],
      ),
      floatingActionButton: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          final isBusy = _notifier.isUploading || _notifier.operatingMediaId != null;
          final isAddDisabled = isBusy || _notifier.hasPendingRetry;

          return FloatingActionButton.extended(
            key: const Key('add_photo_fab'),
            onPressed: isAddDisabled ? null : () => _showPhotoSourcePicker(context),
            icon: _notifier.isUploading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                  )
                : const Icon(Icons.add_a_photo_outlined),
            label: Text(_notifier.isUploading ? 'Enviando...' : 'Adicionar Foto'),
          );
        },
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          if (_notifier.isLoading) {
            return const LoadingIndicator(message: 'Carregando fotografias...');
          }

          if (_notifier.hasInitialLoadError && _notifier.errorMessage != null) {
            return ErrorView(
              title: 'Erro ao carregar fotografias',
              message: _notifier.errorMessage!,
              onRetry: () => _notifier.loadMedia(widget.args.placeId),
            );
          }

          if (_notifier.mediaList.isEmpty) {
            return _buildEmptyState(context, theme);
          }

          return _buildGalleryView(context, theme);
        },
      ),
    );
  }

  Widget _buildPendingUploadBanner(BuildContext context, ThemeData theme) {
    final pending = _notifier.pendingUpload;
    if (pending == null) return const SizedBox.shrink();

    return Container(
      key: const Key('pending_upload_banner'),
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.amber.shade50,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.amber.shade400),
      ),
      child: Row(
        children: [
          const Icon(Icons.cloud_off_outlined, color: Colors.orange, size: 20),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Envio pendente: ${pending.filename}',
              style: const TextStyle(fontSize: 12, color: Colors.black87),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          TextButton(
            key: const Key('retry_upload_button'),
            onPressed: _notifier.isUploading ? null : () => _notifier.retryUpload(),
            child: const Text('Tentar novamente', style: TextStyle(fontSize: 12)),
          ),
          IconButton(
            key: const Key('cancel_retry_button'),
            icon: const Icon(Icons.close, size: 16),
            tooltip: 'Descartar',
            onPressed: _notifier.isUploading ? null : () => _notifier.cancelPendingUpload(),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context, ThemeData theme) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (_notifier.hasPendingRetry) ...[
              _buildPendingUploadBanner(context, theme),
              const SizedBox(height: 16),
            ],
            Icon(
              Icons.photo_library_outlined,
              size: 64,
              color: theme.colorScheme.primary.withAlpha(120),
            ),
            const SizedBox(height: 16),
            Text(
              'Nenhuma fotografia cadastrada',
              style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Estabelecimentos com fotos recebem mais visitas e confiança dos usuários na rede Rewit. A primeira foto adicionada se tornará automaticamente a capa do local.',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: theme.colorScheme.onSurface.withAlpha(160),
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              key: const Key('empty_add_photo_button'),
              onPressed: (_notifier.isUploading ||
                      _notifier.operatingMediaId != null ||
                      _notifier.hasPendingRetry)
                  ? null
                  : () => _showPhotoSourcePicker(context),
              icon: const Icon(Icons.add_a_photo_outlined),
              label: const Text('Adicionar Primeira Fotografia'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildGalleryView(BuildContext context, ThemeData theme) {
    final mediaList = _notifier.mediaList;

    return SingleChildScrollView(
      padding: const EdgeInsets.only(left: 16, right: 16, top: 16, bottom: 80),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (_notifier.hasPendingRetry)
            _buildPendingUploadBanner(context, theme),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Fotos ativas (${mediaList.length} de ${PlaceMediaNotifier.maxMediaPerPlace})',
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colorScheme.onSurface.withAlpha(180),
                ),
              ),
              if (_notifier.isLimitReached)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: Colors.orange.withAlpha(30),
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: Colors.orange.withAlpha(100)),
                  ),
                  child: const Text(
                    'Limite atingido',
                    style: TextStyle(fontSize: 11, color: Colors.orange, fontWeight: FontWeight.bold),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 12),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: mediaList.length,
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              crossAxisSpacing: 12,
              mainAxisSpacing: 12,
              childAspectRatio: 0.76,
            ),
            itemBuilder: (context, index) {
              final photo = mediaList[index];
              return _buildPhotoCard(context, theme, photo);
            },
          ),
        ],
      ),
    );
  }

  Widget _buildPhotoCard(BuildContext context, ThemeData theme, PlaceMediaItem photo) {
    final isBusy = _notifier.operatingMediaId == photo.id;

    return Card(
      elevation: 0,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: photo.isPrimary
              ? theme.colorScheme.primary
              : theme.colorScheme.outlineVariant.withAlpha(80),
          width: photo.isPrimary ? 2 : 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (widget.reviewMediaRepository != null)
                  AuthenticatedImage(
                    url: photo.url,
                    mediaRepository: widget.reviewMediaRepository!,
                    fit: BoxFit.cover,
                  )
                else
                  Container(
                    color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
                    child: Icon(
                      Icons.image,
                      size: 40,
                      color: theme.colorScheme.primary.withAlpha(120),
                    ),
                  ),
                if (photo.isPrimary)
                  Positioned(
                    top: 8,
                    left: 8,
                    child: Container(
                      key: Key('primary_badge_${photo.id}'),
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: Colors.green,
                        borderRadius: BorderRadius.circular(6),
                        boxShadow: const [
                          BoxShadow(color: Colors.black26, blurRadius: 4, offset: Offset(0, 2)),
                        ],
                      ),
                      child: const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.star, size: 14, color: Colors.white),
                          SizedBox(width: 4),
                          Text(
                            'Capa',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 11,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                if (isBusy)
                  Container(
                    color: Colors.black45,
                    child: const Center(
                      child: SizedBox(
                        width: 28,
                        height: 28,
                        child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
            color: theme.colorScheme.surfaceContainerHighest.withAlpha(40),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                if (!photo.isPrimary)
                  TextButton.icon(
                    key: Key('set_primary_button_${photo.id}'),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      visualDensity: VisualDensity.compact,
                    ),
                    onPressed: isBusy
                        ? null
                        : () => _notifier.setPrimaryMedia(
                              placeId: widget.args.placeId,
                              mediaId: photo.id,
                            ),
                    icon: const Icon(Icons.star_outline, size: 16),
                    label: const Text('Capa', style: TextStyle(fontSize: 12)),
                  )
                else
                  const Padding(
                    padding: EdgeInsets.only(left: 8.0),
                    child: Text(
                      'Foto Principal',
                      style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Colors.green),
                    ),
                  ),
                IconButton(
                  key: Key('delete_media_button_${photo.id}'),
                  icon: Icon(
                    Icons.delete_outline,
                    size: 18,
                    color: isBusy ? null : theme.colorScheme.error,
                  ),
                  tooltip: 'Excluir foto',
                  onPressed: isBusy ? null : () => _confirmDelete(context, photo),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
