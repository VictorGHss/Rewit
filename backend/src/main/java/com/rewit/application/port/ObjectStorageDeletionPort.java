package com.rewit.application.port;

import com.rewit.application.dto.storage.ObjectDeletionResult;

/**
 * Capacidade isolada de exclusão no Object Storage (Step 28.4), sempre no bucket configurado.
 *
 * <p>Separada para que a exclusão de órfãos dependa apenas de remover, sem acesso a gravação ou leitura.
 */
public interface ObjectStorageDeletionPort {

    /**
     * Remove o objeto da chave informada. Erros do storage não são lançados: são classificados no resultado.
     * Exclusão de objeto inexistente é {@link ObjectDeletionResult#NOT_FOUND}, um sucesso idempotente.
     *
     * @param key a chave do objeto a ser removido; nula ou vazia não toca o storage e resulta em NOT_FOUND
     * @return o resultado classificado da exclusão
     */
    ObjectDeletionResult delete(String key);
}
