import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';

/// Entidade de autor de avaliação no Feed V2.
class FeedAuthor {
  final String? id;
  final String? handle;
  final String displayName;
  final String? avatarUrl;
  final bool isAnonymous;

  const FeedAuthor({
    this.id,
    this.handle,
    required this.displayName,
    this.avatarUrl,
    this.isAnonymous = false,
  });

  /// Retorna o nome ou identificador seguro para exibição.
  String get displayHandle => isAnonymous ? 'Anônimo' : (handle != null ? '@$handle' : displayName);
}

/// Entidade de alvo avaliado (estabelecimento, produto, serviço).
class FeedTarget {
  final String id;
  final String? reviewId;
  final String targetId;
  final double rating;
  final String? specificComment;
  final DateTime? createdAt;
  final String? targetType;
  final String? targetName;

  const FeedTarget({
    required this.id,
    this.reviewId,
    required this.targetId,
    required this.rating,
    this.specificComment,
    this.createdAt,
    this.targetType,
    this.targetName,
  });

  bool get isPlace => targetType?.toUpperCase() == 'PLACE';
  bool get isProduct => targetType?.toUpperCase() == 'PRODUCT';
  bool get isService => targetType?.toUpperCase() == 'SERVICE';
}

/// Situação da verificação de presença devolvida pelo backend na criação de uma avaliação.
enum CheckInResultStatus {
  /// Presença confirmada: a avaliação gerou um check-in verificado.
  verified,

  /// Avaliação publicada sem check-in verificado; [CheckInResult.reason] diz o motivo.
  notVerified,

  /// Nenhuma verificação pedida (sem local de contexto ou sem localização enviada).
  notRequested,

  /// Valor desconhecido para esta versão do app; nunca tratado como presença confirmada.
  unknown,
}

/// Resultado da verificação de presença: o backend decide sozinho, e o app só exibe o que ele respondeu.
class CheckInResult {
  final CheckInResultStatus status;

  /// Motivo de uma presença não verificada (`OUTSIDE_RADIUS`, `ACCURACY_TOO_LOW`, `ACCURACY_UNAVAILABLE`,
  /// `RECENT_CHECK_IN`, `PLACE_WITHOUT_COORDINATES`); nulo nos demais casos.
  final String? reason;

  const CheckInResult({required this.status, this.reason});

  bool get isVerified => status == CheckInResultStatus.verified;

  static CheckInResult? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    final status = switch (json['status']) {
      'VERIFIED' => CheckInResultStatus.verified,
      'NOT_VERIFIED' => CheckInResultStatus.notVerified,
      'NOT_REQUESTED' => CheckInResultStatus.notRequested,
      _ => CheckInResultStatus.unknown,
    };
    final reason = json['reason'];
    return CheckInResult(status: status, reason: reason is String ? reason : null);
  }
}

/// Entidade representacional de uma avaliação ranqueada e diversificada no Feed V2.
class FeedReview {
  final String id;
  final FeedAuthor author;
  final String? contextPlaceId;
  final String? experienceText;
  final bool isAnonymous;
  final bool isVerifiedOnSite;
  final String visibility;
  final String status;
  final DateTime createdAt;
  final DateTime? updatedAt;
  final List<FeedTarget> targets;
  final int helpfulCount;
  final bool isHelpfulByMe;
  final List<ReviewMediaItem>? mediaItems;

  /// Posse contextual ao usuário autenticado, calculada pelo backend sem expor o id do autor.
  /// Nulo quando o payload de origem não informa (ex.: feed e listagens).
  final bool? isMine;

  /// Resultado da verificação de presença; presente só na resposta da criação da avaliação.
  final CheckInResult? checkIn;

  const FeedReview({
    required this.id,
    required this.author,
    this.contextPlaceId,
    this.experienceText,
    this.isAnonymous = false,
    this.isVerifiedOnSite = false,
    required this.visibility,
    required this.status,
    required this.createdAt,
    this.updatedAt,
    this.targets = const [],
    this.helpfulCount = 0,
    this.isHelpfulByMe = false,
    this.mediaItems,
    this.isMine,
    this.checkIn,
  });

  FeedReview copyWith({
    FeedAuthor? author,
    String? contextPlaceId,
    String? experienceText,
    bool? isAnonymous,
    bool? isVerifiedOnSite,
    String? visibility,
    String? status,
    DateTime? createdAt,
    DateTime? updatedAt,
    List<FeedTarget>? targets,
    int? helpfulCount,
    bool? isHelpfulByMe,
    List<ReviewMediaItem>? mediaItems,
    bool? isMine,
    CheckInResult? checkIn,
  }) {
    return FeedReview(
      id: id,
      author: author ?? this.author,
      contextPlaceId: contextPlaceId ?? this.contextPlaceId,
      experienceText: experienceText ?? this.experienceText,
      isAnonymous: isAnonymous ?? this.isAnonymous,
      isVerifiedOnSite: isVerifiedOnSite ?? this.isVerifiedOnSite,
      visibility: visibility ?? this.visibility,
      status: status ?? this.status,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      targets: targets ?? this.targets,
      helpfulCount: helpfulCount ?? this.helpfulCount,
      isHelpfulByMe: isHelpfulByMe ?? this.isHelpfulByMe,
      mediaItems: mediaItems ?? this.mediaItems,
      isMine: isMine ?? this.isMine,
      checkIn: checkIn ?? this.checkIn,
    );
  }

  /// Média das notas dos alvos vinculados a esta avaliação.
  double? get averageRating {
    if (targets.isEmpty) return null;
    final sum = targets.fold<double>(0.0, (acc, t) => acc + t.rating);
    return sum / targets.length;
  }
}


/// Página de feed devolvida pelo backend (Feed V1 social ou Feed V2 ranqueado).
class FeedPage {
  final List<FeedReview> items;
  final int page;
  final int size;
  final int windowSize;
  final int totalPages;

  /// Total geral de elementos em todas as páginas (informado pelo Feed V1 / PagedResponse).
  final int? totalElements;

  /// Indicador explícito de última página (informado pelo Feed V1 / PagedResponse).
  final bool? isLast;

  const FeedPage({
    required this.items,
    required this.page,
    required this.size,
    required this.windowSize,
    required this.totalPages,
    this.totalElements,
    this.isLast,
  });

  /// Indica se existem mais páginas a carregar.
  /// Prioriza [isLast] quando presente (Feed V1); caso contrário, avalia [page + 1 < totalPages] (Feed V2).
  bool get hasMore {
    if (isLast != null) return !isLast!;
    return page + 1 < totalPages;
  }

  bool get isEmpty => items.isEmpty;
}
