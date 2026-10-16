/// Combinações de anonimato e visibilidade aceitas pelo backend.
///
/// Uma avaliação anônima não pode ser restrita a seguidores: o acesso dependeria de seguir o autor real,
/// e bastaria seguir candidatos para descobrir quem escreveu. O backend recusa a combinação com
/// `422 ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED`, na criação e na edição.
class ReviewPrivacyRules {
  const ReviewPrivacyRules._();

  static const String followersVisibility = 'FOLLOWERS';
  static const String incompatibleCombinationErrorCode = 'ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED';

  static const String followersUnavailableForAnonymous =
      '"Apenas seguidores" não está disponível para avaliações anônimas: como só quem segue você teria acesso, '
      'a autoria poderia ser descoberta. Desative o anonimato para escolher esta opção.';

  static const String anonymousUnavailableForFollowers =
      'Para publicar como anônimo, escolha visibilidade pública ou privada. '
      'Avaliações restritas a seguidores não podem ser anônimas.';

  static const String incompatibleCombinationMessage =
      'Avaliações anônimas não podem ser restritas a seguidores. Escolha visibilidade pública ou privada, '
      'ou desative o anonimato para manter a visibilidade para seguidores.';

  static const String legacyCombinationNotice =
      'Esta avaliação é anônima e restrita a seguidores, combinação que não é mais permitida. Hoje só você '
      'consegue vê-la. Para salvar, escolha visibilidade pública ou privada, ou desative o anonimato para '
      'voltar a mostrá-la aos seguidores.';

  static bool _isFollowers(String visibility) => visibility.toUpperCase() == followersVisibility;

  /// Se a combinação pode ser enviada ao backend.
  static bool isAllowed({required bool isAnonymous, required String visibility}) {
    return !(isAnonymous && _isFollowers(visibility));
  }

  /// Ligar o anonimato só é possível fora de `FOLLOWERS`; desligar é sempre possível, inclusive numa avaliação
  /// antiga que já tenha a combinação.
  static bool canSetAnonymous({required bool value, required String visibility}) {
    return !value || !_isFollowers(visibility);
  }

  /// `FOLLOWERS` só pode ser escolhida numa avaliação identificada; as demais visibilidades sempre podem.
  static bool canSelectVisibility({required String value, required bool isAnonymous}) {
    return !(isAnonymous && _isFollowers(value));
  }

  /// Mensagem para uma recusa do backend: orienta no caso da combinação e mantém o detalhe recebido nos demais.
  static String messageForError(String? errorCode, String detail) {
    return errorCode == incompatibleCombinationErrorCode ? incompatibleCombinationMessage : detail;
  }
}
