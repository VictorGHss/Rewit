import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';

/// Widget seguro para renderização de imagens privadas que exigem Bearer Token.
///
/// Obtém os bytes da imagem através do [ReviewMediaRepository] com headers de autenticação,
/// sem vazar tokens em query parameters, e renderiza via [Image.memory].
class AuthenticatedImage extends StatefulWidget {
  final String url;
  final ReviewMediaRepository mediaRepository;
  final BoxFit fit;
  final double? width;
  final double? height;
  final BorderRadius? borderRadius;
  final Widget? placeholder;
  final Widget Function(BuildContext context, Object error, VoidCallback onRetry)? errorBuilder;

  const AuthenticatedImage({
    super.key,
    required this.url,
    required this.mediaRepository,
    this.fit = BoxFit.cover,
    this.width,
    this.height,
    this.borderRadius,
    this.placeholder,
    this.errorBuilder,
  });

  @override
  State<AuthenticatedImage> createState() => _AuthenticatedImageState();
}

class _AuthenticatedImageState extends State<AuthenticatedImage> {
  Uint8List? _bytes;
  bool _isLoading = true;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _loadImage();
  }

  @override
  void didUpdateWidget(covariant AuthenticatedImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      _loadImage();
    }
  }

  Future<void> _loadImage() async {
    if (!mounted) return;
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final bytes = await widget.mediaRepository.getMediaBytes(widget.url);
      if (!mounted) return;
      setState(() {
        _bytes = bytes;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    Widget content;

    if (_isLoading) {
      content = widget.placeholder ??
          Container(
            width: widget.width,
            height: widget.height,
            color: Theme.of(context).colorScheme.surfaceContainerHighest.withAlpha(100),
            child: const Center(
              child: SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          );
    } else if (_error != null || _bytes == null) {
      if (widget.errorBuilder != null) {
        content = widget.errorBuilder!(context, _error ?? Exception('Erro ao carregar imagem'), _loadImage);
      } else {
        content = Container(
          width: widget.width,
          height: widget.height,
          color: Theme.of(context).colorScheme.surfaceContainerHighest.withAlpha(100),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.broken_image_outlined,
                  size: 28,
                  color: Theme.of(context).colorScheme.error,
                ),
                const SizedBox(height: 4),
                IconButton(
                  icon: const Icon(Icons.refresh, size: 20),
                  tooltip: 'Tentar novamente',
                  onPressed: _loadImage,
                ),
              ],
            ),
          ),
        );
      }
    } else {
      content = Image.memory(
        _bytes!,
        width: widget.width,
        height: widget.height,
        fit: widget.fit,
      );
    }

    if (widget.borderRadius != null) {
      return ClipRRect(
        borderRadius: widget.borderRadius!,
        child: content,
      );
    }

    return content;
  }
}
