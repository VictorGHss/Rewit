package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Contatos públicos de um estabelecimento (perfil comercial): telefone, site e redes sociais. Cada campo é opcional;
 * {@code null} significa não informado.
 *
 * <p>O construtor canônico só reconstitui o que já foi gravado; entrada externa passa por {@link #of}, que valida e
 * normaliza. O telefone é guardado como digitado (sem descartar dígitos, código de país ou formatação); só os espaços
 * repetidos são colapsados.
 */
public record PlaceContactInfo(String phone, String websiteUrl, String instagramUrl, String facebookUrl) {

    public static final PlaceContactInfo EMPTY = new PlaceContactInfo(null, null, null, null);

    public static final int MAX_PHONE_LENGTH = 32;
    /** Faixa de dígitos de um número de contato: 8 (local sem DDD) a 15 (máximo do E.164). */
    public static final int MIN_PHONE_DIGITS = 8;
    public static final int MAX_PHONE_DIGITS = 15;
    public static final int MAX_URL_LENGTH = 500;

    /** Mesmo formato do CHECK chk_places_phone_format (V38): "+" opcional no início, dígitos, espaço, ( ) . - */
    private static final Pattern PHONE_FORMAT = Pattern.compile("^\\+?[0-9(][0-9 ().-]*[0-9]$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Valida e normaliza os contatos recebidos: valores ausentes ou só com espaços viram {@code null}.
     *
     * @throws BusinessException {@code 400 INVALID_PHONE}, {@code INVALID_WEBSITE_URL}, {@code INVALID_INSTAGRAM_URL}
     *                           ou {@code INVALID_FACEBOOK_URL}
     */
    public static PlaceContactInfo of(String phone, String websiteUrl, String instagramUrl, String facebookUrl) {
        return new PlaceContactInfo(
                normalizePhone(phone),
                normalizeUrl(websiteUrl, "do site", "INVALID_WEBSITE_URL"),
                normalizeUrl(instagramUrl, "do Instagram", "INVALID_INSTAGRAM_URL"),
                normalizeUrl(facebookUrl, "do Facebook", "INVALID_FACEBOOK_URL"));
    }

    static String normalizePhone(String phone) {
        String value = blankToNull(phone);
        if (value == null) {
            return null;
        }
        value = WHITESPACE.matcher(value).replaceAll(" ");
        long digits = value.chars().filter(Character::isDigit).count();
        if (value.length() > MAX_PHONE_LENGTH || !PHONE_FORMAT.matcher(value).matches()
                || digits < MIN_PHONE_DIGITS || digits > MAX_PHONE_DIGITS) {
            throw new BusinessException("Telefone inválido: use de 8 a 15 dígitos, com \"+\" opcional no início e "
                    + "espaços, parênteses, pontos ou hífens como separadores", HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
        return value;
    }

    /**
     * Só links absolutos http/https com host e sem credenciais embutidas ({@code https://usuario@host} disfarça o
     * destino real). Outros esquemas ({@code javascript:}, {@code data:}, {@code ftp:}...) são recusados.
     */
    static String normalizeUrl(String url, String label, String code) {
        String value = blankToNull(url);
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_URL_LENGTH
                || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw invalidUrl(label, code);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw invalidUrl(label, code);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null) {
            throw invalidUrl(label, code);
        }
        return value;
    }

    private static BusinessException invalidUrl(String label, String code) {
        return new BusinessException("Endereço " + label + " inválido: use um link http ou https de até "
                + MAX_URL_LENGTH + " caracteres", HttpStatus.BAD_REQUEST, code);
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
