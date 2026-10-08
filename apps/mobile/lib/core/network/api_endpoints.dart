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
  static const String meProfile = '/api/v1/me/profile';
  static const String meDeactivate = '/api/v1/me/deactivate';
  static const String mePassword = '/api/v1/me/password';

  // Feed e Avaliações
  static const String feed = '/api/v1/feed';
  static const String feedV2 = '/api/v2/feed';
  static const String reviews = '/api/v1/reviews';
  static String reviewDetail(String id) => '/api/v1/reviews/$id';
  static String reviewMedia(String id) => '/api/v1/reviews/$id/media';
  static String reviewMediaItem(String reviewId, String mediaId) =>
      '/api/v1/reviews/$reviewId/media/$mediaId';
  static String reviewHelpful(String id) => '/api/v1/reviews/$id/helpful';
  static const String places = '/api/v1/places';
  static String placeDetail(String id) => '/api/v1/places/$id';
  static String targetStats(String id) => '/api/v1/targets/$id/stats';
  static String targetReviewsPath(String id) => '/api/v1/targets/$id/reviews';
  static String targetReviews(
    String id, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) =>
      '/api/v1/targets/$id/reviews?page=$page&size=$size&sort=$sort&verifiedOnly=$verifiedOnly';
  // Produtos e Descoberta no Catálogo (C5.3)
  static const String products = '/api/v1/products';
  static String productDetail(String id) => '/api/v1/products/$id';
  static String productIdentifiers(String id) => '/api/v1/products/$id/identifiers';
  static String productByIdentifier(String type, String value) =>
      '/api/v1/products/identifiers/$type/$value';
  static String placeProductsPath(String placeId) => '/api/v1/places/$placeId/products';
  static String placeProducts(String placeId, {int page = 0, int size = 20}) =>
      '/api/v1/places/$placeId/products?page=$page&size=$size';
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
  static const String myReviewsPath = '/api/v1/me/reviews';
  static String myReviews({int page = 0, int size = 10}) =>
      '/api/v1/me/reviews?page=$page&size=$size';

  // Discussões e Comentários Comunitários
  static String reviewDiscussions(String reviewId, {int page = 0, int size = 20}) =>
      '/api/v1/reviews/$reviewId/discussions?page=$page&size=$size';
  static String createDiscussion(String reviewId) => '/api/v1/reviews/$reviewId/discussions';
  static String discussionReplies(String discussionId, {int page = 0, int size = 20}) =>
      '/api/v1/discussions/$discussionId/replies?page=$page&size=$size';
  static String discussionDetail(String discussionId) => '/api/v1/discussions/$discussionId';
  static String reportDiscussion(String discussionId) => '/api/v1/discussions/$discussionId/reports';

  // Notificações In-App (C5.4)
  static const String notificationsPath = '/api/v1/me/notifications';
  static String notifications({int page = 0, int size = 20}) =>
      '/api/v1/me/notifications?page=$page&size=$size';
  static const String notificationsUnreadCount = '/api/v1/me/notifications/unread-count';
  static String notificationRead(String notificationId) =>
      '/api/v1/me/notifications/$notificationId/read';
  static const String notificationsReadAll = '/api/v1/me/notifications/read-all';
}
