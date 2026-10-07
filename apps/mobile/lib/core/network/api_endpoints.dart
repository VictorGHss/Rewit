/// Constantes de rotas da API REST do Rewit.
class ApiEndpoints {
  const ApiEndpoints._();

  // Autenticação e Perfil do Usuário Autenticado
  static const String authLogin = '/api/v1/auth/login';
  static const String authRegister = '/api/v1/auth/register';
  static const String authRefresh = '/api/v1/auth/refresh';
  static const String authLogout = '/api/v1/auth/logout';
  static const String authMe = '/api/v1/auth/me';

  // Feed e Avaliações
  static const String feed = '/api/v1/feed';
  static const String feedV2 = '/api/v2/feed';
  static const String reviews = '/api/v1/reviews';
  static String reviewDetail(String id) => '/api/v1/reviews/$id';
  static String reviewMedia(String id) => '/api/v1/reviews/$id/media';
  static const String users = '/api/v1/users';
  static const String places = '/api/v1/places';
}
