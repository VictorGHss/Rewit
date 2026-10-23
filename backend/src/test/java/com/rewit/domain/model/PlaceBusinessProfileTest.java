package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Domínio: perfil comercial do estabelecimento (descrição e contatos)")
class PlaceBusinessProfileTest {

    private static final String SITE = "https://www.exemplo.com.br";

    @Test
    @DisplayName("Campos ausentes ou só com espaços ficam sem valor (substituição integral)")
    void absentOrBlankFieldsAreEmpty() {
        assertEquals(new PlaceBusinessProfile(null, PlaceContactInfo.EMPTY),
                PlaceBusinessProfile.of(null, null, null, null, null));
        assertEquals(new PlaceBusinessProfile(null, PlaceContactInfo.EMPTY),
                PlaceBusinessProfile.of("   ", "", " \t ", "\n", "  "));
    }

    @Test
    @DisplayName("Apara as bordas e preserva o conteúdo: quebras de linha da descrição e formatação do telefone")
    void trimsWithoutRewritingContent() {
        PlaceBusinessProfile profile = PlaceBusinessProfile.of("  Café colonial.\nAberto aos domingos.  ",
                "  +55  (41)   3333-4444 ", " " + SITE + "/contato ", " https://www.instagram.com/exemplo ",
                "https://www.facebook.com/exemplo");

        assertEquals("Café colonial.\nAberto aos domingos.", profile.description());
        assertEquals("+55 (41) 3333-4444", profile.contact().phone(), "só espaços repetidos são colapsados");
        assertEquals(SITE + "/contato", profile.contact().websiteUrl());
        assertEquals("https://www.instagram.com/exemplo", profile.contact().instagramUrl());
        assertEquals("https://www.facebook.com/exemplo", profile.contact().facebookUrl());
    }

    @Test
    @DisplayName("Descrição: até 2000 caracteres; 2001 responde 400 INVALID_DESCRIPTION")
    void descriptionLimit() {
        assertEquals(2000, PlaceBusinessProfile.of("a".repeat(2000), null, null, null, null).description().length());
        assertCode("INVALID_DESCRIPTION", () -> PlaceBusinessProfile.of("a".repeat(2001), null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+55 (41) 3333-4444", "(41) 99999-9999", "41 3333 4444", "0800 123 4567",
            "+1 555.123.4567", "+44 20 7946 0958", "33334444", "+123456789012345"})
    @DisplayName("Telefone: formatos nacionais e internacionais comuns são aceitos como enviados")
    void acceptsCommonPhoneFormats(String phone) {
        assertEquals(phone, PlaceContactInfo.of(phone, null, null, null).phone());
    }

    @Test
    @DisplayName("Telefone: exatamente 32 caracteres é aceito; 33 é recusado")
    void phoneLengthLimit() {
        String atLimit = "+55 (41) 3333 - 4444 . 1 - 2 - 3";
        assertEquals(32, atLimit.length());
        assertEquals(atLimit, PlaceContactInfo.of(atLimit, null, null, null).phone());
        assertCode("INVALID_PHONE", () -> PlaceContactInfo.of(atLimit + "4", null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "+1234567890123456", "telefone", "+55 41 3333-4444 ramal 2", "55+41 3333 4444",
            "41-3333-4444-", "-41 3333 4444", "javascript:alert(1)", "+", "++55 41 3333 4444", "41/3333-4444"})
    @DisplayName("Telefone: menos de 8 ou mais de 15 dígitos, letras e símbolos fora do formato respondem 400 INVALID_PHONE")
    void rejectsInvalidPhones(String phone) {
        assertCode("INVALID_PHONE", () -> PlaceContactInfo.of(phone, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {SITE, "http://exemplo.com/caminho?x=1#topo", "HTTPS://EXEMPLO.COM", "https://exemplo.com:8443/a"})
    @DisplayName("Links: http e https absolutos com host são aceitos como enviados")
    void acceptsHttpLinks(String url) {
        assertEquals(url, PlaceContactInfo.of(null, url, url, url).websiteUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JavaScript:alert(1)", "data:text/html;base64,PHNjcmlwdD4=",
            "vbscript:msgbox(1)", "file:///etc/passwd", "ftp://exemplo.com", "mailto:contato@exemplo.com",
            "www.exemplo.com", "//exemplo.com", "https://", "https:///caminho", "https://usuario:senha@exemplo.com",
            "https://instagram.com@exemplo.com", "https://exemplo .com", "https://exemplo.com/\0", "https://exemplo.com/a b"})
    @DisplayName("Links: outros esquemas, link sem esquema ou sem host, credenciais embutidas e espaços respondem 400")
    void rejectsUnsafeOrInvalidLinks(String url) {
        assertCode("INVALID_WEBSITE_URL", () -> PlaceContactInfo.of(null, url, null, null));
        assertCode("INVALID_INSTAGRAM_URL", () -> PlaceContactInfo.of(null, null, url, null));
        assertCode("INVALID_FACEBOOK_URL", () -> PlaceContactInfo.of(null, null, null, url));
    }

    @Test
    @DisplayName("Links: exatamente 500 caracteres é aceito; 501 é recusado com o código do campo")
    void linkLengthLimit() {
        String prefix = "https://exemplo.com/";
        String atLimit = prefix + "a".repeat(PlaceContactInfo.MAX_URL_LENGTH - prefix.length());
        assertEquals(500, atLimit.length());
        assertEquals(atLimit, PlaceContactInfo.of(null, null, atLimit, null).instagramUrl());
        assertCode("INVALID_INSTAGRAM_URL", () -> PlaceContactInfo.of(null, null, atLimit + "a", null));
    }

    @Test
    @DisplayName("Reconstituição a partir do banco não revalida; null equivale a sem contatos no local")
    void reconstitutionDoesNotRevalidate() {
        PlaceContactInfo stored = new PlaceContactInfo("123", null, null, null);
        assertEquals("123", stored.phone());
        Place place = new Place(null, "Local", "local", "BAR", null, "Rua", null, null, "Curitiba", "PR", "BR",
                -25.43, -49.27, 50, "USER", false, null, null, "ACTIVE", null);
        assertEquals(PlaceContactInfo.EMPTY, place.getContactInfo());
        assertNull(place.getContactInfo().phone());
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        BusinessException ex = assertThrows(BusinessException.class, action);
        assertEquals(code, ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
}
