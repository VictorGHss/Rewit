import 'dart:async';

import 'package:flutter/material.dart';

/// Avisos (snackbars) sobre cadastros de local pendentes, vinculados à conta que os originou.
///
/// O `ScaffoldMessenger` do app sobrevive à troca de conta; sem este vínculo, o aviso de uma conta (com o
/// nome do local e a ação de retomada) continuaria visível para a próxima (MB-1d). Somente estes avisos
/// são fechados — confirmações não relacionadas permanecem.
class PendingRegistrationNotices {
  PendingRegistrationNotices._();

  static final Map<ScaffoldFeatureController<SnackBar, SnackBarClosedReason>, _Notice> _notices = {};

  /// Exibe o aviso e o associa a [ownerUserId].
  static void show(BuildContext context, String? ownerUserId, SnackBar snackBar) {
    final messenger = ScaffoldMessenger.maybeOf(context);
    if (messenger == null) return;
    final controller = messenger.showSnackBar(snackBar);
    _notices[controller] = _Notice(ownerUserId ?? '', messenger);
    controller.closed.then((_) => _notices.remove(controller));
  }

  /// Fecha os avisos que não pertencem a [currentUserId] (todos, quando `null`). Executa fora do frame
  /// atual, pois pode ser chamado durante a desmontagem da árvore.
  static void closeAllExcept(String? currentUserId) {
    final stale = _notices.entries
        .where((e) => currentUserId == null || e.value.ownerUserId != currentUserId)
        .map((e) => e.key)
        .toList();
    if (stale.isEmpty) return;
    scheduleMicrotask(() {
      for (final controller in stale) {
        final notice = _notices.remove(controller);
        // Com o messenger desmontado, o aviso já deixou de existir junto com a árvore
        if (notice != null && notice.messenger.mounted) {
          controller.close();
        }
      }
    });
  }
}

class _Notice {
  final String ownerUserId;
  final ScaffoldMessengerState messenger;

  const _Notice(this.ownerUserId, this.messenger);
}
