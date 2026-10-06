package com.rewit.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.common.exception.BusinessException;
import com.rewit.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes do Tratamento Global de Exceções (RFC 7807)")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BusinessException deve gerar ProblemDetail formatado segundo RFC 7807 sem Retry-After")
    void shouldFormatBusinessExceptionProblemDetail() {
        BusinessException ex = new BusinessException("Nota fora do intervalo permitido", HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RATING_RANGE");

        ResponseEntity<ProblemDetail> response = handler.handleBusinessException(ex);

        assertNotNull(response);
        assertEquals(422, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), "erros comuns de negócio não têm Retry-After");

        ProblemDetail problem = response.getBody();
        assertNotNull(problem);
        assertEquals(422, problem.getStatus());
        assertEquals("Nota fora do intervalo permitido", problem.getDetail());
        assertEquals("Regra de Negócio Violada", problem.getTitle());
        assertEquals("INVALID_RATING_RANGE", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("BusinessException com retryAfter deve incluir cabeçalho Retry-After com segundos e manter RFC 7807")
    void shouldIncludeRetryAfterHeaderWhenPresent() {
        BusinessException ex = new BusinessException(
                "Muitas tentativas de login. Tente novamente mais tarde.",
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                Duration.ofSeconds(45)
        );

        ResponseEntity<ProblemDetail> response = handler.handleBusinessException(ex);

        assertNotNull(response);
        assertEquals(429, response.getStatusCode().value());
        assertEquals("45", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

        ProblemDetail problem = response.getBody();
        assertNotNull(problem);
        assertEquals(429, problem.getStatus());
        assertEquals("Muitas tentativas de login. Tente novamente mais tarde.", problem.getDetail());
        assertEquals("Regra de Negócio Violada", problem.getTitle());
        assertEquals("RATE_LIMIT_EXCEEDED", problem.getProperties().get("code"));
        assertEquals("https://api.rewit.app/errors/rate_limit_exceeded", String.valueOf(problem.getType()));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("BusinessException com retryAfter subsegundo deve garantir Retry-After positivo de no mínimo 1")
    void shouldClampSubsecondRetryAfterToOneSecond() {
        BusinessException ex = new BusinessException(
                "Limite excedido",
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                Duration.ofMillis(300)
        );

        ResponseEntity<ProblemDetail> response = handler.handleBusinessException(ex);

        assertNotNull(response);
        assertEquals(429, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("DataIntegrityViolationException com uq_profiles_handle_lower deve gerar 409 HANDLE_ALREADY_EXISTS")
    void shouldFormatDataIntegrityViolationForHandleProblemDetail() {
        org.springframework.dao.DataIntegrityViolationException ex = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_profiles_handle_lower\"")
        );

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("Nome de usuário (@handle) já está em uso", problem.getDetail());
        assertEquals("HANDLE_ALREADY_EXISTS", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("DataIntegrityViolationException com uq_users_email_lower deve gerar 409 EMAIL_ALREADY_EXISTS")
    void shouldFormatDataIntegrityViolationForEmailProblemDetail() {
        org.springframework.dao.DataIntegrityViolationException ex = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_users_email_lower\"")
        );

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("E-mail já cadastrado na plataforma", problem.getDetail());
        assertEquals("EMAIL_ALREADY_EXISTS", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("DataIntegrityViolationException com constraint genérica deve gerar 409 DATA_INTEGRITY_CONFLICT")
    void shouldFormatGenericDataIntegrityViolationProblemDetail() {
        org.springframework.dao.DataIntegrityViolationException ex = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: insert or update on table \"profiles\" violates foreign key constraint \"fk_other\"")
        );

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertNotNull(problem);
        assertEquals(409, problem.getStatus());
        assertEquals("Conflito de integridade de dados", problem.getDetail());
        assertEquals("DATA_INTEGRITY_CONFLICT", problem.getProperties().get("code"));
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("NoResourceFoundException deve gerar 404 RESOURCE_NOT_FOUND, no mesmo formato dos demais erros, sem log")
    void shouldMapNoResourceFoundTo404WithoutLogging() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/actuator/prometheus", "actuator/prometheus");

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleNoResourceFound(ex);

            assertEquals(404, problem.getStatus());
            assertEquals("Recurso não encontrado", problem.getDetail());
            assertEquals("Recurso Não Encontrado", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/resource-not-found", String.valueOf(problem.getType()));
            assertEquals("RESOURCE_NOT_FOUND", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
            assertFalse(String.valueOf(problem.getDetail()).contains("actuator"), "o path pedido não é devolvido");
        });

        assertTrue(logs.isEmpty(), "rota inexistente é erro do cliente: nenhum log");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException deve gerar 400 VALIDATION_ERROR e mapa fieldErrors")
    void shouldFormatValidationExceptionProblemDetail() throws Exception {
        java.lang.reflect.Method method = String.class.getMethod("toString");
        org.springframework.core.MethodParameter methodParameter = new org.springframework.core.MethodParameter(method, -1);
        org.springframework.validation.BeanPropertyBindingResult bindingResult =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new org.springframework.validation.FieldError("target", "email", "Email inválido"));

        org.springframework.web.bind.MethodArgumentNotValidException ex =
                new org.springframework.web.bind.MethodArgumentNotValidException(methodParameter, bindingResult);

        ProblemDetail problem = handler.handleValidationException(ex);

        assertEquals(400, problem.getStatus());
        assertEquals("Erro de Validação de Dados", problem.getTitle());
        assertEquals("https://api.rewit.app/errors/validation-error", String.valueOf(problem.getType()));
        assertEquals("VALIDATION_ERROR", problem.getProperties().get("code"));
        assertEquals("Parâmetros da requisição inválidos", problem.getDetail());
        assertNotNull(problem.getProperties().get("timestamp"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> fieldErrors = (java.util.Map<String, String>) problem.getProperties().get("fieldErrors");
        assertNotNull(fieldErrors);
        assertEquals("Email inválido", fieldErrors.get("email"));
    }

    @Test
    @DisplayName("HttpMessageNotReadableException deve gerar 400 MALFORMED_REQUEST")
    void shouldFormatHttpMessageNotReadableProblemDetail() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("JSON parse error", new org.springframework.mock.http.MockHttpInputMessage("invalid".getBytes()));

        ProblemDetail problem = handler.handleHttpMessageNotReadable(ex);

        assertEquals(400, problem.getStatus());
        assertEquals("Requisição Inválida", problem.getTitle());
        assertEquals("https://api.rewit.app/errors/malformed-request", String.valueOf(problem.getType()));
        assertEquals("MALFORMED_REQUEST", problem.getProperties().get("code"));
        assertEquals("Corpo da requisição inválido ou mal formatado", problem.getDetail());
        assertNotNull(problem.getProperties().get("timestamp"));
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException deve gerar 405 METHOD_NOT_ALLOWED sem log")
    void shouldMapMethodNotAllowedTo405WithoutLogging() {
        org.springframework.web.HttpRequestMethodNotSupportedException ex =
                new org.springframework.web.HttpRequestMethodNotSupportedException("POST", List.of("GET"));

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleHttpRequestMethodNotSupported(ex);

            assertEquals(405, problem.getStatus());
            assertEquals("Método HTTP não suportado para este recurso", problem.getDetail());
            assertEquals("Método Não Permitido", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/method-not-allowed", String.valueOf(problem.getType()));
            assertEquals("METHOD_NOT_ALLOWED", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException deve gerar 415 UNSUPPORTED_MEDIA_TYPE sem log")
    void shouldMapMediaTypeNotSupportedTo415WithoutLogging() {
        org.springframework.web.HttpMediaTypeNotSupportedException ex =
                new org.springframework.web.HttpMediaTypeNotSupportedException(org.springframework.http.MediaType.TEXT_PLAIN, List.of(org.springframework.http.MediaType.APPLICATION_JSON));

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleHttpMediaTypeNotSupported(ex);

            assertEquals(415, problem.getStatus());
            assertEquals("Tipo de mídia da requisição não suportado", problem.getDetail());
            assertEquals("Tipo de Mídia Não Suportado", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/unsupported-media-type", String.valueOf(problem.getType()));
            assertEquals("UNSUPPORTED_MEDIA_TYPE", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("HttpMediaTypeNotAcceptableException deve gerar 406 NOT_ACCEPTABLE sem log")
    void shouldMapMediaTypeNotAcceptableTo406WithoutLogging() {
        org.springframework.web.HttpMediaTypeNotAcceptableException ex =
                new org.springframework.web.HttpMediaTypeNotAcceptableException(List.of(org.springframework.http.MediaType.APPLICATION_JSON));

        List<ILoggingEvent> logs = captureLogs(() -> {
            org.springframework.http.ResponseEntity<ProblemDetail> response = handler.handleHttpMediaTypeNotAcceptable(ex);

            assertEquals(406, response.getStatusCode().value());
            assertEquals(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
            ProblemDetail problem = response.getBody();
            assertNotNull(problem);
            assertEquals(406, problem.getStatus());
            assertEquals("Nenhuma representação aceitável encontrada para o cabeçalho Accept solicitado", problem.getDetail());
            assertEquals("Não Aceitável", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/not-acceptable", String.valueOf(problem.getType()));
            assertEquals("NOT_ACCEPTABLE", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException deve gerar 400 MISSING_PARAMETER sem log")
    void shouldMapMissingServletRequestParameterTo400WithoutLogging() {
        org.springframework.web.bind.MissingServletRequestParameterException ex =
                new org.springframework.web.bind.MissingServletRequestParameterException("q", "String");

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleMissingServletRequestParameter(ex);

            assertEquals(400, problem.getStatus());
            assertEquals("Parâmetro obrigatório da requisição não informado: q", problem.getDetail());
            assertEquals("Parâmetro Ausente", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/missing-parameter", String.valueOf(problem.getType()));
            assertEquals("MISSING_PARAMETER", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException deve gerar 400 TYPE_MISMATCH sem log")
    void shouldMapMethodArgumentTypeMismatchTo400WithoutLogging() {
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException("abc", Integer.class, "page", null, null);

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleMethodArgumentTypeMismatch(ex);

            assertEquals(400, problem.getStatus());
            assertEquals("Parâmetro da requisição com tipo ou formato inválido: page", problem.getDetail());
            assertEquals("Tipo de Parâmetro Inválido", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/type-mismatch", String.valueOf(problem.getType()));
            assertEquals("TYPE_MISMATCH", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("MissingServletRequestPartException deve gerar 400 MISSING_REQUEST_PART sem log")
    void shouldMapMissingServletRequestPartTo400WithoutLogging() {
        org.springframework.web.multipart.support.MissingServletRequestPartException ex =
                new org.springframework.web.multipart.support.MissingServletRequestPartException("file");

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleMissingServletRequestPart(ex);

            assertEquals(400, problem.getStatus());
            assertEquals("Parte obrigatória da requisição não informada: file", problem.getDetail());
            assertEquals("Parte da Requisição Ausente", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/missing-request-part", String.valueOf(problem.getType()));
            assertEquals("MISSING_REQUEST_PART", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException deve gerar 413 PAYLOAD_TOO_LARGE sem log")
    void shouldMapMaxUploadSizeExceededTo413WithoutLogging() {
        org.springframework.web.multipart.MaxUploadSizeExceededException ex =
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10485760);

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleMaxUploadSizeExceeded(ex);

            assertEquals(413, problem.getStatus());
            assertEquals("O tamanho do arquivo excede o limite máximo permitido", problem.getDetail());
            assertEquals("Arquivo Muito Grande", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/payload-too-large", String.valueOf(problem.getType()));
            assertEquals("PAYLOAD_TOO_LARGE", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("NoHandlerFoundException deve gerar 404 RESOURCE_NOT_FOUND sem log")
    void shouldMapNoHandlerFoundTo404WithoutLogging() {
        org.springframework.web.servlet.NoHandlerFoundException ex =
                new org.springframework.web.servlet.NoHandlerFoundException("GET", "/desconhecido", org.springframework.http.HttpHeaders.EMPTY);

        List<ILoggingEvent> logs = captureLogs(() -> {
            ProblemDetail problem = handler.handleNoResourceFound(ex);

            assertEquals(404, problem.getStatus());
            assertEquals("Recurso não encontrado", problem.getDetail());
            assertEquals("Recurso Não Encontrado", problem.getTitle());
            assertEquals("https://api.rewit.app/errors/resource-not-found", String.valueOf(problem.getType()));
            assertEquals("RESOURCE_NOT_FOUND", problem.getProperties().get("code"));
            assertNotNull(problem.getProperties().get("timestamp"));
        });

        assertTrue(logs.isEmpty(), "erro 4xx não deve gerar log");
    }

    @Test
    @DisplayName("ErrorResponse genérica com status 4xx deve ser tratada como CLIENT_ERROR no handleGeneralException sem log ERROR")
    void shouldHandleGeneric4xxErrorResponseWithoutErrorLog() throws Exception {
        org.springframework.web.ErrorResponseException ex =
                new org.springframework.web.ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT);

        List<ILoggingEvent> logs = captureLogs(() -> {
            try {
                ProblemDetail problem = handler.handleGeneralException(ex);

                assertEquals(422, problem.getStatus());
                assertEquals("Requisição Inválida", problem.getTitle());
                assertEquals("CLIENT_ERROR", problem.getProperties().get("code"));
                assertNotNull(problem.getProperties().get("timestamp"));
            } catch (Exception e) {
                fail("não deve lançar exceção", e);
            }
        });

        assertTrue(logs.isEmpty(), "4xx genérico não gera log de erro");
    }

    @Test
    @DisplayName("Exceção inesperada continua gerando 500 e um log ERROR, no formato de erro existente")
    void shouldKeepUnexpectedExceptionAs500WithErrorLog() {
        List<ILoggingEvent> logs = captureLogs(() -> {
            try {
                ProblemDetail problem = handler.handleGeneralException(new IllegalStateException("falha interna"));

                assertEquals(500, problem.getStatus());
                assertEquals("Ocorreu um erro interno inesperado no servidor.", problem.getDetail());
                assertEquals("Erro Interno do Servidor", problem.getTitle());
                assertEquals("https://api.rewit.app/errors/internal-server-error", String.valueOf(problem.getType()));
                assertNotNull(problem.getProperties().get("timestamp"));
            } catch (Exception e) {
                fail("o handler genérico não deve relançar exceção comum");
            }
        });

        assertEquals(1, logs.size());
        assertEquals(Level.ERROR, logs.getFirst().getLevel());
        assertTrue(logs.getFirst().getFormattedMessage().contains("java.lang.IllegalStateException"));
    }

    private List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
