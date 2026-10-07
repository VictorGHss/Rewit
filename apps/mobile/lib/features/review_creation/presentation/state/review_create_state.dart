import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';

/// Estados possíveis do fluxo de criação de avaliação.
sealed class ReviewCreateState {
  const ReviewCreateState();

  bool get isSubmitting => false;
}

/// Estado inicial ou formulário sendo editado.
class ReviewCreateInitial extends ReviewCreateState {
  const ReviewCreateInitial();
}

/// Estado de envio em progresso (com proteção contra submissão duplicada).
class ReviewCreateSubmitting extends ReviewCreateState {
  const ReviewCreateSubmitting();

  @override
  bool get isSubmitting => true;
}

/// Estado de sucesso contendo a avaliação criada.
class ReviewCreateSuccess extends ReviewCreateState {
  final FeedReview createdReview;

  const ReviewCreateSuccess(this.createdReview);
}

/// Estado de erro estruturado (RFC 7807, validação local ou falha de conectividade).
class ReviewCreateError extends ReviewCreateState {
  final String message;
  final int? statusCode;
  final String? errorCode;
  final int? retryAfterSeconds;
  final ProblemDetail? problemDetail;

  const ReviewCreateError({
    required this.message,
    this.statusCode,
    this.errorCode,
    this.retryAfterSeconds,
    this.problemDetail,
  });

  bool get isRateLimited => statusCode == 429;
  bool get isValidationError => statusCode == 400 || statusCode == 422;
}
