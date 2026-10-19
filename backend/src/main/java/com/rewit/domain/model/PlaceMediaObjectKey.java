package com.rewit.domain.model;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Namespace e formato das chaves de objeto geradas para fotografias de locais (Place).
 *
 * <p>Formato produzido pelo upload de {@code PlaceMediaService}:
 * {@code places/{placeId}/{mediaId}/image.{jpg|png}}, com UUIDs em minúsculas.
 * A reconciliação de storage só considera chaves que pertencem a este namespace e
 * seguem este formato.
 */
public final class PlaceMediaObjectKey {

    /** Prefixo de storage gerenciado pela reconciliação de fotografias de locais. */
    public static final String MANAGED_PREFIX = "places/";

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Pattern MANAGED_KEY = Pattern.compile(
            "^places/" + UUID_REGEX + "/" + UUID_REGEX + "/image\\.(jpg|png)$"
    );

    private PlaceMediaObjectKey() {}

    /**
     * @return true se a chave está sob o prefixo gerenciado {@value #MANAGED_PREFIX}
     */
    public static boolean isInManagedNamespace(String key) {
        return key != null && key.startsWith(MANAGED_PREFIX);
    }

    /**
     * @return true se a chave segue exatamente o formato gerado pelo upload de fotografias de locais
     */
    public static boolean isManagedKey(String key) {
        return key != null && MANAGED_KEY.matcher(key).matches();
    }

    /**
     * Extrai o placeId de uma chave gerenciada.
     *
     * @throws IllegalArgumentException se a chave não segue o formato gerenciado
     */
    public static UUID placeIdOf(String key) {
        if (!isManagedKey(key)) {
            throw new IllegalArgumentException("Object key is not a managed place media key");
        }
        return UUID.fromString(key.substring(MANAGED_PREFIX.length(), MANAGED_PREFIX.length() + 36));
    }

    /**
     * Extrai o mediaId de uma chave gerenciada.
     *
     * @throws IllegalArgumentException se a chave não segue o formato gerenciado
     */
    public static UUID mediaIdOf(String key) {
        if (!isManagedKey(key)) {
            throw new IllegalArgumentException("Object key is not a managed place media key");
        }
        int mediaStart = MANAGED_PREFIX.length() + 36 + 1;
        return UUID.fromString(key.substring(mediaStart, mediaStart + 36));
    }
}

