package com.rewit.infrastructure.ratelimit;

import com.rewit.application.ratelimit.RateLimitedAction;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Propriedades do rate limiting distribuído, prefixo {@code rewit.rate-limit}.
 *
 * <p>Os limites de denúncias, comentários e uploads são os mesmos dos limitadores em memória que esta
 * configuração substitui (10, 15 e 10 por usuário em 60 segundos). Não há limite por IP: o uso de IP depende
 * da decisão de produto/jurídico pendente (ADR-011).
 */
@Component
@ConfigurationProperties(prefix = "rewit.rate-limit")
public class RateLimitProperties {

    static final int MIN_KEY_SECRET_BYTES = 32;
    private static final int MIN_KEY_SECRET_DISTINCT_CHARS = 8;

    /** Liga/desliga todo o rate limiting. Desligado, toda tentativa é concedida e o Redis não é consultado. */
    private boolean enabled = true;

    /**
     * Segredo do HMAC que deriva as chaves do Redis a partir do sujeito (e-mail, usuário). Obrigatório com o
     * rate limiting ligado e igual em todas as instâncias, senão cada instância conta em chaves próprias.
     */
    private String keySecret;

    private final Backend backend = new Backend();
    private final Auth auth = new Auth();
    private final Content content = new Content();
    private final QueryPolicies query = new QueryPolicies();

    public Policy policyFor(RateLimitedAction action) {
        return switch (action) {
            case LOGIN -> auth.login;
            case REFRESH -> auth.refresh;
            case REGISTRATION -> auth.registration;
            case REPORT_CREATION -> content.reportCreation;
            case DISCUSSION_CREATION -> content.discussionCreation;
            case MEDIA_UPLOAD -> content.mediaUpload;
            case SEARCH -> query.search;
        };
    }

    /**
     * Valida a configuração antes de montar o rate limiter. Não inclui o segredo nas mensagens.
     *
     * @throws IllegalStateException com todas as violações encontradas
     */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (enabled) {
            validateKeySecret(errors);
        }
        if (backend.failureMode == null) {
            errors.add("backend.failure-mode é obrigatório");
        }
        if (backend.retryInterval == null || backend.retryInterval.isNegative() || backend.retryInterval.isZero()) {
            errors.add("backend.retry-interval deve ser positivo");
        }
        if (backend.localFallbackMaxKeys <= 0) {
            errors.add("backend.local-fallback-max-keys deve ser positivo");
        }
        for (RateLimitedAction action : RateLimitedAction.values()) {
            Policy policy = policyFor(action);
            if (policy.limit <= 0) {
                errors.add(action + ": limit deve ser positivo");
            }
            if (policy.window == null || policy.window.toMillis() <= 0) {
                errors.add(action + ": window deve ser de pelo menos 1ms");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuração inválida de rewit.rate-limit: " + String.join("; ", errors));
        }
    }

    private void validateKeySecret(List<String> errors) {
        if (keySecret == null || keySecret.isBlank()) {
            errors.add("key-secret é obrigatório com o rate limiting ligado (RATE_LIMIT_KEY_SECRET)");
            return;
        }
        if (keySecret.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_SECRET_BYTES) {
            errors.add("key-secret deve ter no mínimo " + MIN_KEY_SECRET_BYTES + " bytes");
        }
        if (keySecret.chars().distinct().count() < MIN_KEY_SECRET_DISTINCT_CHARS) {
            errors.add("key-secret tem baixa variedade de caracteres");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret;
    }

    public Backend getBackend() {
        return backend;
    }

    public Auth getAuth() {
        return auth;
    }

    public Content getContent() {
        return content;
    }

    public QueryPolicies getQuery() {
        return query;
    }

    /** Comportamento do rate limiter em relação ao Redis. */
    public static class Backend {

        /** Decisão enquanto o Redis está indisponível. */
        private RateLimitBackendFailureMode failureMode = RateLimitBackendFailureMode.LOCAL_FALLBACK;

        /** Tempo sem consultar o Redis após uma falha; depois dele, uma única requisição testa o Redis. */
        private Duration retryInterval = Duration.ofSeconds(10);

        /** Máximo de sujeitos rastreados em memória no fallback local; acima dele, sujeitos novos são negados. */
        private int localFallbackMaxKeys = 100_000;

        public RateLimitBackendFailureMode getFailureMode() {
            return failureMode;
        }

        public void setFailureMode(RateLimitBackendFailureMode failureMode) {
            this.failureMode = failureMode;
        }

        public Duration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(Duration retryInterval) {
            this.retryInterval = retryInterval;
        }

        public int getLocalFallbackMaxKeys() {
            return localFallbackMaxKeys;
        }

        public void setLocalFallbackMaxKeys(int localFallbackMaxKeys) {
            this.localFallbackMaxKeys = localFallbackMaxKeys;
        }
    }

    /** Políticas dos endpoints públicos de autenticação. */
    public static class Auth {

        /** Tentativas de login sem sucesso por identidade (e-mail normalizado); login bem-sucedido não conta. */
        private final Policy login = new Policy(10, Duration.ofMinutes(15));

        /** Rotações de refresh token por usuário. */
        private final Policy refresh = new Policy(30, Duration.ofMinutes(5));

        /** Tentativas de cadastro somadas de todos os clientes (sem IP, não há sujeito por cliente). */
        private final Policy registration = new Policy(30, Duration.ofMinutes(1));

        public Policy getLogin() {
            return login;
        }

        public Policy getRefresh() {
            return refresh;
        }

        public Policy getRegistration() {
            return registration;
        }
    }

    /** Políticas de criação de conteúdo por usuário autenticado. */
    public static class Content {

        private final Policy reportCreation = new Policy(10, Duration.ofSeconds(60));
        private final Policy discussionCreation = new Policy(15, Duration.ofSeconds(60));
        private final Policy mediaUpload = new Policy(10, Duration.ofSeconds(60));

        public Policy getReportCreation() {
            return reportCreation;
        }

        public Policy getDiscussionCreation() {
            return discussionCreation;
        }

        public Policy getMediaUpload() {
            return mediaUpload;
        }
    }

    /** Políticas de consultas por usuário autenticado (C5.1). */
    public static class QueryPolicies {

        /**
         * Busca global por usuário. O app consulta com debounce de 350 ms e mínimo de 2 caracteres, então quem digita
         * fica bem abaixo de uma busca por segundo em média; o limite contém varreduras automatizadas do catálogo.
         */
        private final Policy search = new Policy(60, Duration.ofSeconds(60));

        public Policy getSearch() {
            return search;
        }
    }

    /** Janela deslizante de uma ação: no máximo {@code limit} tentativas concedidas em {@code window}. */
    public static class Policy {

        private boolean enabled = true;
        private int limit;
        private Duration window;

        public Policy() {
        }

        public Policy(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
