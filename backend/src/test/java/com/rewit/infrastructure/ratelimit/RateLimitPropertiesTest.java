package com.rewit.infrastructure.ratelimit;

import com.rewit.application.ratelimit.RateLimitedAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rate limiting: propriedades, defaults e validação")
class RateLimitPropertiesTest {

    @Test
    @DisplayName("Limites de denúncias, comentários e uploads preservam os dos limitadores em memória substituídos")
    void contentDefaultsPreserveFormerInMemoryLimits() {
        RateLimitProperties properties = new RateLimitProperties();

        assertPolicy(properties, RateLimitedAction.REPORT_CREATION, 10, Duration.ofSeconds(60));
        assertPolicy(properties, RateLimitedAction.DISCUSSION_CREATION, 15, Duration.ofSeconds(60));
        assertPolicy(properties, RateLimitedAction.MEDIA_UPLOAD, 10, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Defaults de autenticação e de comportamento com o Redis indisponível")
    void authAndBackendDefaults() {
        RateLimitProperties properties = new RateLimitProperties();

        assertTrue(properties.isEnabled());
        assertPolicy(properties, RateLimitedAction.LOGIN, 10, Duration.ofMinutes(15));
        assertPolicy(properties, RateLimitedAction.REFRESH, 30, Duration.ofMinutes(5));
        assertPolicy(properties, RateLimitedAction.REGISTRATION, 30, Duration.ofMinutes(1));
        assertEquals(RateLimitBackendFailureMode.LOCAL_FALLBACK, properties.getBackend().getFailureMode());
        assertEquals(Duration.ofSeconds(10), properties.getBackend().getRetryInterval());
    }

    @Test
    @DisplayName("Mensagens de limite excedido das ações de conteúdo continuam as mesmas")
    void contentMessagesArePreserved() {
        assertEquals("Limite de denúncias excedido. Tente novamente mais tarde.",
                RateLimitedAction.REPORT_CREATION.exceededMessage());
        assertEquals("Limite de criação de comentários excedido. Tente novamente mais tarde.",
                RateLimitedAction.DISCUSSION_CREATION.exceededMessage());
        assertEquals("Limite de upload de mídia excedido. Tente novamente mais tarde.",
                RateLimitedAction.MEDIA_UPLOAD.exceededMessage());
    }

    @Test
    @DisplayName("Ligado, o rate limiting exige key-secret")
    void enabledRequiresKeySecret() {
        RateLimitProperties properties = new RateLimitProperties();

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("key-secret é obrigatório"));
    }

    @Test
    @DisplayName("key-secret curto ou de baixa variedade é rejeitado, sem expor o valor na mensagem")
    void weakKeySecretIsRejectedWithoutLeakingIt() {
        RateLimitProperties shortSecret = new RateLimitProperties();
        shortSecret.setKeySecret("curto-demais-1234");
        IllegalStateException shortEx = assertThrows(IllegalStateException.class, shortSecret::validate);
        assertTrue(shortEx.getMessage().contains("mínimo 32 bytes"));
        assertFalse(shortEx.getMessage().contains("curto-demais-1234"));

        RateLimitProperties lowVariety = new RateLimitProperties();
        lowVariety.setKeySecret("abababababababababababababababababab");
        IllegalStateException varietyEx = assertThrows(IllegalStateException.class, lowVariety::validate);
        assertTrue(varietyEx.getMessage().contains("baixa variedade"));
        assertFalse(varietyEx.getMessage().contains("abababab"));
    }

    @Test
    @DisplayName("Desligado, o rate limiting não exige key-secret")
    void disabledDoesNotRequireKeySecret() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(false);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    @DisplayName("Limite, janela e intervalo de nova tentativa inválidos são reportados juntos")
    void invalidPolicyAndBackendValuesAreReported() {
        RateLimitProperties properties = RateLimitTestSupport.properties();
        properties.getAuth().getLogin().setLimit(0);
        properties.getContent().getMediaUpload().setWindow(Duration.ZERO);
        properties.getBackend().setRetryInterval(Duration.ZERO);
        properties.getBackend().setFailureMode(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(ex.getMessage().contains("LOGIN: limit deve ser positivo"));
        assertTrue(ex.getMessage().contains("MEDIA_UPLOAD: window deve ser de pelo menos 1ms"));
        assertTrue(ex.getMessage().contains("backend.retry-interval deve ser positivo"));
        assertTrue(ex.getMessage().contains("backend.failure-mode é obrigatório"));
    }

    private static void assertPolicy(RateLimitProperties properties, RateLimitedAction action, int limit, Duration window) {
        RateLimitProperties.Policy policy = properties.policyFor(action);
        assertTrue(policy.isEnabled(), action + " habilitada");
        assertEquals(limit, policy.getLimit(), action + " limite");
        assertEquals(window, policy.getWindow(), action + " janela");
    }
}
