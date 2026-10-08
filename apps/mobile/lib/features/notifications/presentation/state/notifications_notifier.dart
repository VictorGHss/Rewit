import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/in_app_notification.dart';
import '../../domain/repositories/notification_repository.dart';
import 'notifications_state.dart';

/// Gerenciador de estado reativo para Notificações In-App (Step 22.0 / C5.4).
class NotificationsNotifier extends ChangeNotifier {
  final NotificationRepository repository;

  NotificationsState _state = const NotificationsInitial();
  NotificationsState get state => _state;

  int _unreadCount = 0;
  int get unreadCount => _unreadCount;

  int _currentRequestId = 0;

  NotificationsNotifier({required this.repository});

  /// Busca apenas a quantidade de notificações não lidas (ex: para badge do AppBar).
  Future<void> fetchUnreadCount() async {
    try {
      final count = await repository.getUnreadCount();
      _unreadCount = math.max(0, count);

      if (_state is NotificationsLoaded) {
        final currentLoaded = _state as NotificationsLoaded;
        _state = currentLoaded.copyWith(unreadCount: _unreadCount);
      }
      notifyListeners();
    } catch (_) {
      // Falha silenciosa para contagem em background
    }
  }

  /// Carrega a primeira página de notificações e sincroniza a contagem de não lidas.
  Future<void> loadNotifications() async {
    final requestId = ++_currentRequestId;
    _state = const NotificationsLoading();
    notifyListeners();

    try {
      final results = await Future.wait([
        repository.getNotifications(page: 0, size: 20),
        repository.getUnreadCount(),
      ]);

      if (requestId != _currentRequestId) {
        return; // Resposta obsoleta descartada
      }

      final page = results[0] as dynamic;
      final count = results[1] as int;
      _unreadCount = math.max(0, count);

      final items = page.items as List<InAppNotification>;

      if (items.isEmpty) {
        _state = const NotificationsEmpty();
      } else {
        _state = NotificationsLoaded(
          notifications: items,
          unreadCount: _unreadCount,
          currentPage: page.pageNumber,
          isLastPage: page.isLast,
          totalElements: page.totalElements,
        );
      }
      notifyListeners();
    } on ApiException catch (e) {
      if (requestId == _currentRequestId) {
        _state = NotificationsError(e.detail);
        notifyListeners();
      }
    } on NetworkException catch (e) {
      if (requestId == _currentRequestId) {
        _state = NotificationsError(e.message);
        notifyListeners();
      }
    } catch (e) {
      if (requestId == _currentRequestId) {
        _state = NotificationsError('Falha ao carregar notificações: ${e.toString()}');
        notifyListeners();
      }
    }
  }

  /// Recarrega as notificações do início (pull-to-refresh).
  Future<void> refresh() async {
    await loadNotifications();
  }

  /// Carrega a próxima página de notificações com deduplicação por identificador único.
  Future<void> loadMore() async {
    final currentState = _state;
    if (currentState is! NotificationsLoaded) return;
    if (currentState.isLastPage || currentState.isLoadingMore) return;

    final requestId = _currentRequestId;
    _state = currentState.copyWith(
      isLoadingMore: true,
      clearLoadMoreError: true,
    );
    notifyListeners();

    try {
      final nextPage = currentState.currentPage + 1;
      final page = await repository.getNotifications(page: nextPage, size: 20);

      if (requestId != _currentRequestId) {
        return; // Resposta obsoleta descartada
      }

      // Deduplicação estrita de itens pelo ID
      final existingIds = currentState.notifications.map((n) => n.id).toSet();
      final newItems = page.items.where((n) => !existingIds.contains(n.id)).toList();
      final combined = [...currentState.notifications, ...newItems];

      _state = currentState.copyWith(
        notifications: combined,
        currentPage: page.pageNumber,
        isLastPage: page.isLast,
        totalElements: page.totalElements,
        isLoadingMore: false,
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (requestId == _currentRequestId) {
        _state = currentState.copyWith(
          isLoadingMore: false,
          loadMoreError: e.detail,
        );
        notifyListeners();
      }
    } catch (e) {
      if (requestId == _currentRequestId) {
        _state = currentState.copyWith(
          isLoadingMore: false,
          loadMoreError: 'Erro ao carregar mais notificações.',
        );
        notifyListeners();
      }
    }
  }

  /// Marca uma notificação específica como lida de forma otimista com reversão em caso de erro.
  Future<void> markAsRead(String notificationId) async {
    final currentState = _state;
    if (currentState is! NotificationsLoaded) return;

    final index = currentState.notifications.indexWhere((n) => n.id == notificationId);
    if (index == -1) return;

    final target = currentState.notifications[index];
    if (target.isRead) return; // Já lida

    final previousNotifications = List<InAppNotification>.from(currentState.notifications);
    final previousCount = _unreadCount;

    // Atualização otimista imediata
    final updatedItem = target.copyWith(readAt: DateTime.now());
    final updatedList = List<InAppNotification>.from(currentState.notifications);
    updatedList[index] = updatedItem;

    _unreadCount = math.max(0, _unreadCount - 1);
    _state = currentState.copyWith(
      notifications: updatedList,
      unreadCount: _unreadCount,
    );
    notifyListeners();

    try {
      await repository.markAsRead(notificationId);
    } catch (e) {
      // Reversão em caso de falha na chamada HTTP
      _unreadCount = previousCount;
      if (_state is NotificationsLoaded) {
        _state = (_state as NotificationsLoaded).copyWith(
          notifications: previousNotifications,
          unreadCount: previousCount,
        );
        notifyListeners();
      }
      rethrow;
    }
  }

  /// Marca todas as notificações como lidas de forma otimista com reversão em caso de erro.
  Future<void> markAllAsRead() async {
    final currentState = _state;
    if (currentState is! NotificationsLoaded) return;
    if (!currentState.hasUnread) return;

    final previousNotifications = List<InAppNotification>.from(currentState.notifications);
    final previousCount = _unreadCount;

    final now = DateTime.now();
    final updatedList = currentState.notifications
        .map((n) => n.isRead ? n : n.copyWith(readAt: now))
        .toList();

    _unreadCount = 0;
    _state = currentState.copyWith(
      notifications: updatedList,
      unreadCount: 0,
      isMarkingAll: true,
    );
    notifyListeners();

    try {
      await repository.markAllAsRead();
      if (_state is NotificationsLoaded) {
        _state = (_state as NotificationsLoaded).copyWith(isMarkingAll: false);
        notifyListeners();
      }
    } catch (e) {
      // Reversão em caso de falha
      _unreadCount = previousCount;
      if (_state is NotificationsLoaded) {
        _state = (_state as NotificationsLoaded).copyWith(
          notifications: previousNotifications,
          unreadCount: previousCount,
          isMarkingAll: false,
        );
        notifyListeners();
      }
      rethrow;
    }
  }
}
