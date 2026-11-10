/// DTO de representação pública do usuário autenticado no Rewit.
class AuthUserDto {
  final String id;
  final String email;
  final String handle;
  final String displayName;
  final bool isVerified;
  final bool isAnonymousDefault;
  final int reputationScore;

  /// URL pública da foto de perfil; nula quando o usuário não tem foto (a interface usa a inicial do nome).
  final String? avatarUrl;

  const AuthUserDto({
    required this.id,
    required this.email,
    required this.handle,
    required this.displayName,
    this.isVerified = false,
    this.isAnonymousDefault = false,
    this.reputationScore = 0,
    this.avatarUrl,
  });

  factory AuthUserDto.fromJson(Map<String, dynamic> json) {
    return AuthUserDto(
      id: json['id'] as String,
      email: json['email'] as String,
      handle: json['handle'] as String,
      displayName: json['displayName'] as String,
      isVerified: json['isVerified'] as bool? ?? false,
      isAnonymousDefault: json['isAnonymousDefault'] as bool? ?? false,
      reputationScore: (json['reputationScore'] as num?)?.toInt() ?? 0,
      avatarUrl: normalizeAvatarUrl(json['avatarUrl'] as String?),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'email': email,
      'handle': handle,
      'displayName': displayName,
      'isVerified': isVerified,
      'isAnonymousDefault': isAnonymousDefault,
      'reputationScore': reputationScore,
      if (avatarUrl != null) 'avatarUrl': avatarUrl,
    };
  }

  AuthUserDto copyWith({
    String? id,
    String? email,
    String? handle,
    String? displayName,
    bool? isVerified,
    bool? isAnonymousDefault,
    int? reputationScore,
    String? avatarUrl,
    bool clearAvatarUrl = false,
  }) {
    return AuthUserDto(
      id: id ?? this.id,
      email: email ?? this.email,
      handle: handle ?? this.handle,
      displayName: displayName ?? this.displayName,
      isVerified: isVerified ?? this.isVerified,
      isAnonymousDefault: isAnonymousDefault ?? this.isAnonymousDefault,
      reputationScore: reputationScore ?? this.reputationScore,
      avatarUrl: clearAvatarUrl ? null : (avatarUrl ?? this.avatarUrl),
    );
  }

  /// URL de avatar utilizável: texto vazio ou só com espaços conta como ausência de foto.
  static String? normalizeAvatarUrl(String? value) {
    final trimmed = value?.trim();
    return trimmed == null || trimmed.isEmpty ? null : trimmed;
  }

  @override
  String toString() => 'AuthUserDto(id: $id, handle: @$handle, displayName: $displayName)';
}
