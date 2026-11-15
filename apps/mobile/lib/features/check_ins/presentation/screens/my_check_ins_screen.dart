import 'package:flutter/material.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

import '../../domain/entities/check_in_entities.dart';
import '../../domain/repositories/check_in_repository.dart';
import '../state/my_check_ins_notifier.dart';

/// Histórico dos check-ins verificados do usuário (`GET /api/v1/me/check-ins`).
class MyCheckInsScreen extends StatefulWidget {
  final CheckInRepository? repository;

  /// Abre a avaliação que gerou o check-in.
  final void Function(String reviewId)? onOpenReview;

  /// Injeção para testes; sem ele, a tela cria e descarta o próprio notifier.
  final MyCheckInsNotifier? notifier;

  const MyCheckInsScreen({super.key, required this.repository, this.onOpenReview, this.notifier});

  @override
  State<MyCheckInsScreen> createState() => _MyCheckInsScreenState();
}

class _MyCheckInsScreenState extends State<MyCheckInsScreen> {
  MyCheckInsNotifier? _notifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier;
    } else if (widget.repository != null) {
      _notifier = MyCheckInsNotifier(repository: widget.repository!);
      _ownsNotifier = true;
    }
    _notifier?.load();
  }

  @override
  void dispose() {
    if (_ownsNotifier) _notifier?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final notifier = _notifier;
    return Scaffold(
      appBar: AppBar(title: const Text('Meus check-ins')),
      body: notifier == null
          ? const Center(child: Text('Check-ins indisponíveis nesta versão do app.'))
          : AnimatedBuilder(
              animation: notifier,
              builder: (context, _) => _buildBody(context, notifier),
            ),
    );
  }

  Widget _buildBody(BuildContext context, MyCheckInsNotifier notifier) {
    if (notifier.items.isEmpty && notifier.isLoading) {
      return const LoadingIndicator(message: 'Carregando seus check-ins...');
    }
    if (notifier.items.isEmpty && notifier.errorMessage != null) {
      return ErrorView(
        key: const Key('my_check_ins_error'),
        title: 'Não foi possível carregar seus check-ins',
        message: notifier.errorMessage!,
        onRetry: notifier.load,
      );
    }
    if (notifier.isEmpty) {
      return Center(
        key: const Key('my_check_ins_empty'),
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.place_outlined, size: 48, color: Theme.of(context).colorScheme.outline),
              const SizedBox(height: 12),
              const Text(
                'Você ainda não tem check-ins verificados.',
                textAlign: TextAlign.center,
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 4),
              const Text(
                'Eles aparecem aqui quando o Rewit confirma sua presença ao publicar uma avaliação no local.',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: notifier.load,
      child: ListView.separated(
        key: const Key('my_check_ins_list'),
        physics: const AlwaysScrollableScrollPhysics(),
        itemCount: notifier.items.length + 1,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          if (index == notifier.items.length) return _buildFooter(notifier);
          return _CheckInTile(item: notifier.items[index], onOpenReview: widget.onOpenReview);
        },
      ),
    );
  }

  Widget _buildFooter(MyCheckInsNotifier notifier) {
    if (notifier.isLoadingMore) {
      return const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()));
    }
    if (notifier.loadMoreError != null) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Text(notifier.loadMoreError!, textAlign: TextAlign.center),
            TextButton(
              key: const Key('my_check_ins_load_more_retry'),
              onPressed: notifier.loadMore,
              child: const Text('Tentar novamente'),
            ),
          ],
        ),
      );
    }
    if (notifier.hasMore) {
      return Padding(
        padding: const EdgeInsets.all(8),
        child: TextButton(
          key: const Key('my_check_ins_load_more'),
          onPressed: notifier.loadMore,
          child: const Text('Carregar mais'),
        ),
      );
    }
    return const SizedBox(height: 16);
  }
}

class _CheckInTile extends StatelessWidget {
  final MyCheckIn item;
  final void Function(String reviewId)? onOpenReview;

  const _CheckInTile({required this.item, this.onOpenReview});

  @override
  Widget build(BuildContext context) {
    final location = [item.placeCity, item.placeState].whereType<String>().where((s) => s.isNotEmpty).join(' - ');
    final verifiedAt = item.verifiedAt.toLocal();
    final date = '${_two(verifiedAt.day)}/${_two(verifiedAt.month)}/${verifiedAt.year} às '
        '${_two(verifiedAt.hour)}:${_two(verifiedAt.minute)}';
    return ListTile(
      key: Key('my_check_in_${item.id}'),
      leading: const Icon(Icons.verified_outlined),
      title: Text(item.placeName),
      subtitle: Text([if (location.isNotEmpty) location, 'Verificado em $date'].join('\n')),
      isThreeLine: location.isNotEmpty,
      onTap: onOpenReview == null ? null : () => onOpenReview!(item.reviewId),
    );
  }

  static String _two(int value) => value.toString().padLeft(2, '0');
}
