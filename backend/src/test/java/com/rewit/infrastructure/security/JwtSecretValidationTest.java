package com.rewit.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Hardening: Validação de Inicialização do JWT_SECRET (Step 4.1)")
class JwtSecretValidationTest {

    @Test
    @DisplayName("1. JWT_SECRET ausente/nulo deve falhar na validação")
    void shouldFailWhenSecretIsNull() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                JwtSecretValidator.validate(null));
        assertTrue(ex.getMessage().contains("segredo ausente ou vazio"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("2. JWT_SECRET vazio ou em branco deve falhar")
    void shouldFailWhenSecretIsBlank(String blankSecret) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                JwtSecretValidator.validate(blankSecret));
        assertTrue(ex.getMessage().contains("segredo ausente ou vazio"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",
            "1234567890123456789012345678901", // 31 bytes
            "weak-key-too-short-for-hs256!"
    })
    @DisplayName("3. JWT_SECRET curto (< 32 bytes) deve falhar")
    void shouldFailWhenSecretIsTooShort(String shortSecret) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                JwtSecretValidator.validate(shortSecret));
        assertTrue(ex.getMessage().contains("comprimento insuficiente"));
        assertFalse(ex.getMessage().contains(shortSecret), "O valor do segredo nunca deve aparecer na mensagem de erro");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "change-me",
            "changeme",
            "password",
            "secret",
            "default",
            "placeholder",
            "123456",
            "jwt-secret",
            "your-secret"
    })
    @DisplayName("4. JWT_SECRET com placeholders ou padrões fracos óbvios deve falhar")
    void shouldFailWhenSecretIsCommonPlaceholder(String placeholder) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                JwtSecretValidator.validate(placeholder));
        assertFalse(ex.getMessage().contains(placeholder), "O valor do segredo nunca deve aparecer na mensagem de erro");
    }

    @Test
    @DisplayName("5. JWT_SECRET com baixa entropia (repetição de caracteres) deve falhar")
    void shouldFailWhenSecretHasLowEntropy() {
        String lowEntropySecret = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; // 40 bytes de 'a'
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                JwtSecretValidator.validate(lowEntropySecret));
        assertTrue(ex.getMessage().contains("entropia insuficiente") || ex.getMessage().contains("inseguro"));
        assertFalse(ex.getMessage().contains(lowEntropySecret));
    }

    @Test
    @DisplayName("6. JWT_SECRET válido com alta entropia (>= 32 bytes) deve passar com sucesso")
    void shouldPassWhenSecretIsValidAndStrong() {
        String validSecret = "e8b5c92f1a4e7d0368a2bf5071de84c935fa782164de90cb15f7a23c4890ef1b";
        assertDoesNotThrow(() -> JwtSecretValidator.validate(validSecret));
    }
}
