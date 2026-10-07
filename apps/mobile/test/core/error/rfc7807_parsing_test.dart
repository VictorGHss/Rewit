import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';

void main() {
  group('RFC 7807 ProblemDetail Parsing', () {
    test('deve parsear JSON RFC 7807 completo com todas as propriedades', () {
      final json = {
        'type': 'https://api.rewit.com/errors/invalid-credentials',
        'title': 'Credenciais Inválidas',
        'status': 401,
        'detail': 'E-mail ou senha incorretos.',
        'instance': '/api/v1/auth/login',
        'code': 'INVALID_CREDENTIALS',
        'timestamp': '2026-10-06T12:00:00.000Z',
        'invalidParams': {'email': 'Formato inválido'},
      };

      final problem = ProblemDetail.fromJson(json);

      expect(problem.type, 'https://api.rewit.com/errors/invalid-credentials');
      expect(problem.title, 'Credenciais Inválidas');
      expect(problem.status, 401);
      expect(problem.detail, 'E-mail ou senha incorretos.');
      expect(problem.instance, '/api/v1/auth/login');
      expect(problem.code, 'INVALID_CREDENTIALS');
      expect(problem.timestamp, isNotNull);
      expect(problem.invalidParams?['email'], 'Formato inválido');
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

    test('deve serializar e deserializar via toJson mantendo integridade', () {
      final original = ProblemDetail(
        type: 'urn:problem:validation',
        title: 'Dados Inválidos',
        status: 400,
        detail: 'Campos incorretos',
        code: 'VALIDATION_FAILED',
        timestamp: DateTime.parse('2026-10-06T10:00:00.000Z'),
        invalidParams: {'handle': 'Já em uso'},
      );

      final jsonMap = original.toJson();
      final reconstructed = ProblemDetail.fromJson(jsonMap);

      expect(reconstructed.type, original.type);
      expect(reconstructed.title, original.title);
      expect(reconstructed.status, original.status);
      expect(reconstructed.detail, original.detail);
      expect(reconstructed.code, original.code);
      expect(reconstructed.invalidParams, original.invalidParams);
    });
  });

  group('ApiException', () {
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

      const rateLimited = ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Limite Excedido',
          status: 429,
          detail: 'Muitas requisições',
        ),
        retryAfterSeconds: 60,
      );
      expect(rateLimited.isRateLimited, isTrue);
      expect(rateLimited.retryAfterSeconds, 60);

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
