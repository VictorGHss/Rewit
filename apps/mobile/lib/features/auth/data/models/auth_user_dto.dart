/// DTO de representação pública do usuário autenticado no Rewit.
class AuthUserDto {
  final String id;
  final String email;
  final String handle;
  final String displayName;
  final bool isVerified;
  final bool isAnonymousDefault;
  final int reputationScore;

  const AuthUserDto({
    required this.id,
    required this.email,
    required this.handle,
    required this.displayName,
    this.isVerified = false,
    this.isAnonymousDefault = false,
    this.reputationScore = 0,
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
    };
  }

  @override
  String toString() => 'AuthUserDto(id: $id, handle: @$handle, displayName: $displayName)';
}
