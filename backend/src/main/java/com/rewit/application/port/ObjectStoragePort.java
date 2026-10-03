package com.rewit.application.port;

/**
 * Porta agnóstica de armazenamento de objetos em nuvem/local (S3/SeaweedFS/MinIO).
 * Mantém a camada de aplicação e domínio 100% desacopladas de qualquer SDK de storage.
 * A exclusão (usada em remoção definitiva ou rollback por compensação) vem de {@link ObjectStorageDeletionPort}.
 */
public interface ObjectStoragePort extends ObjectStorageDeletionPort {

    /**
     * Armazena os bytes do objeto na chave informada.
     *
     * @param key a chave interna única do objeto no storage
     * @param contentType o MIME type validado do arquivo
     * @param data o conteúdo binário já sanitizado
     */
    void put(String key, String contentType, byte[] data);

    /**
     * Recupera o conteúdo binário do objeto armazenado.
     *
     * @param key a chave do objeto
     * @return os bytes do objeto
     */
    byte[] get(String key);

    /**
     * Verifica se o objeto existe no storage.
     *
     * @param key a chave do objeto
     * @return true se o objeto existir, false caso contrário
     */
    boolean exists(String key);
}
