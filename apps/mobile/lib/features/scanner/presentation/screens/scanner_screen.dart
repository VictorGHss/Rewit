import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import '../../domain/entities/scanned_code.dart';
import '../../domain/entities/scanner_state.dart';
import '../state/scanner_notifier.dart';
import '../widgets/scanner_overlay.dart';

/// Tela principal do Scanner MVP de Barcode e QR Code (C5.6).
class ScannerScreen extends StatefulWidget {
  final ProductRepository? productRepository;
  final ScannerNotifier? notifier;
  final Widget Function(BuildContext context, void Function(BarcodeCapture) onDetect)? cameraBuilder;
  final bool autoStartCamera;

  const ScannerScreen({
    super.key,
    this.productRepository,
    this.notifier,
    this.cameraBuilder,
    this.autoStartCamera = true,
  });

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> with WidgetsBindingObserver {
  late final ScannerNotifier _notifier;
  bool _ownsNotifier = false;

  MobileScannerController? _cameraController;
  bool _isTorchOn = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    if (widget.notifier != null) {
      _notifier = widget.notifier!;
      _ownsNotifier = false;
    } else {
      _notifier = ScannerNotifier(productRepository: widget.productRepository);
      _ownsNotifier = true;
    }

    if (widget.cameraBuilder == null) {
      _cameraController = MobileScannerController(
        autoStart: widget.autoStartCamera,
        detectionSpeed: DetectionSpeed.normal,
        facing: CameraFacing.back,
        formats: const [
          BarcodeFormat.ean13,
          BarcodeFormat.ean8,
          BarcodeFormat.upcA,
          BarcodeFormat.itf14,
          BarcodeFormat.qrCode,
        ],
      );
    }

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_notifier.state is ScannerInitial) {
        _notifier.startScanning();
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _cameraController?.dispose();
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_cameraController == null) return;
    if (state == AppLifecycleState.inactive || state == AppLifecycleState.paused) {
      _cameraController?.stop();
    } else if (state == AppLifecycleState.resumed && _notifier.state is ScannerScanning) {
      _cameraController?.start();
    }
  }

  Future<void> _toggleTorch() async {
    if (_cameraController == null) return;
    try {
      await _cameraController!.toggleTorch();
      setState(() {
        _isTorchOn = !_isTorchOn;
      });
    } catch (_) {
      // Ignora erro de hardware caso flash não esteja disponível
    }
  }

  Future<void> _switchCamera() async {
    if (_cameraController == null) return;
    try {
      await _cameraController!.switchCamera();
    } catch (_) {
      // Ignora erro caso não haja câmera secundária
    }
  }

  void _onBarcodeDetected(BarcodeCapture capture) {
    if (capture.barcodes.isEmpty) return;
    final barcode = capture.barcodes.first;
    final rawValue = barcode.rawValue;
    if (rawValue == null || rawValue.isEmpty) return;

    _notifier.onCodeDetected(rawValue, format: barcode.format);
  }

  Future<void> _navigateToProductDetail(String productId) async {
    _cameraController?.stop();
    await Navigator.of(context).pushNamed(
      AppRouter.productDetail,
      arguments: productId,
    );
    if (mounted) {
      _notifier.resumeScanning();
      _cameraController?.start();
    }
  }

  Future<void> _navigateToSearch() async {
    _cameraController?.stop();
    await Navigator.of(context).pushNamed(AppRouter.search);
    if (mounted) {
      _notifier.resumeScanning();
      _cameraController?.start();
    }
  }

  Future<void> _navigateToInternalResource(InternalResource resource) async {
    _cameraController?.stop();
    switch (resource.type) {
      case InternalResourceType.place:
        await Navigator.of(context).pushNamed(
          AppRouter.placeDetail,
          arguments: resource.id,
        );
        break;
      case InternalResourceType.product:
      case InternalResourceType.productLookup:
        // Nota: QR codes do tipo productLookup normalmente disparam a consulta
        // automática no ScannerNotifier antes de exibir o card de produto encontrado.
        // O tratamento aqui é preservado como fallback defensivo caso chegue como ScannerQrInternal.
        await Navigator.of(context).pushNamed(
          AppRouter.productDetail,
          arguments: resource.id,
        );
        break;
      case InternalResourceType.review:
        await Navigator.of(context).pushNamed(
          AppRouter.reviewDetail,
          arguments: resource.id,
        );
        break;
    }
    if (mounted) {
      _notifier.resumeScanning();
      _cameraController?.start();
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('Escanear Código'),
        backgroundColor: Colors.black.withAlpha(200),
        foregroundColor: Colors.white,
        actions: [
          IconButton(
            icon: Icon(_isTorchOn ? Icons.flash_on : Icons.flash_off),
            tooltip: _isTorchOn ? 'Desativar flash' : 'Ativar flash',
            onPressed: _toggleTorch,
          ),
          IconButton(
            icon: const Icon(Icons.flip_camera_ios),
            tooltip: 'Alternar câmera',
            onPressed: _switchCamera,
          ),
        ],
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          final state = _notifier.state;

          if (state is ScannerPermissionDenied) {
            return _buildPermissionDeniedView(context, state);
          }

          return Stack(
            fit: StackFit.expand,
            children: [
              // 1. Câmera
              if (widget.cameraBuilder != null)
                widget.cameraBuilder!(context, _onBarcodeDetected)
              else if (_cameraController != null)
                MobileScanner(
                  controller: _cameraController,
                  onDetect: _onBarcodeDetected,
                  errorBuilder: (context, error) {
                    if (error.errorCode == MobileScannerErrorCode.permissionDenied) {
                      if (_notifier.state is! ScannerPermissionDenied) {
                        WidgetsBinding.instance.addPostFrameCallback((_) {
                          if (mounted && _notifier.state is! ScannerPermissionDenied) {
                            _notifier.onPermissionDenied(permanentlyDenied: false);
                          }
                        });
                      }
                      return _buildPermissionDeniedView(
                        context,
                        const ScannerPermissionDenied(permanentlyDenied: false),
                      );
                    }
                    return Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24.0),
                        child: Text(
                          'Erro na câmera: ${error.errorDetails?.message ?? error.errorCode.name}',
                          textAlign: TextAlign.center,
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                    );
                  },
                )
              else
                const Center(
                  child: Text(
                    'Câmera não inicializada.',
                    style: TextStyle(color: Colors.white),
                  ),
                ),

              // 2. Mira de mira e orientações
              if (state is ScannerScanning || state is ScannerInitial)
                const ScannerOverlay(),

              // 3. Modais/Cards de Feedback ancorados na parte inferior
              _buildBottomFeedbackCard(context, state, theme),
            ],
          );
        },
      ),
    );
  }

  Widget _buildBottomFeedbackCard(
    BuildContext context,
    ScannerState state,
    ThemeData theme,
  ) {
    if (state is ScannerProcessing) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 10)],
          ),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              ),
              SizedBox(width: 16),
              Text(
                'Consultando catálogo...',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
              ),
            ],
          ),
        ),
      );
    }

    if (state is ScannerFound) {
      final product = state.product;
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 12)],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.check_circle, color: Colors.green.shade600, size: 24),
                  const SizedBox(width: 8),
                  Text(
                    'Produto encontrado',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: Colors.green.shade800,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                product.displayName,
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              if (product.brand.isNotEmpty &&
                  !product.name.toLowerCase().contains(product.brand.toLowerCase())) ...[
                const SizedBox(height: 4),
                Text(
                  'Marca: ${product.brand}',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurface.withAlpha(180),
                  ),
                ),
              ],
              if (product.category.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(
                  'Categoria: ${product.category}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurface.withAlpha(150),
                  ),
                ),
              ],
              const SizedBox(height: 18),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => _notifier.resumeScanning(),
                      child: const Text('Escanear novamente'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: () => _navigateToProductDetail(product.id),
                      icon: const Icon(Icons.shopping_bag_outlined, size: 18),
                      label: const Text('Ver produto'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    if (state is ScannerNotFound) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 12)],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.search_off_outlined, color: Colors.orange.shade700, size: 24),
                  const SizedBox(width: 8),
                  Text(
                    'Produto não encontrado',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: Colors.orange.shade900,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Text(
                'O código foi lido corretamente, mas este produto ainda não está no catálogo do Rewit.',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurface.withAlpha(200),
                ),
              ),
              const SizedBox(height: 16),
              ElevatedButton.icon(
                onPressed: _navigateToSearch,
                icon: const Icon(Icons.search, size: 18),
                label: const Text('Buscar pelo nome'),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => _notifier.resumeScanning(),
                      child: const Text('Escanear novamente'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Voltar'),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    if (state is ScannerUnsupported) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 12)],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.warning_amber_rounded, color: theme.colorScheme.error, size: 24),
                  const SizedBox(width: 8),
                  Text(
                    'Formato não suportado',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: theme.colorScheme.error,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                state.message,
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: () => _notifier.resumeScanning(),
                child: const Text('Escanear novamente'),
              ),
            ],
          ),
        ),
      );
    }

    if (state is ScannerQrExternal) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 12)],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.qr_code_2_outlined, color: theme.colorScheme.primary, size: 24),
                  const SizedBox(width: 8),
                  Text(
                    'QR Code detectado',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Text(
                'Conteúdo não reconhecido pelo Rewit.',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurface.withAlpha(200),
                ),
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => _notifier.resumeScanning(),
                      child: const Text('Escanear novamente'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Voltar'),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    if (state is ScannerQrInternal) {
      final res = state.resource;
      String typeLabel;
      switch (res.type) {
        case InternalResourceType.place:
          typeLabel = 'Local Físico';
          break;
        case InternalResourceType.product:
        case InternalResourceType.productLookup:
          typeLabel = 'Produto';
          break;
        case InternalResourceType.review:
          typeLabel = 'Avaliação';
          break;
      }

      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 12)],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.link, color: theme.colorScheme.primary, size: 24),
                  const SizedBox(width: 8),
                  Text(
                    'Recurso Rewit Reconhecido',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '$typeLabel identificado: ${res.id}',
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => _notifier.resumeScanning(),
                      child: const Text('Escanear novamente'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: ElevatedButton(
                      onPressed: () => _navigateToInternalResource(res),
                      child: const Text('Abrir recurso'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    if (state is ScannerError) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [BoxShadow(color: Colors.black38, blurRadius: 12)],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.error_outline, color: theme.colorScheme.error, size: 24),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      state.isRateLimit ? 'Limite de Consultas' : 'Falha na Consulta',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: theme.colorScheme.error,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                state.message,
                style: theme.textTheme.bodyMedium,
              ),
              if (state.retryAfterSeconds != null) ...[
                const SizedBox(height: 4),
                Text(
                  'Tente novamente em ${state.retryAfterSeconds} segundos.',
                  style: theme.textTheme.bodySmall?.copyWith(color: Colors.grey.shade600),
                ),
              ],
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => _notifier.resumeScanning(),
                      child: const Text('Escanear novamente'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Voltar'),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    return const SizedBox.shrink();
  }

  Widget _buildPermissionDeniedView(
    BuildContext context,
    ScannerPermissionDenied state,
  ) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.no_photography_outlined,
              size: 64,
              color: Colors.white70,
            ),
            const SizedBox(height: 16),
            const Text(
              'Acesso à câmera necessário',
              style: TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              state.permanentlyDenied
                  ? 'A permissão de acesso à câmera foi negada permanentemente. Para escanear códigos, habilite a câmera nas configurações do dispositivo.'
                  : 'Para escanear códigos de barras e QR Codes de produtos, o Rewit precisa de permissão de acesso à câmera.',
              style: const TextStyle(color: Colors.white70, fontSize: 14),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            if (!state.permanentlyDenied) ...[
              ElevatedButton(
                onPressed: () {
                  _notifier.startScanning();
                  _cameraController?.start();
                },
                child: const Text('Tentar novamente'),
              ),
              const SizedBox(height: 12),
            ],
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text(
                'Voltar',
                style: TextStyle(color: Colors.white70),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
