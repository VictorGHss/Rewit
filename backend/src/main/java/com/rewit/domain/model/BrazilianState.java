package com.rewit.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * As 27 unidades federativas do Brasil (26 estados e o Distrito Federal), com sigla oficial e nome.
 *
 * <p>Fonte única para validar UF: regiões ("Sul", "Sudeste") e siglas desconhecidas não são UF.
 */
public enum BrazilianState {
    AC("Acre"), AL("Alagoas"), AP("Amapá"), AM("Amazonas"), BA("Bahia"), CE("Ceará"), DF("Distrito Federal"),
    ES("Espírito Santo"), GO("Goiás"), MA("Maranhão"), MT("Mato Grosso"), MS("Mato Grosso do Sul"),
    MG("Minas Gerais"), PA("Pará"), PB("Paraíba"), PR("Paraná"), PE("Pernambuco"), PI("Piauí"),
    RJ("Rio de Janeiro"), RN("Rio Grande do Norte"), RS("Rio Grande do Sul"), RO("Rondônia"), RR("Roraima"),
    SC("Santa Catarina"), SP("São Paulo"), SE("Sergipe"), TO("Tocantins");

    private final String officialName;

    BrazilianState(String officialName) {
        this.officialName = officialName;
    }

    public String getOfficialName() {
        return officialName;
    }

    /** Sigla oficial ("pr", " PR " → PR); qualquer outro valor é vazio. */
    public static Optional<BrazilianState> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 2) {
            return Optional.empty();
        }
        for (BrazilianState state : values()) {
            if (state.name().equals(normalized)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    /** Nome oficial completo, sem diferenciar acentos e maiúsculas ("Parana" → PR); nomes parciais não valem. */
    public static Optional<BrazilianState> fromOfficialName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = fold(name);
        for (BrazilianState state : values()) {
            if (fold(state.officialName).equals(normalized)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    /** Minúsculas, sem acentos e com espaços simples: comparação tolerante de nomes de lugares. */
    public static String fold(String value) {
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
