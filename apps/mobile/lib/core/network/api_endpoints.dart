/// Constantes de rotas da API REST do Rewit.
class ApiEndpoints {
  const ApiEndpoints._();

  // Autenticação e Perfil do Usuário Autenticado
  static const String authLogin = '/api/v1/auth/login';
  static const String authRegister = '/api/v1/auth/register';
  static const String authReactivate = '/api/v1/auth/reactivate';
  static const String authRefresh = '/api/v1/auth/refresh';
  static const String authLogout = '/api/v1/auth/logout';
  static const String authMe = '/api/v1/auth/me';
  static const String meDeactivate = '/api/v1/me/deactivate';

  // Feed e Avaliações
  static const String feed = '/api/v1/feed';
  static const String feedV2 = '/api/v2/feed';
  static const String reviews = '/api/v1/reviews';
  static String reviewDetail(String id) => '/api/v1/reviews/$id';
  static String reviewMedia(String id) => '/api/v1/reviews/$id/media';
  static String reviewMediaItem(String reviewId, String mediaId) =>
      '/api/v1/reviews/$reviewId/media/$mediaId';
  static String reviewHelpful(String id) => '/api/v1/reviews/$id/helpful';
  static const String users = '/api/v1/users';
  static const String places = '/api/v1/places';

  // Perfil Público e Grafo Social
  static String userProfile(String userId) => '/api/v1/users/$userId';
  static String userFollow(String userId) => '/api/v1/users/$userId/follow';
  static String userFollowers(String userId, {int page = 0, int size = 10}) =>
      '/api/v1/users/$userId/followers?page=$page&size=$size';
  static String userFollowing(String userId, {int page = 0, int size = 10}) =>
      '/api/v1/users/$userId/following?page=$page&size=$size';
  static String myFollowers({int page = 0, int size = 10}) =>
      '/api/v1/me/followers?page=$page&size=$size';
  static String myFollowing({int page = 0, int size = 10}) =>
      '/api/v1/me/following?page=$page&size=$size';

  // Discussões e Comentários Comunitários
  static String reviewDiscussions(String reviewId, {int page = 0, int size = 20}) =>
      '/api/v1/reviews/$reviewId/discussions?page=$page&size=$size';
  static String createDiscussion(String reviewId) => '/api/v1/reviews/$reviewId/discussions';
  static String discussionReplies(String discussionId, {int page = 0, int size = 20}) =>
      '/api/v1/discussions/$discussionId/replies?page=$page&size=$size';
  static String discussionDetail(String discussionId) => '/api/v1/discussions/$discussionId';
  static String reportDiscussion(String discussionId) => '/api/v1/discussions/$discussionId/reports';
}
