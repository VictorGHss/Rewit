import '../../presentation/widgets/manual_place_registration_sheet.dart';

/// Armazenamento em memória com escopo estrito por identidade de usuário (MB-1c, MB-1d).
/// Garante que operações pendentes de registro de local com foto não sejam compartilhadas
/// entre diferentes contas, preserva o UUID v4 canônico entre tentativas e valida
/// a existência do arquivo local antes da retomada.
class PendingPlaceRegistrationStore {
  static final Map<String, PendingPlaceRegistration> _userPendingRegistrations = {};

  /// Salva uma operação pendente para o usuário autenticado atual.
  static void save(String userId, PendingPlaceRegistration pending) {
    if (userId.trim().isEmpty) return;
    _userPendingRegistrations[userId] = pending;
  }

  /// Recupera a operação pendente para o usuário autenticado.
  /// Retorna `null` se o usuário não tiver operação pendente ou se [userId] for vazio.
  static PendingPlaceRegistration? get(String? userId) {
    if (userId == null || userId.trim().isEmpty) return null;
    return _userPendingRegistrations[userId];
  }

  /// Limpa a operação pendente do usuário especificado.
  static void clear(String? userId) {
    if (userId == null || userId.trim().isEmpty) return;
    _userPendingRegistrations.remove(userId);
  }

  /// Limpa todas as operações de todos os usuários (útil para testes unitários/reset).
  static void clearAll() {
    _userPendingRegistrations.clear();
  }
}
