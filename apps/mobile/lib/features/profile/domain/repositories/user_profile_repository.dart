import 'package:rewit_mobile/features/profile/domain/entities/follow_user_summary.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';

/// Contrato de repositório para perfil público e grafo social no Rewit.
abstract class UserProfileRepository {
  /// Consulta o perfil público e estatísticas factuais do usuário [userId].
  Future<UserProfile> getUserProfile(String userId);

  /// Passa a seguir o usuário [userId]. Retorna `true` se a relação for estabelecida.
  Future<bool> followUser(String userId);

  /// Deixa de seguir o usuário [userId]. Retorna `false` indicando que não é mais seguido.
  Future<bool> unfollowUser(String userId);

  /// Lista paginada dos seguidores do usuário [userId].
  Future<PagedFollowUsers> getFollowers(String userId, {int page = 0, int size = 10});

  /// Lista paginada de quem o usuário [userId] segue.
  Future<PagedFollowUsers> getFollowing(String userId, {int page = 0, int size = 10});
}
