package com.rewit.infrastructure.security.social;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Propriedades do login social, prefixo {@code rewit.social-auth}.
 *
 * <p>Um provedor só fica ativo com ao menos um client ID (audiência aceita no ID token). Os client IDs são
 * identificadores públicos: a validação do ID token não usa segredo. Emissores e JWKS têm os valores oficiais
 * dos provedores como padrão.
 */
@Component
@ConfigurationProperties(prefix = "rewit.social-auth")
public class SocialAuthProperties {

    // Pacote Android (applicationId) usado no redirecionamento do fluxo web da Apple de volta ao app
    private static final Pattern ANDROID_PACKAGE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    private final Provider google = new Provider(
            List.of("https://accounts.google.com", "accounts.google.com"),
            "https://www.googleapis.com/oauth2/v3/certs");
    private final Provider apple = new Provider(
            List.of("https://appleid.apple.com"),
            "https://appleid.apple.com/auth/keys");

    /** {@code applicationId} do app Android que recebe o retorno do fluxo web da Apple. */
    private String appleAndroidPackage = "app.rewit.mobile";

    /** Tempo limite de conexão e de leitura ao buscar as chaves públicas (ms). */
    private int jwksTimeoutMs = 5000;

    /**
     * Valida a configuração antes de montar os verificadores.
     *
     * @throws IllegalStateException com todas as violações encontradas
     */
    public void validate() {
        List<String> errors = new ArrayList<>();
        validate("google", google, errors);
        validate("apple", apple, errors);
        if (appleAndroidPackage == null || !ANDROID_PACKAGE.matcher(appleAndroidPackage).matches()) {
            errors.add("apple-android-package deve ser um applicationId Android válido");
        }
        if (jwksTimeoutMs <= 0) {
            errors.add("jwks-timeout-ms deve ser positivo");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuração inválida de rewit.social-auth: " + String.join("; ", errors));
        }
    }

    private static void validate(String name, Provider provider, List<String> errors) {
        if (!provider.isEnabled()) {
            return;
        }
        if (provider.issuers == null || provider.issuers.stream().allMatch(String::isBlank)) {
            errors.add(name + ".issuers é obrigatório com o provedor ativo");
        }
        if (provider.jwksUri == null || !provider.jwksUri.startsWith("https://")) {
            errors.add(name + ".jwks-uri deve usar https");
        }
    }

    public Provider getGoogle() {
        return google;
    }

    public Provider getApple() {
        return apple;
    }

    public String getAppleAndroidPackage() {
        return appleAndroidPackage;
    }

    public void setAppleAndroidPackage(String appleAndroidPackage) {
        this.appleAndroidPackage = appleAndroidPackage;
    }

    public int getJwksTimeoutMs() {
        return jwksTimeoutMs;
    }

    public void setJwksTimeoutMs(int jwksTimeoutMs) {
        this.jwksTimeoutMs = jwksTimeoutMs;
    }

    /** Configuração de um provedor OpenID Connect. */
    public static class Provider {

        /** Audiências aceitas no ID token (client IDs do Rewit no provedor). Vazio desativa o provedor. */
        private List<String> clientIds = new ArrayList<>();
        private List<String> issuers;
        private String jwksUri;

        public Provider() {
        }

        Provider(List<String> issuers, String jwksUri) {
            this.issuers = new ArrayList<>(issuers);
            this.jwksUri = jwksUri;
        }

        public boolean isEnabled() {
            return !configuredClientIds().isEmpty();
        }

        /** Client IDs informados, sem espaços nem entradas vazias (a variável de ambiente é separada por vírgula). */
        public List<String> configuredClientIds() {
            return clientIds == null ? List.of()
                    : clientIds.stream().map(String::trim).filter(id -> !id.isEmpty()).distinct().toList();
        }

        public List<String> getClientIds() {
            return clientIds;
        }

        public void setClientIds(List<String> clientIds) {
            this.clientIds = clientIds;
        }

        public List<String> getIssuers() {
            return issuers;
        }

        public void setIssuers(List<String> issuers) {
            this.issuers = issuers;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }
    }
}
