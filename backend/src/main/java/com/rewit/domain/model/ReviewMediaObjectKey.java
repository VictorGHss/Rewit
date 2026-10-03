package com.rewit.domain.model;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Namespace e formato das chaves de objeto geradas para mídias de avaliação (Step 28.1).
 *
 * <p>Formato produzido pelo upload de {@code ReviewMediaService}:
 * {@code reviews/{reviewId}/{mediaId}/image.{jpg|png}}, com UUIDs em minúsculas.
 * A reconciliação de storage só considera chaves que pertencem a este namespace e
 * seguem este formato: qualquer outro objeto não foi criado pela aplicação e nunca
 * pode ser classificado como órfão gerenciado.
 */
public final class ReviewMediaObjectKey {

    /** Único prefixo de storage gerenciado pela reconciliação de mídias. */
    public static final String MANAGED_PREFIX = "reviews/";

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Pattern MANAGED_KEY = Pattern.compile(
            "^reviews/" + UUID_REGEX + "/" + UUID_REGEX + "/image\\.(jpg|png)$"
    );

    private ReviewMediaObjectKey() {}

    /**
     * @return true se a chave está sob o prefixo gerenciado {@value #MANAGED_PREFIX}
     */
    public static boolean isInManagedNamespace(String key) {
        return key != null && key.startsWith(MANAGED_PREFIX);
    }

    /**
     * @return true se a chave segue exatamente o formato gerado pelo upload de mídias
     */
    public static boolean isManagedKey(String key) {
        return key != null && MANAGED_KEY.matcher(key).matches();
    }

    /**
     * Extrai o reviewId de uma chave gerenciada.
     *
     * @throws IllegalArgumentException se a chave não segue o formato gerenciado
     */
    public static UUID reviewIdOf(String key) {
        if (!isManagedKey(key)) {
            throw new IllegalArgumentException("Object key is not a managed review media key");
        }
        return UUID.fromString(key.substring(MANAGED_PREFIX.length(), MANAGED_PREFIX.length() + 36));
    }
}
