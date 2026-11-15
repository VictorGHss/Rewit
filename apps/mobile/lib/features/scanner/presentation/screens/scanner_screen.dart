import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import '../../data/services/android_camera_permission_service.dart';
import '../../domain/entities/scanned_code.dart';
import '../../domain/entities/scanner_state.dart';
import '../../domain/services/camera_permission_service.dart';
import '../state/scanner_notifier.dart';
import '../widgets/scanner_overlay.dart';

/// Tela principal do Scanner MVP de Barcode e QR Code (C5.6).
class ScannerScreen extends StatefulWidget {
  final ProductRepository? productRepository;
  final PlaceRepository? placeRepository;
  final bool selectionMode;
  final ScannerNotifier? notifier;
  final Widget Function(
    BuildContext context,
    void Function(BarcodeCapture) onDetect,
  )? cameraBuilder;
  final bool autoStartCamera;
  final CameraPermissionService? cameraPermissionService;

  const ScannerScreen({
    super.key,
    this.productRepository,
    this.placeRepository,
    this.selectionMode = false,
    this.notifier,
    this.cameraBuilder,
    this.autoStartCamera = true,
    this.cameraPermissionService,
  });

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen>
    with WidgetsBindingObserver {
  late final ScannerNotifier _notifier;
  bool _ownsNotifier = false;
  late final CameraPermissionService _cameraPermissionService;

  MobileScannerController? _cameraController;
  bool _isTorchOn = false;
  bool _isClosing = false;
  bool _isPermissionFlowInProgress = false;
  bool _cameraStoppedByLifecycle = false;
  CameraPermissionStatus _lastKnownPermissionStatus =
      CameraPermissionStatus.unknown;

  bool get _isSelectionMode => widget.selectionMode || _notifier.selectionMode;

  MobileScannerController _createCameraController() {
    return MobileScannerController(
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

  void _ensureCleanCameraController() {
    final current = _cameraController;
    if (widget.cameraBuilder != null || current == null) return;
    if (current.value.error == null) return;

    // Substitui o controller caso tenha falhado anteriormente (ex.: erro de permissão),
    // evitando que o erro obsoleto dispare errorBuilder ao remontar MobileScanner.
    _cameraController = _createCameraController();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(current.dispose());
    });
    if (mounted) {
      setState(() {});
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _cameraPermissionService =
        widget.cameraPermissionService ?? const AndroidCameraPermissionService();

    if (widget.notifier != null) {
      _notifier = widget.notifier!;
      _ownsNotifier = false;
    } else {
      _notifier = ScannerNotifier(
        productRepository: widget.productRepository,
        placeRepository: widget.placeRepository,
        selectionMode: widget.selectionMode,
      );
      _ownsNotifier = true;
    }

    if (widget.cameraBuilder == null) {
      _cameraController = _createCameraController();
    }

    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_initializePermissionsAndCamera());
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
    if (!mounted || _isClosing) return;

    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      if (_cameraController != null && _notifier.state is ScannerScanning) {
        _cameraStoppedByLifecycle = true;
      }
      _cameraController?.stop();
    } else if (state == AppLifecycleState.resumed) {
      if (_isPermissionFlowInProgress) return;
      if (_notifier.state is ScannerPermissionDenied ||
          _notifier.state is ScannerPermissionRationale) {
        unawaited(_recheckPermissionOnResume());
      } else if (_notifier.state is ScannerScanning &&
          _cameraStoppedByLifecycle) {
        _cameraStoppedByLifecycle = false;
        unawaited(_recheckPermissionAndResumeCamera());
      }
    }
  }

