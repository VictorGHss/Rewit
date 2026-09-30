package com.rewit.application.query;

import java.util.Locale;

/**
 * Sorts explícitos suportados pelas consultas de review atualmente válidas para feed e listagem por alvo.
 * Futuras regras de relevância/distância/popularidade/reputação devem entrar em um contrato separado.
 */
public enum ReviewSort {
    NEWEST("newest"),
    RATING_DESC("rating_desc"),
    RATING_ASC("rating_asc");

    private final String value;

    ReviewSort(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ReviewSort fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return NEWEST;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ReviewSort sort : values()) {
            if (sort.value.equals(normalized)) {
                return sort;
            }
        }

        throw new IllegalArgumentException("Unsupported review sort: '" + raw + "'. Supported values: newest, rating_desc, rating_asc");
    }
}
