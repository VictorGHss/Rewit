/// Parâmetros para atualização parcial do perfil do usuário autenticado (PATCH /api/v1/me/profile).
class UpdateProfileInput {
  final String handle;
  final String displayName;
  final String? bio;
  final bool isAnonymousDefault;

  const UpdateProfileInput({
    required this.handle,
    required this.displayName,
    this.bio,
    this.isAnonymousDefault = false,
  });

  Map<String, dynamic> toJson() {
    return {
      'handle': handle,
      'displayName': displayName,
      'bio': bio,
      'isAnonymousDefault': isAnonymousDefault,
    };
  }
}
