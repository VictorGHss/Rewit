import 'package:flutter/material.dart';

/// Overlay visual que posiciona uma mira centralizada e orientações de escaneamento.
class ScannerOverlay extends StatelessWidget {
  final double scanAreaSize;

  const ScannerOverlay({
    super.key,
    this.scanAreaSize = 260.0,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return IgnorePointer(
      child: LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final height = constraints.maxHeight;
        final scanSize = scanAreaSize.clamp(180.0, width * 0.85);

        return Stack(
          children: [
            // Máscara semitransparente com recorte central
            ColorFiltered(
              colorFilter: ColorFilter.mode(
                Colors.black.withAlpha(140),
                BlendMode.srcOut,
              ),
              child: Stack(
                children: [
                  Container(
                    decoration: const BoxDecoration(
                      color: Colors.transparent,
                      backgroundBlendMode: BlendMode.dstOut,
                    ),
                  ),
                  Center(
                    child: Container(
                      width: scanSize,
                      height: scanSize,
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(16),
                      ),
                    ),
                  ),
                ],
              ),
            ),

            // Moldura com cantos destacados da área de captura
            Center(
              child: Container(
                width: scanSize,
                height: scanSize,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: theme.colorScheme.primary,
                    width: 2.5,
                  ),
                ),
                child: Stack(
                  children: [
                    // Rótulo central sutil
                    Center(
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                        decoration: BoxDecoration(
                          color: Colors.black.withAlpha(80),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: const Text(
                          'escaneie aqui',
                          style: TextStyle(
                            color: Colors.white70,
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),

            // Instrução textual abaixo da área de escaneamento
            Positioned(
              top: (height / 2) + (scanSize / 2) + 24,
              left: 24,
              right: 24,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                decoration: BoxDecoration(
                  color: Colors.black.withAlpha(160),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: const Text(
                  'Aponte para um código de barras ou QR Code',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 13,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ),
          ],
        );
      },
    ),
  );
}
}