  Future<void> _initializePermissionsAndCamera() async {
    if (!mounted || _isClosing) return;
    if (_notifier.state is! ScannerInitial) return;

    if (widget.cameraPermissionService == null &&
        (widget.cameraBuilder != null || !widget.autoStartCamera)) {
      _startScanning();
      return;
    }

    _isPermissionFlowInProgress = true;
    try {
      var status = await _cameraPermissionService.checkPermissionStatus();
      if (!mounted || _isClosing || _notifier.state is! ScannerInitial) return;

      if (status == CameraPermissionStatus.denied) {
        final hasRequestedBefore =
            await _cameraPermissionService.hasRequestedPermissionBefore();
        if (!mounted || _isClosing || _notifier.state is! ScannerInitial) {
          return;
        }
        if (!hasRequestedBefore) {
          _lastKnownPermissionStatus = status;
          _notifier.onPermissionRationaleRequired();
          return;
        }
        status = await _cameraPermissionService.requestPermission();
        if (!mounted || _isClosing || _notifier.state is! ScannerInitial) {
          return;
        }
      }

      _applyPermissionResolution(status);
    } finally {
      _isPermissionFlowInProgress = false;
    }
  }

  Future<void> _onContinueFromRationalePressed() async {
    if (!mounted || _isClosing || _isPermissionFlowInProgress) return;
    if (_notifier.state is! ScannerPermissionRationale) return;

    _isPermissionFlowInProgress = true;
    try {
      var status = await _cameraPermissionService.checkPermissionStatus();
      if (!mounted || _isClosing) return;

      if (status == CameraPermissionStatus.denied) {
        status = await _cameraPermissionService.requestPermission();
        if (!mounted || _isClosing) return;
      }

      _applyPermissionResolution(status);
    } finally {
      _isPermissionFlowInProgress = false;
    }
  }

  Future<void> _onRetryPermissionPressed() async {
    if (!mounted || _isClosing || _isPermissionFlowInProgress) return;

    _isPermissionFlowInProgress = true;
    try {
      var status = await _cameraPermissionService.checkPermissionStatus();
      if (!mounted || _isClosing) return;

      if (status == CameraPermissionStatus.denied) {
        status = await _cameraPermissionService.requestPermission();
        if (!mounted || _isClosing) return;
      }

      _applyPermissionResolution(status);
    } finally {
      _isPermissionFlowInProgress = false;
    }
  }

