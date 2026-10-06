package com.rewit.infrastructure.ratelimit;

import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rate limiting: chaves derivadas por HMAC")
class RateLimitKeyDeriverTest {

    private static final String EMAIL = "pessoa.real@rewit.com";

    private final RateLimitKeyDeriver deriver = new RateLimitKeyDeriver(RateLimitTestSupport.TEST_KEY_SECRET);

    @Test
    @DisplayName("A chave não contém e-mail nem id de usuário em claro")
    void keyDoesNotContainSubjectInClear() {
        UUID userId = UUID.randomUUID();

        String identityKey = deriver.derive(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity(EMAIL));
        String userKey = deriver.derive(RateLimitedAction.REFRESH, RateLimitSubject.ofUser(userId));

        assertFalse(identityKey.contains(EMAIL));
        assertFalse(identityKey.contains("pessoa.real"));
        assertFalse(identityKey.contains("@"));
        assertFalse(userKey.contains(userId.toString()));
        assertTrue(identityKey.matches("rewit:rate-limit:login:[A-Za-z0-9_-]{43}"), identityKey);
        assertTrue(userKey.startsWith("rewit:rate-limit:refresh:"));
    }

    @Test
    @DisplayName("Mesmo segredo, ação e sujeito produzem a mesma chave em qualquer instância")
    void derivationIsDeterministicAcrossInstances() {
        RateLimitKeyDeriver otherInstance = new RateLimitKeyDeriver(RateLimitTestSupport.TEST_KEY_SECRET);

        assertEquals(deriver.derive(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity(EMAIL)),
                otherInstance.derive(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity(EMAIL)));
    }

    @Test
    @DisplayName("Segredo, ação ou sujeito diferentes produzem chaves diferentes")
    void keysAreSeparatedBySecretActionAndSubject() {
        String base = deriver.derive(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity(EMAIL));

        RateLimitKeyDeriver otherSecret = new RateLimitKeyDeriver("outro-segredo-de-rate-limit-0123456789-xyz");
        assertNotEquals(base, otherSecret.derive(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity(EMAIL)));
        assertNotEquals(base, deriver.derive(RateLimitedAction.REGISTRATION, RateLimitSubject.ofIdentity(EMAIL)));
        assertNotEquals(base, deriver.derive(RateLimitedAction.LOGIN, RateLimitSubject.ofIdentity("outra@rewit.com")));
    }

    @Test
    @DisplayName("O sujeito não expõe o valor em toString e rejeita identidade vazia")
    void subjectIsRedacted() {
        RateLimitSubject subject = RateLimitSubject.ofIdentity(EMAIL);

        assertFalse(subject.toString().contains(EMAIL));
        assertThrows(IllegalArgumentException.class, () -> RateLimitSubject.ofIdentity(" "));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitKeyDeriver(""));
    }
}
