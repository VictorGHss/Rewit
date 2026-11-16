import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/reports/data/models/report_models.dart';
import 'package:rewit_mobile/features/reports/data/repositories/report_repository_impl.dart';
import 'package:rewit_mobile/features/reports/domain/entities/report_entities.dart';

String _fakeJwtForSub(String sub) {
  final header = base64Url.encode(utf8.encode(jsonEncode({'alg': 'HS256'})));
  final payload = base64Url.encode(utf8.encode(jsonEncode({'sub': sub})));
  return '$header.$payload.sig';
}

void main() {
  const sampleReviewId = '3fa85f64-5717-4562-b3fc-2c963f66afa6';
  const sampleReportId = '7c9e6679-7425-40de-944b-e07fc1f90ae7';

  Map<String, dynamic> buildReportResponseJson({
    String id = sampleReportId,
    String reviewId = sampleReviewId,
    String reason = 'SPAM',
    String status = 'PENDING',
    String createdAt = '2026-11-15T18:30:00.000Z',
  }) {
    return {
      'id': id,
      'reviewId': reviewId,
      'reason': reason,
      'status': status,
      'createdAt': createdAt,
    };
  }

  group('ReportRepositoryImpl & ReportResponseDto Tests', () {
    test('envia POST autenticado para /api/v1/reports com reviewId, reason e sem reporterUserId', () async {
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken: 'jwt-valid-token',
      );
      late http.Request capturedRequest;

      final mockClient = MockClient((request) async {
        capturedRequest = request;
        return http.Response(
          jsonEncode(buildReportResponseJson(reason: 'SPAM')),
          201,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );
      final repository = ReportRepositoryImpl(httpClient: httpClient);

      final receipt = await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.spam,
        detail: 'Conteúdo promocional repetitivo',
      );

      expect(capturedRequest.method, 'POST');
      expect(capturedRequest.url.path, '/api/v1/reports');
      expect(capturedRequest.headers['Authorization'], 'Bearer jwt-valid-token');

      final body = jsonDecode(capturedRequest.body) as Map<String, dynamic>;
      expect(body['reviewId'], sampleReviewId);
      expect(body['reason'], 'SPAM');
      expect(body['detail'], 'Conteúdo promocional repetitivo');
      expect(body.containsKey('reporterUserId'), isFalse);
      expect(body.containsKey('userId'), isFalse);

      expect(receipt.id, sampleReportId);
      expect(receipt.reviewId, sampleReviewId);
      expect(receipt.reason, ReportReason.spam);
      expect(receipt.status, 'PENDING');
      expect(receipt.createdAt, DateTime.parse('2026-11-15T18:30:00.000Z'));
      expect(receipt.outcome, ReviewReportOutcome.created);
      expect(receipt.isNewlyCreated, isTrue);
      expect(receipt.isAlreadyReported, isFalse);
    });

    test('serializa corretamente todos os 6 motivos de ReportReason aceitos pelo backend', () async {
      final expectedCodes = <ReportReason, String>{
        ReportReason.spam: 'SPAM',
        ReportReason.harassment: 'HARASSMENT',
        ReportReason.hateSpeech: 'HATE_SPEECH',
        ReportReason.misinformation: 'MISINFORMATION',
        ReportReason.inappropriateContent: 'INAPPROPRIATE_CONTENT',
        ReportReason.fraud: 'FRAUD',
      };

      expect(ReportReason.values.length, 6);

      for (final entry in expectedCodes.entries) {
        String? sentReason;
        final mockClient = MockClient((request) async {
          final body = jsonDecode(request.body) as Map<String, dynamic>;
          sentReason = body['reason'] as String?;
          return http.Response(
            jsonEncode(buildReportResponseJson(reason: entry.value)),
            201,
            headers: {'content-type': 'application/json'},
          );
        });

        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
        );
        final repository = ReportRepositoryImpl(httpClient: httpClient);

        final receipt = await repository.reportReview(
          reviewId: sampleReviewId,
          reason: entry.key,
        );

        expect(sentReason, entry.value);
        expect(receipt.reason, entry.key);
      }
    });

    test('aplica trim ao detalhe e omite o campo detail quando nulo, vazio ou apenas espaços', () async {
      final sentBodies = <Map<String, dynamic>>[];

      final mockClient = MockClient((request) async {
        sentBodies.add(jsonDecode(request.body) as Map<String, dynamic>);
        return http.Response(
          jsonEncode(buildReportResponseJson()),
          201,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
      );
      final repository = ReportRepositoryImpl(httpClient: httpClient);

      await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.spam,
        detail: null,
      );
      await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.spam,
        detail: '',
      );
      await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.spam,
        detail: '   \n\t  ',
      );
      await repository.reportReview(
        reviewId: '  $sampleReviewId  ',
        reason: ReportReason.spam,
        detail: '  Detalhe com espaços nas pontas  ',
      );

      expect(sentBodies[0].containsKey('detail'), isFalse);
      expect(sentBodies[1].containsKey('detail'), isFalse);
      expect(sentBodies[2].containsKey('detail'), isFalse);
      expect(sentBodies[3]['reviewId'], sampleReviewId);
      expect(sentBodies[3]['detail'], 'Detalhe com espaços nas pontas');
    });

    test('aceita detalhe com exatos 500 caracteres e rejeita acima de 500 caracteres antes do envio', () async {
      var httpCalls = 0;
      final mockClient = MockClient((request) async {
        httpCalls++;
        return http.Response(
          jsonEncode(buildReportResponseJson()),
          201,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
      );
      final repository = ReportRepositoryImpl(httpClient: httpClient);

      final exact500 = 'a' * 500;
      final receipt = await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.misinformation,
        detail: exact500,
      );
      expect(receipt.isNewlyCreated, isTrue);
      expect(httpCalls, 1);

      final over500 = 'a' * 501;
      await expectLater(
        () => repository.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.misinformation,
          detail: over500,
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 400)
              .having((e) => e.errorCode, 'errorCode', 'VALIDATION_ERROR'),
        ),
      );
      expect(httpCalls, 1);
    });

    test('distingue HTTP 201 (created) de HTTP 200 (alreadyReported) mesmo com corpo JSON idêntico', () async {
      var statusToReturn = 201;
      final identicalBody = jsonEncode(buildReportResponseJson(reason: 'FRAUD'));

      final mockClient = MockClient((request) async {
        return http.Response(
          identicalBody,
          statusToReturn,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
      );
      final repository = ReportRepositoryImpl(httpClient: httpClient);

      final createdReceipt = await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.fraud,
      );
      expect(createdReceipt.outcome, ReviewReportOutcome.created);
      expect(createdReceipt.isNewlyCreated, isTrue);
      expect(createdReceipt.isAlreadyReported, isFalse);

      statusToReturn = 200;
      final duplicateReceipt = await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.harassment,
      );
      expect(duplicateReceipt.outcome, ReviewReportOutcome.alreadyReported);
      expect(duplicateReceipt.isNewlyCreated, isFalse);
      expect(duplicateReceipt.isAlreadyReported, isTrue);
      // Backend retorna a denúncia original (FRAUD)
      expect(duplicateReceipt.reason, ReportReason.fraud);
    });

    test('não interpreta resposta como sucesso se JSON for inválido ou status 2xx for inesperado (ex: 204)', () async {
      final mock204Client = MockClient((_) async => http.Response('', 204));
      final repo204 = ReportRepositoryImpl(
        httpClient: RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mock204Client,
        ),
      );

      await expectLater(
        () => repo204.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        ),
        throwsA(isA<FormatException>()),
      );

      expect(
        () => ReportResponseDto.fromJson({'id': sampleReportId}),
        throwsA(isA<FormatException>()),
      );
    });

    test('propaga ApiException com código RFC 7807 e retryAfterSeconds sem mascarar', () async {
      final mockClient = MockClient((_) async {
        return http.Response(
          jsonEncode({
            'type': 'about:blank',
            'title': 'Too Many Requests',
            'status': 429,
            'code': 'RATE_LIMIT_EXCEEDED',
            'detail': 'Limite de denúncias excedido. Tente novamente mais tarde.',
          }),
          429,
          headers: {
            'content-type': 'application/problem+json',
            'retry-after': '42',
          },
        );
      });

      final repository = ReportRepositoryImpl(
        httpClient: RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
        ),
      );

      await expectLater(
        () => repository.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 429)
              .having((e) => e.errorCode, 'errorCode', 'RATE_LIMIT_EXCEEDED')
              .having((e) => e.retryAfterSeconds, 'retryAfterSeconds', 42),
        ),
      );
    });

    test('propaga NetworkException quando ocorre falha de transporte', () async {
      final mockClient = MockClient((_) async {
        throw http.ClientException('Connection reset by peer');
      });

      final repository = ReportRepositoryImpl(
        httpClient: RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
        ),
      );

      await expectLater(
        () => repository.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        ),
        throwsA(isA<NetworkException>()),
      );
    });

    test('preserva renovação automática de sessão via TokenRefreshingClient em 401 AUTHENTICATION_REQUIRED', () async {
      final expiredToken = _fakeJwtForSub('user-123');
      final refreshedToken = _fakeJwtForSub('user-123');
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken: expiredToken,
        initialRefreshToken: 'refresh-1',
      );

      var attempts = 0;
      var sessionExpiredCalled = false;

      final innerClient = MockClient((request) async {
        attempts++;
        if (attempts == 1) {
          expect(request.headers['authorization'], 'Bearer $expiredToken');
          return http.Response(
            jsonEncode({
              'status': 401,
              'code': 'AUTHENTICATION_REQUIRED',
              'detail': 'Token expirado',
            }),
            401,
            headers: {'content-type': 'application/problem+json'},
          );
        }
        expect(request.headers['authorization'], 'Bearer $refreshedToken');
        return http.Response(
          jsonEncode(buildReportResponseJson(reason: 'HARASSMENT')),
          201,
          headers: {'content-type': 'application/json'},
        );
      });

      final refresher = SessionRefresher(
        refreshAccessToken: () async {
          await tokenStorage.saveTokens(
            accessToken: refreshedToken,
            refreshToken: 'refresh-2',
          );
          return refreshedToken;
        },
      );

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(
          inner: innerClient,
          sessionRefresher: refresher,
        ),
        tokenStorage: tokenStorage,
        onSessionExpired: () {
          sessionExpiredCalled = true;
        },
      );

      final repository = ReportRepositoryImpl(httpClient: httpClient);
      final receipt = await repository.reportReview(
        reviewId: sampleReviewId,
        reason: ReportReason.harassment,
      );

      expect(attempts, 2);
      expect(sessionExpiredCalled, isFalse);
      expect(receipt.outcome, ReviewReportOutcome.created);
      expect(receipt.reason, ReportReason.harassment);
    });

    test('interpreta ReportStatus (PENDING, ACCEPTED, REJECTED e desconhecido) em HTTP 200 e DTO', () async {
      for (final entry in <String, ReportStatus>{
        'PENDING': ReportStatus.pending,
        'accepted': ReportStatus.accepted,
        '  REJECTED  ': ReportStatus.rejected,
        'UNEXPECTED_FUTURE_STATUS': ReportStatus.unknown,
      }.entries) {
        final mockClient = MockClient((_) async {
          return http.Response(
            jsonEncode(buildReportResponseJson(status: entry.key)),
            200,
            headers: {'content-type': 'application/json'},
          );
        });

        final repository = ReportRepositoryImpl(
          httpClient: RewitHttpClient(
            baseUrl: 'https://api.rewit.test',
            client: mockClient,
          ),
        );

        final receipt = await repository.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        );

        expect(receipt.outcome, ReviewReportOutcome.alreadyReported);
        expect(receipt.reportStatus, entry.value);
      }

      expect(ReportStatus.fromBackendValue(null), ReportStatus.unknown);
      expect(ReportStatus.fromBackendValue(''), ReportStatus.unknown);
    });

    test('lança FormatException para JSON malformado, createdAt inválido ou motivo desconhecido', () async {
      final malformedJsonRepo = ReportRepositoryImpl(
        httpClient: RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: MockClient((_) async => http.Response('{invalido', 201)),
        ),
      );
      await expectLater(
        () => malformedJsonRepo.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        ),
        throwsA(isA<FormatException>()),
      );

      expect(
        () => ReportResponseDto.fromJson(
          buildReportResponseJson(createdAt: 'not-an-iso-date'),
        ),
        throwsA(isA<FormatException>()),
      );

      expect(
        () => ReportResponseDto.fromJson(
          buildReportResponseJson(reason: 'UNKNOWN_REASON'),
        ),
        throwsA(isA<FormatException>()),
      );
    });

    test('aciona onSessionExpired e propaga ApiException quando refresh é rejeitado em 401 AUTHENTICATION_REQUIRED', () async {
      final expiredToken = _fakeJwtForSub('user-123');
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken: expiredToken,
        initialRefreshToken: 'refresh-expired',
      );
      var attempts = 0;
      var sessionExpiredCalls = 0;

      final innerClient = MockClient((_) async {
        attempts++;
        return http.Response(
          jsonEncode({
            'status': 401,
            'code': 'AUTHENTICATION_REQUIRED',
            'detail': 'Sessão expirada',
          }),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      final refresher = SessionRefresher(refreshAccessToken: () async => '');

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(
          inner: innerClient,
          sessionRefresher: refresher,
        ),
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredCalls++,
      );

      final repository = ReportRepositoryImpl(httpClient: httpClient);

      await expectLater(
        () => repository.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.errorCode, 'errorCode', 'AUTHENTICATION_REQUIRED'),
        ),
      );

      expect(attempts, 1);
      expect(sessionExpiredCalls, 1);
    });

    test('converte indisponibilidade do refresh em NetworkException sem expirar sessão local', () async {
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken: _fakeJwtForSub('user-123'),
        initialRefreshToken: 'refresh-valid',
      );
      var sessionExpiredCalls = 0;

      final innerClient = MockClient((_) async {
        return http.Response(
          jsonEncode({
            'status': 401,
            'code': 'AUTHENTICATION_REQUIRED',
            'detail': 'Token expirado',
          }),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      final refresher = SessionRefresher(
        refreshAccessToken: () async => throw StateError('Rede indisponível'),
      );

      final repository = ReportRepositoryImpl(
        httpClient: RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: TokenRefreshingClient(
            inner: innerClient,
            sessionRefresher: refresher,
          ),
          tokenStorage: tokenStorage,
          onSessionExpired: () => sessionExpiredCalls++,
        ),
      );

      await expectLater(
        () => repository.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        ),
        throwsA(isA<NetworkException>()),
      );
      expect(sessionExpiredCalls, 0);
    });

    test('não aciona renovação de token em 401 ACCOUNT_DISABLED, entrega detalhes ao callback tipado e propaga erro', () async {
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken: _fakeJwtForSub('user-123'),
        initialRefreshToken: 'refresh-1',
      );
      var refreshCalls = 0;
      SessionExpirationDetails? capturedDetails;

      final innerClient = MockClient((_) async {
        return http.Response(
          jsonEncode({
            'status': 401,
            'code': 'ACCOUNT_DISABLED',
            'detail': 'Conta desativada',
          }),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      final repository = ReportRepositoryImpl(
        httpClient: RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: TokenRefreshingClient(
            inner: innerClient,
            sessionRefresher: SessionRefresher(
              refreshAccessToken: () async {
                refreshCalls++;
                return 'never-used';
              },
            ),
          ),
          tokenStorage: tokenStorage,
          onSessionExpiredWithDetails: (details) => capturedDetails = details,
        ),
      );

      await expectLater(
        () => repository.reportReview(
          reviewId: sampleReviewId,
          reason: ReportReason.spam,
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.errorCode, 'errorCode', 'ACCOUNT_DISABLED'),
        ),
      );
      expect(refreshCalls, 0);
      expect(
        capturedDetails,
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );
    });
  });
}