  Future<void> _onOpenAppSettingsPressed() async {
    if (!mounted || _isClosing) return;

    final opened = await _cameraPermissionService.openAppSettings();
    if (!mounted || _isClosing) return;

    if (!opened) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(
          const SnackBar(
            content: Text(
              'Não foi possível abrir as configurações do dispositivo.',
            ),
            duration: Duration(seconds: 3),
          ),
        );
    }
  }

  Future<void> _recheckPermissionOnResume() async {
    if (!mounted || _isClosing || _isPermissionFlowInProgress) return;

    _isPermissionFlowInProgress = true;
    try {
      final status = await _cameraPermissionService.checkPermissionStatus();
      if (!mounted || _isClosing) return;
      final currentState = _notifier.state;
      if (currentState is! ScannerPermissionDenied &&
          currentState is! ScannerPermissionRationale) {
        return;
      }

      _lastKnownPermissionStatus = status;

      if (status == CameraPermissionStatus.granted) {
        _cameraStoppedByLifecycle = false;
        _ensureCleanCameraController();
        _startScanning();
      } else if (status == CameraPermissionStatus.permanentlyDenied) {
        _setPermissionDeniedState(permanentlyDenied: true);
      } else if (status == CameraPermissionStatus.denied) {
        if (currentState is ScannerPermissionRationale) {
          final hasRequestedBefore =
              await _cameraPermissionService.hasRequestedPermissionBefore();
          if (!mounted || _isClosing) return;
          if (!hasRequestedBefore) {
            return;
          }
        }
        _setPermissionDeniedState(permanentlyDenied: false);
      }
    } finally {
      _isPermissionFlowInProgress = false;
    }
  }

  Future<void> _recheckPermissionAndResumeCamera() async {
    if (!mounted || _isClosing || _isPermissionFlowInProgress) return;

    final status = await _cameraPermissionService.checkPermissionStatus();
    if (!mounted || _isClosing) return;
    if (_notifier.state is! ScannerScanning) return;

    _lastKnownPermissionStatus = status;
    if (status == CameraPermissionStatus.denied ||
        status == CameraPermissionStatus.permanentlyDenied) {
      _setPermissionDeniedState(
        permanentlyDenied: status == CameraPermissionStatus.permanentlyDenied,
      );
      return;
    }

    await _safeStartCamera();
  }

  Future<void> _handlePermissionDeniedFromScannerError() async {
    if (!mounted || _isClosing) return;

    if (_notifier.state is! ScannerPermissionDenied) {
      _setPermissionDeniedState(
        permanentlyDenied: _lastKnownPermissionStatus ==
            CameraPermissionStatus.permanentlyDenied,
      );
    }

    if (widget.cameraPermissionService == null && !widget.autoStartCamera) {
      return;
    }

    final status = await _cameraPermissionService.checkPermissionStatus(
      afterRequest: true,
    );
    if (!mounted || _isClosing) return;

    _lastKnownPermissionStatus = status;

    if (status == CameraPermissionStatus.granted) {
      _ensureCleanCameraController();
      _startScanning();
      return;
    }

    if (status == CameraPermissionStatus.permanentlyDenied ||
        status == CameraPermissionStatus.denied) {
      _setPermissionDeniedState(
        permanentlyDenied: status == CameraPermissionStatus.permanentlyDenied,
      );
    }
  }

  void _applyPermissionResolution(CameraPermissionStatus status) {
    if (!mounted || _isClosing) return;
    _lastKnownPermissionStatus = status;

    switch (status) {
      case CameraPermissionStatus.granted:
      case CameraPermissionStatus.unknown:
        _cameraStoppedByLifecycle = false;
        _ensureCleanCameraController();
        _startScanning();
      case CameraPermissionStatus.denied:
        _setPermissionDeniedState(permanentlyDenied: false);
      case CameraPermissionStatus.permanentlyDenied:
        _setPermissionDeniedState(permanentlyDenied: true);
    }
  }

  void _setPermissionDeniedState({required bool permanentlyDenied}) {
    if (!mounted || _isClosing) return;
    final current = _notifier.state;
    if (current is ScannerPermissionDenied &&
        current.permanentlyDenied == permanentlyDenied) {
      return;
    }
    _cameraStoppedByLifecycle = false;
    _cameraController?.stop();
    _notifier.onPermissionDenied(permanentlyDenied: permanentlyDenied);
  }

  Future<void> _safeStartCamera() async {
    final controller = _cameraController;
    if (controller == null ||
        !mounted ||
        _isClosing ||
        !widget.autoStartCamera) {
      return;
    }
    if (controller.value.isRunning || controller.value.isStarting) {
      return;
    }
    try {
      await controller.start();
    } on MobileScannerException catch (error) {
      if (!mounted || _isClosing) return;
      if (error.errorCode == MobileScannerErrorCode.permissionDenied) {
        await _handlePermissionDeniedFromScannerError();
      }
    } catch (_) {
      // Ignora falhas transitórias de ciclo de vida já tratadas na UI.
    }
  }

  Future<void> _toggleTorch() async {
    if (_cameraController == null || !mounted || _isClosing) return;
    try {
      await _cameraController!.toggleTorch();
      if (!mounted || _isClosing) return;
      setState(() {
        _isTorchOn = !_isTorchOn;
      });
    } catch (_) {
      // Ignora erro de hardware caso flash não esteja disponível
    }
  }

  Future<void> _switchCamera() async {
    if (_cameraController == null || !mounted || _isClosing) return;
    try {
      await _cameraController!.switchCamera();
    } catch (_) {
      // Ignora erro caso não haja câmera secundária
    }
  }

  void _startScanning() {
    if (!mounted || _isClosing) return;
    _notifier.startScanning();
  }

  void _resumeScanning() {
    if (!mounted || _isClosing) return;
    _notifier.resumeScanning();
  }

  void _onBarcodeDetected(BarcodeCapture capture) {
    if (!mounted || _isClosing) return;
    if (capture.barcodes.isEmpty) return;
    final barcode = capture.barcodes.first;
    final rawValue = barcode.rawValue;
    if (rawValue == null || rawValue.isEmpty) return;

    _notifier.onCodeDetected(rawValue, format: barcode.format);
  }

  void _closeRoute([SearchResultItem? result]) {
    if (!mounted || _isClosing) return;
    final route = ModalRoute.of(context);
    if (route != null && !route.isCurrent) return;
    final navigator = Navigator.of(context);
    if (!navigator.canPop()) return;
    _isClosing = true;
    _cameraController?.stop();
    navigator.pop(result);
  }

  void _showInvalidSelectionSnackBar(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          duration: const Duration(seconds: 2),
        ),
      );
  }

  void _selectProduct(ProductDetail product) {
    final id = product.id.trim();
    final name = product.name.trim();
    if (id.isEmpty || name.isEmpty) {
      _showInvalidSelectionSnackBar(
        'Produto com dados inválidos para seleção.',
      );
      return;
    }
    final category = product.category.trim();
    _closeRoute(
      SearchResultItem(
        id: id,
        name: name,
        category: category.isEmpty ? null : category,
        targetType: TargetType.product,
        rawTargetType: 'PRODUCT',
        status: product.status,
      ),
    );
  }

  void _selectPlace(PlaceDetail place) {
    final id = place.id.trim();
    final name = place.name.trim();
    if (id.isEmpty || name.isEmpty) {
      _showInvalidSelectionSnackBar(
        'Local com dados inválidos para seleção.',
      );
      return;
    }
    final slug = place.slug.trim();
    final category = place.category.trim();
    _closeRoute(
      SearchResultItem(
        id: id,
        name: name,
        slug: slug.isEmpty ? null : slug,
        category: category.isEmpty ? null : category,
        targetType: TargetType.place,
        rawTargetType: 'PLACE',
        status: place.status,
      ),
    );
  }

  Future<void> _navigateToProductDetail(String productId) async {
    if (_isSelectionMode || _isClosing) return;
    _cameraController?.stop();
    await Navigator.of(context).pushNamed(
      AppRouter.productDetail,
      arguments: productId,
    );
    if (mounted && !_isClosing) {
      _resumeScanning();
      unawaited(_safeStartCamera());
    }
  }

  Future<void> _navigateToSearch() async {
    if (_isSelectionMode || _isClosing) return;
    _cameraController?.stop();
    await Navigator.of(context).pushNamed(AppRouter.search);
    if (mounted && !_isClosing) {
      _resumeScanning();
      unawaited(_safeStartCamera());
    }
  }

  Future<void> _navigateToInternalResource(InternalResource resource) async {
    if (_isSelectionMode || _isClosing) return;
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
    if (mounted && !_isClosing) {
      _resumeScanning();
      unawaited(_safeStartCamera());
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final semantic = RewitSemanticColors.of(context);
    final route = ModalRoute.of(context);
    final canDismiss = route?.impliesAppBarDismissal ?? false;
    final isFullscreenDialog =
        route is PageRoute<dynamic> && route.fullscreenDialog;

    // Superfície base da câmera permanece escura independentemente do tema para
    // enquadrar o vídeo ao vivo e garantir contraste das miras sobre a lente.
    return PopScope<Object?>(
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) {
          _isClosing = true;
          _cameraController?.stop();
        }
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(
          title: const Text('Escanear Código'),
          backgroundColor: semantic.mediaScrim,
          foregroundColor: semantic.onMediaScrim,
          systemOverlayStyle: SystemUiOverlayStyle.light,
          iconTheme: IconThemeData(color: semantic.onMediaScrim),
          actionsIconTheme: IconThemeData(color: semantic.onMediaScrim),
          titleTextStyle: Theme.of(context).appBarTheme.titleTextStyle?.copyWith(
                color: semantic.onMediaScrim,
              ) ??
              TextStyle(
                color: semantic.onMediaScrim,
                fontSize: 18,
                fontWeight: FontWeight.w600,
              ),
          automaticallyImplyLeading: false,
          leading: canDismiss
              ? (isFullscreenDialog
                  ? CloseButton(onPressed: () => _closeRoute())
                  : BackButton(onPressed: () => _closeRoute()))
              : null,
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

            if (state is ScannerPermissionRationale) {
              return _buildPermissionRationaleView(context);
            }

            if (state is ScannerPermissionDenied) {
              return _buildPermissionDeniedView(context, state);
            }

            return Stack(
              fit: StackFit.expand,
              children: [
                // 1. Câmera
                if (widget.cameraBuilder != null)
                  widget.cameraBuilder!(context, _onBarcodeDetected)
                else if (state is ScannerInitial && widget.autoStartCamera)
                  const ColoredBox(color: Colors.black)
                else if (_cameraController != null)
                  MobileScanner(
                    controller: _cameraController,
                    onDetect: _onBarcodeDetected,
                    errorBuilder: (context, error) {
                      if (error.errorCode ==
                          MobileScannerErrorCode.permissionDenied) {
                        if (_notifier.state is! ScannerPermissionDenied) {
                          WidgetsBinding.instance.addPostFrameCallback((_) {
                            if (mounted &&
                                !_isClosing &&
                                _notifier.state is! ScannerPermissionDenied) {
                              unawaited(
                                _handlePermissionDeniedFromScannerError(),
                              );
                            }
                          });
                        }
                        return _buildPermissionDeniedView(
                          context,
                          ScannerPermissionDenied(
                            permanentlyDenied: _lastKnownPermissionStatus ==
                                CameraPermissionStatus.permanentlyDenied,
                          ),
                        );
                      }
                      return Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24.0),
                          child: Text(
                            'Erro na câmera: ${error.errorDetails?.message ?? error.errorCode.name}',
                            textAlign: TextAlign.center,
                            style: TextStyle(color: semantic.onMediaScrim),
                          ),
                        ),
                      );
                    },
                  )
                else
                  Center(
                    child: Text(
                      'Câmera não inicializada.',
                      style: TextStyle(color: semantic.onMediaScrim),
                    ),
                  ),

                // 2. Mira de captura e orientações sobre o feed de câmera
                if (state is ScannerScanning || state is ScannerInitial)
                  const ScannerOverlay(),

                // 3. Modais/Cards de Feedback ancorados na parte inferior
                _buildBottomFeedbackCard(context, state, theme, semantic),
              ],
            );
          },
        ),
      ),
    );
  }

  BoxDecoration _feedbackCardDecoration(
    ColorScheme scheme,
    RewitSemanticColors semantic,
  ) {
    return BoxDecoration(
      color: semantic.elevatedFocalSurface,
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: scheme.outlineVariant),
      boxShadow: [
        BoxShadow(
          color: scheme.shadow.withAlpha(80),
          blurRadius: 12,
          offset: const Offset(0, 4),
        ),
      ],
    );
  }

  Widget _buildFeedbackCard(
    ColorScheme scheme,
    RewitSemanticColors semantic,
    List<Widget> children,
  ) {
    return Align(
      alignment: Alignment.bottomCenter,
      child: Container(
        margin: const EdgeInsets.all(16),
        padding: const EdgeInsets.all(20),
        decoration: _feedbackCardDecoration(scheme, semantic),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: children,
        ),
      ),
    );
  }

  Widget _buildBottomFeedbackCard(
    BuildContext context,
    ScannerState state,
    ThemeData theme,
    RewitSemanticColors semantic,
  ) {
    final scheme = theme.colorScheme;

    if (state is ScannerProcessing) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
          decoration: _feedbackCardDecoration(scheme, semantic),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              ),
              const SizedBox(width: 16),
              Text(
                'Consultando catálogo...',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: scheme.onSurface,
                ),
              ),
            ],
          ),
        ),
      );
    }

    if (state is ScannerFound) {
      final product = state.product;
      return _buildFeedbackCard(scheme, semantic, [
        Row(
          children: [
            Icon(
              Icons.check_circle,
              color: semantic.success.accent,
              size: 24,
            ),
            const SizedBox(width: 8),
            Text(
              'Produto encontrado',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
                color: semantic.success.accent,
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
            !product.name
                .toLowerCase()
                .contains(product.brand.toLowerCase())) ...[
          const SizedBox(height: 4),
          Text(
            'Marca: ${product.brand}',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
        if (product.category.isNotEmpty) ...[
          const SizedBox(height: 2),
          Text(
            'Categoria: ${product.category}',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
        const SizedBox(height: 18),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: _resumeScanning,
                child: const Text('Escanear novamente'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: ElevatedButton.icon(
                onPressed: _isSelectionMode
                    ? () => _selectProduct(product)
                    : () => _navigateToProductDetail(product.id),
                icon: Icon(
                  _isSelectionMode
                      ? Icons.check
                      : Icons.shopping_bag_outlined,
                  size: 18,
                ),
                label: Text(
                  _isSelectionMode ? 'Selecionar produto' : 'Ver produto',
                ),
              ),
            ),
          ],
        ),
      ]);
    }

    if (state is ScannerPlaceFound) {
      final place = state.place;
      return _buildFeedbackCard(scheme, semantic, [
        Row(
          children: [
            Icon(
              Icons.place_outlined,
              color: semantic.success.accent,
              size: 24,
            ),
            const SizedBox(width: 8),
            Text(
              'Local encontrado',
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
                color: semantic.success.accent,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Text(
          place.name,
          style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.bold,
          ),
        ),
        if (place.category.trim().isNotEmpty) ...[
          const SizedBox(height: 4),
          Text(
            'Categoria: ${place.category}',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
        if (place.addressText.trim().isNotEmpty) ...[
          const SizedBox(height: 2),
          Text(
            place.addressText,
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
        const SizedBox(height: 18),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: _resumeScanning,
                child: const Text('Escanear novamente'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: ElevatedButton.icon(
                onPressed: () => _selectPlace(place),
                icon: const Icon(Icons.check, size: 18),
                label: const Text('Selecionar local'),
              ),
            ),
          ],
        ),
      ]);
    }

    if (state is ScannerInvalidSelectionTarget) {
      return _buildFeedbackCard(scheme, semantic, [
        Row(
          children: [
            Icon(
              Icons.block_outlined,
              color: semantic.warning.accent,
              size: 24,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Alvo não selecionável',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: semantic.warning.accent,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Text(
          state.message,
          style: theme.textTheme.bodyMedium?.copyWith(
            color: scheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: _resumeScanning,
                child: const Text('Escanear novamente'),
              ),
            ),
            const SizedBox(width: 8),
            TextButton(
              onPressed: () => _closeRoute(),
              child: const Text('Voltar'),
            ),
          ],
        ),
      ]);
    }

    if (state is ScannerNotFound) {
      final notFoundTitle = state.isPlace
          ? 'Local não encontrado'
          : 'Produto não encontrado';
      final notFoundDescription = state.isPlace
          ? 'Não encontramos este local cadastrado no catálogo do Rewit.'
          : _isSelectionMode
              ? 'O código foi lido corretamente, mas este produto não foi encontrado no catálogo do Rewit.'
              : 'O código foi lido corretamente, mas este produto ainda não está no catálogo do Rewit.';

      return _buildFeedbackCard(scheme, semantic, [
        Row(
          children: [
            Icon(
              Icons.search_off_outlined,
              color: semantic.warning.accent,
              size: 24,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                notFoundTitle,
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: semantic.warning.accent,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Text(
          notFoundDescription,
          style: theme.textTheme.bodyMedium?.copyWith(
            color: scheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 16),
        if (_isSelectionMode) ...[
          ElevatedButton.icon(
            onPressed: () => _closeRoute(),
            icon: const Icon(Icons.arrow_back, size: 18),
            label: const Text('Voltar para busca'),
          ),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: _resumeScanning,
            child: const Text('Escanear novamente'),
          ),
        ] else ...[
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
                  onPressed: _resumeScanning,
                  child: const Text('Escanear novamente'),
                ),
              ),
              const SizedBox(width: 8),
              TextButton(
                onPressed: () => _closeRoute(),
                child: const Text('Voltar'),
              ),
            ],
          ),
        ],
      ]);
    }

    if (state is ScannerUnsupported) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: _feedbackCardDecoration(scheme, semantic),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(
                    Icons.warning_amber_rounded,
                    color: scheme.error,
                    size: 24,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    'Formato não suportado',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: scheme.error,
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
                onPressed: _resumeScanning,
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
          decoration: _feedbackCardDecoration(scheme, semantic),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(
                    Icons.qr_code_2_outlined,
                    color: scheme.primary,
                    size: 24,
                  ),
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
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: _resumeScanning,
                      child: const Text('Escanear novamente'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  TextButton(
                    onPressed: () => _closeRoute(),
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

      if (_isSelectionMode) {
        return _buildFeedbackCard(scheme, semantic, [
          Row(
            children: [
              Icon(Icons.block_outlined, color: semantic.warning.accent, size: 24),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Alvo não selecionável',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: semantic.warning.accent,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Este QR Code ($typeLabel) não pôde ser selecionado diretamente como alvo.',
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: _resumeScanning,
                  child: const Text('Escanear novamente'),
                ),
              ),
              const SizedBox(width: 8),
              TextButton(
                onPressed: () => _closeRoute(),
                child: const Text('Voltar'),
              ),
            ],
          ),
        ]);
      }

      return _buildFeedbackCard(scheme, semantic, [
        Row(
          children: [
            Icon(Icons.link, color: scheme.primary, size: 24),
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
                onPressed: _resumeScanning,
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
      ]);
    }

    if (state is ScannerError) {
      return Align(
        alignment: Alignment.bottomCenter,
        child: Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(20),
          decoration: _feedbackCardDecoration(scheme, semantic),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(Icons.error_outline, color: scheme.error, size: 24),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      state.isRateLimit
                          ? 'Limite de Consultas'
                          : 'Falha na Consulta',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: scheme.error,
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
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ],
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: _resumeScanning,
                      child: const Text('Escanear novamente'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  TextButton(
                    onPressed: () => _closeRoute(),
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

  Widget _buildPermissionRationaleView(BuildContext context) {
    final semantic = RewitSemanticColors.of(context);
    final foregroundMuted = semantic.onMediaScrim.withAlpha(200);

    return Center(
      key: const Key('scanner_permission_rationale_view'),
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 32.0, vertical: 24.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.qr_code_scanner,
              size: 64,
              color: semantic.onMediaScrim,
            ),
            const SizedBox(height: 16),
            Text(
              'Usar a câmera para escanear',
              style: TextStyle(
                color: semantic.onMediaScrim,
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'O Rewit precisa da câmera para ler códigos de barras de produtos e QR Codes de locais, produtos e avaliações.',
              style: TextStyle(color: foregroundMuted, fontSize: 14),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'A câmera só fica ativa enquanto o scanner estiver aberto.',
              style: TextStyle(color: foregroundMuted, fontSize: 12),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              key: const Key('scanner_rationale_continue_button'),
              onPressed: () {
                unawaited(_onContinueFromRationalePressed());
              },
              child: const Text('Continuar'),
            ),
            const SizedBox(height: 12),
            TextButton(
              key: const Key('scanner_rationale_cancel_button'),
              onPressed: () => _closeRoute(),
              child: Text(
                'Agora não',
                style: TextStyle(color: semantic.onMediaScrim),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPermissionDeniedView(
    BuildContext context,
    ScannerPermissionDenied state,
  ) {
    final semantic = RewitSemanticColors.of(context);
    final foregroundMuted = semantic.onMediaScrim.withAlpha(200);

    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.no_photography_outlined,
              size: 64,
              color: foregroundMuted,
            ),
            const SizedBox(height: 16),
            Text(
              'Acesso à câmera necessário',
              style: TextStyle(
                color: semantic.onMediaScrim,
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
              style: TextStyle(color: foregroundMuted, fontSize: 14),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            if (state.permanentlyDenied) ...[
              ElevatedButton(
                onPressed: () {
                  unawaited(_onOpenAppSettingsPressed());
                },
                child: const Text('Abrir configurações'),
              ),
              const SizedBox(height: 12),
            ] else ...[
              ElevatedButton(
                onPressed: () {
                  unawaited(_onRetryPermissionPressed());
                },
                child: const Text('Tentar novamente'),
              ),
              const SizedBox(height: 12),
            ],
            TextButton(
              onPressed: () => _closeRoute(),
              child: Text(
                'Voltar',
                style: TextStyle(color: semantic.onMediaScrim),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
