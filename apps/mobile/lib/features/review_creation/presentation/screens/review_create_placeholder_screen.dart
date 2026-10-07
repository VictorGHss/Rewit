import 'package:flutter/material.dart';

/// Tela placeholder para Criação de Avaliação (próxima etapa).
class ReviewCreatePlaceholderScreen extends StatelessWidget {
  const ReviewCreatePlaceholderScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Nova Avaliação'),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.rate_review_outlined, size: 64, color: Colors.grey),
              const SizedBox(height: 16),
              Text(
                'Criar Avaliação',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              const Text(
                'O fluxo de criação de avaliações com validação espacial será implementado nas próximas etapas.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.grey),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
