package com.rewit.infrastructure.security;

import com.rewit.config.SecurityConfig;
import com.rewit.domain.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Hardening F1: Testes Unitários de Decodificação Canônica de Segredos, JWT e Rotação de Pepper")
class AuthHardeningCryptoUnitTest {

    private static final SecureRandom RNG = new SecureRandom();

    private static String randomBase64(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return Base64.getEncoder().encodeToString(buf);
    }

    private static String randomBase64Url(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    // =========================================================================
    // F1-AUD-01: Decodificação canônica de JWT_SECRET entre JwtTokenService e SecurityConfig.jwtDecoder()
    // =========================================================================

    @Test
    @DisplayName("F1-AUD-01: Access token assinado com JWT_SECRET Base64 padrão é aceito pelo JwtDecoder real do Spring Security")
    void jwtIssuedWithStandardBase64SecretIsAcceptedBySpringJwtDecoder() {
        String base64Secret = randomBase64(32); // 44 chars terminando em '='
        String refreshSecret = randomBase64(32);
        JwtTokenService tokenService = new JwtTokenService(base64Secret, refreshSecret);
        SecurityConfig securityConfig = new SecurityConfig(base64Secret, "rewit-api", "rewit-clients");
        JwtDecoder decoder = securityConfig.jwtDecoder();

        UUID userId = UUID.randomUUID();
        String accessToken = tokenService.generateAccessToken(userId, Role.ADMIN);

        Jwt decoded = decoder.decode(accessToken);
        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals("ADMIN", decoded.getClaimAsString("role"));
    }

    @Test
    @DisplayName("F1-AUD-01: Access token assinado com JWT_SECRET Base64URL é aceito pelo JwtDecoder real do Spring Security")
    void jwtIssuedWithBase64UrlSecretIsAcceptedBySpringJwtDecoder() {
        String base64UrlSecret = randomBase64Url(48); // 64 chars Base64URL sem padding
        String refreshSecret = randomBase64Url(48);
        JwtTokenService tokenService = new JwtTokenService(base64UrlSecret, refreshSecret);
        SecurityConfig securityConfig = new SecurityConfig(base64UrlSecret, "rewit-api", "rewit-clients");
        JwtDecoder decoder = securityConfig.jwtDecoder();

        UUID userId = UUID.randomUUID();
        String accessToken = tokenService.generateAccessToken(userId, Role.MODERATOR);

        Jwt decoded = decoder.decode(accessToken);
        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals("MODERATOR", decoded.getClaimAsString("role"));
    }

    @Test
    @DisplayName("F1-AUD-01: Access token assinado com JWT_SECRET textual UTF-8 válido é aceito pelo JwtDecoder real")
    void jwtIssuedWithTextualUtf8SecretIsAcceptedBySpringJwtDecoder() {
        String utf8Secret = "textual-jwt-key-with-symbols-!@#$%^&*()_+-=2026-rewit-entropy";
        String refreshSecret = "textual-refresh-key-with-symbols-!@#$%^&*()_+-=2026-rewit-entropy";
        JwtTokenService tokenService = new JwtTokenService(utf8Secret, refreshSecret);
        SecurityConfig securityConfig = new SecurityConfig(utf8Secret, "rewit-api", "rewit-clients");
        JwtDecoder decoder = securityConfig.jwtDecoder();

        UUID userId = UUID.randomUUID();
        String accessToken = tokenService.generateAccessToken(userId, Role.USER);

        Jwt decoded = decoder.decode(accessToken);
        assertEquals(userId.toString(), decoded.getSubject());
    }

    // =========================================================================
    // F1-AUD-06: Validação estrita do material criptográfico (sem fallback silencioso)
    // =========================================================================

    @Test
    @DisplayName("F1-AUD-06: Base64 sintaticamente válida de 32 chars (decodifica apenas 24 bytes) é rejeitada sem fallback UTF-8")
    void rejectsShortStandardBase64WithoutUtf8Fallback() {
        String base64Of24Bytes = randomBase64(24); // exatamente 32 caracteres Base64
        assertEquals(32, base64Of24Bytes.length());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SecuritySecretValidator.decodeSecret("JWT_SECRET", base64Of24Bytes));
        assertTrue(ex.getMessage().contains("comprimento insuficiente"));
        assertFalse(ex.getMessage().contains(base64Of24Bytes));
    }

