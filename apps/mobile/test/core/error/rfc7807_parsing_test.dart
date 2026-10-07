import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';

void main() {
  group('RFC 7807 ProblemDetail Parsing', () {
    test('deve parsear JSON RFC 7807 com fieldErrors (contrato real backend 400)', () {
      final json = {
        'type': 'https://api.rewit.app/errors/validation-error',
        'title': 'Erro de Validação de Dados',
        'status': 400,
        'detail': 'Parâmetros da requisição inválidos',
        'code': 'VALIDATION_ERROR',
        'fieldErrors': {
          'targets': 'A publicação deve conter pelo menos um alvo avaliado',
          'targets[0].rating': 'A nota deve ser no mínimo 1.0',
        },
        'timestamp': '2026-10-07T14:30:00.000Z',
      };

      final problem = ProblemDetail.fromJson(json);

      expect(problem.status, 400);
      expect(problem.code, 'VALIDATION_ERROR');
      expect(problem.detail, 'Parâmetros da requisição inválidos');
      expect(problem.hasFieldErrors, isTrue);
      expect(problem.fieldErrors, isNotNull);
      expect(problem.fieldErrors?.length, 2);
      expect(problem.getFieldError('targets'), 'A publicação deve conter pelo menos um alvo avaliado');
      expect(problem.getFieldError('targets[0].rating'), 'A nota deve ser no mínimo 1.0');
      expect(problem.getFieldError('inexistente'), isNull);
      expect(problem.hasFieldError('targets'), isTrue);
      expect(problem.hasFieldError('inexistente'), isFalse);
      expect(problem.allFieldErrors.length, 2);
    });

    test('deve parsear JSON RFC 7807 400 sem fieldErrors mantendo mensagem genérica', () {
      final json = {
        'type': 'https://api.rewit.app/errors/malformed-request',
        'title': 'Requisição Inválida',
        'status': 400,
        'detail': 'Corpo da requisição inválido ou mal formatado',
        'code': 'MALFORMED_REQUEST',
      };

      final problem = ProblemDetail.fromJson(json);

      expect(problem.status, 400);
      expect(problem.code, 'MALFORMED_REQUEST');
      expect(problem.detail, 'Corpo da requisição inválido ou mal formatado');
      expect(problem.fieldErrors, isNull);
      expect(problem.hasFieldErrors, isFalse);
      expect(problem.getFieldError('qualquer'), isNull);
      expect(problem.hasFieldError('qualquer'), isFalse);
      expect(problem.allFieldErrors, isEmpty);
    });

    test('deve preservar compatibilidade com formato legado invalidParams', () {
      final json = {
        'type': 'https://api.rewit.com/errors/invalid-credentials',
        'title': 'Credenciais Inválidas',
        'status': 400,
        'detail': 'Dados de entrada incorretos.',
        'code': 'INVALID_PARAMS',
        'invalidParams': {
          'email': 'Formato de e-mail inválido',
          'age': 17,
        },
      };

      final problem = ProblemDetail.fromJson(json);

      expect(problem.status, 400);
      expect(problem.code, 'INVALID_PARAMS');
      expect(problem.invalidParams, isNotNull);
      expect(problem.hasFieldErrors, isTrue);
      expect(problem.getFieldError('email'), 'Formato de e-mail inválido');
      expect(problem.getFieldError('age'), '17');
      expect(problem.hasFieldError('email'), isTrue);
      expect(problem.allFieldErrors['email'], 'Formato de e-mail inválido');
      expect(problem.allFieldErrors['age'], '17');
    });

    test('fieldErrors tem precedência sobre invalidParams quando ambos presentes', () {
      final json = {
        'status': 400,
        'detail': 'Erro composto',
        'fieldErrors': {'username': 'Nome de usuário em uso'},
        'invalidParams': {'username': 'Formato incorreto'},
      };

      final problem = ProblemDetail.fromJson(json);

      expect(problem.getFieldError('username'), 'Nome de usuário em uso');
      expect(problem.allFieldErrors['username'], 'Nome de usuário em uso');
    });

    test('preservação estrita do código RFC 7807 (code)', () {
      final json = {
        'type': 'https://api.rewit.app/errors/duplicate-target',
        'title': 'Alvo Duplicado',
        'status': 422,
        'detail': 'Alvo já avaliado.',
        'code': 'DUPLICATE_REVIEW_TARGET',
      };

      final problem = ProblemDetail.fromJson(json);

      expect(problem.code, 'DUPLICATE_REVIEW_TARGET');
      expect(problem.status, 422);

      const apiException = ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Erro',
          status: 422,
          detail: 'Alvo já avaliado.',
          code: 'DUPLICATE_REVIEW_TARGET',
        ),
      );
      expect(apiException.errorCode, 'DUPLICATE_REVIEW_TARGET');
      expect(apiException.isValidationError, isTrue);
    });

    test('erro 429 com Retry-After', () {
      const rateLimited = ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/rate-limit',
          title: 'Limite Excedido',
          status: 429,
          detail: 'Limite de requisições excedido.',
          code: 'RATE_LIMIT_EXCEEDED',
        ),
        retryAfterSeconds: 30,
      );

      expect(rateLimited.statusCode, 429);
      expect(rateLimited.isRateLimited, isTrue);
      expect(rateLimited.retryAfterSeconds, 30);
      expect(rateLimited.errorCode, 'RATE_LIMIT_EXCEEDED');
      expect(rateLimited.detail, 'Limite de requisições excedido.');
    });

    test('deve aplicar fallbacks para JSON RFC 7807 com campos ausentes', () {
      final json = <String, dynamic>{
        'detail': 'Acesso negado ao recurso.',
      };

      final problem = ProblemDetail.fromJson(json, fallbackStatus: 403);

      expect(problem.type, 'about:blank');
      expect(problem.title, 'Erro na requisição');
      expect(problem.status, 403);
      expect(problem.detail, 'Acesso negado ao recurso.');
      expect(problem.code, isNull);
      expect(problem.instance, isNull);
    });

    test('deve converter corpo vazio em ProblemDetail padrão com status HTTP', () {
      final problem = ProblemDetail.fromResponseBody('', 404);

      expect(problem.status, 404);
      expect(problem.title, 'Não Encontrado');
      expect(problem.detail, 'Resposta vazia do servidor.');
    });

    test('deve tratar resposta não-JSON (texto simples ou HTML) de forma segura', () {
      const plainText = 'Bad Gateway: Nginx 502';
      final problem = ProblemDetail.fromResponseBody(plainText, 502);

      expect(problem.status, 502);
      expect(problem.title, 'Serviço Indisponível');
      expect(problem.detail, plainText);
    });

    test('deve serializar e deserializar via toJson mantendo integridade com fieldErrors', () {
      final original = ProblemDetail(
        type: 'urn:problem:validation',
        title: 'Dados Inválidos',
        status: 400,
        detail: 'Campos incorretos',
        code: 'VALIDATION_FAILED',
        timestamp: DateTime.parse('2026-10-06T10:00:00.000Z'),
        fieldErrors: {'targets': 'Pelo menos um alvo é obrigatório'},
        invalidParams: {'handle': 'Já em uso'},
      );

      final jsonMap = original.toJson();
      final reconstructed = ProblemDetail.fromJson(jsonMap);

      expect(reconstructed.type, original.type);
      expect(reconstructed.title, original.title);
      expect(reconstructed.status, original.status);
      expect(reconstructed.detail, original.detail);
      expect(reconstructed.code, original.code);
      expect(reconstructed.fieldErrors, original.fieldErrors);
      expect(reconstructed.invalidParams, original.invalidParams);
      expect(reconstructed.getFieldError('targets'), 'Pelo menos um alvo é obrigatório');
    });
  });

  group('ApiException delegadores e helpers', () {
    test('expõe delegação direta para consulta de fieldErrors', () {
      const apiException = ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Erro de Validação',
          status: 400,
          detail: 'Parâmetros inválidos',
          code: 'VALIDATION_ERROR',
          fieldErrors: {'rating': 'Nota deve ser entre 1.0 e 5.0'},
        ),
      );

      expect(apiException.hasFieldErrors, isTrue);
      expect(apiException.hasFieldError('rating'), isTrue);
      expect(apiException.hasFieldError('comment'), isFalse);
      expect(apiException.getFieldError('rating'), 'Nota deve ser entre 1.0 e 5.0');
      expect(apiException.getFieldError('comment'), isNull);
      expect(apiException.allFieldErrors, {'rating': 'Nota deve ser entre 1.0 e 5.0'});
      expect(apiException.isValidationError, isTrue);
    });

    test('deve expor getters de status com precisão', () {
      const unauthorized = ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Não Autorizado',
        status: 401,
        detail: 'Token expirado',
      ));
      expect(unauthorized.isUnauthorized, isTrue);
      expect(unauthorized.isForbidden, isFalse);

      const forbidden = ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Proibido',
        status: 403,
        detail: 'Permissão insuficiente',
      ));
      expect(forbidden.isForbidden, isTrue);

      const notFound = ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Não Encontrado',
        status: 404,
        detail: 'Recurso não encontrado',
      ));
      expect(notFound.isNotFound, isTrue);

      const conflict = ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Conflito',
        status: 409,
        detail: 'Email já existe',
      ));
      expect(conflict.isConflict, isTrue);

      const serverError = ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Erro Interno',
        status: 500,
        detail: 'Falha no banco',
      ));
      expect(serverError.isServerError, isTrue);
    });
  });
}
