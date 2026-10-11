/// Entidade de domínio para Respostas Oficiais de Empresas (C11).
class BusinessResponse {
  final String id;
  final String reviewId;
  final String? discussionId;
  final String businessAccountId;
  final String corporateName;
  final String placeId;
  final String placeName;
  final String content;
  final DateTime createdAt;

  const BusinessResponse({
    required this.id,
    required this.reviewId,
    this.discussionId,
    required this.businessAccountId,
    required this.corporateName,
    required this.placeId,
    required this.placeName,
    required this.content,
    required this.createdAt,
  });

  bool get isReviewResponse => discussionId == null;
  bool get isDiscussionResponse => discussionId != null;
}

/// Página de respostas oficiais retornada pela API REST.
class BusinessResponsePage {
  final List<BusinessResponse> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const BusinessResponsePage({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });
}

/// Local elegível vinculado a uma conta comercial para responder a uma avaliação.
class EligiblePlace {
  final String placeId;
  final String placeName;

  const EligiblePlace({
    required this.placeId,
    required this.placeName,
  });
}

/// Conta comercial elegível administrada pelo usuário autenticado.
class EligibleBusinessAccount {
  final String businessAccountId;
  final String corporateName;
  final List<EligiblePlace> places;
  final String? activeResponseId;
  final bool canPublish;

  const EligibleBusinessAccount({
    required this.businessAccountId,
    required this.corporateName,
    required this.places,
    this.activeResponseId,
    required this.canPublish,
  });
}

/// Resultado da consulta de elegibilidade para responder oficialmente a uma avaliação.
class BusinessResponseEligibility {
  final String reviewId;
  final bool respondable;
  final List<EligibleBusinessAccount> accounts;

  const BusinessResponseEligibility({
    required this.reviewId,
    required this.respondable,
    required this.accounts,
  });

  bool get hasEligibleAccounts =>
      respondable && accounts.any((a) => a.canPublish && a.places.isNotEmpty);
}