    @Test
    @DisplayName("F1-AUD-06: Base64URL sintaticamente válida de 32 chars (decodifica apenas 24 bytes) é rejeitada sem fallback UTF-8")
    void rejectsShortBase64UrlWithoutUtf8Fallback() {
        // Garante presença de '-' ou '_' para exercitar especificamente o ramo Base64URL
        String base64UrlOf24Bytes = "abc-def_ghi-jkl_mno-pqr_stu-vwx0";
        assertEquals(32, base64UrlOf24Bytes.length());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SecuritySecretValidator.decodeSecret("JWT_SECRET", base64UrlOf24Bytes));
        assertTrue(ex.getMessage().contains("comprimento insuficiente"));
        assertFalse(ex.getMessage().contains(base64UrlOf24Bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "YWJj=ZGVmZ2hpamtsbW5vcHFyc3R1dnd4YWJjZGVmZ2hpamts", // padding no meio
            "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4YWJjZGVmZ2hp=",     // comprimento incompatível com Base64 padrão
            "abc+def/ghi-jkl_mno+pqr/stu-vwx0abc+def/ghi-jkl_"  // mistura de alfabetos +/ e -_
    })
    @DisplayName("F1-AUD-06: Chaves Base64 malformadas são rejeitadas explicitamente")
    void rejectsMalformedBase64Secrets(String malformed) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SecuritySecretValidator.decodeSecret("JWT_SECRET", malformed));
        assertTrue(ex.getMessage().contains("malformado"));
        assertFalse(ex.getMessage().contains(malformed));
    }

    @Test
    @DisplayName("F1-AUD-06: Validação de independência rejeita reutilização envolvendo chaves anteriores")
    void rejectsSecretReuseIncludingPreviousKeys() {
        String s1 = randomBase64(32);
        String s2 = randomBase64(32);
        String s3 = randomBase64(32);

        Map<String, String> withDuplicatePrevious = new LinkedHashMap<>();
        withDuplicatePrevious.put("JWT_SECRET", s1);
        withDuplicatePrevious.put("PASSWORD_PEPPER_SECRET", s2);
        withDuplicatePrevious.put("PASSWORD_PEPPER_SECRET_PREVIOUS", s1); // reutiliza JWT_SECRET
        withDuplicatePrevious.put("REFRESH_TOKEN_SECRET", s3);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SecuritySecretValidator.validateDistinct(withDuplicatePrevious));
        assertTrue(ex.getMessage().contains("Reutilização de chave criptográfica proibida"));
    }

    // =========================================================================
    // F1-AUD-03 & F1-AUD-09: Rotação do Password Pepper e validação de keyId
    // =========================================================================

    @Test
    @DisplayName("F1-AUD-03: Default de keyId é não vazio ('v1') e hash gerado carrega pw2$v1$v1$argon2id$...")
    void defaultKeyIdIsNonEmpty() {
        Argon2PasswordHasher hasher = new Argon2PasswordHasher(randomBase64(32));
        assertEquals("v1", hasher.getActiveKeyId());

        UUID userId = UUID.randomUUID();
        String hash = hasher.hash(userId, "SenhaForte123!");
        assertTrue(hash.startsWith("pw2$v1$v1$argon2id$"), "Formato real esperado: " + hash);
        assertFalse(hash.contains("$$"), "Não deve conter separador duplo vazio '$$'");
        assertTrue(hasher.matches(userId, "SenhaForte123!", hash));
        assertFalse(hasher.needsUpgrade(hash));
    }

    @Test
    @DisplayName("F1-AUD-03: Rotação com chave anterior valida hash antigo, sinaliza needsUpgrade e não executa Argon2 duplicado em senha errada")
    void pepperRotationWorksWithoutDuplicateArgon2OnFailure() {
        String keyA = randomBase64(32);
        String keyB = randomBase64(32);
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        // Gera hash com a chave antiga (key-a)
        Argon2PasswordHasher oldHasher = new Argon2PasswordHasher(
                new Argon2PasswordEncoder(16, 32, 1, 19456, 2), keyA, "key-a", null, null);
        String oldHash = oldHasher.hash(userId, "MinhaSenha123!");
        assertTrue(oldHash.startsWith("pw2$v1$key-a$argon2id$"));

        // Cria hasher rotacionado (ativa: key-b, anterior: key-a) com delegate instrumentado
        AtomicInteger argon2Calls = new AtomicInteger();
        PasswordEncoder realEncoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        PasswordEncoder countingDelegate = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return realEncoder.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                argon2Calls.incrementAndGet();
                return realEncoder.matches(rawPassword, encodedPassword);
            }
        };

        Argon2PasswordHasher rotatedHasher = new Argon2PasswordHasher(countingDelegate, keyB, "key-b", keyA, "key-a");

        // 1. Senha correta com chave anterior autentica em exatamente 1 chamada Argon2 e exige upgrade
        argon2Calls.set(0);
        assertTrue(rotatedHasher.matches(userId, "MinhaSenha123!", oldHash));
        assertEquals(1, argon2Calls.get(), "Deve executar exatamente uma verificação Argon2");
        assertTrue(rotatedHasher.needsUpgrade(oldHash));

        // 2. Vínculo ao UUID permanece preservado na chave anterior
        assertFalse(rotatedHasher.matches(otherUserId, "MinhaSenha123!", oldHash));

        // 3. Senha incorreta com chave anterior falha em exatamente 1 chamada Argon2 (sem segunda tentativa na chave ativa)
        argon2Calls.set(0);
        assertFalse(rotatedHasher.matches(userId, "SenhaErrada999!", oldHash));
        assertEquals(1, argon2Calls.get(), "Senha incorreta com chave anterior NÃO pode disparar segunda derivação Argon2");

        // 4. Chave desconhecida é rejeitada sem nenhuma chamada Argon2
        String unknownKeyHash = oldHash.replace("pw2$v1$key-a$", "pw2$v1$key-z$");
        argon2Calls.set(0);
        assertFalse(rotatedHasher.matches(userId, "MinhaSenha123!", unknownKeyHash));
        assertEquals(0, argon2Calls.get(), "keyId desconhecido não deve executar Argon2");

        // 5. Upgrade gera hash com key-b e needsUpgrade == false
        String upgradedHash = rotatedHasher.hash(userId, "MinhaSenha123!");
        assertTrue(upgradedHash.startsWith("pw2$v1$key-b$argon2id$"));
        assertTrue(rotatedHasher.matches(userId, "MinhaSenha123!", upgradedHash));
        assertFalse(rotatedHasher.needsUpgrade(upgradedHash));
    }

    @Test
    @DisplayName("F1-AUD-03 / F1-AUD-09: Rejeita keyIds vazios, com separadores '$', inválidos ou iguais entre ativa e anterior")
    void rejectsInvalidOrDuplicateKeyIds() {
        String keyA = randomBase64(32);
        String keyB = randomBase64(32);
        PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

        for (String invalidId : new String[]{"", "   ", "v1$bad", "key with space", "key.dot", "a".repeat(33)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Argon2PasswordHasher(encoder, keyA, invalidId, null, null),
                    "Deveria rejeitar activeKeyId inválido: '" + invalidId + "'");
        }

        // IDs iguais durante rotação
        assertThrows(IllegalArgumentException.class,
                () -> new Argon2PasswordHasher(encoder, keyB, "v1", keyA, "v1"));

        // Chave anterior sem previousKeyId
        assertThrows(IllegalArgumentException.class,
                () -> new Argon2PasswordHasher(encoder, keyB, "v2", keyA, ""));
    }
}
